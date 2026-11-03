package com.zhou.peakflow.controller;

import com.zhou.peakflow.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统信息控制器
 */
@Slf4j
@RestController
@RequestMapping("/system")
public class SystemController {

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    /**
     * 版本信息：部署后用来确认线上跑的是哪个版本、哪个环境
     */
    @GetMapping("/version")
    public Result<Map<String, Object>> version() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("app", "PeakFlow");
        info.put("version", "1.0.0");
        info.put("profile", activeProfile);
        info.put("time", LocalDateTime.now().toString());
        log.info("查询版本信息，profile={}", activeProfile);
        return Result.success(info);
    }
}
