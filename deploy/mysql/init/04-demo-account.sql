
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
-- ============================================================================
-- PeakFlow 秒杀商城 · 演示账号  【仅限本地开发与在线演示】
-- ----------------------------------------------------------------------------
-- 内容：user(5) / user_role(6)
-- 依赖：必须先加载 01-schema.sql（user 表）与 02-base-data.sql（role 表，
--       user_role.role_id 引用它）。
--
-- ⚠️ 生产环境严禁加载本文件 —— 它包含密码公开的演示账号：
--      pfadmin    / Admin123456     超级管理员（admin，全部 11 个权限点）
--      sysadmin   / Admin123456     超级管理员（admin）
--      productops / Ops123456       商品运营（product_ops）
--      ordersvc   / Service123456   订单客服（order_service）
--      malluser   / User123456      商城普通用户（user）
--
--   生产环境的管理员请改用「接口注册 + 授权」的方式创建，
--   见 ../prod/grant-role.sql 与 ../README.md，
--   这样密码全程不进入代码仓库。
--
-- 【时区】会话时区锁定为北京时间，与 JDBC 的 serverTimezone=Asia/Shanghai 对齐。
-- ============================================================================

/*!40103 SET TIME_ZONE='+08:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

LOCK TABLES `user` WRITE;
/*!40000 ALTER TABLE `user` DISABLE KEYS */;
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`, `deleted`, `is_temp_password`) VALUES (12,'malluser','$2a$10$W5Rr.IZEw79dPvrr.PM1YuxtsaS6IbWeS5AQnsBbgHDBPfwfhhTL6','malluser@peakflow.test','13800000001','商城用户',NULL,1,'2026-10-15 22:24:50','127.0.0.1','2026-10-15 18:36:38','2026-10-16 10:33:24',0,0);
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`, `deleted`, `is_temp_password`) VALUES (13,'sysadmin','$2b$10$q9kiOZw2QVMtkft/cd5InOGMf6M6CaiA3s5A677pgl6vTn6insgqu','sysadmin@peakflow.test','13800000002','系统管理员',NULL,1,'2026-10-16 10:31:45','127.0.0.1','2026-10-15 18:36:38','2026-10-16 10:31:45',0,0);
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`, `deleted`, `is_temp_password`) VALUES (14,'productops','$2b$10$Lrk5mqg4SZPwVJ0.FPzsbOQgc4II4dQT8Tbo3Fj.5YVwOnWaVIXJC','productops@peakflow.test','13800000003','商品运营',NULL,1,'2026-10-15 19:46:48','127.0.0.1','2026-10-15 18:36:38','2026-10-15 19:46:48',0,0);
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`, `deleted`, `is_temp_password`) VALUES (15,'ordersvc','$2b$10$lV.pCxfHfyNwdlb7GyTtyeyWpUbsOFSBuOicktEvZknM2Po7Ti696','ordersvc@peakflow.test','13800000004','订单客服',NULL,1,'2026-10-15 22:26:12','127.0.0.1','2026-10-15 18:36:38','2026-10-15 22:26:12',0,0);
INSERT INTO `user` (`id`, `username`, `password`, `email`, `phone`, `nickname`, `avatar`, `status`, `last_login_time`, `last_login_ip`, `create_time`, `update_time`, `deleted`, `is_temp_password`) VALUES (17,'pfadmin','$2b$10$Li3nsQ1bMn7YWJWp3xrPNeQqJggWPOPhNmckevyEgy5M6BVuOTEFy','pfadmin@peakflow.test','13800000000','超级管理员',NULL,1,'2026-10-16 10:35:01','127.0.0.1','2026-10-15 18:53:26','2026-10-16 10:35:01',0,0);
/*!40000 ALTER TABLE `user` ENABLE KEYS */;
UNLOCK TABLES;

LOCK TABLES `user_role` WRITE;
/*!40000 ALTER TABLE `user_role` DISABLE KEYS */;
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (12,12,1,'2026-10-15 18:36:38');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (13,13,2,'2026-10-15 18:36:38');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (14,14,3,'2026-10-15 18:36:38');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (15,15,4,'2026-10-15 18:36:38');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (17,17,1,'2026-10-15 18:53:26');
INSERT INTO `user_role` (`id`, `user_id`, `role_id`, `create_time`) VALUES (18,17,2,'2026-10-15 18:53:26');
/*!40000 ALTER TABLE `user_role` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

