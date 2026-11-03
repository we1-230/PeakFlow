package com.zhou.peakflow.config;

import com.zhou.peakflow.enums.RefundStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 退款单「未完结占位列」口径自检（启动时执行一次，只告警不阻断）
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@RequiredArgsConstructor
public class RefundOpenStatusGuard implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    private static final String GUARD_LOG_PREFIX = "【退款单口径自检】";

    @Override
    public void run(ApplicationArguments args) {
        // 全程 try-catch：自检只是为了"早发现"，绝不能因为它失败而把应用启动搞挂
        try {
            String expression = jdbcTemplate.queryForObject(
                    "SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_record' " +
                            "  AND COLUMN_NAME = 'open_order_id'",
                    String.class);

            if (expression == null) {
                log.error("{}表 refund_record 缺少生成列 open_order_id —— " +
                        "「防重复申请的数据库层兜底」已失效！请执行 sql/fix_open_order_index.sql",
                        GUARD_LOG_PREFIX);
                return;
            }

            // Java 常量是 "0,1,3,7"，生成列里是 "0',1',3',7'" 这种带引号的形式，
            // 所以逐个状态码去表达式里找，而不是直接字符串相等比较
            for (String code : RefundStatus.OPEN_STATUS_SQL.split(",")) {
                if (!expression.contains(code.trim())) {
                    log.error("{}【口径不一致】生成列 open_order_id 的表达式缺少状态码 {} —— "
                                    + "Java 侧 OPEN_STATUS_SQL={}, DDL 侧={}。"
                                    + "后果：该状态下 uk_open_order 唯一索引失效，"
                                    + "并发下同一订单可能插出两张未完结退款单！"
                                    + "请执行 sql/fix_open_order_index.sql",
                            GUARD_LOG_PREFIX, code.trim(), RefundStatus.OPEN_STATUS_SQL, expression);
                    return;
                }
            }

            log.info("{}通过：生成列口径与 OPEN_STATUS_SQL={} 一致",
                    GUARD_LOG_PREFIX, RefundStatus.OPEN_STATUS_SQL);

        } catch (Exception e) {
            // 表还没建 / 无权限读 information_schema 等情况，降级为告警，不影响启动
            log.warn("{}自检未执行（不影响启动）：{}", GUARD_LOG_PREFIX, e.getMessage());
        }
    }
}
