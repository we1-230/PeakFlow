package com.zhou.peakflow.constant;

/**
 * Redis Key 统一管理（所有 key 集中定义，避免散落各处难以排查与监控）
 */
public final class RedisKeyConstant {

    private RedisKeyConstant() {
    }

    // ---- 商品 ----

    /** 普通商品详情缓存 */
    public static final String PRODUCT_DETAIL = "product:detail";

    /** 秒杀商品详情缓存 */
    public static final String SECKILL_PRODUCT_DETAIL = "seckill:product:detail";

    /** 普通商品分页缓存 */
    public static final String PRODUCT_PAGE = "product:page";

    /** 秒杀商品分页缓存 */
    public static final String SECKILL_PRODUCT_PAGE = "seckill:product:page";

    /** 商品分类缓存 */
    public static final String PRODUCT_CATEGORY = "product:category";

    /** 秒杀商品列表缓存（预留） */
    public static final String PRODUCT_SECKILL_LIST = "product:seckill:list";

    // ---- 购物车 ----

    /** 购物车 Hash：field = productId，value = 数量 */
    public static final String CART = "cart";

    /** 购物车「被取消选中」的商品集合（默认全选，只记例外，用排除法表达选中态） */
    public static final String CART_UNSELECTED = "cart:unselected";

    // ---- 用户 ----

    /** 登录用户缓存：存状态与权限，管理员强制下线依赖它 */
    public static final String LOGIN_USER = "login:user";

    /** 登录失败计数 */
    public static final String LOGIN_FAIL = "login:fail";

    /** 账号锁定标记 */
    public static final String LOGIN_LOCK = "login:lock";

    /** Token 黑名单（登出 / 强退） */
    public static final String TOKEN_BLACKLIST = "token:blacklist";

    /** 图形验证码 */
    public static final String CAPTCHA_REDIS_PREFIX = "captcha:";

    // ---- 订单 ----

    /** 订单详情缓存 */
    public static final String ORDER_PREFIX = "order:detail";

    /** 订单提交防重（幂等） */
    public static final String ORDER_SUBMIT = "order:submit";

    /** 订单超时延迟队列（预留） */
    public static final String ORDER_TIMEOUT = "order:timeout";

    /** 订单自增序号（生成订单号用） */
    public static final String ORDER_SEQ = "order:seq";

    /** 用户订单列表缓存（预留） */
    public static final String USER_ORDERS = "user:orders";

    /** 空订单缓存（防穿透，预留） */
    public static final String NULL_ORDER = "null:order";

    /** 用户下单分布式锁（预留） */
    public static final String ORDER_LOCK = "order:lock";

    // ---- 秒杀 ----

    /** 秒杀库存（预热用） */
    public static final String SECKILL_STOCK = "seckill:stock";

    /** 秒杀结果（供前端轮询） */
    public static final String SECKILL_RESULT = "seckill:result";

    /** 秒杀下单分布式锁（预留） */
    public static final String SECKILL_ORDER_LOCK = "seckill:order:lock";

    /** 秒杀用户限购标记前缀 */
    public static final String SECKILL_USER_PREFIX = "seckill:user";

    /** 秒杀订单成功幂等标记 */
    public static final String SECKILL_ORDER_SUCCESS = "seckill:order:success";

    /** 秒杀消息处理门闩（防 MQ 重投递导致重复消费） */
    public static final String SECKILL_PROCESS = "seckill:process";
}
