package com.zhou.peakflow.utils;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.PutObjectRequest;
import com.zhou.peakflow.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 文件上传：配了凭证走阿里云 OSS，没配凭证降级到本地磁盘。
 *
 * 为什么要降级：上传是头像/商品图的前置能力，没有 OSS 账号的人一上传就报错，
 * 整个链路就走不通。降级后 URL 指向本服务的 /uploads/** 静态映射，
 * 前端拿到的仍是一个可直接放进 &lt;img src&gt; 的地址。
 *
 * ⚠️ 降级目录是**单机本地盘**：多实例部署时各实例各写各的，A 写的文件 B 读不到。
 *    生产多实例必须改用 OSS 或共享卷，否则会出现「上传成功但图片 404」。
 *
 * @date 2023/03/22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliOssUtil {

    private final OssProperties ossProperties;

    /** 降级存储目录 */
    @Value("${app.upload.local-dir:./uploads}")
    private String localDir;

    /** 降级时 URL 的前缀（指向本服务对外地址；容器里用 APP_UPLOAD_PUBLIC_BASE_URL 覆盖） */
    @Value("${app.upload.public-base-url:http://127.0.0.1:8080}")
    private String publicBaseUrl;

    public String uploadFile(String objectName, InputStream in) throws Exception {
        // 没配 AccessKey 就别去连 OSS 了：必然失败，还会白等一次网络超时
        if (!StringUtils.hasText(ossProperties.getAccessKeyId())) {
            return saveToLocal(objectName, in);
        }
        return uploadToOss(objectName, in);
    }

    private String uploadToOss(String objectName, InputStream in) throws Exception {
        log.info("开始上传文件到OSS，objectName={}", objectName);

        String url = "";

        OSS ossClient = OSSClientBuilder.create()
                .endpoint(ossProperties.getEndpoint())
                .credentialsProvider(new DefaultCredentialProvider(
                        ossProperties.getAccessKeyId(),
                        ossProperties.getAccessKeySecret()))
                .region(ossProperties.getRegion())
                .build();

        try {
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                    ossProperties.getBucketName(),
                    objectName,
                    in);

            ossClient.putObject(putObjectRequest);
            url = "https://" + ossProperties.getBucketName() + "." +
                    ossProperties.getEndpoint() + "/" + objectName;

            log.info("文件上传成功，url={}", url);

        } catch (OSSException oe) {
            log.error("OSS上传失败: {}", oe.getErrorMessage());
            throw new Exception("OSS上传失败: " + oe.getErrorMessage());
        } catch (ClientException ce) {
            log.error("OSS客户端异常: {}", ce.getMessage());
            throw new Exception("OSS客户端异常: " + ce.getMessage());
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
                log.debug("OSS客户端已关闭");
            }
        }
        return url;
    }

    private String saveToLocal(String objectName, InputStream in) throws Exception {
        Path dir = Paths.get(localDir).toAbsolutePath().normalize();
        // 只取文件名再拼接：objectName 由服务端生成，但不能只靠上游保证不带 ../ 之类
        Path target = dir.resolve(Paths.get(objectName).getFileName().toString());

        Files.createDirectories(dir);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

        String url = publicBaseUrl + "/uploads/" + target.getFileName();
        log.info("未配置 OSS 凭证，已降级保存到本地：{} → {}", target, url);
        return url;
    }
}
