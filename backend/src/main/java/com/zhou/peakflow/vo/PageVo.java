package com.zhou.peakflow.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 分页结果返回对象 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageVo<T> {
    //当前页数据列表
    private List<T> list;
    //总条数
    private long total;
    //总页数
    private long pages;
    //当前页码
    private long pageNum;
    //每页条数
    private long pageSize;
}
