package com.zhou.peakflow.utils;

import com.zhou.peakflow.exception.BusinessException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.lettuce.core.RedisCommandTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.zhou.peakflow.constant.RedisKeyConstant.LOGIN_USER;
import static com.zhou.peakflow.constant.RedisKeyConstant.TOKEN_BLACKLIST;

/**
 * JWT 签发、解析与登出黑名单；Redis 不可用时鉴权降级放行（可用性优先）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    @Value("${jwt.token-prefix}")
    private String tokenPrefix;

    private final RedisTemplate<String, Object> redisTemplate;

    private static final long TOKEN_REFRESH_THRESHOLD_SECONDS = 30 * 60;

    private static final long REFRESH_TOKEN_DAYS = 7;

    /** Redis 不可用导致的鉴权降级累计次数（用于限流打日志，不做业务判断） */
    private static final AtomicLong DEGRADE_COUNT = new AtomicLong();

    private static volatile long lastLogAt = 0;

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(Long userId, String username, List<String> permissions) {
        log.debug("生成Token：userId={}, username={}, permissions={}", userId, username, permissions);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", permissions);
        claims.put("tokenType", "access");
        // 随机 jti：同一秒内为同一用户签发两次，若 payload 完全相同，HS256 会产出完全一样的
        // token 字符串 —— 于是"登出/刷新/改密后立刻重登"拿到的 token 会与刚进黑名单的那枚撞号而立即失效。
        // 加入随机因子后，每次签发的 token 唯一，黑名单按裸 token 记 key 即可精确命中。
        claims.put("jti", UUID.randomUUID().toString());

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration * 1000);

        String token = Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();

        log.debug("Token生成成功，expiresAt={}", expiryDate);
        return token;
    }

    // 从 Token 中解析权限列表（签发时已放入 "role" claim）
    @SuppressWarnings("unchecked")
    public List<String> getPermissionsFromToken(String token) {
        Claims claims = parseToken(token);
        Object role = claims.get("role");
        if (role instanceof List<?>) {
            return ((List<?>) role).stream().map(String::valueOf).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    public String generateRefreshToken(Long userId) {
        log.debug("生成刷新Token：userId={}", userId);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tokenType", "refresh");
        claims.put("jti", UUID.randomUUID().toString());

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + REFRESH_TOKEN_DAYS * 24 * 60 * 60 * 1000);

        String token = Jwts.builder()
                .setClaims(claims)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();

        log.debug("刷新Token生成成功，expiresAt={}", expiryDate);
        return token;
    }

    public Claims parseToken(String token) {
        log.debug("解析Token，token={}", DesensitizationUtil.maskToken(token));

        // 统一在入口剥离 "Bearer " 前缀（幂等：没有前缀时原样返回）。
        token = removeTokenPrefix(token);

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 检查是否在黑名单
            Boolean isBlacklisted = false;
            try {
                isBlacklisted = redisTemplate.hasKey(TOKEN_BLACKLIST + token);
            } catch (RedisConnectionFailureException | RedisCommandTimeoutException e) {
                // 降级放行，但只对"Redis 不可用"放行
                long n = DEGRADE_COUNT.incrementAndGet();
                long now = System.currentTimeMillis();
                if (now - lastLogAt > 60_000) {          // 每 60 秒最多打一次
                    lastLogAt = now;
                    log.warn("Redis 不可用，鉴权降级放行中；累计降级次数={}", n, e);  // e 放最后 → 有堆栈
                }
            }
            //标记：redis是否可用
            boolean redisAvailable = true;
            Boolean isLogin=false;

            try {
                // 直接使用上方已解析出的 claims 取用户名。
                String tokenUsername = claims.get("username", String.class);
                isLogin = redisTemplate.hasKey(LOGIN_USER + ":" + tokenUsername);
            } catch (RedisConnectionFailureException e) {
                log.error("redis连接异常，鉴权降级放行", e);
                redisAvailable = false;
            }

            // 只有redis正常的时候，才去检查登录key
            if (redisAvailable) {
                if (!Boolean.TRUE.equals(isLogin)) {
                    throw new BusinessException("请重新登录");
                }
            }


            if (Boolean.TRUE.equals(isBlacklisted)) {
                log.warn("Token在黑名单中，已失效");
                throw new BusinessException("token已失效，请重新登录");
            }


            log.debug("Token解析成功，subject={}", claims.getSubject());
            return claims;

        } catch (ExpiredJwtException e) {
            log.warn("Token已过期");
            throw new BusinessException("token已过期");
        } catch (JwtException e) {
            log.warn("Token无效：{}", e.getMessage());
            throw new BusinessException("token无效");
        }
    }

    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        String username = claims.get("username", String.class);
        log.debug("从Token获取用户名：{}", username);
        return username;
    }

    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        Long userId = claims.get("userId", Long.class);
        log.debug("从Token获取用户ID：{}", userId);
        return userId;
    }

    public boolean validateToken(String token) {
        parseToken(token);
        return true;
    }

    // 获取Token剩余有效时间（秒）
    public long getRemainingTime(String token) {
        try {
            Claims claims = parseToken(token);
            Date expiration = claims.getExpiration();
            long remaining = expiration.getTime() - System.currentTimeMillis();
            long remainingSeconds = remaining > 0 ? remaining / 1000 : 0;
            log.debug("Token剩余有效时间：{}秒", remainingSeconds);
            return remainingSeconds;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean needRefresh(String token) {
        long remaining = getRemainingTime(token);
        boolean needRefresh = remaining < TOKEN_REFRESH_THRESHOLD_SECONDS;  // 30分钟
        if (needRefresh) {
            log.debug("Token需要续期，剩余时间={}秒", remaining);
        }
        return needRefresh;
    }

    // 将Token加入黑名单（登出时调用）
    public void addToBlacklist(String token) {
        // key 归一化：parseToken 里的黑名单检查用的是「裸 token」，
        // 这里若直接用带 "Bearer " 前缀的原串写入，写 key 与查 key 对不上，登出形同虚设
        token = removeTokenPrefix(token);
        long time = this.getRemainingTime(token);
        if (time > 0) {
            redisTemplate.opsForValue().set(TOKEN_BLACKLIST + token, "", time, TimeUnit.SECONDS);
            log.info("Token已加入黑名单，有效时间={}秒", time);
        } else {
            log.debug("Token已过期，无需加入黑名单");
        }
    }

    public String removeTokenPrefix(String token) {
        if (token == null || !token.startsWith(tokenPrefix)) {
            return token;
        }
        // 必须 trim：配置 jwt.token-prefix=Bearer（不带空格），而请求头实际是 "Bearer xxx"，
        // 直接 substring 会留下前导空格 → 与「裸 token」算出的黑名单 key 不一致 → 登出失效
        return token.substring(tokenPrefix.length()).trim();
    }

}