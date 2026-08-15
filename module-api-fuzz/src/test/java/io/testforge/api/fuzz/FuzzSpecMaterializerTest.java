package io.testforge.api.fuzz;

import io.testforge.api.discovery.ApiDiscoveryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FuzzSpecMaterializerTest {

    @TempDir
    Path tempDir;

    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    @Test
    void unknownSpecThrows() {
        ApiDiscoveryProperties props = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "other", new ApiDiscoveryProperties.Spec("classpath:/other.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(props, resourceLoader, tempDir);

        assertThatThrownBy(() -> materializer.materialize("missing"))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("Unknown API spec id: 'missing'")
                .hasMessageContaining("[other]");
    }

    @Test
    void remoteUrlReturnsAsIs() {
        ApiDiscoveryProperties props = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("https://example.com/openapi.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(props, resourceLoader, tempDir);

        MaterializedSpec result = materializer.materialize("demo");

        assertThat(result).isInstanceOf(MaterializedSpec.RemoteUrl.class);
        assertThat(((MaterializedSpec.RemoteUrl) result).url()).isEqualTo("https://example.com/openapi.yaml");
    }

    @Test
    void missingResourceThrows() {
        ApiDiscoveryProperties props = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("classpath:/openapi/missing.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(props, resourceLoader, tempDir);

        assertThatThrownBy(() -> materializer.materialize("demo"))
                .isInstanceOf(ApiFuzzException.class)
                .hasMessageContaining("does not exist for 'demo'")
                .hasMessageContaining("classpath:/openapi/missing.yaml");
    }

    @Test
    void fileResourceReturnsUnchanged() throws Exception {
        Path specFile = tempDir.resolve("demo.yaml");
        Files.writeString(specFile, "openapi: 3.0.0\n");

        ApiDiscoveryProperties props = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("file:" + specFile.toAbsolutePath())
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(props, resourceLoader, tempDir);

        MaterializedSpec result = materializer.materialize("demo");

        assertThat(result).isInstanceOf(MaterializedSpec.LocalFile.class);
        assertThat(((MaterializedSpec.LocalFile) result).path()).isEqualTo(specFile.toAbsolutePath());
    }

    @Test
    void classpathResourceIsMaterialized() throws Exception {
        ApiDiscoveryProperties props = new ApiDiscoveryProperties(null, null, null, null, null, Map.of(
                "demo", new ApiDiscoveryProperties.Spec("classpath:/openapi/demo.yaml")
        ));
        FuzzSpecMaterializer materializer = new FuzzSpecMaterializer(props, resourceLoader, tempDir);

        MaterializedSpec result = materializer.materialize("demo");

        assertThat(result).isInstanceOf(MaterializedSpec.LocalFile.class);
        Path materializedPath = ((MaterializedSpec.LocalFile) result).path();
        
        assertThat(materializedPath).startsWith(tempDir.resolve("spec"));
        assertThat(materializedPath.getFileName().toString()).isEqualTo("demo.yaml");
        
        String content = Files.readString(materializedPath);
        assertThat(content).contains("openapi: 3.0.0");
    }
}
