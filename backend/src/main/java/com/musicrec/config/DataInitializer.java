package com.musicrec.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Automatically seeds sample tracks and the anonymous user into MySQL
 * the first time the application starts (when the tracks table is empty).
 *
 * Runs AFTER Hibernate has applied ddl-auto:update (ensured by
 * spring.jpa.defer-datasource-initialization=true in application.yml).
 * Safe to run on every restart — skips if data already exists.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Override
    public void run(String... args) {
        try {
            Integer trackCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tracks", Integer.class);

            if (trackCount == null || trackCount == 0) {
                log.info("Tracks table is empty — loading sample music data...");
                ScriptUtils.executeSqlScript(
                        dataSource.getConnection(),
                        new ClassPathResource("data.sql"));
                Integer loaded = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tracks", Integer.class);
                log.info("Sample data loaded: {} tracks ready.", loaded);
            } else {
                log.info("Database already has {} tracks — skipping seed.", trackCount);
            }
        } catch (Exception e) {
            log.error("DataInitializer failed (non-fatal): {}", e.getMessage());
            // Non-fatal: app continues; user can run schema.sql manually if needed
        }
    }
}
