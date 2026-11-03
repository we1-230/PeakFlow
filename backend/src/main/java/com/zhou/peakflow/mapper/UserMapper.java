package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户表 Mapper：状态与密码相关更新全部用 CAS（带旧值条件）保证并发安全
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    // ---- 新增 ----

    // 新增用户，自增主键回填到实体的 id
    @Insert("insert into user(username, password, email,phone, nickname ,avatar, status) " +
            "values(#{username}, #{password}, #{email}, #{phone},#{nickname},#{avatar}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int customInsert(User user);

    // 给新注册用户分配角色（role_id=1 普通用户）；填 2 会让所有新用户直接获得管理员权限
    @Insert("INSERT INTO user_role (user_id, role_id) " +
            "SELECT u.id, 1 " +
            "FROM user u " +
            "WHERE u.username = #{username} AND u.deleted = 0")
    void userRole(@Param("username") String username);

    // ---- 查询 ----

    // 按用户名查询
    @Select("SELECT * FROM user WHERE username = #{username} AND deleted = 0")
    User selectByUsername(@Param("username") String username);

    // 按 ID 查询（含状态，供封号 / 解封 / 重置密码使用）
    @Select("SELECT * FROM user WHERE id = #{userId} AND deleted = 0")
    User selectStatusById(@Param("userId") Long userId);

    // 用户名是否已存在，返回已存在记录数（0 表示可用）
    @Select("SELECT COUNT(*) FROM user WHERE username = #{username} AND deleted = 0")
    int countByUsername(@Param("username") String username);

    // 邮箱是否已被注册，返回已存在记录数（0 表示可用）
    @Select("SELECT COUNT(*) FROM user WHERE email = #{email} AND deleted = 0")
    int countByEmail(@Param("email") String email);

    // 查询用户权限码（RBAC：user → user_role → role → role_permission → permission，多角色取并集）
    @Select("SELECT DISTINCT p.perm_code AS perm_code " +
            "FROM `user` u " +
            "JOIN user_role ur ON u.id = ur.user_id " +
            "JOIN `role` r ON ur.role_id = r.id " +
            "JOIN role_permission rp ON r.id = rp.role_id " +
            "JOIN permission p ON rp.permission_id = p.id " +
            "WHERE u.username = #{username} AND u.deleted = 0")
    List<String> selectPermissionsByUsername(@Param("username") String username);

    // ---- 更新 ----

    // 封禁账号也记录本次登录，所以不校验 status
    @Update("UPDATE user SET last_login_time = NOW(), last_login_ip = #{ip} WHERE id = #{userId} AND deleted = 0")
    int updateLastLogin(@Param("userId") Long userId, @Param("ip") String ip);

    // 按用户名更新头像（仅未删除且正常的账号）
    @Update("UPDATE user SET avatar = #{avatar} WHERE username = #{username} AND deleted = 0 AND status = 1")
    void updateAvatarByUsername(@Param("avatar") String avatar, @Param("username") String username);

    // 按用户名更新昵称（仅未删除且正常的账号）
    @Update("UPDATE user SET nickname = #{nickname}, update_time = NOW() WHERE username = #{username} AND deleted = 0 AND status = 1")
    void updateNicknameByUsername(@Param("nickname") String nickname, @Param("username") String username);

    // 插入登录日志（login_log 表没有逻辑删除字段）
    @Insert("INSERT INTO login_log (user_id,username, ip,login_time,remark) VALUES (#{userId},#{username},#{ip},NOW(),'登录成功')")
    void insertLoginLog(@Param("userId") Long userId, @Param("username") String username, @Param("ip") String ip);

    // CAS 更新用户状态：仅当数据库当前状态 = oldStatus 时才生效
    @Update("UPDATE user SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{userId} AND status = #{oldStatus} AND deleted = 0")
    int updateStatusWithCas(@Param("userId") Long userId,
                            @Param("newStatus") Integer newStatus,
                            @Param("oldStatus") Integer oldStatus);

    // CAS 更新密码：仅当数据库当前密文 = oldPassword 时才生效，防并发覆盖（仅未删除且正常账号）
    @Update("UPDATE user SET password = #{newPassword}, update_time = NOW() " +
            "WHERE id = #{userId} AND password = #{oldPassword} AND deleted = 0 AND status = 1")
    int updatePasswordWithCas(@Param("userId") Long userId,
                              @Param("newPassword") String newPassword,
                              @Param("oldPassword") String oldPassword);

    // CAS 同时更新密码并清除临时密码标记（单条 SQL 原子完成）
    @Update("UPDATE user " +
            "SET password = #{newEncodePwd}, is_temp_password = 0 " +
            "WHERE id = #{id} AND password = #{oldEncodePwd} AND deleted = 0 AND status = 1")
    int updatePasswordAndTempFlagCasById(@Param("id") Long id,
                                         @Param("newEncodePwd") String newEncodePwd,
                                         @Param("oldEncodePwd") String oldEncodePwd);

    // 管理员重置密码为临时密码：同时置 is_temp_password = 1，强制用户下次登录后改密
    @Update("UPDATE user SET password = #{encodedPassword}, is_temp_password = 1, update_time = NOW() " +
            "WHERE id = #{userId} AND deleted = 0 AND status = 1")
    int resetPasswordCas(@Param("userId") Long userId,
                         @Param("encodedPassword") String encodedPassword);

    // ---- 后台统计 ----

    // 用户总数（未删除）
    @Select("SELECT COUNT(*) FROM user WHERE deleted = 0")
    Long countAllUsers();

    // 正常状态用户数
    @Select("SELECT COUNT(*) FROM user WHERE deleted = 0 AND status = 1")
    Long countActiveUsers();
}
