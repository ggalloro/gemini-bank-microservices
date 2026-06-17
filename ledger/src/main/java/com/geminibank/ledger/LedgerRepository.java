package com.geminibank.ledger;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * SQLite persistence via JdbcTemplate. Money is stored as integer cents; the
 * HTTP layer converts to/from 2-digit decimals.
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, n) -> new Account(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("iban"),
            rs.getString("name"),
            rs.getLong("balance"),
            rs.getString("created_at"));

    private static final RowMapper<Transaction> TX_MAPPER = (rs, n) -> new Transaction(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("type"),
            rs.getLong("amount"),
            rs.getString("counterparty"),
            rs.getString("description"),
            rs.getString("created_at"));

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account findAccount(long id) {
        List<Account> rows = jdbc.query("SELECT * FROM accounts WHERE id = ?", ACCOUNT_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Account> findAccountsByUser(long userId) {
        return jdbc.query("SELECT * FROM accounts WHERE user_id = ? ORDER BY id", ACCOUNT_MAPPER, userId);
    }

    /** Insert a new account; returns the created row, or null on IBAN collision. */
    public Account insertAccount(long userId, String iban, String name, String createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO accounts (user_id, iban, name, balance, created_at) VALUES (?, ?, ?, 0, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setString(2, iban);
                ps.setString(3, name);
                ps.setString(4, createdAt);
                return ps;
            }, keyHolder);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return null;
        }
        return findAccount(keyHolder.getKey().longValue());
    }

    public void adjustBalance(long accountId, long deltaCents) {
        jdbc.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", deltaCents, accountId);
    }

    /** Insert a transaction; returns the created row. */
    public Transaction insertTransaction(long accountId, String type, long amountCents,
                                         String counterparty, String description, String createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO transactions (account_id, type, amount, counterparty, description, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId);
            ps.setString(2, type);
            ps.setLong(3, amountCents);
            ps.setString(4, counterparty);
            ps.setString(5, description);
            ps.setString(6, createdAt);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return jdbc.query("SELECT * FROM transactions WHERE id = ?", TX_MAPPER, id).get(0);
    }

    public List<Transaction> findTransactions(long accountId, String dateFrom, String dateTo) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transactions WHERE account_id = ?");
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(accountId);
        if (dateFrom != null && !dateFrom.isEmpty()) {
            sql.append(" AND created_at >= ?");
            params.add(dateFrom);
        }
        if (dateTo != null && !dateTo.isEmpty()) {
            // Make 'to' inclusive of the whole day when a bare date is given.
            String upper = dateTo.contains("T") ? dateTo : dateTo + "T23:59:59.999999+00:00";
            sql.append(" AND created_at <= ?");
            params.add(upper);
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        return jdbc.query(sql.toString(), TX_MAPPER, params.toArray());
    }
}
