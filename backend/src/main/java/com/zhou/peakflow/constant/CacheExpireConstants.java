package com.zhou.peakflow.constant;

/**
 * 缓存过期时间统一管理（单位写在常量名后缀里：秒 / 分钟 / 小时 / 天）
 */
public final class CacheExpireConstants {

    private CacheExpireConstants() {
    }

    // ---- 商品 ----

    /** 普通商品详情缓存 60 分钟 */
    public static final long PRODUCT_DETAIL_MINUTES = 60;

    /** 普通商品分页缓存 10 分钟 */
    public static final long PRODUCT_PAGE_MINUTES = 10;

    /** 秒杀商品详情缓存 10 分钟 */
    public static final long SECKILL_PRODUCT_DETAIL_MINUTES = 10;

    /** 秒杀商品分页缓存 1 分钟 */
    public static final long SECKILL_PRODUCT_PAGE_MINUTES = 1;

    /** 分类缓存 12 小时 */
    public static final long CATEGORY_HOURS = 12;

    // ---- 购物车 ----

    /** 购物车缓存 30 天（每次操作续期） */
    public static final long CART_DAYS = 30;

    // ---- 用户 ----

    /** 登录用户缓存 24 小时（管理员强制下线依赖它） */
    public static final long LOGIN_USER_HOURS = 24;

    /** 登录失败计数 60 分钟（超时自动清零） */
    public static final long LOGIN_FAIL_MINUTES = 60;

    /** 账号锁定 15 分钟（连续失败达到上限后触发） */
    public static final long LOGIN_LOCK_MINUTES = 15;

    /** 图形验证码有效期 120 秒 */
    public static final int CAPTCHA_EXPIRE_SECOND = 120;

    /** 用户详情缓存 2 小时 */
    public static final long USER_CACHE_HOURS = 2;

    // ---- 订单 ----

    /** 订单缓存 30 分钟 */
    public static final long ORDER_CACHE_MINUTES = 30;

    /** 空值缓存 60 秒（防穿透，只缓存空结果，所以时间要短） */
    public static final long NULL_CACHE_SECONDS = 60;

    /** 订单防重提交 10 秒 */
    public static final long ORDER_SUBMIT_SECONDS = 10;

    /** 订单自增序号 2 秒 */
    public static final long ORDER_SEQ_SECONDS = 2;

    // ---- 秒杀 ----

    /** 秒杀结果缓存 30 秒 */
    public static final long SECKILL_RESULT_SECONDS = 30;

    /** 秒杀结果轮询 5 分钟 */
    public static final long SECKILL_RESULT_MINUTES = 5;

    /** 秒杀用户标记 12 小时 */
    public static final long SECKILL_USER_MARK_HOURS = 12;

    /** 秒杀库存 12 小时 */
    public static final long SECKILL_STOCK_HOURS = 12;

    /** 秒杀幂等标记 24 小时 */
    public static final long SECKILL_IDEMPOTENT_HOURS = 24;

    /** 秒杀消息处理门闩 24 小时 */
    public static final long SECKILL_PROCESS_HOURS = 24;
}
