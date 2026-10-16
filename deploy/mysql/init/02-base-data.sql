
-- ============================================================================
-- PeakFlow 秒杀商城 · 基础字典数据
-- ----------------------------------------------------------------------------
-- 内容：permission(11) / role(4) / role_permission(21)
-- 用途：演示环境与生产环境【共用】。这是权限体系的最小必需数据，
--       缺少它 @PreAuthorize 鉴权全部失效，系统无法正常运行。
-- 安全：不含任何账号、密码或个人信息，可安全公开。
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

LOCK TABLES `permission` WRITE;
/*!40000 ALTER TABLE `permission` DISABLE KEYS */;
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (1,'用户管理','user:manage','api',0,NULL,'2026-10-05 14:23:03','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (3,'数据看板','stats:view','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (4,'商品管理','product:manage','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (5,'分类管理','category:manage','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (6,'秒杀管理','seckill:manage','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (7,'订单查询','order:read','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (8,'订单发货','order:ship','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (9,'退款查询','refund:read','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (10,'退款审核','refund:audit','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (11,'退款作废','refund:close','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `permission` (`id`, `perm_name`, `perm_code`, `perm_type`, `parent_id`, `url`, `create_time`, `update_time`) VALUES (12,'图片上传','file:upload','api',0,NULL,'2026-10-08 15:06:01','2026-10-08 15:06:01');
/*!40000 ALTER TABLE `permission` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `role` WRITE;
/*!40000 ALTER TABLE `role` DISABLE KEYS */;
INSERT INTO `role` (`id`, `role_name`, `role_code`, `description`, `status`, `create_time`, `update_time`) VALUES (1,'普通用户','user','仅能操作自己的订单、地址、购物车',1,'2026-10-05 14:23:03','2026-10-08 15:06:01');
INSERT INTO `role` (`id`, `role_name`, `role_code`, `description`, `status`, `create_time`, `update_time`) VALUES (2,'超级管理员','admin','拥有全部管理权限，不参与日常业务',1,'2026-10-05 14:23:03','2026-10-08 15:06:01');
INSERT INTO `role` (`id`, `role_name`, `role_code`, `description`, `status`, `create_time`, `update_time`) VALUES (3,'商品运营','product_ops','管理商品、分类、秒杀活动与图片素材；看不到订单与用户数据',1,'2026-10-08 15:06:01','2026-10-08 15:06:01');
INSERT INTO `role` (`id`, `role_name`, `role_code`, `description`, `status`, `create_time`, `update_time`) VALUES (4,'订单客服','order_service','订单查询与发货、退款审核与签收退货；不能作废退款',1,'2026-10-08 15:06:01','2026-10-08 15:06:01');
/*!40000 ALTER TABLE `role` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `role_permission` WRITE;
/*!40000 ALTER TABLE `role_permission` DISABLE KEYS */;
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (3,2,5,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (4,2,12,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (5,2,7,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (6,2,8,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (7,2,4,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (8,2,10,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (9,2,11,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (10,2,9,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (11,2,6,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (12,2,3,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (13,2,1,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (18,3,5,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (19,3,12,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (20,3,4,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (21,3,6,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (22,3,3,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (25,4,7,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (26,4,8,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (27,4,10,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (28,4,9,'2026-10-08 15:06:01');
INSERT INTO `role_permission` (`id`, `role_id`, `permission_id`, `create_time`) VALUES (29,4,3,'2026-10-08 15:06:01');
/*!40000 ALTER TABLE `role_permission` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

