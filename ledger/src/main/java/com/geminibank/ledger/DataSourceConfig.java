package com.geminibank.ledger;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Wires a SQLite {@link DataSource} (path from {@code LEDGER_DB}, default
 * {@code ledger.db}) and a {@link JdbcTemplate}, then creates the schema.
 *
 * Deliberately uses plain JDBC + JdbcTemplate (no JPA/Hibernate) to keep the
 * service light and fast-starting, mirroring the original Flask + sqlite3 code.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${LEDGER_DB:ledger.db}") String dbPath) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        initSchema(jdbc);
        return jdbc;
    }

    private void initSchema(JdbcTemplate jdbc) {
        jdbc.execute("PRAGMA foreign_keys = ON");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id    INTEGER NOT NULL,
                    iban       TEXT NOT NULL UNIQUE,
                    name       TEXT NOT NULL,
                    balance    INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id   INTEGER NOT NULL,
                    type         TEXT NOT NULL,
                    amount       INTEGER NOT NULL,
                    counterparty TEXT,
                    description  TEXT,
                    created_at   TEXT NOT NULL,
                    FOREIGN KEY (account_id) REFERENCES accounts (id)
                )
                """);
    }
}
