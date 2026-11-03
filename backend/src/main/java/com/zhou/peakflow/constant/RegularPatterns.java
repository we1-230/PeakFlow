package com.zhou.peakflow.constant;

/**
 * 正则表达式统一管理（只放校验用到的 pattern，供 @Pattern 注解或 matches 调用）
 */
public final class RegularPatterns {

    private RegularPatterns() {
    }

    /** 用户名：2-20 位，字母、数字、下划线、中文 */
    public static final String USERNAME = "^[a-zA-Z0-9_\\u4e00-\\u9fa5]{2,20}$";

    /** 密码：2-20 位，字母、数字、中文 */
    public static final String PASSWORD = "^[\\u4e00-\\u9fa5a-zA-Z0-9]{2,20}$";

    /** 昵称：2-10 位，中文、字母、数字、下划线、短横线 */
    public static final String NICKNAME = "^[\\u4e00-\\u9fa5a-zA-Z0-9_-]{2,10}$";

    /** 手机号：中国大陆手机号 */
    public static final String PHONE = "^1[3-9]\\d{9}$";

    /** 邮箱：基础邮箱格式 */
    public static final String EMAIL = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    /** 分类名称：2-20 位，中文、字母、数字、下划线、短横线 */
    public static final String CATEGORY_NAME = "^[\\u4e00-\\u9fa5a-zA-Z0-9_\\-]{2,20}$";

    /** 商品名称：2-30 位，支持中文、字母、数字、下划线、短横线、空格 */
    public static final String PRODUCT_NAME = "^[a-zA-Z0-9_\\u4e00-\\u9fa5\\- ]{2,30}$";

    /** URL：http / https 开头 */
    public static final String URL = "^(http|https)://.*$";

    /** IPv4 地址 */
    public static final String IP = "^(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|[1-9])\\." +
            "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)\\." +
            "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)\\." +
            "(1\\d{2}|2[0-4]\\d|25[0-5]|[1-9]\\d|\\d)$";
}
