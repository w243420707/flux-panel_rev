package com.admin.config;

/** Shared by fresh installs and the existing idempotent database migration runner. */
public final class NodeUsageSchema {
    private NodeUsageSchema() {}

    public static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS node_vps_usage (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              node_id INT NOT NULL,
              hardware_id CHAR(64) NOT NULL DEFAULT '',
              system_id CHAR(64) NOT NULL DEFAULT '',
              mac_id CHAR(64) NOT NULL DEFAULT '',
              installation_id VARCHAR(36) NOT NULL DEFAULT '',
              weak_identity_approved TINYINT NOT NULL DEFAULT 0,
              started_at BIGINT NOT NULL,
              replaced_at BIGINT NULL,
              address VARCHAR(255) NOT NULL DEFAULT '',
              upload_bytes BIGINT NOT NULL DEFAULT 0,
              download_bytes BIGINT NOT NULL DEFAULT 0,
              meter_epoch VARCHAR(36) NOT NULL,
              last_sequence BIGINT NOT NULL,
              checkpoint_upload_bytes BIGINT NOT NULL,
              checkpoint_download_bytes BIGINT NOT NULL,
              last_reported_at BIGINT NOT NULL,
              KEY idx_node_vps_usage_node (node_id, id),
              CONSTRAINT fk_node_vps_usage_node FOREIGN KEY (node_id) REFERENCES node(id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;
}
