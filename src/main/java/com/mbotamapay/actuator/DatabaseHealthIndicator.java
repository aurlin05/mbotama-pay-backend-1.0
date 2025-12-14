package com.mbotamapay.actuator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Database Health Indicator
 * Reports detailed database connection health and response time
 */
@Component("database")
@RequiredArgsConstructor
@Slf4j
public class DatabaseHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        long startTime = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection()) {
            // Check connection is valid
            if (!connection.isValid(5)) {
                return Health.down()
                        .withDetail("error", "Connection validation failed")
                        .build();
            }

            // Execute simple query to measure response time
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1");
                    ResultSet rs = ps.executeQuery()) {

                if (!rs.next()) {
                    return Health.down()
                            .withDetail("error", "Query returned no results")
                            .build();
                }
            }

            long responseTime = System.currentTimeMillis() - startTime;

            // Get connection pool info
            String dbProduct = connection.getMetaData().getDatabaseProductName();
            String dbVersion = connection.getMetaData().getDatabaseProductVersion();

            Health.Builder builder = Health.up()
                    .withDetail("database", dbProduct)
                    .withDetail("version", dbVersion)
                    .withDetail("responseTimeMs", responseTime);

            // Warning if response time is high
            if (responseTime > 1000) {
                log.warn("Database response time is slow: {}ms", responseTime);
                builder.withDetail("warning", "High response time");
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Database health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("responseTimeMs", System.currentTimeMillis() - startTime)
                    .build();
        }
    }
}
