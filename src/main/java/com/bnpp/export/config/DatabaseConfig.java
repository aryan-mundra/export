package com.bnpp.export.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

@Configuration
@Profile("!mock")
public class DatabaseConfig {

    static final String H2_URL = "jdbc:h2:mem:regliss;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
    static final String POSTGRES_URL = "jdbc:postgresql://localhost:543/regliss";

    @Bean(name = "primaryDataSource")
    @Primary
    public DataSource primaryDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(H2_URL)
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }

    @Bean(name = "reglissDataSource")
    public DataSource reglissDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES_URL);
        dataSource.setUsername("regliss_reader");
        dataSource.setPassword(System.getenv().getOrDefault("REG_DB_PASSWORD", "reader_password"));
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(5);
        dataSource.setPoolName("regliss-ro");
        dataSource.setConnectionTimeout(5_000);
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("primaryDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "reglissReadOnlyJdbcTemplate")
    public JdbcTemplate reglissReadOnlyJdbcTemplate(@Qualifier("reglissDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "reglissNamedParameterJdbcTemplate")
    public NamedParameterJdbcTemplate reglissNamedParameterJdbcTemplate(
            @Qualifier("reglissDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
