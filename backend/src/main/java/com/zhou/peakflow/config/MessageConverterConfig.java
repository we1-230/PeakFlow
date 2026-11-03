package com.zhou.peakflow.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 强制「本服务对外只讲 JSON」。
 *
 * <p><b>为什么需要这个类：</b>
 * 依赖 com.aliyun:alibabacloud-oss-v2 把 jackson-dataformat-xml 传递引进了 classpath，
 * Spring MVC 检测到它就会自动注册 {@link MappingJackson2XmlHttpMessageConverter}，
 * 于是「同一个接口返回什么格式」变成了由客户端 Accept 头的**顺序**决定：
 *
 * <ul>
 *   <li>浏览器 / axios 的 Accept 以 application/json 打头 → 挑中 JSON，正常；</li>
 *   <li>Java RestTemplate 的 Accept 里 application/xml 排在 application/json 之前
 *       → 挑中 XML，接口返回 {@code <Map><success>true</success>...</Map>}。</li>
 * </ul>
 *
 * 后者曾造成真实缺陷：退款服务用 RestTemplate 调第三方退款接口，拿到的是 XML，
 * 按 JSON 解析必然失败，于是把「第三方已受理」误判成「第三方拒绝受理」，退款单被写成「退款失败」。
 *
 * <p>本项目没有任何对外 XML 接口，XML 转换器纯粹是噪声，直接从 MVC 转换器链里摘掉，
 * 让所有未声明 produces 的接口恒定为 JSON。
 *
 * <p><b>对 OSS SDK 无影响：</b>alibabacloud-oss-v2 用的是它自己的 ObjectMapper 解析 XML 响应，
 * 不经过 Spring MVC 的消息转换器，摘掉这里不影响上传 / 列举等 OSS 操作。
 */
@Slf4j
@Configuration
public class MessageConverterConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        boolean removed = converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
        if (removed) {
            log.info("【MVC 配置】已移除 XML 消息转换器（由 jackson-dataformat-xml 传递依赖引入），"
                    + "本服务对外恒定序列化为 JSON，避免同接口出现两种响应格式");
        }
    }
}
