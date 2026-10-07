
package gov.com.ai.webapp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Production database configuration for GST 3B batch processing.
 *
 * <p>Responsibilities:
 * <ul>
 *     <li>Configure HikariCP connection pool</li>
 *     <li>Configure Oracle JDBC performance properties</li>
 *     <li>Create primary DataSource</li>
 *     <li>Create JdbcTemplate</li>
 *     <li>Create transaction manager for chunk-level transactions</li>
 * </ul>
 *
 * <p>Important:
 * XGBoost prediction must NOT be executed while a JDBC connection is held.
 * The batch architecture should therefore be:
 *
 * <pre>
 * DB read chunk
 *      ↓
 * JDBC connection released
 *      ↓
 * XGBoost prediction
 *      ↓
 * DB batch MERGE
 *      ↓
 * transaction commit
 * </pre>
 */
@Configuration
@EnableTransactionManagement
@Slf4j
public class DbConfig {

    /**
     * Creates Hikari configuration.
     *
     * <p>Most environment-specific values such as:
     * <ul>
     *     <li>JDBC URL</li>
     *     <li>username</li>
     *     <li>password</li>
     *     <li>maximum pool size</li>
     *     <li>timeouts</li>
     * </ul>
     *
     * should be supplied from application.properties,
     * environment variables, Kubernetes secrets, etc.
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariConfig hikariConfig() {

        HikariConfig config = new HikariConfig();

        // ================================================================
        // Pool identity
        // ================================================================

        config.setPoolName("Gst3bHikariCP");

        // ================================================================
        // Oracle connection validation
        // ================================================================

        /*
         * Oracle does not support MySQL-style validation queries.
         * DUAL is lightweight and safe.
         */
        config.setConnectionTestQuery("SELECT 1 FROM DUAL");

        /*
         * Keep connection validation before handing a connection
         * to JdbcTemplate.
         */
        config.setValidationTimeout(5_000);

        // ================================================================
        // Transaction behavior
        // ================================================================

        /*
         * Spring's DataSourceTransactionManager controls transaction
         * boundaries for batch writes.
         *
         * JdbcTemplate operations outside a transaction can use the
         * pool default, while TransactionTemplate controls bulk MERGE
         * transactions explicitly.
         */
        config.setAutoCommit(true);

        // ================================================================
        // Hikari safety defaults
        // ================================================================

        /*
         * Do NOT use an aggressive 60-second leak threshold for large
         * Oracle batch processing.
         *
         * A legitimate Oracle operation may occasionally exceed 60 sec.
         *
         * Configure this through:
         *
         * spring.datasource.hikari.leak-detection-threshold
         *
         * Recommended production value:
         * 120000 (2 minutes)
         *
         * It is deliberately not hard-coded here because operations
         * teams may want to change it without recompilation.
         */

        // ================================================================
        // Oracle JDBC driver optimizations
        // ================================================================

        /*
         * Oracle implicit statement caching.
         */
        config.addDataSourceProperty(
                "implicitCachingEnabled",
                "true"
        );

        /*
         * Number of statements Oracle JDBC can cache.
         */
        config.addDataSourceProperty(
                "maxStatements",
                "250"
        );

        /*
         * Oracle JDBC batch value.
         *
         * JdbcTemplate.batchUpdate() remains the primary batching
         * mechanism. This property is an additional Oracle driver
         * optimization.
         */
        config.addDataSourceProperty(
                "defaultBatchValue",
                "1000"
        );

        /*
         * Oracle network row prefetch.
         *
         * Global JdbcTemplate fetch size is also configured below.
         */
        config.addDataSourceProperty(
                "defaultRowPrefetch",
                "100"
        );

        return config;
    }

    /**
     * Primary application DataSource.
     */
    @Bean
    @Primary
    public DataSource dataSource(HikariConfig hikariConfig) {

        log.info(
                "Initializing GST 3B HikariCP DataSource. poolName={}, jdbcUrl={}, maxPoolSize={}, minIdle={}",
                hikariConfig.getPoolName(),
                hikariConfig.getJdbcUrl(),
                hikariConfig.getMaximumPoolSize(),
                hikariConfig.getMinimumIdle()
        );

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);

        log.info(
                "GST 3B HikariCP initialized successfully. poolName={}, maximumPoolSize={}, minimumIdle={}, connectionTimeout={}ms",
                dataSource.getPoolName(),
                dataSource.getMaximumPoolSize(),
                dataSource.getMinimumIdle(),
                dataSource.getConnectionTimeout()
        );

        return dataSource;
    }

    /**
     * JdbcTemplate used by repositories.
     *
     * <p>The global fetch size is intentionally moderate. Individual
     * high-volume queries may override it using PreparedStatement.
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        /*
         * Suitable default for GST 3B bulk reads.
         *
         * Risk service can further control fetch size:
         *
         * ps.setFetchSize(Math.min(chunkSize, 1000));
         */
        jdbcTemplate.setFetchSize(1000);

        /*
         * Prevent indefinitely hanging SQL operations.
         *
         * This is JDBC statement timeout, not Hikari connection timeout.
         *
         * 300 seconds = 5 minutes.
         *
         * Individual operations can override this if required.
         */
        jdbcTemplate.setQueryTimeout(300);

        return jdbcTemplate;
    }

    /**
     * Transaction manager for chunk-level database transactions.
     *
     * <p>Do not wrap the entire monthly GST batch in one transaction.
     * Use TransactionTemplate so each chunk can be committed independently.
     */
    @Bean
    public PlatformTransactionManager transactionManager(
            DataSource dataSource) {

        return new DataSourceTransactionManager(dataSource);
    }
}

