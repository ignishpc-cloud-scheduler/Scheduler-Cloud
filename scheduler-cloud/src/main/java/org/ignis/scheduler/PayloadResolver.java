package org.ignis.scheduler;

import org.ignis.scheduler.model.IBindMount;
import org.ignis.scheduler.model.IClusterRequest;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PayloadResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadResolver.class);
    private static final String CLOUD_PAYLOAD_DIR = "/ignis/dfs/payload";

    // Reference: [38], [39]
    public Path detectMainScript(List<String> args){
        if(args == null || args.isEmpty()) return null;
        for(String a: args){
            if(a == null) continue;
            Path path = Paths.get(a);
            if(Files.exists(path) && Files.isRegularFile(path)){
                return path.toAbsolutePath().normalize();
            }
        }
        LOGGER.debug("No main script detected in args {}", args);
        return null;
    }

    public List<IBindMount> buildPayloadBindsFromArgs(IClusterRequest driver) {
        Path script = detectMainScript(driver.resources().args());
        if (script == null) {
            return List.of();
        }

        Path scriptDir = script.getParent();
        if (scriptDir == null || !Files.isDirectory(scriptDir)) {
        
            LOGGER.warn("Script directory not found, falling back to single file upload");
            String cloudTarget = CLOUD_PAYLOAD_DIR + "/" + script.getFileName();
            return List.of(new IBindMount(cloudTarget, script.toString(), true));
        }

        return List.of(new IBindMount(CLOUD_PAYLOAD_DIR, scriptDir.toAbsolutePath().toString(), true));
    }

    public String resolveCloudScriptPath(IClusterRequest driver) {
        Path script = detectMainScript(driver.resources().args());
        if (script == null) {
            return null;
        }
        return CLOUD_PAYLOAD_DIR + "/" + script.getFileName();
    }

    public String resolveCommand(IClusterRequest driver) {
        List<String> args = driver.resources().args();
        if (args == null || args.isEmpty()) return "";

        Path mainLocalScript = detectMainScript(args);
        String cloudScriptPath = (mainLocalScript != null) ? resolveCloudScriptPath(driver) : null;

        List<String> command = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;

            if (mainLocalScript != null) {
                Path candidate = Paths.get(arg);
                try {
                    if (Files.exists(candidate) &&
                            Files.isRegularFile(candidate) &&
                            candidate.toAbsolutePath().normalize().equals(mainLocalScript)) {
                        command.add(cloudScriptPath);
                        continue;
                    }
                } catch (Exception ignored) {}
            }

            command.add(arg);
        }

        return String.join(" ", command);
    }
}
