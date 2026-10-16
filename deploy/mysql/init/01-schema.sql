
-- ============================================================================
-- PeakFlow 秒杀商城 · 表结构（schema）
-- ----------------------------------------------------------------------------
-- 用途：演示环境与生产环境【共用】。只建表，不含任何业务数据。
-- 加载：docker-compose 挂载到 MySQL 容器的 /docker-entrypoint-initdb.d/，
--       数据卷首次初始化时自动执行（卷已存在则不会重复执行）。
-- 表数：18 张 = 业务表 15 + Agent 扩展 3
-- 生成：mysqldump --no-data peakflow
-- ============================================================================

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+08:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `address`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `address` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `receiver_name` varchar(50) NOT NULL COMMENT '收货人姓名',
  `receiver_phone` varchar(20) NOT NULL COMMENT '收货人电话',
  `province` varchar(50) NOT NULL COMMENT '省份',
  `city` varchar(50) NOT NULL COMMENT '城市',
  `district` varchar(50) NOT NULL COMMENT '区/县',
  `detail_address` varchar(200) NOT NULL COMMENT '详细地址',
  `is_default` tinyint DEFAULT '0' COMMENT '是否默认地址：0-否，1-是',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_address` (`user_id`,`receiver_name`,`receiver_phone`,`province`,`city`,`district`,`detail_address`,`deleted`),
  KEY `idx_user_deleted_default_time` (`user_id`,`deleted`,`is_default`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收货地址表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `cart`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cart` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '购物车ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `quantity` int NOT NULL COMMENT '购买数量',
  `selected` tinyint DEFAULT '1' COMMENT '是否选中：0-未选中，1-选中',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`,`product_id`,`deleted`),
  KEY `idx_product_id` (`product_id`),
  CONSTRAINT `fk_cart_product_id` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`),
  CONSTRAINT `fk_cart_user_id` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `category`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `name` varchar(50) NOT NULL COMMENT '分类名称',
  `icon` varchar(500) DEFAULT NULL COMMENT '分类图标URL',
  `sort` int DEFAULT '0' COMMENT '排序（越小越靠前）',
  `status` tinyint DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`),
  KEY `idx_status_deleted_sort` (`status`,`deleted`,`sort`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `daily_task_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `daily_task_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `day_no` int NOT NULL COMMENT '第几天，1-80',
  `stage_no` int DEFAULT NULL COMMENT '阶段编号',
  `stage_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '阶段名称',
  `day_title` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '每日标题',
  `old_review` longtext COLLATE utf8mb4_unicode_ci COMMENT '旧内容主动回忆',
  `main_task` longtext COLLATE utf8mb4_unicode_ci COMMENT '当天主任务/项目任务',
  `p0_question` longtext COLLATE utf8mb4_unicode_ci COMMENT 'P0八股/今日八股题',
  `knowledge_points` longtext COLLATE utf8mb4_unicode_ci COMMENT '知识点与复习标签',
  `oral_practice` longtext COLLATE utf8mb4_unicode_ci COMMENT '面试口述',
  `review_checkin` longtext COLLATE utf8mb4_unicode_ci COMMENT '复盘打卡',
  `daily_output` longtext COLLATE utf8mb4_unicode_ci COMMENT '今日产出',
  `fault_drill` longtext COLLATE utf8mb4_unicode_ci COMMENT '故障演练',
  `stage_review` longtext COLLATE utf8mb4_unicode_ci COMMENT '阶段复盘',
  `algorithm_task` longtext COLLATE utf8mb4_unicode_ci COMMENT '算法任务',
  `resume_task` longtext COLLATE utf8mb4_unicode_ci COMMENT '简历投递任务',
  `mock_interview` longtext COLLATE utf8mb4_unicode_ci COMMENT '模拟面试任务',
  `extra_content` longtext COLLATE utf8mb4_unicode_ci COMMENT '其他当天重要内容',
  `raw_markdown` longtext COLLATE utf8mb4_unicode_ci COMMENT '当天完整Markdown原文',
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'LOCKED' COMMENT '状态：LOCKED/CURRENT/COMPLETED',
  `is_unlocked` tinyint(1) DEFAULT '0' COMMENT '是否解锁',
  `is_completed` tinyint(1) DEFAULT '0' COMMENT '是否完成',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `day_no` (`day_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `login_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `ip` varchar(50) DEFAULT NULL COMMENT '登录IP',
  `login_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_login_time` (`login_time`),
  KEY `idx_user_login_time` (`user_id`,`login_time` DESC),
  KEY `idx_username` (`username`),
  CONSTRAINT `fk_login_log_user_id` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户登录日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `order_no` varchar(32) NOT NULL COMMENT '订单号（唯一）',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `address_id` bigint NOT NULL COMMENT '地址ID',
  `total_amount` decimal(10,2) NOT NULL COMMENT '订单总金额',
  `pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额',
  `status` tinyint DEFAULT '0' COMMENT '订单状态：0-待付款，1-已付款，2-已发货，3-已完成，4-已取消，5-已关闭',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `shipping_time` datetime DEFAULT NULL COMMENT '发货时间',
  `complete_time` datetime DEFAULT NULL COMMENT '完成时间',
  `cancel_time` datetime DEFAULT NULL COMMENT '取消时间',
  `refund_time` datetime DEFAULT NULL COMMENT '退款时间',
  `refund_amount` decimal(10,2) DEFAULT NULL COMMENT '退款金额',
  `tracking_no` varchar(50) DEFAULT NULL COMMENT '物流单号',
  `logistics_company` varchar(50) DEFAULT NULL COMMENT '物流公司',
  `remark` varchar(500) DEFAULT NULL COMMENT '用户备注',
  `order_type` tinyint DEFAULT '0' COMMENT '订单类型：0-普通订单，1-秒杀订单',
  `is_seckill` tinyint DEFAULT '0' COMMENT '是否秒杀订单：0-否，1-是',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  `version` int DEFAULT '0' COMMENT '乐观锁版本号',
  `seckill_product_id` bigint DEFAULT NULL COMMENT '秒杀商品ID（仅秒杀订单填充；普通订单为 NULL）',
  `seckill_slot` varchar(64) GENERATED ALWAYS AS (if(((`order_type` = 1) and (`status` not in (4,5))),concat(`user_id`,_utf8mb4':',`seckill_product_id`),NULL)) VIRTUAL COMMENT '限购占位列：占用时=userId:productId，否则NULL；配合唯一索引实现部分唯一',
  `receiver_name` varchar(50) DEFAULT NULL COMMENT '收货人姓名（下单时从地址快照）',
  `receiver_phone` varchar(20) DEFAULT NULL COMMENT '收货人电话（下单时从地址快照）',
  `receiver_province` varchar(50) DEFAULT NULL COMMENT '收货省份（下单时从地址快照）',
  `receiver_city` varchar(50) DEFAULT NULL COMMENT '收货城市（下单时从地址快照）',
  `receiver_district` varchar(50) DEFAULT NULL COMMENT '收货区县（下单时从地址快照）',
  `receiver_detail_address` varchar(200) DEFAULT NULL COMMENT '收货详细地址（下单时从地址快照）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_seckill_user_product` (`seckill_slot`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`),
  KEY `fk_order_address_id` (`address_id`),
  KEY `idx_user_create_time` (`user_id`,`create_time` DESC),
  KEY `idx_user_status_cancel` (`user_id`,`status`,`cancel_time`),
  CONSTRAINT `fk_order_address_id` FOREIGN KEY (`address_id`) REFERENCES `address` (`id`),
  CONSTRAINT `fk_order_user_id` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单项ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `product_name` varchar(100) NOT NULL COMMENT '商品名称（快照）',
  `product_image` varchar(500) DEFAULT NULL COMMENT '商品图片（快照）',
  `product_price` decimal(10,2) NOT NULL COMMENT '商品单价（快照）',
  `quantity` int NOT NULL COMMENT '购买数量',
  `subtotal` decimal(10,2) NOT NULL COMMENT '小计金额',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_product_id` (`product_id`),
  CONSTRAINT `fk_order_item_order_id` FOREIGN KEY (`order_id`) REFERENCES `order` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_order_item_product_id` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单商品项表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `order_operation_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `order_no` varchar(32) DEFAULT NULL COMMENT '订单号',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人名称',
  `operation` varchar(50) DEFAULT NULL COMMENT '操作类型',
  `old_status` tinyint DEFAULT NULL COMMENT '原状态',
  `new_status` tinyint DEFAULT NULL COMMENT '新状态',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `pay_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pay_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `out_trade_no` varchar(64) NOT NULL COMMENT '商户订单号（我方生成，幂等主键）',
  `trade_no` varchar(64) DEFAULT NULL COMMENT '第三方交易号（第三方生成，支付成功后才有值）',
  `order_id` bigint NOT NULL COMMENT '关联的业务订单ID',
  `order_no` varchar(32) NOT NULL COMMENT '关联的业务订单号（冗余，便于对账）',
  `user_id` bigint NOT NULL COMMENT '付款用户ID',
  `amount` decimal(10,2) NOT NULL COMMENT '应付金额（下单时快照，回调时用于比对防改价）',
  `pay_channel` varchar(32) NOT NULL DEFAULT 'MOCK_PAY' COMMENT '支付渠道：MOCK_PAY/ALIPAY/WECHAT',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '支付单状态：0-待支付 1-支付成功 2-已关闭 3-已退款',
  `notify_time` datetime DEFAULT NULL COMMENT '第三方回调时间',
  `notify_content` varchar(1000) DEFAULT NULL COMMENT '第三方回调报文原文（留档，便于排查/对账）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_out_trade_no` (`out_trade_no`),
  UNIQUE KEY `uk_trade_no` (`trade_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_order_status` (`order_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `perm_name` varchar(50) NOT NULL COMMENT '权限名称',
  `perm_code` varchar(100) NOT NULL COMMENT '权限编码（用于@PreAuthorize）',
  `perm_type` varchar(20) DEFAULT NULL COMMENT '权限类型：menu-菜单，button-按钮',
  `parent_id` bigint DEFAULT '0' COMMENT '父权限ID，0表示顶级',
  `url` varchar(200) DEFAULT NULL COMMENT '请求路径',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `product`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  `name` varchar(100) NOT NULL COMMENT '商品名称',
  `description` varchar(500) DEFAULT NULL COMMENT '商品描述',
  `detail` text COMMENT '商品详情（富文本）',
  `image` varchar(500) DEFAULT NULL COMMENT '商品主图URL',
  `price` decimal(10,2) NOT NULL COMMENT '原价',
  `seckill_price` decimal(10,2) DEFAULT NULL COMMENT '秒杀价',
  `stock` int DEFAULT '0' COMMENT '普通库存',
  `seckill_stock` int DEFAULT '0' COMMENT '秒杀库存',
  `sold_count` int DEFAULT '0' COMMENT '已售数量',
  `category_id` bigint NOT NULL COMMENT '分类ID',
  `is_seckill` tinyint DEFAULT '0' COMMENT '是否秒杀商品：0-否，1-是',
  `seckill_start_time` datetime DEFAULT NULL COMMENT '秒杀开始时间',
  `seckill_end_time` datetime DEFAULT NULL COMMENT '秒杀结束时间',
  `status` tinyint DEFAULT '1' COMMENT '状态：0-下架，1-上架',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_name` (`name`),
  KEY `idx_seckill_time` (`seckill_start_time`,`seckill_end_time`),
  KEY `idx_category_deleted` (`category_id`,`deleted`),
  KEY `idx_category_seckill_deleted_time` (`category_id`,`is_seckill`,`deleted`,`create_time`),
  KEY `idx_seckill_query` (`is_seckill`,`status`,`deleted`,`seckill_start_time`,`seckill_end_time`),
  KEY `idx_seckill_deleted_time` (`is_seckill`,`deleted`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=101 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `refund_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refund_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(64) NOT NULL COMMENT '商户退款单号（我方生成，幂等主键）',
  `order_id` bigint NOT NULL COMMENT '关联的业务订单ID',
  `order_no` varchar(32) NOT NULL COMMENT '关联的业务订单号（冗余，便于列表展示）',
  `user_id` bigint NOT NULL COMMENT '申请退款用户ID',
  `out_trade_no` varchar(64) DEFAULT NULL COMMENT '原支付单的商户订单号（退款必须能追溯到哪笔收款）',
  `refund_trade_no` varchar(64) DEFAULT NULL COMMENT '第三方退款流水号（第三方生成，退款成功后才有值）',
  `refund_amount` decimal(10,2) NOT NULL COMMENT '退款金额（本实现为全额退款）',
  `refund_type` tinyint NOT NULL DEFAULT '1' COMMENT '退款类型：1-仅退款（订单待发货，货未出库）2-退货退款（订单已发货/已完成，即申请售后）',
  `refund_channel` varchar(32) NOT NULL DEFAULT 'MOCK_PAY' COMMENT '退款渠道：MOCK_PAY/ALIPAY/WECHAT',
  `reason` varchar(255) DEFAULT NULL COMMENT '用户填写的退款原因',
  `return_logistics_company` varchar(64) DEFAULT NULL COMMENT '退货物流公司（退货退款必填）',
  `return_tracking_no` varchar(64) DEFAULT NULL COMMENT '退货物流单号（退货退款必填）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态：0-待审核 1-退款中 2-退款成功 3-退款失败 4-已驳回 5-用户已撤销 6-管理员作废',
  `auditor_id` bigint DEFAULT NULL COMMENT '审核人ID（自动审核为空）',
  `auditor_name` varchar(64) DEFAULT NULL COMMENT '审核人姓名',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_remark` varchar(255) DEFAULT NULL COMMENT '审核意见（驳回时必填，直接展示给用户）',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '退款失败原因',
  `apply_time` datetime DEFAULT NULL COMMENT '申请时间',
  `refund_time` datetime DEFAULT NULL COMMENT '退款完成时间',
  `notify_content` varchar(1000) DEFAULT NULL COMMENT '第三方退款回调报文原文（留档，便于排查/对账）',
  `version` int NOT NULL DEFAULT '0' COMMENT '乐观锁版本号',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `open_order_id` bigint GENERATED ALWAYS AS (if((`status` in (0,1,3,7)),`order_id`,NULL)) VIRTUAL COMMENT '未完结占位列：未完结时=order_id，终态为NULL；配合 uk_open_order 保证同一订单只有一条未完结退款单',
  `return_received_time` datetime DEFAULT NULL COMMENT '商家确认收到退货的时间（退货退款专用）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refund_trade_no` (`refund_trade_no`),
  UNIQUE KEY `uk_open_order` (`open_order_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_order_status` (`order_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款单表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(50) NOT NULL COMMENT '角色名称',
  `role_code` varchar(50) NOT NULL COMMENT '角色编码',
  `description` varchar(200) DEFAULT NULL COMMENT '角色描述',
  `status` tinyint DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `role_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `role_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_id`),
  KEY `idx_permission_id` (`permission_id`),
  CONSTRAINT `fk_role_permission_permission_id` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_role_permission_role_id` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=30 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `session_summary`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_summary` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT '会话ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `intent` varchar(100) DEFAULT NULL COMMENT '对话意图',
  `summary` varchar(200) DEFAULT NULL COMMENT 'LLM提炼的对话摘要',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='L2短期对话摘要表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(255) NOT NULL COMMENT '密码（BCrypt加密）',
  `email` varchar(100) NOT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) DEFAULT NULL COMMENT '头像URL',
  `status` tinyint DEFAULT '1' COMMENT '状态：0-禁用，1-启用',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(50) DEFAULT NULL COMMENT '最后登录IP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  `is_temp_password` tinyint DEFAULT '0' COMMENT '是否临时密码：0=正常密码，1=管理员重置临时密码，必须修改密码',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username_del` (`username`,`deleted`),
  UNIQUE KEY `uk_email_del` (`email`,`deleted`),
  KEY `idx_status_username_id` (`status`,`username`,`id`)
) ENGINE=InnoDB AUTO_INCREMENT=32 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_biz_memory`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_biz_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `biz_context` text COMMENT '核心业务上下文(预算/品类/时段/商品/订单/到手价)',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户业务记忆表(跨会话长期记忆)';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_role_id` (`role_id`),
  CONSTRAINT `fk_user_role_role_id` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_role_user_id` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

