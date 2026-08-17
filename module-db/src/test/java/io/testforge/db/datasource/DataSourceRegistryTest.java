package io.testforge.db.datasource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceRegistryTest {

    private final DataSource ds1 = createDataSource("jdbc:h2:mem:ds1;DB_CLOSE_DELAY=-1");
    private final DataSource ds2 = createDataSource("jdbc:h2:mem:ds2;DB_CLOSE_DELAY=-1");

    private static DataSource createDataSource(String url) {
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url(url)
                .build();
    }

    @Test
    void resolveByName() {
        Map<String, DataSource> map = Map.of("primary", ds1, "audit", ds2);
        DataSourceRegistry registry = new DataSourceRegistry(map, "primary");

        assertThat(registry.resolve("primary")).isSameAs(ds1);
        assertThat(registry.resolve("audit")).isSameAs(ds2);
    }

    @Test
    void resolveNullOrBlankFallsBackToDefault() {
        Map<String, DataSource> map = Map.of("primary", ds1, "audit", ds2);
        DataSourceRegistry registry = new DataSourceRegistry(map, "primary");

        assertThat(registry.resolve(null)).isSameAs(ds1);
        assertThat(registry.resolve("")).isSameAs(ds1);
        assertThat(registry.resolve("   ")).isSameAs(ds1);
    }

    @Test
    void singleDataSourceIsDefaultWithNoConfiguration() {
        Map<String, DataSource> map = Map.of("singleDs", ds1);
        DataSourceRegistry registry = new DataSourceRegistry(map, null);

        assertThat(registry.defaultName()).isEqualTo("singleDs");
        assertThat(registry.resolveDefault()).isSameAs(ds1);
        assertThat(registry.resolve(null)).isSameAs(ds1);
    }

    @Test
    void explicitDefaultWins() {
        Map<String, DataSource> map = Map.of("ds1", ds1, "ds2", ds2);
        DataSourceRegistry registry = new DataSourceRegistry(map, "ds2");

        assertThat(registry.defaultName()).isEqualTo("ds2");
        assertThat(registry.resolveDefault()).isSameAs(ds2);
    }

    @Test
    void unknownNameThrowsIllegalArgumentExceptionWithDetailedMessage() {
        Map<String, DataSource> map = Map.of("alpha", ds1, "beta", ds2);
        DataSourceRegistry registry = new DataSourceRegistry(map, "alpha");

        assertThatThrownBy(() -> registry.resolve("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("[alpha, beta]")
                .hasMessageContaining("Register a DataSource bean with name 'unknown'");
    }

    @Test
    void unknownConfiguredDefaultFailsAtConstruction() {
        Map<String, DataSource> map = Map.of("ds1", ds1, "ds2", ds2);

        assertThatThrownBy(() -> new DataSourceRegistry(map, "nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("[ds1, ds2]");
    }

    @Test
    void twoDataSourcesWithNoDefaultMakeResolveDefaultThrowIllegalStateException() {
        Map<String, DataSource> map = Map.of("audit", ds2, "primary", ds1);
        DataSourceRegistry registry = new DataSourceRegistry(map, null);

        assertThatThrownBy(registry::resolveDefault)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[audit, primary]")
                .hasMessageContaining("forge.db.default-datasource")
                .hasMessageContaining("@Primary");

        assertThatThrownBy(registry::defaultName)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[audit, primary]")
                .hasMessageContaining("forge.db.default-datasource")
                .hasMessageContaining("@Primary");
    }

    @Test
    void zeroDataSourcesThrowIllegalStateException() {
        DataSourceRegistry registry = new DataSourceRegistry(Map.of(), null);

        assertThatThrownBy(registry::resolveDefault)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DataSource is configured");

        assertThatThrownBy(registry::defaultName)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DataSource is configured");
    }

    @Test
    void mapIsDefensivelyCopied() {
        Map<String, DataSource> mutableMap = new HashMap<>();
        mutableMap.put("ds1", ds1);

        DataSourceRegistry registry = new DataSourceRegistry(mutableMap, null);
        mutableMap.put("ds2", ds2);

        assertThat(registry.names()).containsExactly("ds1");
        assertThatThrownBy(() -> registry.resolve("ds2")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namesIsSortedAndUnmodifiable() {
        Map<String, DataSource> map = Map.of("zebra", ds2, "apple", ds1);
        DataSourceRegistry registry = new DataSourceRegistry(map, null);

        assertThat(registry.names()).containsExactly("apple", "zebra");
        assertThatThrownBy(() -> registry.names().add("banana")).isInstanceOf(UnsupportedOperationException.class);
    }
}
