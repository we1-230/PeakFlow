package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.service.OssUploadService;
import com.zhou.peakflow.utils.AliOssUtil;
import com.zhou.peakflow.utils.ImageCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
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

    /**
     * 压缩目标边长。项目里图片上传的唯一场景是头像（个人中心 80px、顶栏 32px 展示位），
     * 按 2x 屏取 400px 已足够清晰；再大只是浪费跨国带宽。
     */
    private static final int IMAGE_MAX_EDGE = ImageCompressor.MAX_EDGE_AVATAR;

    @Override
    public String uploadImage(MultipartFile file) {
        log.info("开始上传图片，文件大小={} bytes", file.getSize());

        // 校验文件是否为空
        if (file == null || file.isEmpty()) {
            log.warn("图片上传失败：文件为空");
            throw new BusinessException("文件不能为空");
        }

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

        String extension = originFilename.substring(originFilename.lastIndexOf(".")).toLowerCase();

        // ------------------------------------------------------------------
        // 压缩（2026-10-17 新增）
        //
        // 线上实测：用户上传的头像原样存储，实际有 1.33MB / 828KB / 388KB 等文件。
        // 服务部署在境外，国内访客实测可用吞吐约 60KB/s ⇒ 1.33MB 的头像要传 20 秒以上，
        // 这是「头像加载非常缓慢」的直接原因。
        //
        // 这里在落盘前统一缩放 + 重编码，1.3MB → 约 40~80KB。
        // 压缩只是优化：任何异常都在 ImageCompressor 内部兜底为「原样返回」，
        // 绝不因为压缩失败而让上传失败。
        // ------------------------------------------------------------------
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            log.error("图片上传失败：读取文件异常", e);
            throw new BusinessException("文件读取失败");
        }

        ImageCompressor.Result compressed =
                ImageCompressor.compress(rawBytes, extension, IMAGE_MAX_EDGE);

        // 扩展名可能变化：不带透明的大 PNG 会被转成 JPEG（压缩率高得多）
        String finalExtension = compressed.changed ? compressed.extension : extension;
        String filename = UUID.randomUUID().toString() + finalExtension;

        log.info("图片处理：原始 {} bytes → 上传 {} bytes，changed={}，尺寸={}x{}",
                rawBytes.length, compressed.bytes.length, compressed.changed,
                compressed.width, compressed.height);

        // 上传文件到OSS（未配 AccessKey 时自动降级写本地 uploads）
        String url;
        try (InputStream in = new ByteArrayInputStream(compressed.bytes)) {
            url = aliOssUtil.uploadFile(filename, in);
            log.debug("文件上传OSS成功，filename={}, url={}", filename, url);
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
