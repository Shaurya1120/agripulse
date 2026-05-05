package com.agripulse.app.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
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

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");

        if (databaseUrl.startsWith("jdbc:postgresql://")) {
            dataSource.setJdbcUrl(databaseUrl);

            if (StringUtils.hasText(databaseUsername)) {
                dataSource.setUsername(databaseUsername);
            }
            if (StringUtils.hasText(databasePassword)) {
                dataSource.setPassword(databasePassword);
            }

            return dataSource;
        }

        try {
            URI uri = new URI(databaseUrl);
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
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

    private String[] extractCredentials(String userInfo) {
        if (!StringUtils.hasText(userInfo) || !userInfo.contains(":")) {
            return new String[]{"", ""};
        }

        String[] pieces = userInfo.split(":", 2);
        return new String[]{pieces[0], pieces[1]};
    }
}
