package com.zhou.peakflow.dto.session;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * Agent 会话保存入参
 */
@Data
public class SaveSessionDto {

    /** 会话ID */
    @NotBlank(message = "sessionId不能为空")
    private String sessionId;

    /** 用户ID */
    @NotNull(message = "userId不能为空")
    private Long userId;

    /** 对话意图（可选） */
    private String intent;

    /** 对话摘要 / 工具调用历史摘要（可选） */
    private String summary;
}
