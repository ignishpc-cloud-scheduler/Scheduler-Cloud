package org.ignis.scheduler;

import org.ignis.scheduler.model.IClusterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class EC2Operations implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(EC2Operations.class);
    private final AwsFactory awsFactory;
    private final Ec2Client ec2;
    private final SsmClient ssm;

    private static final int IP_POLL_INTERVAL_MS = 2000;
    private static final int IP_POLL_MAX_ATTEMPTS = 30;

    public EC2Operations(Ec2Client ec2, SsmClient ssm, AwsFactory awsFactory) {
        this.ec2 = ec2;
        this.ssm = ssm;
        this.awsFactory = awsFactory;
    }

    // Reference [19], [22], [23]
    public String createEC2Instance(String instanceName, String userDataScript, String amiId, String subnet, String sgId, String iam, InstanceType instanceType, String iamInstanceProfile, String jobId, String role) throws ISchedulerException {
        try{
            List<Tag> tags = new ArrayList<>();
            tags.add(Tag.builder().key("Name").value(instanceName).build());
            tags.add(Tag.builder().key("JobName").value(instanceName.split("-")[0]).build());
            if(jobId != null && !jobId.isBlank()){
                tags.add(Tag.builder().key("JobId").value(jobId).build());
            }
            if (role != null && !role.isBlank()) {                
                tags.add(Tag.builder().key("Role").value(role).build()); 
            }

            RunInstancesRequest runRequest = RunInstancesRequest.builder()
                    .imageId(amiId)
                    .instanceType(instanceType)
                    .maxCount(1)
                    .minCount(1)
                    .subnetId(subnet)
                    .securityGroupIds(sgId)
                    .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                            .name("LabRole")
                            .build())
                    .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
                    .userData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)))
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(tags)
                            .build())
                    .build();

            RunInstancesResponse response = ec2.runInstances(runRequest);
            String instanceId = response.instances().get(0).instanceId();

            LOGGER.info("Instance launched: {}", instanceId);
            return instanceId;
        } catch (Ec2Exception e) {
            LOGGER.error("Failed to create EC2 instance. AWS error: {} - {}",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown",
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage(),
                    e);
            throw new ISchedulerException("Failed to create EC2 instance: " +
                    (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        }
    }

    // Reference: [40]
    public Instance getInstanceInfo(String instanceId) throws ISchedulerException {
        if (instanceId == null || instanceId.trim().isEmpty()){
            throw new ISchedulerException("Instance id can't be null or empty");
        }
        try{
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build();

            DescribeInstancesResponse response = ec2.describeInstances(request);
            for (Reservation reservation : response.reservations()) {
                for (Instance instance : reservation.instances()) {
                    if(instanceId.equals(instance.instanceId())) {
                        return instance;
                    }
                }
            }
            LOGGER.debug("Instance not found: {}", instanceId);
            return null;

        } catch (Ec2Exception e) {
            LOGGER.error("Failed to get instance info for {}: {}", instanceId, e.getMessage());
            throw new ISchedulerException("Failed to get instance info for " + instanceId, e);
        }
    }

    public String getInstanceState(String instanceId) throws ISchedulerException {
        Instance inst = getInstanceInfo(instanceId);
        if (inst == null) {
            return "not_found";
        }
        return inst.state().nameAsString().toLowerCase();
    }


    public InstanceType resolveInstanceType(IClusterRequest driver) throws ISchedulerException {
        String type =  System.getenv("IGNIS_INSTANCE_TYPE");
        if(type != null && !type.isBlank()) {
            try {
                return InstanceType.fromValue(type.trim());
            }  catch (Exception e) {
                throw new ISchedulerException("Invalid instance type '" + type, e);
            }
        }

        int cpus = driver.resources().cpus();
        long ram = driver.resources().memory() / (1024L * 1024L);

        if (cpus <= 2 && ram <= 2048)  return InstanceType.T3_SMALL;
        if (cpus <= 2 && ram <= 4096)  return InstanceType.T3_MEDIUM;
        if (cpus <= 2 && ram <= 8192)  return InstanceType.M6_I_LARGE;
        if (cpus <= 2 && ram <= 16384) return InstanceType.R6_I_LARGE;

        return InstanceType.M6_I_LARGE;
    }

    // Reference: [46], [47]
    public String resolveAMI() throws ISchedulerException {
        String userAMI = System.getenv("IGNIS_AMI");
        if(userAMI != null && !userAMI.isBlank()) return userAMI.trim();

        String paramName = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64";

        try{
            return ssm.getParameter(GetParameterRequest.builder()
                            .name(paramName)
                            .build())
                    .parameter()
                    .value();
        } catch (Exception e) {
            throw new ISchedulerException("Failed to resolve AMI via SSM (" + paramName + ")", e);
        }
    }

    public String resolveAvailabilityZone() throws ISchedulerException {
        // 1. Resolver AZ vía variable de entorno
        String configuredAZ = System.getenv("IGNIS_AWS_AZ");
        if(configuredAZ != null && !configuredAZ.isBlank()) {
            LOGGER.info("Using AZ from IGNIS_AWS_AZ: {}", configuredAZ);
            return configuredAZ.trim();
        }

        // 2. Si falla env buscar una zona disponible
        try{
            DescribeAvailabilityZonesRequest request = DescribeAvailabilityZonesRequest.builder()
                    .filters(Filter.builder()
                            .name("state")
                            .values("available")
                            .build())
                    .build();

            List<AvailabilityZone> zones = ec2.describeAvailabilityZones(request).availabilityZones();
            if(!zones.isEmpty()) {
                String az = zones.get(0).zoneName();
                LOGGER.info("Auto-resolved AZ for region {}: {}", awsFactory.getRegion(), az);
                return az;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not auto-resolve AZ from AWS, falling back to default", e);
        }

        // 3. Si falla el anterior devolver un fallback
        String fallback = awsFactory.getRegion().id() + "a";
        LOGGER.info("Using fallback AZ: {}", fallback);
        return fallback;
    }

    public void verifyConnectivity() throws ISchedulerException {
        try {
            ec2.describeAvailabilityZones(
                    DescribeAvailabilityZonesRequest.builder().build()
            );
        } catch (Ec2Exception e) {
            throw new ISchedulerException("Cannot connect to AWS: " +
                    (e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage()), e);
        }
    }

    public String waitForPrivateIp(String instanceId) throws ISchedulerException {
        LOGGER.info("Waiting for private IP of instance {}", instanceId);

        for(int i=0; i < IP_POLL_MAX_ATTEMPTS; i++){
            Instance inst = getInstanceInfo(instanceId);
            if(inst != null && inst.privateIpAddress() != null && !inst.privateIpAddress().isBlank()) {
                LOGGER.info("Private IP for {}: {} (after {} polls)", instanceId, inst.privateIpAddress(), i+1);
                return inst.privateIpAddress();
            }
            try{
                Thread.sleep(IP_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ISchedulerException("Interrupted while waiting for private IP of " + instanceId,e);
            }
        }
        throw  new ISchedulerException("Timeout waiting for private IP of instance " + instanceId);
    }

    public String waitForPublicIp(String instanceId) throws ISchedulerException {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                DescribeInstancesResponse resp = ec2.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build());
                for (var r : resp.reservations()) {
                    for (var inst : r.instances()) {
                        String publicIp = inst.publicIpAddress();
                        if (publicIp != null && !publicIp.isBlank()) {
                            return publicIp;
                        }
                    }
                }
            } catch (Ec2Exception e) {
                LOGGER.debug("Could not describe instance {} yet: {}", instanceId, e.getMessage());
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ISchedulerException("Interrupted while waiting for public IP", e);
            }
        }
        throw new ISchedulerException("Timeout waiting for public IP of " + instanceId);
    }

    public void waitUntilRunning(String instanceId) throws ISchedulerException {
        LOGGER.info("Waiting for instance {}", instanceId);
        try{
            ec2.waiter().waitUntilInstanceRunning(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build()
            );
            LOGGER.info("Instance {} is now running", instanceId);
        } catch (Exception e) {
            throw new ISchedulerException("Failed waiting for instance " + instanceId + " to reach running state", e);
        }
    }

    private void terminateByJobAndRole(String jobId, String role) throws ISchedulerException {
        try {
            List<Filter> filters = new ArrayList<>();
            filters.add(Filter.builder().name("tag:JobId").values(jobId).build());
            filters.add(Filter.builder().name("instance-state-name")
                    .values("pending", "running", "stopping", "stopped").build());
            if (role != null) {
                filters.add(Filter.builder().name("tag:Role").values(role).build());
            }

            DescribeInstancesRequest describeRequest = DescribeInstancesRequest.builder()
                    .filters(filters).build();

            List<String> instanceIds = new ArrayList<>();
            ec2.describeInstancesPaginator(describeRequest).forEach(page ->
                    page.reservations().forEach(r ->
                            r.instances().forEach(inst -> instanceIds.add(inst.instanceId()))));

            String roleLabel = (role == null) ? "instances" : role + " instances";
            if (instanceIds.isEmpty()) {
                LOGGER.info("No active {} found for job {}", roleLabel, jobId);
                return;
            }

            LOGGER.info("Terminating {} {} for job {}: {}",
                    instanceIds.size(), roleLabel, jobId, instanceIds);

            ec2.terminateInstances(TerminateInstancesRequest.builder()
                    .instanceIds(instanceIds).build());
        } catch (Ec2Exception e) {
            throw new ISchedulerException(
                    "Failed to terminate " + (role == null ? "instances" : role + " instances") 
                    + " for job " + jobId, e);
        }
    }


    public void terminateExecutorsByJob(String jobId) throws ISchedulerException {
        terminateByJobAndRole(jobId, "executor");
    }


    public void terminateDriverByJob(String jobId) throws ISchedulerException {
        terminateByJobAndRole(jobId, "driver");
    }
    

    public List<Instance> describeInstancesByTag(String jobId) throws ISchedulerException {
        try{
            DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                    .filters(
                            Filter.builder().name("tag:JobId").values(jobId).build(),
                            Filter.builder().name(("instance-state-name")).values("pending", "running").build()
                    ).build();

            List<Instance> instances = new ArrayList<>();
            ec2.describeInstancesPaginator(request).forEach(page ->
                    page.reservations().forEach(r->
                            instances.addAll(r.instances())));

            LOGGER.debug("Found {} executor instances for job {}", instances.size(), jobId);
            return instances;

        } catch (Ec2Exception e) {
            throw new ISchedulerException("Failed to describe executor instances for job " + jobId, e);
        }
    }

    @Override
    public void close() {
        ec2.close();
        ssm.close();
    }
}
