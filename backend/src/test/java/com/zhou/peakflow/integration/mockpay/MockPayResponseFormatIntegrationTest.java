package com.zhou.peakflow.integration.mockpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhou.peakflow.config.MockPayProperties;
import com.zhou.peakflow.integration.support.IntegrationTestBase;
import com.zhou.peakflow.utils.PaySignUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MockPay 接口「响应格式恒定 JSON」集成测试（真实 MVC 转换器链）。
 *
 * <p><b>它守护的真实缺陷：</b>依赖 {@code com.aliyun:alibabacloud-oss-v2} 把
 * {@code jackson-dataformat-xml} 传递引入了 classpath，Spring MVC 据此注册了
 * {@code MappingJackson2XmlHttpMessageConverter}。由于内容协商会看客户端 Accept 的<b>顺序</b>挑格式：</p>
 * <ul>
 *   <li>浏览器 / axios 的 Accept 以 application/json 打头 ⇒ 返回 JSON，看不出来；</li>
 *   <li>Java {@code RestTemplate} 的 Accept 里 application/xml 排在 application/json 之前
 *       ⇒ 返回 {@code <Map><success>true</success>...</Map>}。</li>
 * </ul>
 *
 * <p>后果：{@code RefundServiceImpl} 用 RestTemplate 调第三方退款接口，拿到 XML 后按 JSON 解析必然失败，
 * 于是把「第三方已受理」误判成「第三方拒绝受理」，退款单被写成「退款失败」——
 * 钱其实已经退给用户了，这属于真实资损路径。</p>
 *
 * <p>因此本类用 {@code RestTemplate} 默认生成的 Accept 原样打接口，断言应答必须是 JSON。
 * 若哪天 XML 转换器又因为某个新依赖回到 classpath，本用例会立刻变红。</p>
 */
@DisplayName("MockPay 响应格式恒定为 JSON 集成测试")
class MockPayResponseFormatIntegrationTest extends IntegrationTestBase {

    /**
     * Spring RestTemplate 的 {@code StringHttpMessageConverter} 视角拼出的 Accept —— application/xml
     * 与 text/xml 排在 application/json 之前。这不是手写猜的，而是 {@code new RestTemplate()} 实际发出的头
     * （转换器链 [ByteArray, String, ..., XML, JSON] 逐个贡献 supported media types 后的结果）。
     */
    private static final String REST_TEMPLATE_ACCEPT =
            "text/plain, application/xml, text/xml, application/json, application/*+xml, application/*+json, */*";

    private static final String REFUND_NOTIFY_URL = "http://127.0.0.1:8080/refund/notify";

    @Autowired
    private MockPayProperties mockPayProperties;

    @Autowired
    private RequestMappingHandlerAdapter handlerAdapter;

    // 报文构造（签名算法与 RefundServiceImpl 保持一致，避免签不过被提前拦下）

    private Map<String, Object> refundBody(String outRefundNo, String outTradeNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_refund_no", outRefundNo);
        params.put("out_trade_no", outTradeNo);
        params.put("refund_amount", "199.00");
        params.put("notify_url", REFUND_NOTIFY_URL);
        params.put("timestamp", "1792079000000");
        params.put("sign", PaySignUtil.sign(params, mockPayProperties.getSecretKey()));
        return params;
    }

    private Map<String, Object> payBody(String outTradeNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("out_trade_no", outTradeNo);
        params.put("amount", "199.00");
        params.put("timestamp", "1792079000000");
        params.put("sign", PaySignUtil.sign(params, mockPayProperties.getSecretKey()));
        return params;
    }

    private MvcResult postWithAccept(String url, Map<String, Object> body, String accept) throws Exception {
        MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaTypes(accept).toArray(new MediaType[0]))
                .characterEncoding(StandardCharsets.UTF_8.name())
                .content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(builder).andReturn();
    }

    /** 断言应答确实是 JSON（而不是被内容协商切成了 XML） */
    private void assertJsonResponse(MvcResult result, String scene) throws Exception {
        String contentType = result.getResponse().getContentType();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertNotNull(contentType, scene + "：响应缺少 Content-Type");
        assertTrue(contentType.startsWith(MediaType.APPLICATION_JSON_VALUE),
                scene + "：Content-Type 应为 application/json，实际=" + contentType
                        + "，响应体=" + content);
        assertFalse(content.startsWith("<"),
                scene + "：响应体被序列化成了 XML，实际=" + content);

        // 必须是可解析的 JSON 对象，且带着协议约定的 success 标志位
        JsonNode node = objectMapper.readTree(content);
        assertTrue(node.has("success"),
                scene + "：应答缺少 success 字段（商户侧要靠它判断是否受理），实际=" + content);
        assertTrue(node.has("message"), scene + "：应答缺少 message 字段，实际=" + content);
    }

    @Test
    @DisplayName("[回归] RestTemplate 的 Accept 下 /mock-pay/refund 必须返回 JSON")
    void refundStaysJsonUnderRestTemplateAccept() throws Exception {
        MvcResult result = postWithAccept("/mock-pay/refund",
                refundBody("REF-FMT-TEST-0001", "PAY-FMT-TEST-0001"), REST_TEMPLATE_ACCEPT);

        assertEquals(200, result.getResponse().getStatus(), "HTTP 状态码应为 200");
        assertJsonResponse(result, "/mock-pay/refund");

        // 该交易未收款 ⇒ 第三方明确拒绝；重点是"这个拒绝要以 JSON 形态抵达商户"
        assertEquals(false, objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("success").asBoolean(),
                "原交易不存在时应返回 success=false");
    }

    @Test
    @DisplayName("[回归] RestTemplate 的 Accept 下 /mock-pay/pay 必须返回 JSON")
    void payStaysJsonUnderRestTemplateAccept() throws Exception {
        MvcResult result = postWithAccept("/mock-pay/pay",
                payBody("PAY-FMT-TEST-0002"), REST_TEMPLATE_ACCEPT);

        assertEquals(200, result.getResponse().getStatus(), "HTTP 状态码应为 200");
        assertJsonResponse(result, "/mock-pay/pay");
    }

    @Test
    @DisplayName("[回归] 最宽泛的 Accept 下也必须返回 JSON")
    void staysJsonUnderWildcardAccept() throws Exception {
        MvcResult result = postWithAccept("/mock-pay/refund",
                refundBody("REF-FMT-TEST-0003", "PAY-FMT-TEST-0003"), "*/*");

        assertEquals(200, result.getResponse().getStatus(), "HTTP 状态码应为 200");
        assertJsonResponse(result, "/mock-pay/refund (Accept: */*)");
    }

    @Test
    @DisplayName("[配置] MVC 消息转换器链里不应存在 XML 转换器")
    void xmlMessageConverterShouldBeRemoved() {
        List<HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();
        boolean hasXml = converters.stream()
                .anyMatch(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
        assertFalse(hasXml,
                "XML 消息转换器必须被 MessageConverterConfig 摘掉，否则同一接口会因 Accept 不同"
                        + "而返回两种格式；当前转换器链=" + converters);
    }
}
