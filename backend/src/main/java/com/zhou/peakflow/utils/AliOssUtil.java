package com.zhou.peakflow.utils;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.model.PutObjectRequest;
import com.zhou.peakflow.config.OssProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.InputStream;

/**
 * 阿里云 OSS 上传工具（每次上传独立建客户端，finally 中关闭）
 *
 * @date 2023/03/22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AliOssUtil {

    private final OssProperties ossProperties;

    public String uploadFile(String objectName, InputStream in) throws Exception {
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
}