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

    /**
     * 降级时 URL 的前缀。
     *
     * 留空（默认）= 返回根相对路径 /uploads/&lt;文件名&gt;，由浏览器按当前页面 origin 解析。
     * 这是最稳的做法：同一份产物在 http/https、IP/域名、本机/线上都自动正确，
     * 也不会出现「https 页面加载 http 图片」被当成混合内容拦截。
     *
     * ⚠️ 绝对不要填 http://127.0.0.1:8080 —— 浏览器里的 127.0.0.1 指用户自己的电脑，
     *    部署到服务器后所有上传后的图片都会裂开（表现为「上传成功但页面渲染不出来」）。
     * 只有把图片放到独立图床/CDN 时才填完整前缀。
     */
    @Value("${app.upload.public-base-url:}")
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

        // 相对路径优先：前缀留空时返回 /uploads/xxx.jpg，由浏览器按页面 origin 解析。
        // 这样 http/https、IP/域名、本机/线上都不需要改配置，也不会踩混合内容拦截。
        String path = "/uploads/" + target.getFileName();
        String url = StringUtils.hasText(publicBaseUrl)
                ? trimTrailingSlash(publicBaseUrl) + path
                : path;
        log.info("未配置 OSS 凭证，已降级保存到本地：{} → {}", target, url);
        return url;
    }

    /** 去掉末尾斜杠，避免 APP_UPLOAD_PUBLIC_BASE_URL 手写成 http://x.com/ 时拼出 //uploads */
    private String trimTrailingSlash(String base) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
