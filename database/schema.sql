CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account VARCHAR(128) NOT NULL,
    email VARCHAR(254) NULL,
    phone VARCHAR(11) NULL,
    password_hash VARCHAR(255) NOT NULL,
    create_time DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_account (account),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fraud_news (
    url VARCHAR(2048) NOT NULL,
    title TEXT NULL,
    summary TEXT NULL,
    content MEDIUMTEXT NULL,
    publish_time DATETIME NULL,
    source VARCHAR(255) NULL,
    fraud_tags VARCHAR(1024) NULL,
    confidence DOUBLE NULL,
    raw_html MEDIUMTEXT NULL,
    content_hash CHAR(64) NOT NULL,
    deepseek_analysis MEDIUMTEXT NULL,
    UNIQUE KEY uk_fraud_news_url (url(768))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
