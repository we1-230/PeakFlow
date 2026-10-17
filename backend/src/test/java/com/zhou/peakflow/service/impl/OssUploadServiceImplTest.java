package com.zhou.peakflow.service.impl;

import com.zhou.peakflow.exception.BusinessException;
import com.zhou.peakflow.service.OssUploadService;
import com.zhou.peakflow.utils.AliOssUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传服务 · <b>压缩是否真的接进了上传链路</b>（2026-10-17）。
 *
 * <h3>为什么单独写这个测试</h3>
 * 真实踩过的坑：`ImageCompressor` 类确实编译进了 jar，
 * 但 `OssUploadServiceImpl` 里的接入代码在一次 `git reset --hard` 中丢失，
 * 于是线上表现为「类存在、单测全绿、上传后文件依然是 1.19MB」——
 * 因为**压缩器根本没被调用**。
 *
 * <p>只测 `ImageCompressor` 本身发现不了这种问题（它自测是通的），
 * 必须从「上传入口」进、到「写入 OSS 的字节」出，断言两头对得上。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上传服务 · 压缩接入链路")
class OssUploadServiceImplTest {

    @Mock
    private AliOssUtil aliOssUtil;

    @InjectMocks
    private OssUploadServiceImpl uploadService;

    /**
     * 造一张 1000×1000 的<b>噪点</b>图，模拟真实照片。
     *
     * <p>为什么不用纯色/渐变：PNG 对规律图案压缩率极高（横竖线图 1600² 只有 40KB），
     * 缩到 400² 后插值反而产生更多细节、体积可能不降反升，
     * 于是「压缩后未变小 → 保留原图」这条兜底会生效，测不到真正的压缩路径。
     * 噪点图接近照片熵值，缩放收益才是真实场景的量级。</p>
     */
    private byte[] bigPng() throws Exception {
        // 1000²（噪点 PNG 约 3MB）—— 再大就超过上传接口 5MB 的限制，
        // 而 1000 > 400 仍能触发缩放，足够验证压缩链路。
        BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);   // 固定种子，保证用例可复现
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
    @DisplayName("[核心] 上传大图 → 真正写出去的字节必须远小于原始字节")
    void upload_largeImage_shouldUploadCompressedBytes() throws Exception {
        byte[] original = bigPng();
        MockMultipartFile file = new MockMultipartFile(
                "file", "big-avatar.png", "image/png", original);

        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/abc.png");

        String url = uploadService.uploadImage(file);
        assertEquals("https://peakflow.top/uploads/abc.png", url);

        // 抓住真正传给 OSS 的字节流，量化断言「确实被压缩过」
        ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
        verify(aliOssUtil).uploadFile(anyString(), captor.capture());
        byte[] uploaded;
        try (InputStream in = captor.getValue()) {
            uploaded = in.readAllBytes();
        }

        assertTrue(uploaded.length < original.length,
                "上传的字节必须比原始文件小：original=" + original.length + " uploaded=" + uploaded.length);
        // 1000→400 是 6 倍面积差，压到 1/3 以下是很宽松的门槛
        assertTrue(uploaded.length < original.length / 3,
                "压缩不够：original=" + original.length + " uploaded=" + uploaded.length);
    }

    @Test
    @DisplayName("[文件名] 压缩后扩展名跟随实际格式（PNG 无透明会转 .jpg）")
    void upload_compressedExtension_shouldFollowActualFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "big-avatar.png", "image/png", bigPng());
        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/x.jpg");

        uploadService.uploadImage(file);

        // 捕获文件名：不带透明的 PNG 会转 JPEG，扩展名必须是 .jpg，
        // 否则 nginx 会按 .png 返回错误的 Content-Type
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(aliOssUtil).uploadFile(nameCaptor.capture(), any(InputStream.class));
        assertTrue(nameCaptor.getValue().endsWith(".jpg"),
                "扩展名应为 .jpg，实际：" + nameCaptor.getValue());
    }

    @Test
    @DisplayName("[小图] 已足够小时不重编码（避免无意义的重压缩与画质损失）")
    void upload_smallImage_shouldPassThroughMostly() throws Exception {
        byte[] buf;
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 300, 300);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        buf = bos.toByteArray();

        MockMultipartFile file = new MockMultipartFile("file", "small.png", "image/png", buf);
        when(aliOssUtil.uploadFile(anyString(), any(InputStream.class)))
                .thenReturn("https://peakflow.top/uploads/s.png");

        uploadService.uploadImage(file);

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(aliOssUtil).uploadFile(nameCaptor.capture(), any(InputStream.class));
        assertTrue(nameCaptor.getValue().endsWith(".png"), "小图应保持 .png");
    }

    @Test
    @DisplayName("[安全] 非图片扩展名仍被拒绝（压缩不能放宽原有校验）")
    void upload_nonImage_shouldStillReject() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", new byte[]{1, 2, 3});
        assertThrows(BusinessException.class, () -> uploadService.uploadImage(file));
    }

    @Test
    @DisplayName("[安全] 空文件仍被拒绝")
    void upload_empty_shouldReject() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        assertThrows(BusinessException.class, () -> uploadService.uploadImage(file));
    }
}
