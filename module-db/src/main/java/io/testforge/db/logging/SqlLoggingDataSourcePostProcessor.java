package io.testforge.db.logging;

import javax.sql.DataSource;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every DataSource bean with a logging proxy so each SQL statement a
 * test executes shows up in the log (logger name: {@code forge.sql}). When a
 * DB assertion fails, the query that produced the result is the first thing
 * you want to see.
 */
public class SqlLoggingDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
            return ProxyDataSourceBuilder.create(dataSource)
                    .name(beanName)
                    .logQueryBySlf4j(SLF4JLogLevel.INFO, "forge.sql")
                    .multiline()
                    .build();
        }
        return bean;
    }
}
