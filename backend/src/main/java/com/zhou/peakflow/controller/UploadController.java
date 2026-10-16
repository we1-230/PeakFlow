package com.zhou.peakflow.controller;

import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.service.OssUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


/**
 * 文件上传控制器
 */
@Slf4j
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    private final OssUploadService ossUploadService;

    @PreAuthorize("hasAuthority('file:upload')")
    @PostMapping("/image")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("上传图片请求，文件名={}，大小={} bytes",
                file.getOriginalFilename(), file.getSize());

        try {
            String url = ossUploadService.uploadImage(file);
            log.info("图片上传成功，url={}", url);
            return Result.success("图片上传成功", url);
        } catch (BusinessException e) {
            log.warn("图片上传失败：{}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("图片上传异常", e);
            return Result.error(ResultCode.SYSTEM_ERROR);
        }
    }
}
