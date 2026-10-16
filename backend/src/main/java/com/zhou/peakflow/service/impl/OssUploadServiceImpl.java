package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.service.OssUploadService;
import com.zhou.peakflow.utils.AliOssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * OSS上传服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OssUploadServiceImpl implements OssUploadService {

    private final AliOssUtil aliOssUtil;

    // 最大文件大小：5MB
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    // 支持的图片格式
    private static final String[] ALLOWED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".webp"};

    @Override
    public String uploadImage(MultipartFile file) {
        log.info("开始上传图片，文件大小={} bytes", file.getSize());

        // 校验文件是否为空
        if (file == null || file.isEmpty()) {
            log.warn("图片上传失败：文件为空");
            throw new BusinessException("文件不能为空");
        }

        // 获取原始文件名
        String originFilename = file.getOriginalFilename();
        if (originFilename == null || originFilename.isEmpty()) {
            log.warn("图片上传失败：文件名为空");
            throw new BusinessException("文件名不能为空");
        }

        // 校验文件大小
        long fileSize = file.getSize();
        if (fileSize > MAX_FILE_SIZE) {
            log.warn("图片上传失败：文件大小超限，size={} bytes", fileSize);
            throw new BusinessException("文件大小不能超过5MB");
        }

        // 校验文件类型
        if (!isValidImageType(originFilename)) {
            log.warn("图片上传失败：文件类型不支持，filename={}", originFilename);
            throw new BusinessException("仅支持jpg/jpeg/png/gif/webp格式的图片文件");
        }

        // 生成唯一文件名
        String extension = originFilename.substring(originFilename.lastIndexOf("."));
        String filename = UUID.randomUUID().toString() + extension;

        // 上传文件到OSS
        String url;
        try (InputStream in = file.getInputStream()) {
            url = aliOssUtil.uploadFile(filename, in);
            log.debug("文件上传OSS成功，filename={}, url={}", filename, url);
        } catch (IOException e) {
            log.error("图片上传失败：文件读取异常", e);
            throw new BusinessException("文件读取失败");
        } catch (Exception e) {
            log.error("图片上传失败：文件上传异常", e);
            throw new BusinessException("文件上传失败");
        }

        log.info("图片上传成功，url={}", url);
        return url;
    }

    // 校验文件类型是否为允许的图片格式
    private boolean isValidImageType(String filename) {
        if (filename == null) {
            return false;
        }
        String lowerCaseFilename = filename.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerCaseFilename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
