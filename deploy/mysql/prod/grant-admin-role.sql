-- ============================================================================
-- PeakFlow 秒杀商城 · 生产环境：为账号授予角色（支持全部 4 个角色）
-- ----------------------------------------------------------------------------
-- 【关于文件名】文件名是 grant-admin-role.sql（与部署目录规范一致），
--   但它并不只能授 admin —— 改下面参数区的 @role_code 即可授任意一个角色，
--   可选值：admin / product_ops / order_service / user。
--
-- 【为什么需要这个脚本】
--   仓库里的演示账号（pfadmin 等）密码是公开的，生产环境绝不能复用。
--   生产账号的正确创建路径是「先注册、再授权」，全程密码不经过仓库：
--
--     ① 通过接口注册账号（密码由应用 BCrypt 加密后落库，明文不落地、不进仓库）
--          POST /auth/register
--          { "username": "opsadmin",
--            "password": "<你的强密码>",
--            "email":    "opsadmin@your-domain.com",
--            "nickname": "运营管理员" }
--        注意：新注册用户默认被分配 role_id = 1（普通用户），
--              见 backend/.../mapper/UserMapper.java#userRole
--
--     ② 执行本脚本，把账号设置成目标角色
--     ③ 验证：POST /auth/login 拿到 token 后访问对应管理页，能返回数据即成功
--
-- 【本脚本的语义】覆盖式 —— 把账号的角色「设置成」指定角色（而非追加）。
--   先清空该账号现有角色关联，再授予目标角色，因此结果确定、可重复执行。
--
-- 【安全说明】本文件不含任何密码或哈希，可安全提交到公开仓库。
-- ============================================================================


-- ============================================================================
-- 参数区：改这两行即可
-- ============================================================================
SET @username  = 'opsadmin';   -- 要授权的账号名（须已通过 /auth/register 注册）
SET @role_code = 'admin';      -- 目标角色编码，可选值见下表

-- 可选角色编码一览：
--   admin          超级管理员   11 个权限点：全部
--                  user:manage / stats:view / product:manage / category:manage /
--                  seckill:manage / order:read / order:ship / refund:read /
--                  refund:audit / refund:close / file:upload
--   product_ops    商品运营      5 个权限点：stats:view / product:manage /
--                  category:manage / seckill:manage / file:upload
--   order_service  订单客服      5 个权限点：stats:view / order:read /
--                  order:ship / refund:read / refund:audit
--   user           普通用户      0 个管理权限：仅能操作自己的订单、地址、购物车


-- ============================================================================
-- ① 解析角色 id，并做编码自检
--    编码写错时 @role_id 为 NULL，下面的清空/授权会自动跳过（不会破坏现有授权）
-- ============================================================================
SET @role_id = (SELECT id FROM role WHERE role_code = @role_code);

SELECT CASE
         WHEN @role_code IS NULL OR @role_code = ''
           THEN '[ERROR] 未设置 @role_code'
         WHEN @role_id IS NULL
           THEN CONCAT('[ERROR] 角色编码无效: ', @role_code,
                       '  —— 可选值: admin / product_ops / order_service / user')
         ELSE CONCAT('[OK] 角色编码有效: ', @role_code, ' (', 
                     (SELECT role_name FROM role WHERE id = @role_id), ')')
       END AS 角色编码自检;


-- ============================================================================
-- ② 覆盖式设置角色：先清空该账号现有角色关联
-- ============================================================================
DELETE ur FROM user_role ur
JOIN user u ON u.id = ur.user_id
WHERE u.username = @username
  AND @role_id IS NOT NULL;


-- ============================================================================
-- ③ 授予目标角色（NOT EXISTS 保证幂等）
-- ============================================================================
INSERT INTO user_role (user_id, role_id)
SELECT u.id, @role_id
FROM user u
WHERE u.username = @username
  AND u.deleted = 0
  AND @role_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM user_role x
      WHERE x.user_id = u.id AND x.role_id = @role_id
  );


-- ============================================================================
-- ④ 校验：输出该账号的最终角色与权限清单
--    查不到行  → 账号名写错，或该账号不存在 / 已被逻辑删除
--    role 为 NULL → 角色编码无效（对应 ① 的 [ERROR] 提示）
--    user 角色会输出 1 行、权限码为 NULL（该角色本来就没有管理权限）
-- ============================================================================
SELECT u.id            AS 用户ID,
       u.username      AS 账号,
       r.role_code     AS 角色编码,
       r.role_name     AS 角色名,
       p.perm_code     AS 权限码
FROM user u
LEFT JOIN user_role ur  ON ur.user_id = u.id
LEFT JOIN role r        ON r.id = ur.role_id
LEFT JOIN role_permission rp ON rp.role_id = r.id
LEFT JOIN permission p  ON p.id = rp.permission_id
WHERE u.username = @username
ORDER BY p.id;


-- ============================================================================
-- 附：查看全部账号及其角色（排查用，按需取消注释）
-- ============================================================================
-- SELECT u.id AS 用户ID, u.username AS 账号, u.status AS 状态,
--        GROUP_CONCAT(r.role_code ORDER BY r.id) AS 角色
-- FROM user u
-- LEFT JOIN user_role ur ON ur.user_id = u.id
-- LEFT JOIN role r ON r.id = ur.role_id
-- WHERE u.deleted = 0
-- GROUP BY u.id, u.username, u.status
-- ORDER BY u.id;
