package com.zhou.peakflow.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * 图片压缩工具（2026-10-17 新增，纯 JDK 实现，不引入任何第三方依赖）。
 *
 * <h3>为什么需要它</h3>
 * 线上排查发现：用户上传的头像原样落在磁盘上，实际文件分别是
 * <b>1.33 MB / 484 KB / 324 KB</b> —— 服务部署在境外，国内访客的可用吞吐
 * 只有约 60 KB/s（实测），一个 1.3 MB 的头像要传 <b>20 秒以上</b>。
 * 页面因此长时间显示空白/占位，用户感知就是「头像加载非常缓慢」。
 *
 * <p>上传时按展示尺寸缩放并重编码，1.3 MB → 约 50~80 KB（省 95%），
 * 视觉上在 80×80 的头像位置几乎无差别。这是「第一次访问慢」的最主要来源之一。</p>
 *
 * <h3>设计取舍</h3>
 * <ul>
 *   <li><b>只用 ImageIO</b>：不引入 Thumbnailator / imgscalr 等依赖，减少供应链与镜像体积。
 *       ImageIO 是 JDK 自带，无额外风险。</li>
 *   <li><b>只缩不放</b>：原图小于目标尺寸时保持原尺寸，避免放大导致模糊 + 体积反增。</li>
 *   <li><b>透明通道处理</b>：带 alpha 的图输出 PNG（保留透明），否则输出 JPEG（压缩率高）。
 *       PNG 无法调节质量，只能靠缩放降体积，所以对 PNG 只在超过目标尺寸时才重编码。</li>
 *   <li><b>不支持 webp / gif 动图</b>：ImageIO 原生不支持 webp；GIF 动图缩放会丢帧。
 *       这两种格式原样返回，避免「上传后图坏了」这种更严重的问题。</li>
 *   <li><b>任何异常都回落原图</b>：压缩是优化，不是功能。压缩失败绝不能导致上传失败。</li>
 * </ul>
 */
@Slf4j
public final class ImageCompressor {

    /** 头像：展示位最大约 96px，按 2x 屏取 400 足够（再大是浪费带宽） */
    public static final int MAX_EDGE_AVATAR = 400;

    /** 商品图 / 通用图片：详情页最大展示约 600px，2x 屏取 1600 */
    public static final int MAX_EDGE_GENERAL = 1600;

    /** JPEG 质量：0.82 是「肉眼几乎无损」与体积的常用平衡点 */
    private static final float JPEG_QUALITY = 0.82f;

    /** 小于这个体积的图不值得重编码（重编码可能反而变大） */
    private static final long SKIP_BELOW_BYTES = 80 * 1024;

    private ImageCompressor() {
    }

    /**
     * 压缩结果。{@code changed=false} 表示原样返回（调用方应使用原始字节）。
     */
    public static final class Result {
        public final byte[] bytes;
        public final String extension;
        public final boolean changed;
        public final int width;
        public final int height;

        Result(byte[] bytes, String extension, boolean changed, int width, int height) {
            this.bytes = bytes;
            this.extension = extension;
            this.changed = changed;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 压缩图片。
     *
     * @param rawBytes  原始文件字节
     * @param extension 原始扩展名（含点，小写），如 {@code .jpg}
     * @param maxEdge   目标最大边长（像素）
     * @return 压缩结果；无法处理时返回 {@code changed=false} 的原图
     */
    public static Result compress(byte[] rawBytes, String extension, int maxEdge) {
        if (rawBytes == null || rawBytes.length == 0) {
            return new Result(rawBytes, extension, false, 0, 0);
        }
        String ext = extension == null ? "" : extension.toLowerCase();

        // webp / gif 交给原样存储：ImageIO 不支持 webp；GIF 缩放会丢动画
        if (!".jpg".equals(ext) && !".jpeg".equals(ext) && !".png".equals(ext)) {
            log.debug("【图片压缩】跳过不支持的格式 ext={} size={}", ext, rawBytes.length);
            return new Result(rawBytes, ext, false, 0, 0);
        }

        try {
            BufferedImage src = readImage(rawBytes);
            if (src == null) {
                log.warn("【图片压缩】ImageIO 无法解码，回落原图 ext={} size={}", ext, rawBytes.length);
                return new Result(rawBytes, ext, false, 0, 0);
            }

            int w = src.getWidth();
            int h = src.getHeight();
            int longEdge = Math.max(w, h);

            boolean needResize = longEdge > maxEdge;
            // 小图且体积已经不大：没必要折腾（重编码可能反而更大）
            if (!needResize && rawBytes.length <= SKIP_BELOW_BYTES) {
                return new Result(rawBytes, ext, false, w, h);
            }

            BufferedImage target = needResize ? scale(src, maxEdge) : src;
            boolean hasAlpha = target.getColorModel().hasAlpha();

            // 带透明的出 PNG（保透明），其余出 JPEG（体积小得多）
            String outExt = hasAlpha ? ".png" : ".jpg";
            byte[] out = hasAlpha ? writePng(target) : writeJpeg(target, JPEG_QUALITY);

            if (out == null || out.length == 0) {
                log.warn("【图片压缩】重编码结果为空，回落原图");
                return new Result(rawBytes, ext, false, w, h);
            }
            // 压缩后反而变大（极端情况：原本就是高质量小图）⇒ 用原图
            if (out.length >= rawBytes.length) {
                log.debug("【图片压缩】压缩后未变小（{} → {}），保留原图", rawBytes.length, out.length);
                return new Result(rawBytes, ext, false, w, h);
            }

            log.info("【图片压缩】{}x{} → {}x{}，{} → {} bytes（省 {}%）",
                    w, h, target.getWidth(), target.getHeight(),
                    rawBytes.length, out.length,
                    Math.round((1 - (double) out.length / rawBytes.length) * 100));
            return new Result(out, outExt, true, target.getWidth(), target.getHeight());

        } catch (Exception e) {
            // 压缩失败绝不能导致上传失败 —— 回落原图
            log.warn("【图片压缩】处理异常，回落原图：{}", e.getMessage());
            return new Result(rawBytes, ext, false, 0, 0);
        }
    }

    /** 解码；对某些「扩展名与内容不符」的文件返回 null 而不是抛异常 */
    private static BufferedImage readImage(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 等比缩放到长边 = maxEdge。
     *
     * <p>用 {@link RenderingHints#VALUE_INTERPOLATION_BILINEAR} 而非默认最近邻，
     * 否则缩放后的图会有明显锯齿（头像这种小图上尤其难看）。</p>
     */
    private static BufferedImage scale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        double ratio = (double) maxEdge / Math.max(w, h);
        int newW = Math.max(1, (int) Math.round(w * ratio));
        int newH = Math.max(1, (int) Math.round(h * ratio));

        // 保留 alpha 通道（PNG 透明图缩放后仍需透明）
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(newW, newH, type);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] writeJpeg(BufferedImage img, float quality) throws IOException {
        // JPEG 不支持 alpha：转 RGB 后再写，否则会抛 "Bogus input colorspace"
        BufferedImage rgb = img;
        if (img.getColorModel().hasAlpha()) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // 极端环境（精简 JRE 缺 jpeg writer）⇒ 走默认写出
            ByteArrayOutputStream fallback = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpeg", fallback);
            return fallback.toByteArray();
        }

        ImageWriter writer = writers.next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static byte[] writePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
