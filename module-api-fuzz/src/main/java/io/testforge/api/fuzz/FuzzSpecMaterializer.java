package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class FuzzSpecMaterializer {

    private final ApiDiscoveryProperties discoveryProperties;
    private final ResourceLoader resourceLoader;
    private final Path outputDir;

    public FuzzSpecMaterializer(ApiDiscoveryProperties discoveryProperties, ResourceLoader resourceLoader, Path outputDir) {
        this.discoveryProperties = discoveryProperties;
        this.resourceLoader = resourceLoader;
        this.outputDir = outputDir;
    }

    public MaterializedSpec materialize(String specId) {
        Map<String, ApiDiscoveryProperties.Spec> specs = discoveryProperties.specs();
        if (!specs.containsKey(specId)) {
            throw new ApiFuzzException("Unknown API spec id: '" + specId + "'. Registered ids are: " + specs.keySet());
        }

        String location = specs.get(specId).location();
        if (location == null || location.isBlank()) {
            throw new ApiFuzzException("API spec location must not be blank for '" + specId + "'");
        }

        if (location.startsWith("http://") || location.startsWith("https://")) {
            return new MaterializedSpec.RemoteUrl(location);
        }

        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new ApiFuzzException("API spec resource does not exist for '" + specId + "' at location: " + location);
        }

        try {
            if (resource.isFile() && !location.startsWith("classpath:")) {
                return new MaterializedSpec.LocalFile(resource.getFile().toPath().toAbsolutePath().normalize());
            }

            String filename = resource.getFilename();
            if (filename == null) {
                filename = specId + ".yaml";
            }
            String extension = "";
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = filename.substring(dotIndex);
            }

            Path specOutputDir = outputDir.resolve("spec");
            Files.createDirectories(specOutputDir);

            Path targetFile = specOutputDir.resolve(specId + extension);
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return new MaterializedSpec.LocalFile(targetFile.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to materialize API spec '" + specId + "' from " + location, e);
        }
    }
}
