package com.zhou.peakflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhou.peakflow.dto.session.SaveSessionDto;
import com.zhou.peakflow.mapper.SessionSummaryMapper;
import com.zhou.peakflow.pojo.Result;
import com.zhou.peakflow.pojo.SessionSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.Duration;
import java.util.List;

/**
 * Agent 会话持久化接口（Java 侧业务会话审计）
 *
 * 与 Python Checkpointer 分工：
 * — Python Checkpointer 保存 Agent 工作流状态（崩溃恢复）
 * — Java 此处保存完整业务会话（审计回溯），活跃会话 Redis 缓存 + 全量 MySQL
 *
 */
@Slf4j
@RestController
@RequestMapping("/agent/session")
@RequiredArgsConstructor
@Tag(name = "Agent会话存储", description = "Java 侧 Agent 业务会话持久化（审计回溯）")
public class AgentSessionController {

    private static final String SESSION_REDIS_KEY = "agent:session:summary:";
    private static final Duration SESSION_CACHE_TTL = Duration.ofHours(2);

    private final SessionSummaryMapper sessionSummaryMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 保存会话记录（活跃会话 Redis 缓存 + 全量 MySQL）
     */
    @PostMapping("/save")
    @Operation(summary = "保存 Agent 会话记录")
    public Result<Void> saveSession(@Valid @RequestBody SaveSessionDto dto) {
        log.info("保存Agent会话，sessionId={}, userId={}", dto.getSessionId(), dto.getUserId());

        SessionSummary summary = new SessionSummary();
        summary.setSessionId(dto.getSessionId());
        summary.setUserId(dto.getUserId());
        summary.setIntent(dto.getIntent());
        summary.setSummary(dto.getSummary());
        sessionSummaryMapper.insert(summary);

        // 活跃会话写 Redis 高速缓存（2h TTL）
        try {
            stringRedisTemplate.opsForValue().set(
                    SESSION_REDIS_KEY + dto.getSessionId(),
                    dto.getSummary() == null ? "" : dto.getSummary(),
                    SESSION_CACHE_TTL);
        } catch (Exception e) {
            log.warn("会话Redis缓存写入失败（不影响主流程），sessionId={}", dto.getSessionId());
        }

        return Result.success("会话已保存");
    }

    /**
     * 查询会话记录（按 sessionId 读取用户历史对话/工具调用摘要）
     */
    @GetMapping("/query")
    @Operation(summary = "查询 Agent 会话记录")
    public Result<List<SessionSummary>> querySessions(@RequestParam Long userId,
                                                      @RequestParam(required = false) String sessionId) {
        LambdaQueryWrapper<SessionSummary> wrapper = new LambdaQueryWrapper<SessionSummary>()
                .eq(SessionSummary::getUserId, userId)
                .orderByDesc(SessionSummary::getCreatedAt)
                .last("LIMIT 50");
        if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(SessionSummary::getSessionId, sessionId);
        }
        List<SessionSummary> list = sessionSummaryMapper.selectList(wrapper);
        return Result.success(list);
    }
}
