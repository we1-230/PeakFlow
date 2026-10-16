package com.zhou.peakflow.pojo;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体（表 user）
 */
@Data
@TableName("user")
public class User extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String email;

    private String phone;

    @NotBlank(message = "昵称不能为空")
    private String nickname;

    private String avatar;

    private Integer status;

    private LocalDateTime lastLoginTime;

    private String lastLoginIp;

    // 临时密码标记（管理员重置后置 1，强制用户下次登录改密）
    private Integer isTempPassword;

}