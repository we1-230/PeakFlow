package com.zhou.peakflow.pojo;

import lombok.Data;

/**
 * 图形验证码返回对象
 */
@Data
public class CaptchaVO {

    // 验证码标识，提交登录时需原样带回，服务端据此从 Redis 取真值比对
    private String captchaUuid;

    // 验证码图片（Base64）
    private String imageBase64;
}