package com.zhou.peakflow.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片压缩工具测试（2026-10-17）。
 *
 * <p>背景：线上上传的头像是 <b>1.33MB / 484KB / 324KB</b> 的原图，服务在境外、
 * 国内实测吞吐约 60KB/s ⇒ 一个头像要传 20 秒以上，用户看到的就是「头像一直转圈」。
 * 这里守护三件事：</p>
 * <ol>
 *   <li><b>真的变小了</b>（这是修复的目的，必须量化断言，不能只断言"没报错"）</li>
 *   <li><b>尺寸被限制住</b>（否则体积靠运气）</li>
 *   <li><b>任何异常都回落原图</b>（压缩是优化，不能让上传失败）</li>
 * </ol>
 */
@DisplayName("图片压缩工具 · 体积/尺寸/兜底")
class ImageCompressorTest {

    /** 造一张指定尺寸的图片（内容带渐变，避免纯色被压到极小导致断言失真） */
    private byte[] sample(String format, int w, int h, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            for (int x = 0; x < w; x++) {
                g.setColor(new Color((x * 7) % 255, (x * 13) % 255, (x * 29) % 255));
                g.drawLine(x, 0, x, h);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, format, bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("[核心] 1600x1600 的大 JPEG 压缩到 400px：体积显著下降、边长受限")
    void compress_largeJpeg_shouldShrinkAndResize() throws Exception {
        byte[] src = sample("jpg", 1600, 1600, false);
        int sizeBefore = src.length;

        ImageCompressor.Result r = ImageCompressor.compress(src, ".jpg", ImageCompressor.MAX_EDGE_AVATAR);

        assertTrue(r.changed, "大图应该被处理");
        assertEquals(400, Math.max(r.width, r.height), "长边应压到 400");
        assertTrue(r.bytes.length < sizeBefore, "压缩后体积必须变小");
        // 量化断言：至少省 80%（1600→400 是 16 倍面积差，这个阈值很宽松）
        assertTrue(r.bytes.length < sizeBefore * 0.2,
                "压缩率不足： " + sizeBefore + " → " + r.bytes.length);

        // 输出必须是能再次解码的合法图片（防止写出坏文件）
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(r.bytes));
        assertNotNull(decoded, "压缩结果必须可被解码");
        assertEquals(400, Math.max(decoded.getWidth(), decoded.getHeight()));
    }

    @Test
    @DisplayName("[尺寸] 小图不放大：400x300 的图保持原尺寸，只有超标才缩放")
    void compress_smallImage_shouldNotUpscale() throws Exception {
        byte[] src = sample("jpg", 400, 300, false);
        ImageCompressor.Result r = ImageCompressor.compress(src, ".jpg", 400);

        // 不缩放（400 已 <= 400）；体积本来就不大所以直接原样返回
        assertTrue(Math.max(r.width, r.height) <= 400, "不得放大，实际 " + r.width + "x" + r.height);
    }

    @Test
    @DisplayName("[透明] 带 alpha 的 PNG 保持 PNG（转 JPEG 会变黑底）")
    void compress_transparentPng_shouldStayPng() throws Exception {
        byte[] src = sample("png", 800, 800, true);

        ImageCompressor.Result r = ImageCompressor.compress(src, ".png", 400);

        assertEquals(".png", r.extension, "带透明通道仍应输出 PNG");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(r.bytes));
        assertNotNull(decoded);
        assertTrue(decoded.getColorModel().hasAlpha(), "透明通道必须保留");
    }

    @Test
    @DisplayName("[格式] webp/gif 不做压缩（ImageIO 不支持 / 会丢动画），原样返回")
    void compress_unsupportedFormat_shouldReturnOriginal() throws Exception {
        byte[] fake = new byte[]{1, 2, 3, 4, 5};
        ImageCompressor.Result webp = ImageCompressor.compress(fake, ".webp", 400);
        assertEquals(5, webp.bytes.length);
        assertTrue(!webp.changed);

        ImageCompressor.Result gif = ImageCompressor.compress(fake, ".gif", 400);
        assertEquals(5, gif.bytes.length);
        assertTrue(!gif.changed);
    }

    @Test
    @DisplayName("[兜底] 损坏的图片字节 → 原样返回，绝不抛异常")
    void compress_corruptBytes_shouldFallbackToOriginal() {
        byte[] garbage = "this-is-not-an-image".getBytes();
        ImageCompressor.Result r = ImageCompressor.compress(garbage, ".jpg", 400);

        assertTrue(!r.changed, "无法解码时应回落原图");
        assertEquals(garbage.length, r.bytes.length);
    }

    @Test
    @DisplayName("[兜底] 空输入 → 安全返回，不 NPE")
    void compress_emptyInput_shouldBeSafe() {
        ImageCompressor.Result r1 = ImageCompressor.compress(null, ".jpg", 400);
        assertNotNull(r1);
        assertTrue(!r1.changed);

        ImageCompressor.Result r2 = ImageCompressor.compress(new byte[0], ".jpg", 400);
        assertNotNull(r2);
        assertTrue(!r2.changed);
    }

    @Test
    @DisplayName("[真实场景] 模拟线上那个 1.33MB 头像 → 压到 100KB 以内")
    void compress_realWorldAvatarSize_shouldDropBelow100KB() throws Exception {
        // 用 2048x2048 高熵图模拟用户上传的手机原图
        byte[] src = sample("png", 2048, 2048, false);
        int before = src.length;

        ImageCompressor.Result r = ImageCompressor.compress(src, ".png", ImageCompressor.MAX_EDGE_AVATAR);

        assertTrue(r.changed);
        assertTrue(r.bytes.length < 100 * 1024,
                "应压到 100KB 以内，实际 " + (r.bytes.length / 1024) + "KB（原始 " + (before / 1024) + "KB）");
    }
}
