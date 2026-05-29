package org.ignis.scheduler;

import software.amazon.awssdk.services.ec2.model.InstanceType;

public record ClusterConfig (
    String region, 
    String subnet, 
    String sg, 
    String ami, 
    String bucket, 
    String efsMountIp, 
    InstanceType instanceType

) {}

