package com.zhou.peakflow.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付签名 / 验签工具
 */
@Slf4j
public final class PaySignUtil {

    /** 签名算法：HMAC-SHA256 */
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** 签名字段名（拼接待签串时要排除它自己） */
    public static final String SIGN_FIELD = "sign";

    private PaySignUtil() {
    }

    public static String buildSignContent(Map<String, ?> params) {
        // TreeMap 天然按 key 的字典序排序，正好满足"按参数名 ASCII 升序"的要求
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, ?> entry : params.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                // 排除 sign 本身；空值不参与签名（真实支付宝也是这个规则）
                if (key == null || SIGN_FIELD.equalsIgnoreCase(key) || value == null) {
                    continue;
                }
                String text = String.valueOf(value);
                if (text.isEmpty()) {
                    continue;
                }
                sorted.put(key, text);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    public static String sign(Map<String, ?> params, String secret) {
        String content = buildSignContent(params);
        return hmacSha256Base64(content, secret);
    }

    // 验签：用同样的规则重算签名，与传入的 sign 比对
    public static boolean verify(Map<String, ?> params, String secret, String sign) {
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        String expected = sign(params, secret);
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sign.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            // 打印待签串，方便定位"为什么签名对不上"（生产环境注意脱敏）
            log.warn("[支付验签失败] 本地重算={}, 收到={}, 待签串={}", expected, sign, buildSignContent(params));
        }
        return ok;
    }

    private static String hmacSha256Base64(String content, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            // 算法不可用属于环境级异常，直接抛出（不应该发生）
            throw new IllegalStateException("计算支付签名失败", e);
        }
    }
}
