package com.zhou.peakflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhou.peakflow.pojo.SessionSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 会话摘要 Mapper
 */
@Mapper
public interface SessionSummaryMapper extends BaseMapper<SessionSummary> {
}
