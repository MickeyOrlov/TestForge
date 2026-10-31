package io.testforge.db.logging;

import io.testforge.db.diagnostics.SqlJournal;
import io.testforge.db.diagnostics.SqlJournalListener;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps every DataSource bean with a proxy that logs each SQL statement a
 * test executes (logger name: {@code forge.sql}, with {@code forge.db.log-sql})
 * and/or records it for failed-test diagnostics (with
 * {@code forge.db.diagnostics.enabled}). When a DB assertion fails, the query
 * that produced the result is the first thing you want to see.
 */
public class SqlLoggingDataSourcePostProcessor implements BeanPostProcessor {

    private final boolean log;
    private final Supplier<SqlJournal> journal;

    /** Logging only, as before diagnostics existed. */
    public SqlLoggingDataSourcePostProcessor() {
        this(true, null);
    }

    /**
     * @param journal looked up per statement, {@code null} for no recording: the
     *                datasource is wrapped before the journal bean exists
     */
    public SqlLoggingDataSourcePostProcessor(boolean log, Supplier<SqlJournal> journal) {
        this.log = log;
        this.journal = journal;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource) && (log || journal != null)) {
            ProxyDataSourceBuilder builder = ProxyDataSourceBuilder.create(dataSource).name(beanName);
            if (log) {
                builder.logQueryBySlf4j(SLF4JLogLevel.INFO, "forge.sql").multiline();
            }
            if (journal != null) {
                builder.listener(new SqlJournalListener(journal));
            }
            return builder.build();
        }
        return bean;
    }
}
