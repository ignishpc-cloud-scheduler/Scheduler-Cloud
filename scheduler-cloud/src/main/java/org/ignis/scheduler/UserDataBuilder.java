package org.ignis.scheduler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class UserDataBuilder {

    private static final String DRIVER_TEMPLATE_PATH = "scripts/userdata.sh";
    private static final String EXECUTOR_TEMPLATE_PATH = "scripts/userdata-executor.sh";

    public String buildUserData(String region, String jobName, String jobId, String bucket, String bundleKey, String image, String command, String subnet, String sg, String instanceType, String ami, String efsId, String efsMountIp) throws ISchedulerException{
        String template = loadTemplate(DRIVER_TEMPLATE_PATH);

        Map<String, String> vars = new HashMap<>();
        vars.put("JOB_NAME", shellEscapeSingleQuotes(jobName));
        vars.put("JOB_ID", shellEscapeSingleQuotes(jobId));
        vars.put("BUCKET", shellEscapeSingleQuotes(bucket));
        vars.put("BUNDLE_KEY", shellEscapeSingleQuotes(bundleKey));
        vars.put("IMAGE", shellEscapeSingleQuotes(image));
        vars.put("CMD", shellEscapeSingleQuotes(command));
        vars.put("REGION", region);
        vars.put("SUBNET_ID", shellEscapeSingleQuotes(subnet));
        vars.put("SG_ID", shellEscapeSingleQuotes(sg));
        vars.put("INSTANCE_TYPE", shellEscapeSingleQuotes(instanceType));
        vars.put("AMI",  shellEscapeSingleQuotes(ami));
        vars.put("EFS_ID", shellEscapeSingleQuotes(efsId));
        vars.put("EFS_MOUNT_IP", shellEscapeSingleQuotes(efsMountIp != null ? efsMountIp.trim() : ""));

        return renderTemplate(template, vars);
    }

    public String buildExecutorUserData(String region, String jobId, String containerName, String bucket, String bundleKey, String image, Map<String, String> env, List<String> args, String efsMountIp, int totalExecutors) throws ISchedulerException {
        String template = loadTemplate(EXECUTOR_TEMPLATE_PATH);

        if (totalExecutors < 1) {
            throw new ISchedulerException("totalExecutors must be >= 1 in buildExecutorUserData()");
        }

        Map<String, String> safeEnv = new TreeMap<>();
        if (env != null) {
            for (var e : env.entrySet()) {
                if (e == null || e.getKey() == null) continue;
                String key = e.getKey().trim();
                if (key.isEmpty()) continue;
                String value = e.getValue() == null ? "" : e.getValue();
                safeEnv.put(key, value);
            }
        }

        StringBuilder envFlags = new StringBuilder();
        for (var e : safeEnv.entrySet()) {
            envFlags.append("  -e ")
                    .append(e.getKey())
                    .append("='")
                    .append(shellEscapeSingleQuotes(e.getValue()))
                    .append("' \\\n");
        }

        List<String> safeArgs = args == null
                ? List.of()
                : args.stream().filter(Objects::nonNull).toList();

        if (safeArgs.isEmpty()) {
            throw new ISchedulerException("Executor args are empty in buildExecutorUserData()");
        }

        String executorCmd = safeArgs.stream()
                .map(arg -> "'" + shellEscapeSingleQuotes(arg) + "'")
                .collect(Collectors.joining(" "));

        Map<String, String> vars = new HashMap<>();
        vars.put("REGION", shellEscapeSingleQuotes(region));
        vars.put("JOB_ID", shellEscapeSingleQuotes(jobId));
        vars.put("CONTAINER_NAME", shellEscapeSingleQuotes(containerName));
        vars.put("BUCKET", shellEscapeSingleQuotes(bucket));
        vars.put("BUNDLE_KEY", shellEscapeSingleQuotes(bundleKey));
        vars.put("IMAGE", shellEscapeSingleQuotes(image));
        vars.put("EXECUTOR_ENV", envFlags.toString());
        vars.put("EXECUTOR_CMD", executorCmd);
        vars.put("EFS_MOUNT_IP", shellEscapeSingleQuotes(efsMountIp != null ? efsMountIp.trim() : ""));
        vars.put("TOTAL_EXECUTORS", Integer.toString(totalExecutors));

        return renderTemplate(template, vars);
    }

    private String loadTemplate(String resourcePath) throws ISchedulerException {
        try(InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)){
            if (is == null) throw new ISchedulerException("Resource not found: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e){
            throw new ISchedulerException("Failed to load resource: " + resourcePath, e);
        }
    }

    private String renderTemplate(String template, Map<String, String> vars) {
        String result = template;
        for (var e : vars.entrySet()) {
            String value = e.getValue() != null ? e.getValue() : "";
            result = result.replace("{{" + e.getKey() + "}}", value);
        }
        return result;
    }

    private static String shellEscapeSingleQuotes(String s) {
        if (s == null) return "";
        return s.replace("'", "'\"'\"'");
    }
}