package com.magicvvu.fanzha.backend.service.fraud.storage;

import com.magicvvu.fanzha.backend.config.FraudNewsStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class JdbcFraudNewsStore {
    private final JdbcTemplate jdbcTemplate;
    private final FraudNewsStorageProperties properties;
    private final AtomicBoolean schemaEnsured = new AtomicBoolean(false);
    private volatile boolean summaryColumnAvailable = false;
    private volatile boolean fraudTagsColumnAvailable = false;
    private volatile boolean confidenceColumnAvailable = false;
    private volatile boolean deepseekAnalysisColumnAvailable = false;

    public boolean isDatabaseReady() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                return false;
            }
            ensureBaseTable();
            ensureExtendedSchema();
            return true;
        } catch (Exception e) {
            log.error("fraud_news database not ready: {}", e.getMessage());
            return false;
        }
    }

    public boolean upsert(FraudNewsRow row) {
        ensureBaseTable();
        ensureExtendedSchema();
        String table = sanitizeIdentifier(properties.getTable());
        FraudNewsStorageProperties.Columns c = properties.getColumns();

        String rowUrl = truncateUrl(row.getUrl());
        if (rowUrl == null || rowUrl.trim().isEmpty()) {
            // url 列是 NOT NULL + UNIQUE，无法为空；在输入缺失时用稳定占位值兜底，避免整条记录丢失
            rowUrl = "missing-url://" + System.currentTimeMillis();
        }
        String normalizedTitle = nullIfBlank(row.getTitle());
        String normalizedSummary = nullIfBlank(row.getSummary());
        String normalizedContent = nullIfBlank(row.getContent());
        String normalizedSource = nullIfBlank(row.getSource());
        String normalizedTags = nullIfBlank(row.getFraudTags());
        String normalizedRawHtml = nullIfBlank(row.getRawHtml());
        String normalizedDeepseek = nullIfBlank(row.getDeepseekAnalysisJson());
        String normalizedHash = nullIfBlank(row.getContentHash());
        if (normalizedHash == null) {
            normalizedHash = sha256Hex(
                    rowUrl + "\n"
                            + safe(normalizedTitle) + "\n"
                            + safe(normalizedContent) + "\n"
                            + safe(normalizedRawHtml)
            );
        }

        String existingHash = findContentHashByUrl(rowUrl);
        if (existingHash != null && !existingHash.trim().isEmpty() && existingHash.equals(normalizedHash)) {
            log.debug("Skip upsert: same url and content_hash already in DB. url={}", abbreviateUrl(rowUrl));
            return false;
        }
        // 不再按 content_hash 全局去重：多条不同 URL 可能对应相同正文（如搜狗占位文案），会误伤入库。

        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<String> updates = new ArrayList<>();

        columns.add(c.getUrl());
        values.add(rowUrl);
        updates.add(qname(c.getUrl()) + "=VALUES(" + qname(c.getUrl()) + ")");

        columns.add(c.getTitle());
        values.add(normalizedTitle);
        updates.add(qname(c.getTitle()) + "=VALUES(" + qname(c.getTitle()) + ")");

        if (summaryColumnAvailable) {
            columns.add(c.getSummary());
            values.add(normalizedSummary);
            updates.add(qname(c.getSummary()) + "=VALUES(" + qname(c.getSummary()) + ")");
        }

        columns.add(c.getContent());
        values.add(normalizedContent);
        updates.add(qname(c.getContent()) + "=VALUES(" + qname(c.getContent()) + ")");

        columns.add(c.getPublishTime());
        values.add(row.getPublishTime() == null ? null : Timestamp.valueOf(row.getPublishTime()));
        updates.add(qname(c.getPublishTime()) + "=VALUES(" + qname(c.getPublishTime()) + ")");

        columns.add(c.getSource());
        values.add(normalizedSource);
        updates.add(qname(c.getSource()) + "=VALUES(" + qname(c.getSource()) + ")");

        if (fraudTagsColumnAvailable) {
            columns.add(c.getFraudTags());
            values.add(normalizedTags);
            updates.add(qname(c.getFraudTags()) + "=VALUES(" + qname(c.getFraudTags()) + ")");
        }
        if (confidenceColumnAvailable) {
            columns.add(c.getConfidence());
            values.add(row.getConfidence());
            updates.add(qname(c.getConfidence()) + "=VALUES(" + qname(c.getConfidence()) + ")");
        }

        columns.add(c.getRawHtml());
        values.add(normalizedRawHtml);
        updates.add(qname(c.getRawHtml()) + "=VALUES(" + qname(c.getRawHtml()) + ")");

        columns.add(c.getContentHash());
        values.add(normalizedHash);
        updates.add(qname(c.getContentHash()) + "=VALUES(" + qname(c.getContentHash()) + ")");

        if (deepseekAnalysisColumnAvailable && c.getDeepseekAnalysis() != null && !c.getDeepseekAnalysis().trim().isEmpty()) {
            columns.add(c.getDeepseekAnalysis());
            values.add(normalizedDeepseek);
            updates.add(qname(c.getDeepseekAnalysis()) + "=VALUES(" + qname(c.getDeepseekAnalysis()) + ")");
        }

        List<String> quotedCols = new ArrayList<>();
        for (String col : columns) {
            quotedCols.add(qname(col));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO `").append(table).append("` (").append(String.join(",", quotedCols)).append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(",");
            }
            sql.append("?");
        }
        sql.append(") ON DUPLICATE KEY UPDATE ").append(String.join(",", updates));
        try {
            jdbcTemplate.update(sql.toString(), values.toArray());
        } catch (DataAccessException e) {
            log.error("fraud_news INSERT failed table={} url={} err={}", table, abbreviateUrl(rowUrl), e.getMessage());
            throw e;
        }
        log.info("fraud_news row persisted url={}", abbreviateUrl(rowUrl));
        return true;
    }

    private static final int MAX_URL_LENGTH = 2048;

    private static String truncateUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.length() <= MAX_URL_LENGTH) {
            return url;
        }
        return url.substring(0, MAX_URL_LENGTH);
    }

    private static String abbreviateUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    private static String nullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Long.toHexString(System.nanoTime());
        }
    }

    /**
     * 表不存在时 ALTER 会静默失败，导致后续 INSERT 全部报错。启动时先 CREATE TABLE IF NOT EXISTS。
     */
    private void ensureBaseTable() {
        String table = sanitizeIdentifier(properties.getTable());
        FraudNewsStorageProperties.Columns c = properties.getColumns();
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE IF NOT EXISTS `").append(table).append("` (");
        ddl.append("`").append(sanitizeIdentifier(c.getUrl())).append("` VARCHAR(").append(MAX_URL_LENGTH).append(") NOT NULL,");
        ddl.append("`").append(sanitizeIdentifier(c.getTitle())).append("` TEXT,");
        ddl.append("`").append(sanitizeIdentifier(c.getSummary())).append("` TEXT,");
        ddl.append("`").append(sanitizeIdentifier(c.getContent())).append("` MEDIUMTEXT,");
        ddl.append("`").append(sanitizeIdentifier(c.getPublishTime())).append("` DATETIME NULL,");
        ddl.append("`").append(sanitizeIdentifier(c.getSource())).append("` VARCHAR(255) NULL,");
        ddl.append("`").append(sanitizeIdentifier(c.getFraudTags())).append("` VARCHAR(1024) NULL,");
        ddl.append("`").append(sanitizeIdentifier(c.getConfidence())).append("` DOUBLE NULL,");
        ddl.append("`").append(sanitizeIdentifier(c.getRawHtml())).append("` MEDIUMTEXT,");
        ddl.append("`").append(sanitizeIdentifier(c.getContentHash())).append("` CHAR(64) NOT NULL");
        if (c.getDeepseekAnalysis() != null && !c.getDeepseekAnalysis().trim().isEmpty()) {
            ddl.append(",`").append(sanitizeIdentifier(c.getDeepseekAnalysis().trim())).append("` MEDIUMTEXT NULL");
        }
        ddl.append(", UNIQUE KEY `uk_fraud_news_url` (`").append(sanitizeIdentifier(c.getUrl())).append("`(768))");
        ddl.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        try {
            jdbcTemplate.execute(ddl.toString());
        } catch (DataAccessException e) {
            log.warn("CREATE TABLE IF NOT EXISTS failed (table may already exist with different DDL): {}", e.getMessage());
        }
    }

    private static String sanitizeIdentifier(String raw) {
        if (raw == null || !raw.matches("(?i)[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + raw);
        }
        return raw;
    }

    private String qname(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("column name required");
        }
        return "`" + sanitizeIdentifier(name.trim()) + "`";
    }

    private String findContentHashByUrl(String url) {
        String table = sanitizeIdentifier(properties.getTable());
        FraudNewsStorageProperties.Columns c = properties.getColumns();
        String sql = "SELECT " + qname(c.getContentHash()) + " FROM `" + table + "` WHERE " + qname(c.getUrl()) + "=? LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, url);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private void ensureExtendedSchema() {
        String table = sanitizeIdentifier(properties.getTable());
        FraudNewsStorageProperties.Columns c = properties.getColumns();
        if (schemaEnsured.compareAndSet(false, true)) {
            summaryColumnAvailable = ensureColumn(table, c.getSummary(), "TEXT NULL");
            fraudTagsColumnAvailable = ensureColumn(table, c.getFraudTags(), "VARCHAR(1024) NULL");
            confidenceColumnAvailable = ensureColumn(table, c.getConfidence(), "DOUBLE NULL");
            if (c.getDeepseekAnalysis() != null && !c.getDeepseekAnalysis().trim().isEmpty()) {
                deepseekAnalysisColumnAvailable = ensureColumn(table, c.getDeepseekAnalysis(), "MEDIUMTEXT NULL");
            }
        }
        // 即使初始化阶段失败，也在后续 upsert 持续自愈，避免 deepseek_analysis 一直不可写
        if (!summaryColumnAvailable && c.getSummary() != null && !c.getSummary().trim().isEmpty()) {
            summaryColumnAvailable = ensureColumn(table, c.getSummary(), "TEXT NULL");
        }
        if (!fraudTagsColumnAvailable && c.getFraudTags() != null && !c.getFraudTags().trim().isEmpty()) {
            fraudTagsColumnAvailable = ensureColumn(table, c.getFraudTags(), "VARCHAR(1024) NULL");
        }
        if (!confidenceColumnAvailable && c.getConfidence() != null && !c.getConfidence().trim().isEmpty()) {
            confidenceColumnAvailable = ensureColumn(table, c.getConfidence(), "DOUBLE NULL");
        }
        if (!deepseekAnalysisColumnAvailable && c.getDeepseekAnalysis() != null && !c.getDeepseekAnalysis().trim().isEmpty()) {
            deepseekAnalysisColumnAvailable = ensureColumn(table, c.getDeepseekAnalysis(), "MEDIUMTEXT NULL");
        }

        // 修复历史表结构：summary/deepseek_analysis 若被建成 VARCHAR，会导致截断或无法完整写入
        ensureTextCapacity(table, c.getSummary(), false);
        ensureTextCapacity(table, c.getDeepseekAnalysis(), true);
    }

    private boolean ensureColumn(String table, String column, String ddlType) {
        if (column == null || column.trim().isEmpty()) {
            return false;
        }
        String col = sanitizeIdentifier(column.trim());
        String tbl = sanitizeIdentifier(table);
        if (columnExists(tbl, col)) {
            return true;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + tbl + "` ADD COLUMN `" + col + "` " + ddlType);
        } catch (Exception e) {
            log.debug("ALTER ADD COLUMN skipped: {}.{}", tbl, col, e);
        }
        return columnExists(tbl, col);
    }

    private boolean columnExists(String table, String column) {
        try {
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW COLUMNS FROM `" + table + "` LIKE ?", column);
            return rows != null && !rows.isEmpty();
        } catch (Exception e) {
            log.debug("columnExists check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 若列类型是 VARCHAR/CHAR，升级为 TEXT 或 MEDIUMTEXT，避免 summary/deepseek_analysis 被截断。
     */
    private void ensureTextCapacity(String table, String column, boolean mediumTextPreferred) {
        if (column == null || column.trim().isEmpty()) {
            return;
        }
        String tbl = sanitizeIdentifier(table);
        String col = sanitizeIdentifier(column.trim());
        try {
            List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("SHOW COLUMNS FROM `" + tbl + "` LIKE ?", col);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            Object typeObj = rows.get(0).get("Type");
            String type = typeObj == null ? "" : typeObj.toString().toLowerCase();
            if (type.contains("text")) {
                return;
            }
            if (type.startsWith("varchar") || type.startsWith("char")) {
                String ddlType = mediumTextPreferred ? "MEDIUMTEXT NULL" : "TEXT NULL";
                try {
                    jdbcTemplate.execute("ALTER TABLE `" + tbl + "` MODIFY COLUMN `" + col + "` " + ddlType);
                    log.info("Upgraded column type to {} for {}.{}", ddlType, tbl, col);
                } catch (Exception e) {
                    log.warn("Failed to upgrade column type for {}.{} err={}", tbl, col, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("ensureTextCapacity skipped for {}.{} err={}", tbl, col, e.getMessage());
        }
    }
}
