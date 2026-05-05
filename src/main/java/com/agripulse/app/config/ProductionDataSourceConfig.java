package com.agripulse.app.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

// Production platforms often provide PostgreSQL URLs in slightly different formats.
// This config accepts either a normal JDBC URL or a cloud-style postgres:// URL.
@Configuration
@Profile("prod")
public class ProductionDataSourceConfig {

    @Bean
    public HikariDataSource productionDataSource(
            @Value("${DATABASE_URL}") String databaseUrl,
            @Value("${DATABASE_USERNAME:}") String databaseUsername,
            @Value("${DATABASE_PASSWORD:}") String databasePassword) {

        if (!StringUtils.hasText(databaseUrl)) {
            throw new IllegalStateException("DATABASE_URL is required in production.");
        }

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");

        String normalizedUrl = databaseUrl.trim();
        if (normalizedUrl.startsWith("jdbc:postgresql://")) {
            dataSource.setJdbcUrl(normalizedUrl);

            if (StringUtils.hasText(databaseUsername)) {
                dataSource.setUsername(databaseUsername);
            }
            if (StringUtils.hasText(databasePassword)) {
                dataSource.setPassword(databasePassword);
            }

            return dataSource;
        }

        try {
            URI uri = new URI(normalizeCloudPostgresUrl(normalizedUrl));
            if (!StringUtils.hasText(uri.getHost()) || !StringUtils.hasText(uri.getPath())) {
                throw new IllegalStateException("DATABASE_URL must include a host and database name.");
            }

            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
            if (StringUtils.hasText(uri.getQuery())) {
                jdbcUrl = jdbcUrl + "?" + uri.getQuery();
            }
            dataSource.setJdbcUrl(jdbcUrl);

            String[] credentials = extractCredentials(uri.getUserInfo());
            String username = StringUtils.hasText(databaseUsername) ? databaseUsername : credentials[0];
            String password = StringUtils.hasText(databasePassword) ? databasePassword : credentials[1];

            if (StringUtils.hasText(username)) {
                dataSource.setUsername(username);
            }
            if (StringUtils.hasText(password)) {
                dataSource.setPassword(password);
            }

            return dataSource;
        }
        catch (URISyntaxException exception) {
            throw new IllegalStateException("DATABASE_URL is not a valid PostgreSQL connection string.", exception);
        }
    }

    private String normalizeCloudPostgresUrl(String databaseUrl) {
        String lower = databaseUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("postgres://") || lower.startsWith("postgresql://")) {
            return databaseUrl;
        }
        throw new IllegalStateException("DATABASE_URL must start with jdbc:postgresql://, postgres://, or postgresql://");
    }

    private String[] extractCredentials(String userInfo) {
        if (!StringUtils.hasText(userInfo) || !userInfo.contains(":")) {
            return new String[]{"", ""};
        }

        String[] pieces = userInfo.split(":", 2);
        return new String[]{pieces[0], pieces[1]};
    }
}
