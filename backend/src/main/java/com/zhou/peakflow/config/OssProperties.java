package com.zhou.peakflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云OSS配置属性类
 */
@Data
@Component
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {
    /** OSS服务端点地址 */
    private String endpoint;
    /** 阿里云访问密钥ID */
    private String accessKeyId;
    /** 阿里云访问密钥 */
    private String accessKeySecret;
    /** OSS存储桶名称 */
    private String bucketName;
    /** OSS所在区域 */
    private String region;
}