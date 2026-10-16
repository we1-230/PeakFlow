-- ============================================================================
-- PeakFlow 秒杀商城 · 建库（三环境一次建齐）
-- ----------------------------------------------------------------------------
-- 一个 MySQL 容器承载三套环境，用【库名】做隔离：
--     peakflow_dev   本地开发   schema + 权限字典 + 商品目录 + 演示账号
--     peakflow_test  自动化测试 schema + 权限字典 + 商品目录（不含演示密码）
--     peakflow_prod  生产       schema + 权限字典 + 商品目录（账号走 grant-admin-role.sql 授权）
--
-- 【为什么拆成两个脚本】
--   本文件只负责「把三个库建出来」。灌数据由 05-provision-envs.sh 负责，
--   因为 01~04 这几个 dump 文件里没有 USE 语句，只能作用于「当前选中的库」，
--   靠 .sh 显式指定目标库，比在每个 sql 顶部塞 USE 更不容易出错。
--
-- 字符集与既有环境保持一致：utf8mb4 / utf8mb4_0900_ai_ci（MySQL 8 默认排序规则）
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `peakflow_dev`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `peakflow_test`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS `peakflow_prod`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

SELECT SCHEMA_NAME AS `已建库`, DEFAULT_COLLATION_NAME AS `排序规则`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('peakflow_dev', 'peakflow_test', 'peakflow_prod')
ORDER BY SCHEMA_NAME;
