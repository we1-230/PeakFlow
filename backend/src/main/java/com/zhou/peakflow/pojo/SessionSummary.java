package com.zhou.peakflow.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 会话摘要实体（对应 session_summary 表）
 *
 * Java 侧承担完整业务会话审计与持久化；
 * Python Checkpointer 只负责 Agent 工作流状态断点恢复。
 *
 */
@Data
@TableName("session_summary")
public class SessionSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private Long userId;

    /** 对话意图 */
    private String intent;

    /** 对话摘要（LLM 提炼或工具调用摘要） */
    private String summary;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
