package org.ignis.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class TerraformManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TerraformManager.class);

    private static final String TF_BIN_PROP = "ignis.terraform.bin";
    private static final String TF_RESOURCE_DIR = "terraform";

    private final String terraformBinary;
    private final Map<String, String> outputs = new HashMap<>();
    private Path workDir = null;
    private final String region;
    private final String az;

    public TerraformManager(String region, String az) {
        this.terraformBinary = System.getProperty(TF_BIN_PROP, "terraform");
        this.region = region;
        this.az = az;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public void provision() throws ISchedulerException {
        if(this.workDir != null) {
            LOGGER.warn("WorkDir already exists");
            throw new ISchedulerException("TerraformManager already provisioned");
        }

        try {
            workDir = Files.createTempDirectory("ignis-terraform-");
            LOGGER.debug("Creating temporary directory: {}", workDir.toString());

            copyTerraformResourcesTo(workDir);

            System.out.println("[ignis-cloud] Provisioning infrastructure...");
            executeTerraform(workDir, "init", "-input=false");

            System.out.println("[ignis-cloud] Applying Terraform plan...");
            executeTerraform(workDir, "apply", "-auto-approve", "-input=false",
                    "-var", "aws_region=" + region,
                    "-var", "availability_zone=" + az);

            captureOutputs(workDir);

            printInfrastructureTable();
            System.out.println("[ignis-cloud] Infrastructure ready.");
            LOGGER.info("Terraform infrastructure applied successfully.");

        } catch (Exception e) {
            if (workDir != null && Files.exists(workDir)) {
                try {
                    destroy(false);
                    deleteDirectoryRecursively(workDir);
                    LOGGER.info("Cleaning up partial directory after failure");
                } catch (Exception ex) {
                    LOGGER.warn("Could not clean up directory after error", ex);
                }
            }
            workDir = null;
            throw new ISchedulerException("Failure during terraform provision", e);
        }
    }

    public String requireOutput(String key) throws ISchedulerException {
        String value = outputs.get(key);
        if (value == null) {
            throw new ISchedulerException("Output required not found: " + key);
        }
        return value;
    }

    // Reference: [12], [13], [14], [15]
    private void copyTerraformResourcesTo(Path destination) throws ISchedulerException, IOException {
        ClassLoader cl = getClass().getClassLoader();
        URL url = cl.getResource(TF_RESOURCE_DIR);
        if (url == null) {
            throw new IOException("Cannot find resource " + TF_RESOURCE_DIR);
        }
        if (!url.getProtocol().equals("jar")) {
            throw new IOException(
                    "Terraform resources must be loaded from a JAR. " +
                            "Got protocol '" + url.getProtocol() + "'. " +
                            "Make sure the scheduler is packaged with 'gradle jarlibs'."
            );
        }
        String jarPath = url.toString().split("!")[0].substring("jar:".length());
        String prefix = TF_RESOURCE_DIR + "/";
        jarPath = jarPath.substring(5);

        String urlStr = url.toString();
        String jarUrlPart = urlStr.substring(0, urlStr.indexOf("!/"));

        if (jarUrlPart.startsWith("jar:file:")) {
            jarPath = jarUrlPart.substring("jar:file:".length());
        } else {
            throw new IOException("Unexpected JAR URL format: " + urlStr);
        }

        try(JarFile jar = new JarFile(jarPath)) {
            jar.stream()
                    .filter(entry -> entry.getName().startsWith(prefix))
                    .forEach(entry -> {
                        try {
                            String relPath = entry.getName().substring(prefix.length());
                            if(relPath.isEmpty()) {
                                return;
                            }
                            Path target = destination.resolve(relPath);
                            if(entry.isDirectory()) {
                                Files.createDirectories(target);
                            } else {
                                Files.createDirectories(target.getParent());
                                try (InputStream is = jar.getInputStream(entry)) {
                                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    // Reference: [10], [11]
    private void executeTerraform(Path workDir, String... args) throws ISchedulerException {
        List<String> command = new ArrayList<>();
        command.add(terraformBinary);
        command.addAll(Arrays.asList(args));

        LOGGER.info("Executing command {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try{
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.debug("[Terraform out] " + line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ISchedulerException("Command failed with code " + exitCode);
            }
        } catch (IOException e) {
            LOGGER.error("Real IOException message: {}", e.getMessage(), e);
            throw new ISchedulerException("I/O error executing: " + String.join(" ", command) + " → " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ISchedulerException("Interrupted", e);
        }
    }

    private boolean executeTerraformAllowFail(Path workDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add(terraformBinary);
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.debug("[Terraform out] " + line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOGGER.warn("Command exited with code {} (ignored): {}",
                        exitCode, String.join(" ", command));
                return false;
            }
            return true;
        }catch (IOException e) {
            LOGGER.warn("I/O error executing (ignored): {} → {}", String.join(" ", command), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while executing (ignored): {}", String.join(" ", command));
            return false;
        }
    }

    // Reference: [10], [11]
    private void captureOutputs(Path workDir) throws ISchedulerException {
        LOGGER.info("Capturing Terraform outputs in directory {}", workDir);

        ProcessBuilder pb = new ProcessBuilder(terraformBinary, "output", "-json");
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            StringBuilder jsonOutput = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonOutput.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new ISchedulerException("Command failed with exit code " + exitCode + ": ");
            }

            String json = jsonOutput.toString().trim();
            if(json.isEmpty()) {
                throw new ISchedulerException("Terraform output is empty");
            }

            var root = parseJson(json);

            outputs.put("subnet_id", getOutputValue(root, "subnet_id"));
            outputs.put("sg_id", getOutputValue(root, "sg_id"));
            outputs.put("vpc_id", getOutputValue(root, "vpc_id"));
            outputs.put("jobs_bucket_name", getOutputValue(root, "jobs_bucket_name"));
            outputs.put("efs_dns_name", getOutputValue(root, "efs_dns_name"));
            outputs.put("efs_id", getOutputValue(root, "efs_id"));
            outputs.put("efs_mount_target_ip", getOutputValue(root, "efs_mount_target_ip"));

        } catch (Exception e){
            LOGGER.error("Failed to capture Terraform outputs in directory {}", workDir, e);
            throw new ISchedulerException("Failed to capture Terraform outputs in directory", e);
        }
    }

    private String getOutputValue(JsonNode root, String outputName) {
        JsonNode node = root.path(outputName).path("value");
        if (node.isMissingNode() || node.isNull()) {
            LOGGER.warn("Output '{}' not found or null", outputName);
            return null;
        }
        return node.asText().trim();
    }

    // Reference: [1], [18]
    private JsonNode parseJson(String json) throws ISchedulerException {
        try {
            var mapper = new ObjectMapper();
            return mapper.readTree(json);
        } catch (IOException ex) {
            throw new ISchedulerException(ex.getMessage(), ex);
        }
    }

    // Reference: [16]
    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete file: {}", path, e);
                        }
                    });
        }
    }

    public void destroy(boolean preserveBucket) throws ISchedulerException {
        if(this.workDir == null || !Files.exists(this.workDir)) {
            LOGGER.info("Terraform temporary directory has been deleted");
            return;
        }

        try{
            if(preserveBucket){
                executeTerraformAllowFail(this.workDir, "state", "rm", "aws_s3_bucket_public_access_block.ignis_jobs");
                executeTerraformAllowFail(this.workDir, "state", "rm", "aws_s3_bucket.ignis_jobs");
            }
            
            executeTerraform(this.workDir, "destroy", "-auto-approve", "-input=false",
                    "-var", "aws_region=" + region,
                    "-var", "availability_zone=" + az);
            LOGGER.info("Destroy completed");


        } catch (Exception e){
            LOGGER.error("Failed to destroy Terraform", e);
            throw new ISchedulerException("Failed to destroy Terraform", e);
        } finally {
            cleanupWorkDir();
            this.workDir = null;
        }
    }

    private void cleanupWorkDir() {
        if (this.workDir == null || !Files.exists(this.workDir)) return;

        try {
            deleteDirectoryRecursively(this.workDir);
            LOGGER.info("Cleanup completed");
        } catch (Exception e){
            LOGGER.error("Failed to cleanup Terraform", e);
        }
    }

    private void printInfrastructureTable() {
        String vpc    = outputs.getOrDefault("vpc_id",           "N/A");
        String subnet = outputs.getOrDefault("subnet_id",        "N/A");
        String sg     = outputs.getOrDefault("sg_id",            "N/A");
        String bucket = outputs.getOrDefault("jobs_bucket_name", "N/A");
        String efs = outputs.getOrDefault("efs_dns_name", "N/A");

        int col1 = 20;
        int col2 = Math.max(50, Math.max(Math.max(vpc.length(), subnet.length()),
                Math.max(sg.length(), bucket.length())) + 2);

        String separator = "+" + "-".repeat(col1 + 2) + "+" + "-".repeat(col2 + 2) + "+";
        String fmt = "| %-" + col1 + "s | %-" + col2 + "s |";

        System.out.println();
        System.out.println("[ignis-cloud] Provisioned infrastructure:");
        System.out.println(separator);
        System.out.printf((fmt) + "%n", "Resource", "Value");
        System.out.println(separator);
        System.out.printf((fmt) + "%n", "VPC ID",           vpc);
        System.out.printf((fmt) + "%n", "Subnet ID",        subnet);
        System.out.printf((fmt) + "%n", "Security Group ID", sg);
        System.out.printf((fmt) + "%n", "S3 Bucket",        bucket);
        System.out.printf((fmt) + "%n", "EFS",        efs);
        System.out.println(separator);
        System.out.println();
    }
}
