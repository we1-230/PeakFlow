package com.zhou.peakflow.dto.user;

import com.zhou.peakflow.dto.page.PageRequest;
import lombok.Data;

/** 管理端用户分页查询入参 */
@Data
public class AdminUserPageDto extends PageRequest {

    // 用户名模糊查询
    private String username;
    // 用户状态筛选
    private Integer status;
}
