package org.ignis.scheduler;

public record JobConfig (
    String subnet, 
    String sg, 
    String bucket, 
    String efs, 
    String efsMountIp, 
    String iamInstanceProfile
){}


