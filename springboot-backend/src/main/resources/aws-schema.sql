CREATE TABLE IF NOT EXISTS `aws_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(120) NOT NULL,
  `access_key_id` VARCHAR(128) NOT NULL,
  `access_key_encrypted` LONGTEXT NULL,
  `access_secret_encrypted` LONGTEXT NOT NULL,
  `session_token_encrypted` LONGTEXT NULL,
  `mfa_serial_number` VARCHAR(255) NULL,
  `mfa_secret_encrypted` LONGTEXT NULL,
  `proxy_encrypted` LONGTEXT NULL,
  `role_arn` VARCHAR(1000) NULL,
  `role_external_id_encrypted` LONGTEXT NULL,
  `role_session_name` VARCHAR(128) NULL,
  `region` VARCHAR(80) NOT NULL,
  `regions` LONGTEXT NULL,
  `proxy` VARCHAR(1000) NULL,
  `remark` VARCHAR(1000) NULL,
  `group_name` VARCHAR(120) NULL,
  `group_id` BIGINT NULL,
  `proxy_pool_id` BIGINT NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_aws_account_group` (`group_name`),
  KEY `idx_aws_account_region` (`region`),
  KEY `idx_aws_account_group_id` (`group_id`),
  KEY `idx_aws_account_proxy_id` (`proxy_pool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_account_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(120) NOT NULL,
  `remark` VARCHAR(500) NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_account_group_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_proxy_pool` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(120) NOT NULL,
  `endpoint` VARCHAR(500) NULL,
  `proxy_encrypted` LONGTEXT NOT NULL,
  `remark` VARCHAR(500) NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_proxy_pool_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_ec2_instance_profile` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NOT NULL,
  `region` VARCHAR(80) NULL,
  `instance_id` VARCHAR(64) NOT NULL,
  `name` VARCHAR(255) NULL,
  `instance_type` VARCHAR(80) NULL,
  `image_id` VARCHAR(80) NULL,
  `subnet_id` VARCHAR(80) NULL,
  `security_group_ids` VARCHAR(2000) NULL,
  `root_volume_size` VARCHAR(32) NULL,
  `user_data` LONGTEXT NULL,
  `tags_json` LONGTEXT NULL,
  `remark` VARCHAR(1000) NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_profile_instance` (`account_id`,`region`,`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_ec2_instance_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT NULL,
  `region` VARCHAR(80) NULL,
  `availability_zone` VARCHAR(80) NULL,
  `name` VARCHAR(255) NOT NULL,
  `image_id` VARCHAR(80) NULL,
  `instance_type` VARCHAR(80) NULL,
  `key_name` VARCHAR(255) NULL,
  `architecture` VARCHAR(32) NULL,
  `disk_gib` INT NULL,
  `password_encrypted` LONGTEXT NULL,
  `spot` TINYINT NULL,
  `enable_ipv6` TINYINT NULL,
  `disable_public_ip` TINYINT NULL,
  `static_ip` TINYINT NULL,
  `cidr` VARCHAR(128) NULL,
  `subnet_id` VARCHAR(80) NULL,
  `security_group_ids` VARCHAR(2000) NULL,
  `root_volume_size` VARCHAR(32) NULL,
  `user_data` LONGTEXT NULL,
  `tags_json` LONGTEXT NULL,
  `remark` VARCHAR(1000) NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_aws_template_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_node_binding` (
  `node_id` BIGINT NOT NULL,
  `account_id` BIGINT NOT NULL,
  `instance_id` VARCHAR(64) NOT NULL,
  `region` VARCHAR(80) NULL,
  `updated_time` BIGINT NULL,
  PRIMARY KEY (`node_id`),
  KEY `idx_aws_binding_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_share_group` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `expires_at` BIGINT NULL,
  `permissions` VARCHAR(1000) NOT NULL DEFAULT 'read',
  `node_ids_json` LONGTEXT NULL,
  `access_token` VARCHAR(255) NULL,
  `created_time` BIGINT NULL,
  `updated_time` BIGINT NULL,
  `status` INT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_share_token` (`access_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_failover_config` (
  `id` BIGINT NOT NULL,
  `enabled` TINYINT NOT NULL DEFAULT 0,
  `backup_group_id` BIGINT NULL,
  `max_instances` INT NOT NULL DEFAULT 1,
  `cron` VARCHAR(128) NOT NULL DEFAULT '0 */10 * * * *',
  `conditions_json` LONGTEXT NULL,
  `telegram_enabled` TINYINT NOT NULL DEFAULT 0,
  `node_ids_json` LONGTEXT NULL,
  `template_id` BIGINT NULL,
  `account_selection` VARCHAR(32) NULL DEFAULT 'ordered',
  `required_vcpu` INT NULL DEFAULT 2,
  `traffic_limit_bytes` BIGINT NULL,
  `telegram_bot_token_encrypted` LONGTEXT NULL,
  `telegram_chat_id` VARCHAR(255) NULL,
  `next_check_at` BIGINT NULL,
  `lease_until` BIGINT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_operation` (
  `id` VARCHAR(96) NOT NULL,
  `active_target` VARCHAR(255) NULL,
  `account_id` BIGINT NULL,
  `region` VARCHAR(80) NULL,
  `instance_id` VARCHAR(64) NULL,
  `action` VARCHAR(64) NULL,
  `request_hash` VARCHAR(128) NULL,
  `status` VARCHAR(32) NOT NULL,
  `message` VARCHAR(1000) NULL,
  `result_json` LONGTEXT NULL,
  `created_at` BIGINT NULL,
  `finished_at` BIGINT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_operation_active` (`active_target`),
  KEY `idx_aws_operation_account` (`account_id`),
  KEY `idx_aws_operation_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `aws_failover_job` (
  `id` VARCHAR(96) NOT NULL,
  `source_key` VARCHAR(255) NULL,
  `node_id` BIGINT NULL,
  `old_account_id` BIGINT NULL,
  `old_instance_id` VARCHAR(64) NULL,
  `old_region` VARCHAR(80) NULL,
  `new_account_id` BIGINT NULL,
  `new_instance_id` VARCHAR(64) NULL,
  `new_region` VARCHAR(80) NULL,
  `reason` VARCHAR(64) NULL,
  `status` VARCHAR(32) NULL,
  `message` VARCHAR(1000) NULL,
  `created_at` BIGINT NULL,
  `finished_at` BIGINT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aws_failover_source` (`source_key`),
  KEY `idx_aws_failover_node` (`node_id`),
  KEY `idx_aws_failover_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
