package com.zhou.peakflow.utils;

import com.zhou.peakflow.config.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AliOssUtil 本地降级分支（未配 OSS 凭证 → 写本地盘）的回归测试。
 *
 * <p>背景（线上真实故障）：app.upload.public-base-url 曾配成 http://127.0.0.1:8080，
 * 后端把该地址原样拼进图片 URL 返回给浏览器。浏览器里的 127.0.0.1 指用户自己的电脑，
 * 于是「头像/商品图上传成功，但页面渲染不出来」，全站图片裂开。
 *
 * <p>这里锁定修复后的契约：<b>前缀留空时返回根相对路径 /uploads/&lt;文件名&gt;</b>，
 * 由浏览器按当前页面 origin 解析，http/https、IP/域名、本机/线上都自动正确。
 */
@DisplayName("AliOssUtil · 本地降级上传")
class AliOssUtilTest {

    @TempDir
    Path tempDir;

    private AliOssUtil aliOssUtil;

    @BeforeEach
    void setUp() {
        // accessKeyId 留空 → uploadFile 走 saveToLocal 降级分支，不会真的连 OSS
        OssProperties props = new OssProperties();
        aliOssUtil = new AliOssUtil(props);
        ReflectionTestUtils.setField(aliOssUtil, "localDir", tempDir.toString());
    }

    private InputStream imageStream() {
        return new ByteArrayInputStream("fake-image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("前缀留空 → 返回根相对路径，且绝不能出现 127.0.0.1")
    void returnsRelativePathWhenBaseUrlBlank() throws Exception {
        ReflectionTestUtils.setField(aliOssUtil, "publicBaseUrl", "");

        String url = aliOssUtil.uploadFile("abc.jpg", imageStream());

        assertEquals("/uploads/abc.jpg", url);
        assertFalse(url.contains("127.0.0.1"),
                "URL 不能包含 127.0.0.1：浏览器会把它解析成用户自己的电脑，图片必然裂开");
        assertTrue(Files.exists(tempDir.resolve("abc.jpg")), "文件应真实落到本地目录");
    }

    @Test
    @DisplayName("前缀为 null → 同样退回相对路径，不抛异常")
    void handlesNullBaseUrl() throws Exception {
        ReflectionTestUtils.setField(aliOssUtil, "publicBaseUrl", null);

        String url = aliOssUtil.uploadFile("ghi.jpg", imageStream());

        assertEquals("/uploads/ghi.jpg", url);
    }

    @Test
    @DisplayName("配了前缀（含尾斜杠）→ 拼成绝对地址，且不出现双斜杠")
    void usesConfiguredBaseUrlAndTrimsTrailingSlash() throws Exception {
        ReflectionTestUtils.setField(aliOssUtil, "publicBaseUrl", "https://cdn.example.com/");

        String url = aliOssUtil.uploadFile("def.jpg", imageStream());

        assertEquals("https://cdn.example.com/uploads/def.jpg", url);
        assertFalse(url.contains("//uploads"), "尾斜杠未去掉会拼出 //uploads，部分网关会 404");
    }
}
