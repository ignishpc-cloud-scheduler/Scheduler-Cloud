package org.ignis.scheduler;

public class ExecutorHandle {
        
    private final String instanceId;
    private final String containerName;
    String privateIp;

    public ExecutorHandle(String instanceId, String containerName) {
        this.instanceId = instanceId;
        this.containerName = containerName;
    }

    public String getInstanceId(){
        return this.instanceId;
    }

    public String getContainerName(){
        return this.containerName;
    }
}