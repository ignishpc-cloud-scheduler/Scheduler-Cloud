package org.ignis.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

public class AwsFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(AwsFactory.class);

    private final Region region;

    public AwsFactory(Region region) {
        this.region = region;
        LOGGER.info("Initializing AWS factory for region {}", region);
    }

    public Region getRegion() {
        return region;
    }

    // Reference: [20], [21]
    public Ec2Client createEc2Client() {
        var builder = Ec2Client.builder();
        if(region != null) {
            builder.region(region);
        }
        return builder.build();
    }

    // Reference: [28]
    public S3Client createS3Client() {
        var builder = S3Client.builder();
        if(region != null) {
            builder.region(region);
        }
        return builder.build();
    }

    public SsmClient createSsmClient(){
        var builder =  SsmClient.builder();
        if(region != null) {
            builder.region(region);
        }
        return builder.build();
    }
}


