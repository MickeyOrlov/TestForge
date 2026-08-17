package io.testforge.db.datasource;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Thread-safe, immutable registry of named {@link DataSource} instances.
 */
public final class DataSourceRegistry {

    private final SortedMap<String, DataSource> dataSources;
    private final String defaultName;

    public DataSourceRegistry(Map<String, DataSource> dataSources, String defaultName) {
        if (dataSources == null) {
            throw new IllegalArgumentException("DataSources map must not be null");
        }
        SortedMap<String, DataSource> copy = new TreeMap<>();
        for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
            String key = entry.getKey();
            DataSource value = entry.getValue();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("DataSource name must not be null or blank");
            }
            if (value == null) {
                throw new IllegalArgumentException("DataSource value for name '" + key + "' must not be null");
            }
            copy.put(key, value);
        }
        this.dataSources = Collections.unmodifiableSortedMap(copy);

        if (defaultName != null && !defaultName.isBlank()) {
            if (!this.dataSources.containsKey(defaultName)) {
                throw new IllegalArgumentException("Default DataSource name '" + defaultName
                        + "' not found in configured DataSources: " + this.dataSources.keySet());
            }
            this.defaultName = defaultName;
        } else {
            this.defaultName = null;
        }
    }

    public DataSource resolve(String name) {
        if (name == null || name.isBlank()) {
            return resolveDefault();
        }
        DataSource ds = dataSources.get(name);
        if (ds == null) {
            throw new IllegalArgumentException("No DataSource configured with name '" + name
                    + "'. Configured DataSources: " + names()
                    + ". Register a DataSource bean with name '" + name + "' or check the bean name.");
        }
        return ds;
    }

    public DataSource resolveDefault() {
        String name = defaultName();
        return dataSources.get(name);
    }

    public String defaultName() {
        if (defaultName != null) {
            return defaultName;
        }
        if (dataSources.isEmpty()) {
            throw new IllegalStateException("No DataSource is configured in the registry. Register at least one DataSource bean.");
        }
        if (dataSources.size() == 1) {
            return dataSources.keySet().iterator().next();
        }
        throw new IllegalStateException("Multiple DataSources configured " + names()
                + " but no default specified. Set 'forge.db.default-datasource' or mark one DataSource bean with @Primary.");
    }

    public Set<String> names() {
        return dataSources.keySet();
    }
}
