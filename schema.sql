-- =============================================
-- AIbot 数据库表结构
-- 数据库: aibot
-- 字符集: utf8mb4
-- =============================================

CREATE DATABASE IF NOT EXISTS aibot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aibot;

-- -------------------------------------------
-- 会话历史表：记录每个会话的类型和ID映射
-- type: 业务类型（pdf / service）
-- chat_id: 会话唯一标识，格式 {模块编号}_{时间戳}_{随机串}
--   1_ = PDF知识图谱模块
--   2_ = 图书馆预约助手模块
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_history` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `type`       VARCHAR(32)  NOT NULL                COMMENT '会话类型：pdf / service',
    `chat_id`    VARCHAR(128) NOT NULL                COMMENT '会话ID',
    `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_type_chat_id` (`type`, `chat_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话历史表';
