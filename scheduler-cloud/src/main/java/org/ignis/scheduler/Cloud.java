package org.ignis.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ignis.scheduler.model.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Cloud implements IScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(Cloud.class);

    private final TerraformManager terraformManager;
    private final AwsFactory awsFactory;
    private final EC2Operations ec2;
    private final S3Operations s3;
    private final UserDataBuilder userDataBuilder;
    private final BundleCreator bundleCreator;
    private final PayloadResolver payloadResolver;

    private static final int SSH_PORT = 1963;
    private static final int FREE_PORT_BASE = 20000;
    private static final long TCP_PROBE_TIMEOUT_MS = 2000;
    private static final long TCP_PROBE_INTERVAL_MS = 1000;
    private static final long S3_READY_INTERVAL_MS = 5000;
    private static final long POLL_INTERVAL_SECONDS = 30;
    private static final long MAX_WAIT_SECONDS = 3600 * 6;

    private final Map<String, JobMeta> jobs = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, IContainerInfo.IStatus> runtimeStatus = new ConcurrentHashMap<>();

    private final static Map<String, IContainerInfo.IStatus> CLOUD_STATUS = new HashMap<>() {{
        put("pending", IContainerInfo.IStatus.ACCEPTED);
        put("running", IContainerInfo.IStatus.RUNNING);
        put("stopping", IContainerInfo.IStatus.RUNNING);
        put("stopped", IContainerInfo.IStatus.FINISHED);
        put("shutting-down", IContainerInfo.IStatus.DESTROYED);
        put("terminated", IContainerInfo.IStatus.DESTROYED);
        put("not_found", IContainerInfo.IStatus.ERROR);
    }};

    public Cloud(String url) throws ISchedulerException, Exception {
        LOGGER.info("Initializing Cloud scheduler at: {}", url);

        this.awsFactory = new AwsFactory(resolveRegion());

        Ec2Client ec2Client = awsFactory.createEc2Client();
        S3Client s3Client = awsFactory.createS3Client();
        SsmClient ssmClient = awsFactory.createSsmClient();

        this.ec2 = new EC2Operations(ec2Client, ssmClient, awsFactory);
        this.terraformManager = new TerraformManager(awsFactory.getRegion().id(), ec2.resolveAvailabilityZone());
        this.s3 = new S3Operations(s3Client);
        this.userDataBuilder = new UserDataBuilder();
        this.bundleCreator = new BundleCreator();
        this.payloadResolver = new PayloadResolver();
    }

    private Region resolveRegion() throws ISchedulerException {
        String configuredRegion = System.getenv("IGNIS_AWS_REGION");

        if (configuredRegion != null && !configuredRegion.isBlank()) {
            try {
                return Region.of(configuredRegion.trim());
            } catch (Exception e) {
                throw new ISchedulerException("Invalid AWS region '" + configuredRegion + "'. Example: eu-west-1", e);
            }
        }

        try {
            Region auto = new DefaultAwsRegionProviderChain().getRegion();
            if (auto != null) return auto;
        } catch (Exception e) {
            LOGGER.debug("Default AWS region provider chain failed", e);
        }

        throw new ISchedulerException("AWS region not configured. Set IGNIS_AWS_REGION or configure it in ~/.aws/config (aws configure) or export AWS_REGION/AWS_DEFAULT_REGION.");
    }

    private JobMeta resolveJobMeta(String jobId) {
        JobMeta meta = jobs.get(jobId);
        if (meta != null) return meta;

        String bucket;
        try {
            bucket = terraformManager.requireOutput("jobs_bucket_name");
        } catch (Exception e) {
            bucket = System.getenv("IGNIS_JOBS_BUCKET");
        }

        if (bucket == null || bucket.isBlank()) {
            LOGGER.warn("Could not resolve bucket for job {}", jobId);
            return null;
        }

        meta = s3.loadJobMetaFromS3(jobId, bucket);
        if (meta != null) {
            jobs.put(jobId, meta);
        }

        return meta;
    }

    private IContainerInfo.IStatus statusFromS3(JobMeta meta) {
        String key = "jobs/" + meta.jobId() + "/status.json";
        try {
            String json = s3.getString(meta.bucket(), key);
            if (json == null || json.isBlank()){
                return null;
            } 

            String state = mapper.readTree(json).path("state").asText(null);
            if (state == null) {
                 return null;
            }

            return switch (state) {
                case "FINISHED" ->  IContainerInfo.IStatus.FINISHED;
                case "FAILED" ->    IContainerInfo.IStatus.ERROR;
                case "DESTROYED" -> IContainerInfo.IStatus.DESTROYED;
                default ->          IContainerInfo.IStatus.UNKNOWN;
            };
        } catch (Exception e) {
            LOGGER.debug("Could not read/parse status.json for job {}", meta.jobId(), e);
            return null;
        }
    }

    // Métodos auxiliares de createJob

    // Resuelve y valida los parámetros de configuración necesarios para lanzar la instancia EC2 del driver
    private JobConfig resolveJobConfig() throws ISchedulerException {
        String iamInstanceProfile = System.getenv("IGNIS_IAM_INSTANCE_PROFILE");
        if (iamInstanceProfile == null || iamInstanceProfile.isBlank()) {
            throw new ISchedulerException("Missing IGNIS_IAM_INSTANCE_PROFILE (IAM creation disabled in this AWS account)");
        }
        String subnet =     terraformManager.requireOutput("subnet_id");
        String sg =         terraformManager.requireOutput("sg_id");
        String bucket =     terraformManager.requireOutput("jobs_bucket_name");
        String efs =        terraformManager.requireOutput("efs_id");
        String efsMountIp = terraformManager.requireOutput("efs_mount_target_ip");

        if (subnet == null || sg == null || bucket == null || efs == null || efsMountIp == null || iamInstanceProfile == null) {
            throw new ISchedulerException("Missing required environment variables for job creation");
        }

        return new JobConfig(subnet, sg, bucket, efs, efsMountIp, iamInstanceProfile);
    }

    // Empaqueta el código del usuario (script principal y dependencias) en un bundle, lo sube a S3 y devuelve la key resultante
    private String packageAndUploadBundle(IClusterRequest driver, String bucket, String jobId) throws ISchedulerException {
        List<IBindMount> binds = new ArrayList<>(payloadResolver.buildPayloadBindsFromArgs(driver));  
        BundleResult result = bundleCreator.createBundleTarGzHybrid(binds, bucket, jobId, s3);
        return s3.uploadJobBundle(bucket, jobId, result.tarGz());
    }

    // Lanza la instancia EC2 para el driver con el user data que monta el bundle y ejecuta el script principal
    private String launchDriverInstance(IClusterRequest driver, String jobName, String jobId, String bundleKey, JobConfig config)  throws ISchedulerException {
        String image = driver.resources().image();
        String cmd = payloadResolver.resolveCommand(driver);
        String ami = ec2.resolveAMI();
        InstanceType instanceType = ec2.resolveInstanceType(driver);

        String userData = userDataBuilder.buildUserData(
                awsFactory.getRegion().id(), jobName, jobId,
                config.bucket(), bundleKey, image, cmd,
                config.subnet(), config.sg(), instanceType.name(), ami, config.efs(), config.efsMountIp());
        
        return ec2.createEC2Instance(
                jobName + "-driver", userData, ami,
                config.subnet(), config.sg(), "", instanceType, config.iamInstanceProfile(), jobId, "driver");
    }

    // Guarda la información del job en memoria y en S3 para que luego getJob pueda mostrarla
    private void persistJobMeta(IClusterRequest driver, String jobId, String jobName, String instanceId, String bundleKey, String bucket, String driverIp) {
        JobMeta meta = new JobMeta(
            jobId, jobName, bucket, instanceId, bundleKey,
            driver.resources().image(), payloadResolver.resolveCommand(driver),
            driver.resources().cpus(), driver.resources().memory(),
            driver.resources().gpu(), driver.resources().args(), driverIp);

        jobs.put(jobId, meta);
        try {
            s3.saveJobMetaToS3(meta);
        } catch (Exception e) {
            LOGGER.warn("Failed to save job meta to S3 for job {}, continuing", jobId, e);
        }
    }

    private void awaitJobAndDestroy(String jobId, String bucket) {
        System.out.println("[ignis-cloud] Waiting for job " + jobId + " to finish...");
    
        String key = "jobs/" + jobId + "/driver-finished.json";
        long start = System.currentTimeMillis();
        int pollCount = 0;
        boolean finishedCleanly = false;
    
        while (true) {
            pollCount++;
            try {
                String json = s3.getString(bucket, key);
                if (json != null) {
                    System.out.println("[ignis-cloud] Job " + jobId + " finished: " + json.trim());
                    finishedCleanly = true;
                    break;
                }
            } catch (Exception e) {
                LOGGER.warn("Poll #{} error (will retry): {}", pollCount, e.getMessage());
            }
        
            if ((System.currentTimeMillis() - start) / 1000 > MAX_WAIT_SECONDS) {
                System.out.println("[ignis-cloud] Timeout waiting for job " + jobId + " after " + MAX_WAIT_SECONDS + "s, destroying anyway");
                break;
            }
        
            try {
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[ignis-cloud] Interrupted while waiting for job " + jobId);
                return;
            }
        }

        if (finishedCleanly) {
            try {
                System.out.println("[ignis-cloud] Downloading results for job " + jobId + "...");
                s3.downloadJob(jobId, bucket);
                System.out.println("[ignis-cloud] Results downloaded.");
            } catch (Exception e) {
                LOGGER.error("Failed to download results for job " + jobId, e);
                System.err.println("[ignis-cloud] WARNING: results download failed: " + e.getMessage());
            }
        } else {
            System.err.println("[ignis-cloud] Job did not finish cleanly, skipping results download");
        }
    
        // Margen para que las EC2 terminen y liberen ENIs
        System.out.println("[ignis-cloud] Waiting 60s for EC2 instances to fully terminate...");
        try { Thread.sleep(60_000); } catch (InterruptedException e) { /* ok */ }
    
        try {
            terraformManager.destroy(true);
            System.out.println("[ignis-cloud] Infrastructure destroyed.");
        } catch (Exception e) {
            LOGGER.error("Failed to destroy infrastructure for job " + jobId, e);
            System.err.println("[ignis-cloud] WARNING: destroy failed: " + e.getMessage());
        }
    }


    @Override
    public String createJob(String name, IClusterRequest driver, IClusterRequest... executors) throws ISchedulerException {
        
        // Provisionar la infraestructura necesaria con Terraform (VPC, subnet, security group, S3 bucket, etc.)
        //terraformManager.ensureInfrastructure();
        terraformManager.provision();

        // Generar un jobId único y un nombre para el job
        String jobId = ISchedulerUtils.genId().substring(0, 8);
        String jobName = name.replace("/", "-") + "-" + jobId;
        LOGGER.info("Creating job with name {} and id {}", jobName, jobId);

        // Obtener los datos de configuración necesarios para lanzar la instancia EC2 del driver
        JobConfig config = resolveJobConfig();

        try {
            // Preparar el bundle con el script principal y posibles archivos relacionados (payload), subiéndolo a S3
            String bundleKey = packageAndUploadBundle(driver, config.bucket(), jobId);

            // Lanzar la instancia EC2 que ejecutará el driver
            String instanceId = launchDriverInstance(driver, jobName, jobId, bundleKey, config);

            // Obtener la IP del driver
            String driverIp = ec2.waitForPublicIp(instanceId);

            // Guardar la información del job en memoria y en S3 para que luego getJob pueda mostrarla
            persistJobMeta(driver, jobId, jobName, instanceId, bundleKey, config.bucket(), driverIp);

            awaitJobAndDestroy(jobId, config.bucket());
            
            LOGGER.info("Created job with name {} and id {}", jobName, jobId);
            return jobId;

        } catch (Exception e) {
            throw new ISchedulerException("Failed to prepare and launch EC2 instance for job " + jobId, e);
        }
    }

    // Métodos auxiliares de cancelJob

    // Marca el job como DESTROYED en S3 para que getJob pueda reportarlo correctamente.
    private void markJobDestroyedInS3(JobMeta meta) {
        try {
            String key = "jobs/" + meta.jobId() + "/status.json";
            String body = mapper.writeValueAsString(Map.of("state", "DESTROYED", "rc", 143));
            s3.putString(meta.bucket(), key, body, "application/json");
        } catch (Exception e) {
            LOGGER.warn("Failed to update status.json for job {}, continuing with termination", meta.jobId(), e);
        }
    }

    // Termina todas las instancias EC2 asociadas al job (driver y executors) y elimina la información del job en memoria.
    private void terminateAllJobInstances(String jobId) throws ISchedulerException {
        try {
            ec2.terminateExecutorsByJob(jobId);
            ec2.terminateDriverByJob(jobId);
            LOGGER.info("Terminated all EC2 instances for job {}", jobId);
        } catch (Exception e) {
            throw new ISchedulerException("Error terminating EC2 instances for job " + jobId, e);
        }
    }

    @Override
    public void cancelJob(String id) throws ISchedulerException {
        LOGGER.info("Canceling job with id {}", id);

        JobMeta meta = jobs.get(id);
        if (meta == null) {
            meta = resolveJobMeta(id);
        }
        if (meta == null) {
            throw new ISchedulerException("job " + id + " not found");
        }
        try {
            markJobDestroyedInS3(meta);
            terminateAllJobInstances(id);
        } finally {
            jobs.remove(id);
        }
        LOGGER.info("Job {} canceled", id);
    }

    // Métodos auxiliares para getJob

    // Obtener el estado del job mientras se está ejecutando (ACCEPTED/RUNNING)
    private IContainerInfo.IStatus resolveJobStatus(String id, JobMeta meta) {
        IContainerInfo.IStatus s3Status = statusFromS3(meta);
        if (s3Status != null) {
            runtimeStatus.remove(id);
            return s3Status;
        }

        IContainerInfo.IStatus cached = runtimeStatus.getOrDefault(id, IContainerInfo.IStatus.ACCEPTED);
        if (cached == IContainerInfo.IStatus.ACCEPTED) {
            runtimeStatus.put(id, IContainerInfo.IStatus.RUNNING);
        }
        return cached;
    }

    private IContainerInfo buildDriverContainerInfo(JobMeta meta, IContainerInfo.IStatus status) throws ISchedulerException {
        String containerName = meta.jobName() + "-driver";
        String driverHost = meta.driverHost();
        try {
            if (driverHost == null || driverHost.isBlank()) {
                driverHost = ec2.waitForPublicIp(meta.instanceId());
            }    
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve driver host for job {}, using instance ID as fallback", meta.jobId(), e);
            throw new ISchedulerException("Failed to resolve driver host for job " + meta.jobId(), e);
        }
    
        return IContainerInfo.builder()
            .id(meta.instanceId())
            .node(driverHost)
            .image(meta.image())
            .args(meta.args() != null ? meta.args() : List.of())
            .cpus(meta.cpus())
            .gpu(meta.gpu())
            .memory(meta.memory())
            .writable(true)
            .tmpdir(true)
            .ports(List.of())
            .binds(List.of())
            .nodelist(List.of())
            .hostnames(Map.of())
            .env(Map.of(
                    "IGNIS_SCHEDULER_ENV_JOB", meta.jobId(),
                    "IGNIS_SCHEDULER_ENV_CONTAINER", containerName
            ))
            .network(IContainerInfo.INetworkMode.BRIDGE)
            .status(status)
            .provider(IContainerInfo.IProvider.DOCKER)
            .schedulerOptArgs(Map.of())
            .build();
    }

    @Override
    public IJobInfo getJob(String id) throws ISchedulerException {
        LOGGER.info("Getting job with id {}", id);

        JobMeta meta = resolveJobMeta(id);
        if (meta == null) {
            throw new ISchedulerException("Job " + id + " not found");
        }

        IContainerInfo.IStatus status = resolveJobStatus(id, meta);
        IContainerInfo driverContainer = buildDriverContainerInfo(meta, status);

        IClusterInfo driverCluster = IClusterInfo.builder()
            .id("0-driver")
            .instances(1)
            .containers(List.of(driverContainer))
            .build();

        return IJobInfo.builder()
            .name(meta.jobName())
            .id(meta.jobId())
            .clusters(List.of(driverCluster))
            .build();
    }

    @Override
    public List<IJobInfo> listJobs(Map<String, String> filters) throws ISchedulerException {
        throw new UnsupportedOperationException();
    }

    // Métodos auxiliares para createCluster

    // Resuelve y valida los parámetros de configuración del cluster
    private ClusterConfig resolveClusterConfig() throws ISchedulerException {
        String region = awsFactory.getRegion().id();
        String subnet = System.getenv("IGNIS_SUBNET_ID");
        String sg = System.getenv("IGNIS_SG_ID");
        String ami = System.getenv("IGNIS_AMI");
        String bucket = System.getenv("IGNIS_JOBS_BUCKET");
        String efsMountIp = System.getenv("EFS_MOUNT_IP");
        String instanceTypeStr = System.getenv("IGNIS_INSTANCE_TYPE");

        if (region == null || subnet == null || sg == null || ami == null || bucket == null || efsMountIp == null || instanceTypeStr == null) {
            throw new ISchedulerException("Missing required environment variables for cluster creation");
        }

        InstanceType instanceType = InstanceType.fromValue(
        instanceTypeStr.trim().toLowerCase().replace("_", "."));

        return new ClusterConfig(region, subnet, sg, ami, bucket, efsMountIp, instanceType);
    }

    // Lanza una instancia EC2 por cada executor solicitado en el cluster
    private List<ExecutorHandle> launchExecutorInstances(String job, IClusterRequest request, JobMeta meta, ClusterConfig config) throws ISchedulerException {
        List<ExecutorHandle> handles = new ArrayList<>();
        int numExecutors = request.instances();

        for(int i = 0; i < numExecutors; i++){
            String executorName = job + "-executor-" + (i);

            String userData = userDataBuilder.buildExecutorUserData(
                        config.region(), job, executorName,
                        meta.bucket(), meta.bundleKey(), meta.image(),
                        request.resources().env(),
                        request.resources().args(), config.efsMountIp(), numExecutors);

            String instanceId = ec2.createEC2Instance(
                        executorName, userData, config.ami(),
                        config.subnet(), config.sg(), "",
                        config.instanceType(),"", job, "executor");

            handles.add(new ExecutorHandle(instanceId, executorName));
            LOGGER.info("Launched EC2 instance {} for executor {}", instanceId, executorName);
        }
        
        return handles;
    }

    // Espera a que cada instancia EC2 alcance el estado "running" y obtiene su IP privada
    private void waitForExecutorsRunning(List<ExecutorHandle> handles) throws ISchedulerException {
        for (ExecutorHandle h : handles) {
            ec2.waitUntilRunning(h.getInstanceId());
            h.privateIp = ec2.waitForPrivateIp(h.getInstanceId());
        }
    }

    @Override
    public IClusterInfo createCluster(String job, IClusterRequest request) throws ISchedulerException {
        LOGGER.info("Creating cluster for job {}", job);

        JobMeta meta = resolveJobMeta(job);
        if (meta == null) {
            throw new ISchedulerException("Job " + job + " not found");
        }

        // Obtener la configuración necesaria para lanzar las instancias EC2 de los executors
        ClusterConfig config = resolveClusterConfig();

        int numExecutors = request.instances();
        LOGGER.info("Launching {} executor instance(s) for job {}", numExecutors, job);

        // 1) Lanzar las instancias EC2 de los executors
        List<ExecutorHandle> handles = launchExecutorInstances(job, request, meta, config);

        // 2) Esperar a que estén en running y obtener sus IPs privadas
        waitForExecutorsRunning(handles);

        // 3) Construir nodelist/hostnames compartidos para todos los executors
        Map<String, String> allHostnames = new HashMap<>();
        List<String> allNodes = new ArrayList<>();
        for (ExecutorHandle h : handles) {
            allHostnames.put(h.privateIp, h.privateIp);
            allNodes.add(h.privateIp);
        }
        LOGGER.info("All executors running. Nodes: {}", allNodes);

        // 4) Esperar a que cada executor publique su marker de readiness y construir su info
        List<IContainerInfo> containers = new ArrayList<>();
        for (ExecutorHandle h : handles) {
            waitForExecutorReadyS3(config.bucket(), job, h.getContainerName(), 300);
            waitForExecutorTcp(h.privateIp, 1963, 120);

            containers.add(buildExecutorContainerInfo(
                    h.getInstanceId(), h.getContainerName(), h.privateIp,
                    job, request, allHostnames, allNodes));
        }

        LOGGER.info("Cluster ready for job {} with {} executor(s)", job, containers.size());
        
        return IClusterInfo.builder()
                    .id(request.name())
                    .instances(request.instances())
                    .containers(containers)
                    .build();
        
    }

    // Construye la información del contenedor executor, incluyendo la configuración de entorno y puertos
    private Map<String, String> buildExecutorEnv(IClusterRequest request, String job, String containerName, String privateIp) {
        
        Map<String, String> env = new HashMap<>();
        if (request.resources().env() != null) {
            env.putAll(request.resources().env());
        }

        String jobDir = "/ignis/dfs/payload/" + job;
        String executorDir = jobDir + "/tmp/" + containerName;

        // Identidad y directorios estándar de Ignis
        env.put("IGNIS_SCHEDULER_ENV_JOB", job);
        env.put("IGNIS_SCHEDULER_ENV_CONTAINER", containerName);
        env.put("IGNIS_WDIR", "/ignis/dfs/payload");
        env.put("IGNIS_EXECUTOR_DIRECTORY", executorDir);
        env.put("IGNIS_EXECUTOR_WDIR", executorDir);
        env.put("IGNIS_JOB_DIR", jobDir);
        env.put("IGNIS_JOB_SOCKETS", jobDir + "/sockets");
        env.put("IGNIS_HEALTHCHECK_DISABLE", "true");

        return env;
    }

    private IContainerInfo buildExecutorContainerInfo( String instanceId, String containerName, String privateIp, String job, IClusterRequest request, Map<String, String> allHostnames, List<String> allNodes ) {

        Map<String, String> env = buildExecutorEnv(request, job, containerName, privateIp);
        List<IPortMapping> ports = buildExecutorPorts(request);

        return IContainerInfo.builder()
                .id(instanceId)
                .node(privateIp)
                .image(request.resources().image())
                .args(request.resources().args() != null ? request.resources().args() : List.of())
                .cpus(request.resources().cpus())
                .gpu(request.resources().gpu())
                .memory(request.resources().memory())
                .writable(true)
                .tmpdir(true)
                .ports(ports)
                .binds(List.of())
                .nodelist(allNodes)
                .hostnames(allHostnames)
                .env(env)
                .network(IContainerInfo.INetworkMode.BRIDGE)
                .status(IContainerInfo.IStatus.RUNNING)
                .provider(IContainerInfo.IProvider.DOCKER)
                .schedulerOptArgs(Map.of())
                .build();
    }

    // Construye el mapeo de puertos del container del executor
    private List<IPortMapping> buildExecutorPorts(IClusterRequest request) {
        List<IPortMapping> result = new ArrayList<>();
        result.add(new IPortMapping(SSH_PORT, SSH_PORT, IPortMapping.Protocol.TCP));

        List<IPortMapping> requested = request.resources().ports();
        if (requested == null || requested.isEmpty()) {
            return result;
        }

        int freeTcpNeeded = 0;
        for (IPortMapping p : requested) {
            if (p.protocol() != IPortMapping.Protocol.TCP) {
                continue;
            }

            if (p.container() <= 0) {
                // Puerto "libre": se asignará un número fijo desde FREE_PORT_BASE.
                freeTcpNeeded++;
                continue;
            }

            if (p.container() == SSH_PORT) {
                // Ya reservado, lo ignoramos para no duplicarlo.
                continue;
            }
        
            int host = p.host() > 0 ? p.host() : p.container();
            result.add(new IPortMapping(p.container(), host, p.protocol()));
        }

        for (int i = 0; i < freeTcpNeeded; i++) {
            int port = FREE_PORT_BASE + i;
            result.add(new IPortMapping(port, port, IPortMapping.Protocol.TCP));
        }
        return result;
    }


    private void waitForExecutorTcp(String host, int port, int timeoutSeconds) throws ISchedulerException {
        pollUntilReady(
                timeoutSeconds,
                TCP_PROBE_INTERVAL_MS,
                "TCP connectivity to " + host + ":" + port,
                () -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, port), (int) TCP_PROBE_TIMEOUT_MS);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            );
        LOGGER.info("Executor reachable at {}:{}", host, port);
    }

    private void waitForExecutorReadyS3(String bucket, String jobId, String containerName, int timeoutSeconds) throws ISchedulerException {
        String key = "jobs/" + jobId + "/executors/" + containerName + "/ready.json";

        pollUntilReady(
                timeoutSeconds,
                S3_READY_INTERVAL_MS,
                "ready marker s3://" + bucket + "/" + key,
                () -> {
                    try {
                        String json = s3.getString(bucket, key);
                        return json != null && !json.isBlank();
                    } catch (Exception e) {
                        LOGGER.debug("Ready marker for {} not available yet: {}", containerName, e.getMessage());
                        return false;
                    }
                }
        );
        LOGGER.info("Executor {} signaled ready in S3", containerName);
    }


    private void pollUntilReady(int timeoutSeconds, long intervalMs, String description, BooleanSupplier check) throws ISchedulerException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ISchedulerException("Interrupted while waiting for " + description, e);
            }
        }

        throw new ISchedulerException("Timeout waiting for " + description);
    }

    @Override
    public void destroyCluster(String job, String id) throws ISchedulerException {
        LOGGER.info("Destroying cluster {} for job {}", id, job);

        try {
            ec2.terminateExecutorsByJob(job);
        } catch (Exception e) {
            throw e;
        }
    }

    // Indica si una instancia es el driver
    private boolean isDriverInstance(Instance inst) {
        return inst.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .anyMatch(name -> name.endsWith("-driver"));
    }

    private IContainerInfo buildContainerInfoFromInstance(Instance inst, String job, JobMeta meta, List<String> allNodes, Map<String, String> allHostnames) {
        String instanceId = inst.instanceId();
        String privateIp = inst.privateIpAddress() != null ? inst.privateIpAddress() : "";
        String containerName = inst.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .findFirst()
                .orElse(instanceId);

        IContainerInfo.IStatus status = CLOUD_STATUS.getOrDefault(
                inst.state().nameAsString().toLowerCase(),
                IContainerInfo.IStatus.UNKNOWN);

        return IContainerInfo.builder()
                .id(instanceId)
                .node(privateIp)
                .image(meta.image())
                .args(meta.args() != null ? meta.args() : List.of())
                .cpus(meta.cpus())
                .memory(meta.memory())
                .gpu(meta.gpu())
                .writable(true)
                .tmpdir(true)
                .ports(List.of())
                .binds(List.of())
                .nodelist(allNodes)
                .hostnames(allHostnames)
                .env(Map.of(
                        "IGNIS_SCHEDULER_ENV_JOB", job,
                        "IGNIS_SCHEDULER_ENV_CONTAINER", containerName
                ))
                .network(IContainerInfo.INetworkMode.BRIDGE)
                .status(status)
                .provider(IContainerInfo.IProvider.DOCKER)
                .schedulerOptArgs(Map.of())
                .build();
    }

    @Override
    public IClusterInfo getCluster(String job, String id) throws ISchedulerException {
        LOGGER.info("Getting cluster {} for job {}", id, job);
        JobMeta meta;
        try {
            meta = resolveJobMeta(job);
        } catch (Exception e) {
            throw e;
        }

        if (meta == null) {
            throw new ISchedulerException("Job " + job + " not found");
        }

        List<Instance> allInstances;
        try {
            allInstances = ec2.describeInstancesByTag(job);

            for (Instance inst : allInstances) {
                String name = inst.tags().stream()
                        .filter(t -> "Name".equals(t.key()))
                        .map(Tag::value)
                        .findFirst()
                        .orElse("<no-name>");
            }
        } catch (Exception e) {
            throw e;
        }

        List<Instance> executorInstances = allInstances.stream()
                .filter(i -> !isDriverInstance(i))
                .toList();

        if (executorInstances.isEmpty()) {
            LOGGER.warn("No executor instances found for cluster {} of job {}", id, job);
        }

        List<String> allNodes = executorInstances.stream()
                .map(Instance::privateIpAddress)
                .filter(Objects::nonNull)
                .filter(ip -> !ip.isBlank())
                .toList();

        Map<String, String> allHostnames = allNodes.stream().collect(Collectors.toMap(ip -> ip, ip -> ip));

        List<IContainerInfo> containers;
        try {
            containers = executorInstances.stream()
                    .map(inst -> {
                        String name = inst.tags().stream()
                                .filter(t -> "Name".equals(t.key()))
                                .map(Tag::value)
                                .findFirst()
                                .orElse(inst.instanceId());

                        IContainerInfo info = buildContainerInfoFromInstance(inst, job, meta, allNodes, allHostnames);
                        return info;
                    }).toList();
        } catch (Exception e) {
            throw e;
        }

        IClusterInfo result = IClusterInfo.builder()
                .id(id)
                .instances(containers.size())
                .containers(containers)
                .build();
        return result;
    }

    @Override // TODO
    public IClusterInfo repairCluster(String job, IClusterInfo cluster, IClusterRequest request) throws ISchedulerException {
        LOGGER.info("Repairing cluster {} for job {}", cluster.id(), job);
        LOGGER.warn("repairCluster called for cluster {} of job {} but is not implemented; " + "returning cluster as-is", cluster.id(), job);
        return cluster;
    }

    @Override
    public IContainerInfo.IStatus getContainerStatus(String job, String id) throws ISchedulerException {

        if (id == null || id.isBlank()) {
            throw new ISchedulerException("Container id cannot be null or empty");
        }

        try {
            String awsState = ec2.getInstanceState(id);

            if (awsState == null) {
                LOGGER.warn("Could not get EC2 state for instance {}", id);
                return IContainerInfo.IStatus.UNKNOWN;
            }

            IContainerInfo.IStatus status = CLOUD_STATUS.getOrDefault(
                    awsState.toLowerCase(),
                    IContainerInfo.IStatus.UNKNOWN
            );
            return status;

        } catch (ISchedulerException e) {
            throw e;
        } catch (Exception e) {
            throw new ISchedulerException("Failed to get EC2 instance status for " + id, e);
        }
    }

    @Override
    public void healthCheck() throws ISchedulerException {
        LOGGER.info("Performing health check for Cloud scheduler");

        try {
            ec2.verifyConnectivity();
        } catch (Exception e) {
            throw new ISchedulerException("AWS connectivity check failed", e);
        }

        LOGGER.info("Finished health check for Cloud scheduler");
    }
}