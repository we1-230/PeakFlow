package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.mapper.UserMapper;
import com.zhou.peakflow.utils.AliOssUtil;
import com.zhou.peakflow.utils.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户头像上传 · <b>压缩是否真的接进了这条路径</b>（2026-10-18）。
 *
 * <h3>为什么必须单独测这条</h3>
 * 上一轮给图片压缩时只改了 {@code OssUploadServiceImpl}（后台商品图上传，走
 * {@code /upload/image}），而**用户头像走的是另一条完全独立的链路**：
 *
 * <pre>
 *   前端 User.uploadAvatar()  →  POST /user/avatar  →  UserServiceImpl#changAvatar
 *   前端（后台商品图）        →  POST /upload/image →  OssUploadServiceImpl#uploadImage
 * </pre>
 *
 * 结果是：商品图压缩生效了、单测也全绿，但**线上用户头像仍然原样落盘**
 * （实测单个文件 1.33MB / 1.6MB）。在约 60KB/s 的跨国链路上，一个头像要传 20 秒以上，
 * 页面上还有多个头像展示位 —— 这才是用户反馈「头像加载极慢」的真因。
 *
 * <p>教训：**同一个能力有多个入口时，必须逐个确认接入**；
 * 只测被改的那个类，永远发现不了「另一个入口没改」。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户头像 · 压缩接入链路")
class UserServiceImplAvatarTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private AliOssUtil aliOssUtil;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private static final String TOKEN = "mock-token";
    private static final String USERNAME = "malluser";

    /** 造一张 1000×1000 噪点图（模拟手机原图；噪点保证 PNG 体积接近真实照片） */
    private byte[] bigPhoto() throws Exception {
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(2026);
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < 1000; x++) {
                img.setRGB(x, y, rnd.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("[核心] 上传大图头像 → 真正写出去的字节必须远小于原图")
    void changAvatar_largePhoto_shouldUploadCompressed() throws Exception {
        byte[] original = bigPhoto();
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", original);

        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/abc.jpg");

        userService.changAvatar(TOKEN, file);

        // 抓真正传给 OSS 的字节，量化断言压缩生效
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(aliOssUtil).uploadFile(anyString(), captor.capture());
        byte[] uploaded;
        try (InputStream in = captor.getValue()) {
            uploaded = in.readAllBytes();
        }

        assertTrue(uploaded.length < original.length,
                "上传字节必须小于原图：original=" + original.length + " uploaded=" + uploaded.length);
        // 1000→400 是 6 倍面积差，压到 1/3 以下很宽松
        assertTrue(uploaded.length < original.length / 3,
                "压缩不够：original=" + original.length + " uploaded=" + uploaded.length);
    }

    @Test
    @DisplayName("[扩展名] 不带透明的 PNG 会转 JPEG，文件名扩展名必须跟着改")
    void changAvatar_compressedToJpeg_shouldRenameExtension() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", bigPhoto());

        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/x.jpg");

        userService.changAvatar(TOKEN, file);

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(aliOssUtil).uploadFile(nameCaptor.capture(), any(InputStream.class));
        assertTrue(nameCaptor.getValue().endsWith(".jpg"),
                "扩展名应为 .jpg（否则 nginx 会按 .png 返回错误 Content-Type），实际："
                        + nameCaptor.getValue());
    }

    @Test
    @DisplayName("[上限] 5MB 以内可上传（2MB 时代的限制已放宽，手机原图能过）")
    void changAvatar_3MB_shouldBeAccepted() throws Exception {
        // 3MB：旧限制（2MB）会直接拒绝，现在应当通过
        byte[] big = new byte[3 * 1024 * 1024];
        // 前 8 字节写成 PNG 魔数并塞入一张真图，保证 ImageIO 能解码
        byte[] real = bigPhoto();
        System.arraycopy(real, 0, big, 0, Math.min(real.length, big.length));

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", big);
        when(jwtUtil.getUsernameFromToken(TOKEN)).thenReturn(USERNAME);
        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/big.jpg");

        String url = userService.changAvatar(TOKEN, file);
        assertEquals("https://peakflow.top/uploads/big.jpg", url);
    }

    @Test
    @DisplayName("[安全] 超过 5MB 仍拒绝")
    void changAvatar_over5MB_shouldReject() {
        byte[] huge = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "huge.png", "image/png", huge);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changAvatar(TOKEN, file));
        assertTrue(ex.getMessage().contains("5MB"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("[安全] 伪造扩展名的非图片内容仍被拒绝（压缩不能放宽原有校验）")
    void changAvatar_fakeImage_shouldReject() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png", "not-an-image".getBytes());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changAvatar(TOKEN, file));
        assertTrue(ex.getMessage().contains("不是有效图片"), "实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("[安全] 不支持的扩展名仍被拒绝")
    void changAvatar_badExtension_shouldReject() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "a.exe", "application/octet-stream", new byte[]{1, 2, 3});
        assertThrows(BusinessException.class, () -> userService.changAvatar(TOKEN, file));
    }
}
