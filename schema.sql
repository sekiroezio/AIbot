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


-- -------------------------------------------
-- 图书表：馆藏图书信息（供 BookQueryMcp 子项目使用）
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS `book` (
    `id`     INT          NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`   VARCHAR(128) NOT NULL                COMMENT '书名',
    `author` VARCHAR(64)  DEFAULT NULL            COMMENT '作者',
    `type`   VARCHAR(32)  DEFAULT NULL            COMMENT '类型：亚洲文学、欧美文学、诗歌、科幻、历史、其它',
    `stock`  INT          DEFAULT 0               COMMENT '库存数量',
    PRIMARY KEY (`id`),
    KEY `idx_type` (`type`),
    KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书表';


-- -------------------------------------------
-- 图书预约表：借阅预约记录（供 BookQueryMcp 子项目使用）
-- -------------------------------------------
CREATE TABLE IF NOT EXISTS `book_reservation` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '主键ID（同时作为预约单号）',
    `book_name`   VARCHAR(128) NOT NULL                COMMENT '书名',
    `reader_name` VARCHAR(64)  NOT NULL                COMMENT '借阅人姓名',
    `phone`       VARCHAR(20)  NOT NULL                COMMENT '借阅人电话',
    `remark`      VARCHAR(256) DEFAULT NULL            COMMENT '备注',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='图书预约表';


-- =============================================
-- 示例数据（可选）
-- =============================================

INSERT INTO `book` (`name`, `author`, `type`, `stock`) VALUES
('百年孤独', '加西亚·马尔克斯', '欧美文学', 5),
('活着', '余华', '亚洲文学', 3),
('三体', '刘慈欣', '科幻', 8),
('人间词话', '王国维', '诗歌', 2),
('万历十五年', '黄仁宇', '历史', 4),
('了不起的盖茨比', '菲茨杰拉德', '欧美文学', 6),
('1984', '乔治·奥威尔', '科幻', 3),
('围城', '钱钟书', '亚洲文学', 5),
('唐诗三百首', '蘅塘退士', '诗歌', 7),
('人类简史', '尤瓦尔·赫拉利', '历史', 2);
