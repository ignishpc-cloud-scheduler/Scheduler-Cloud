package org.ignis.scheduler;

import java.util.List;

public record JobMeta(
        String jobId,
        String jobName,
        String bucket,
        String instanceId,
        String bundleKey,
        String image,
        String cmd,
        int cpus,
        long memory,
        String gpu,
        List<String> args,
        String driverHost
        // ports, binds, hostnames...
) {}
