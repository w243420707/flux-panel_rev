-- phpMyAdmin SQL Dump
-- version 5.2.0
-- https://www.phpmyadmin.net/
--
-- 主机： localhost
-- 生成日期： 2025-08-14 21:52:52
-- 服务器版本： 5.7.40-log
-- PHP 版本： 7.4.33

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

--
-- 数据库： `gost`
--

-- --------------------------------------------------------

--
-- 表的结构 `forward`
--

CREATE TABLE `forward` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `user_name` varchar(100) NOT NULL,
  `name` varchar(100) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `in_port` int(10) NOT NULL,
  `out_port` int(10) DEFAULT NULL,
  `remote_addr` longtext NOT NULL,
  `strategy` varchar(100) NOT NULL DEFAULT 'fifo',
  `interface_name` varchar(200) DEFAULT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL,
  `inx` int(10) NOT NULL DEFAULT '0'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `node`
--

CREATE TABLE `node` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `secret` varchar(100) NOT NULL,
  `remote_change_ip_token` varchar(128) NOT NULL DEFAULT '',
  `change_ip_min_interval_minutes` int(10) DEFAULT NULL,
  `change_ip_remote_api` varchar(1000) DEFAULT NULL,
  `ip` longtext,
  `server_ip` varchar(100) NOT NULL DEFAULT '',
  `server_ipv4` varchar(100) NOT NULL DEFAULT '',
  `server_ipv6` varchar(100) NOT NULL DEFAULT '',
  `port_sta` int(10) NOT NULL,
  `port_end` int(10) NOT NULL,
  `version` varchar(100) DEFAULT NULL,
  `wall_monitor_enabled` tinyint(1) NOT NULL DEFAULT '1',
  `wall_monitor_status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `wall_monitor_last_check_at` bigint(20) DEFAULT NULL,
  `wall_monitor_consecutive_failures` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_china_success_count` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_china_total_count` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_global_success_count` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_global_total_count` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_latency_ms` double DEFAULT NULL,
  `wall_monitor_message` varchar(1000) DEFAULT NULL,
  `wall_monitor_external_status` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
  `wall_monitor_external_last_check_at` bigint(20) DEFAULT NULL,
  `wall_monitor_external_consecutive_failures` int(10) NOT NULL DEFAULT '0',
  `wall_monitor_external_message` varchar(1000) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `speed_limit`
--

CREATE TABLE `speed_limit` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `speed` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `tunnel_name` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `statistics_flow`
--

CREATE TABLE `statistics_flow` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `total_flow` bigint(20) NOT NULL,
  `time` varchar(100) NOT NULL,
  `created_time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `tunnel`
--

CREATE TABLE `tunnel` (
  `id` int(10) NOT NULL,
  `name` varchar(100) NOT NULL,
  `traffic_ratio` decimal(10,1) NOT NULL DEFAULT '1.0',
  `in_node_id` int(10) NOT NULL,
  `in_node_ids` longtext DEFAULT NULL,
  `in_ip` varchar(1000) NOT NULL,
  `out_node_id` int(10) NOT NULL,
  `out_node_ids` longtext DEFAULT NULL,
  `out_ip` varchar(1000) NOT NULL,
  `type` int(10) NOT NULL,
  `protocol` varchar(10) NOT NULL DEFAULT 'tls',
  `flow` int(10) NOT NULL,
  `tcp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `udp_listen_addr` varchar(100) NOT NULL DEFAULT '[::]',
  `interface_name` varchar(200) DEFAULT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `user`
--

