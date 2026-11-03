package com.zhou.peakflow.utils;

/**
 * 敏感信息脱敏工具（用于日志输出与响应返回，避免明文泄露手机号 / 邮箱 / 证件号 / Token）
 */
public final class DesensitizationUtil {

    private DesensitizationUtil() {
    }

    /** 手机号脱敏：138****1234 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 11) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    /** 邮箱脱敏：a***b@example.com */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String[] parts = email.split("@");
        String username = parts[0];
        if (username.length() <= 2) {
            return "***@" + parts[1];
        }
        String prefix = username.substring(0, 1);
        String suffix = username.substring(username.length() - 1);
        return prefix + "***" + suffix + "@" + parts[1];
    }

    /** 身份证号脱敏：1101**********1234 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 18) {
            return idCard;
        }
        return idCard.replaceAll("(\\d{4})\\d{10}(\\d{4})", "$1**********$2");
    }

    /** 银行卡号脱敏：6212 **** **** 1234 */
    public static String maskBankCard(String cardNo) {
        if (cardNo == null || cardNo.length() < 16) {
            return cardNo;
        }
        return cardNo.replaceAll("(\\d{4})\\d{8,12}(\\d{4})", "$1 **** **** $2");
    }

    /** Token 脱敏：只保留前 6 位与后 4 位，过短时统一返回 *** */
    public static String maskToken(String token) {
        if (token == null || token.length() < 12) {
            return "***";
        }
        return token.substring(0, 6) + "****" + token.substring(token.length() - 4);
    }
}
