package com.zhou.peakflow.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * OSS 上传服务：统一封装图片上传，返回可访问的图片 URL
 */
public interface OssUploadService {

    // 上传图片文件（multipart 方式）
    String uploadImage(MultipartFile file);
}
