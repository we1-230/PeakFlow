package com.zhou.peakflow.constant;

/**
 * 跨模块业务常量（各模块共用的阈值；模块专属常量放在各自常量类，如 {@link OrderConstants}）
 */
public final class BusinessConstants {

    private BusinessConstants() {
    }

    // ---- 分页 ----

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页最大条数（防止前端传入超大 pageSize 拖垮数据库） */
    public static final int MAX_PAGE_SIZE = 100;

    /** 允许查询的最大页码（超过则提示用户收窄筛选条件） */
    public static final int MAX_PAGE_NUM = 1000;

    // ---- 购物车 ----

    /** 单个商品在购物车中的最大数量 */
    public static final int MAX_CART_ITEM_QUANTITY = 999;

    // ---- 逻辑删除 ----

    /** 逻辑删除：未删除（与各表 deleted 字段口径一致） */
    public static final int NOT_DELETED = 0;

    /** 逻辑删除：已删除 */
    public static final int DELETED = 1;
}
