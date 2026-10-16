-- 批量删除任务表 DDL
-- 用于记录批量删除异步任务的进度和结果
-- 2026-10-16 添加

CREATE TABLE IF NOT EXISTS `forward_batch_delete_task` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `task_id` varchar(64) NOT NULL COMMENT '任务 ID',
  `forward_ids` longtext NOT NULL COMMENT '待删除转发 ID 数组 JSON',
  `status` int(11) NOT NULL DEFAULT '0' COMMENT '任务状态 0-初始化 1-执行中 2-成功 3-失败 4-部分成功',
  `total` int(11) NOT NULL DEFAULT '0' COMMENT '总数',
  `processed` int(11) NOT NULL DEFAULT '0' COMMENT '已处理数',
  `success` int(11) NOT NULL DEFAULT '0' COMMENT '成功数',
  `fail` int(11) NOT NULL DEFAULT '0' COMMENT '失败数',
  `fail_reasons` longtext DEFAULT NULL COMMENT '失败原因 JSON',
  `created_by` bigint(20) DEFAULT NULL COMMENT '创建者',
  `created_at` bigint(20) DEFAULT NULL COMMENT '创建时间',
  `finished_at` bigint(20) DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量删除任务表';
