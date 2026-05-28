package org.ignis.scheduler;

public record LargeFile(
        String relativePath,
        String s3Key
) { }
