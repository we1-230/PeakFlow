package com.zhou.peakflow;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类：开启定时与异步任务，支撑订单兜底扫描（超时关单 / 自动确认收货）等后台作业
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.zhou.peakflow.mapper")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        log.info("--------------------------高并发秒杀系统项目启动成功------------------------");
    }
}