CREATE TABLE `user` (
  `id` int(10) NOT NULL,
  `user` varchar(100) NOT NULL,
  `pwd` varchar(100) NOT NULL,
  `role_id` int(10) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `num` int(10) NOT NULL,
  `created_time` bigint(20) NOT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `user`
--

INSERT INTO `user` (`id`, `user`, `pwd`, `role_id`, `exp_time`, `flow`, `in_flow`, `out_flow`, `flow_reset_time`, `num`, `created_time`, `updated_time`, `status`) VALUES
(1, 'admin_user', '3c85cdebade1c51cf64ca9f3c09d182d', 0, 2727251700000, 99999, 0, 0, 0, 99999, 1748914865000, 1754011744252, 1);

-- --------------------------------------------------------

--
-- 表的结构 `user_tunnel`
--

CREATE TABLE `user_tunnel` (
  `id` int(10) NOT NULL,
  `user_id` int(10) NOT NULL,
  `tunnel_id` int(10) NOT NULL,
  `speed_id` int(10) DEFAULT NULL,
  `num` int(10) NOT NULL,
  `flow` bigint(20) NOT NULL,
  `in_flow` bigint(20) NOT NULL DEFAULT '0',
  `out_flow` bigint(20) NOT NULL DEFAULT '0',
  `flow_reset_time` bigint(20) NOT NULL,
  `exp_time` bigint(20) NOT NULL,
  `status` int(10) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- --------------------------------------------------------

--
-- 表的结构 `vite_config`
--

CREATE TABLE `vite_config` (
  `id` int(10) NOT NULL,
  `name` varchar(200) NOT NULL,
  `value` varchar(200) NOT NULL,
  `time` bigint(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `vite_config`
--

INSERT INTO `vite_config` (`id`, `name`, `value`, `time`) VALUES
(1, 'app_name', 'flux', 1755147963000);

-- --------------------------------------------------------

--
-- 表的结构 `cloudflare_dns_setting`
--

CREATE TABLE `cloudflare_dns_setting` (
  `id` int(10) NOT NULL,
  `created_time` bigint(20) DEFAULT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) DEFAULT NULL,
  `enabled` int(10) DEFAULT NULL,
  `api_token` varchar(1000) DEFAULT NULL,
  `zone_id` varchar(255) DEFAULT NULL,
  `zone_name` varchar(255) DEFAULT NULL,
  `ttl` int(10) DEFAULT NULL,
  `proxied` int(10) DEFAULT NULL,
  `record_type` varchar(20) DEFAULT NULL,
  `sync_interval_seconds` int(10) DEFAULT NULL,
  `auto_update_node_ip` int(10) DEFAULT NULL,
  `last_sync_at` bigint(20) DEFAULT NULL,
  `last_sync_status` varchar(32) DEFAULT NULL,
  `last_sync_message` varchar(1000) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转存表中的数据 `cloudflare_dns_setting`
--

INSERT INTO `cloudflare_dns_setting` (`id`, `created_time`, `updated_time`, `status`, `enabled`, `ttl`, `proxied`, `record_type`, `sync_interval_seconds`, `auto_update_node_ip`) VALUES
(1, UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000, 1, 0, 60, 0, 'AUTO', 120, 1);

-- --------------------------------------------------------

--
-- 表的结构 `cloudflare_dns_binding`
--

CREATE TABLE `cloudflare_dns_binding` (
  `id` int(10) NOT NULL,
  `created_time` bigint(20) DEFAULT NULL,
  `updated_time` bigint(20) DEFAULT NULL,
  `status` int(10) DEFAULT NULL,
  `tunnel_id` int(10) DEFAULT NULL,
  `domain` longtext DEFAULT NULL,
  `node_ids` longtext DEFAULT NULL,
  `use_tunnel_nodes` int(10) DEFAULT NULL,
  `record_type` varchar(20) DEFAULT NULL,
  `smart_pool_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `smart_pool_preferred_node_ids` longtext DEFAULT NULL,
  `smart_pool_active_node_ids` longtext DEFAULT NULL,
  `smart_pool_backup_node_ids` longtext DEFAULT NULL,
  `smart_pool_last_switch_at` bigint(20) DEFAULT NULL,
  `smart_pool_next_rotate_at` bigint(20) DEFAULT NULL,
  `last_sync_at` bigint(20) DEFAULT NULL,
  `last_sync_status` varchar(32) DEFAULT NULL,
  `last_sync_message` varchar(1000) DEFAULT NULL,
  `last_resolved_ips` longtext DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

--
-- 转储表的索引
--

--
-- 表的索引 `forward`
--
ALTER TABLE `forward`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_forward_user_created` (`user_id`,`created_time`),
  ADD KEY `idx_forward_user_tunnel_status` (`user_id`,`tunnel_id`,`status`),
  ADD KEY `idx_forward_tunnel` (`tunnel_id`),
  ADD KEY `idx_forward_status` (`status`);

--
-- 表的索引 `node`
--
ALTER TABLE `node`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_node_secret` (`secret`),
  ADD KEY `idx_node_status` (`status`),
  ADD KEY `idx_node_wall_monitor_status` (`wall_monitor_status`);

--
-- 表的索引 `speed_limit`
--
ALTER TABLE `speed_limit`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_speed_limit_tunnel_id` (`tunnel_id`);

--
-- 表的索引 `statistics_flow`
--
ALTER TABLE `statistics_flow`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_statistics_flow_user_id_id` (`user_id`,`id`),
  ADD KEY `idx_statistics_flow_created_time` (`created_time`);

--
-- 表的索引 `tunnel`
--
ALTER TABLE `tunnel`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_tunnel_in_node_id` (`in_node_id`),
  ADD KEY `idx_tunnel_out_node_id` (`out_node_id`),
  ADD KEY `idx_tunnel_status` (`status`);

--
-- 表的索引 `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_status_exp` (`status`,`exp_time`),
  ADD KEY `idx_user_flow_reset` (`flow_reset_time`),
  ADD KEY `idx_user_role_status` (`role_id`,`status`);

--
-- 表的索引 `user_tunnel`
--
ALTER TABLE `user_tunnel`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_user_tunnel_user_tunnel` (`user_id`,`tunnel_id`),
  ADD KEY `idx_user_tunnel_status_exp` (`status`,`exp_time`),
  ADD KEY `idx_user_tunnel_flow_reset` (`flow_reset_time`);

--
-- 表的索引 `vite_config`
--
ALTER TABLE `vite_config`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `name` (`name`);

--
-- 表的索引 `cloudflare_dns_setting`
--
ALTER TABLE `cloudflare_dns_setting`
  ADD PRIMARY KEY (`id`);

--
-- 表的索引 `cloudflare_dns_binding`
--
ALTER TABLE `cloudflare_dns_binding`
  ADD PRIMARY KEY (`id`),
  ADD KEY `idx_cloudflare_dns_binding_tunnel_id` (`tunnel_id`),
  ADD KEY `idx_cloudflare_dns_binding_status` (`status`);

--
-- 在导出的表使用AUTO_INCREMENT
--

--
-- 使用表AUTO_INCREMENT `forward`
--
ALTER TABLE `forward`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `node`
--
ALTER TABLE `node`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `speed_limit`
--
ALTER TABLE `speed_limit`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `statistics_flow`
--
ALTER TABLE `statistics_flow`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `tunnel`
--
ALTER TABLE `tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user`
--
ALTER TABLE `user`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `user_tunnel`
--
ALTER TABLE `user_tunnel`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `vite_config`
--
ALTER TABLE `vite_config`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;

--
-- 使用表AUTO_INCREMENT `cloudflare_dns_setting`
--
ALTER TABLE `cloudflare_dns_setting`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=2;

--
-- 使用表AUTO_INCREMENT `cloudflare_dns_binding`
--
ALTER TABLE `cloudflare_dns_binding`
  MODIFY `id` int(10) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
