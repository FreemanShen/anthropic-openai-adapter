package com.example.adapter.logging;

import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 日志安全处理工具类，防止敏感信息泄露和日志过大。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li><b>截断</b>（truncate）：防止超长 payload（如图片 base64）撑爆日志文件</li>
 *   <li><b>归一化</b>（normalize）：将换行符替换为空格，使多行日志变成单行便于查看</li>
 *   <li><b>预览</b>（bodyPreview）：从 byte[] 中解码并截断，用于记录请求/响应 body</li>
 * </ol>
 *
 * <h2>为什么要截断？</h2>
 * AI API 的请求/响应中可能包含：
 * <ul>
 *   <li>图片 base64 数据（可达数 MB）</li>
 *   <li>长文本生成结果</li>
 *   <li>工具调用参数（JSON 可能很大）</li>
 * </ul>
 * 若直接写入日志，会严重占用磁盘空间并降低日志可读性。
 *
 * <h2>使用示例</h2>
 * <pre>
 * // 截断过长内容
 * log.debug("payload={}", LogSanitizer.truncate(requestBody));
 *
 * // 归一化 URL 参数
 * log.info("uri={}", LogSanitizer.normalize(request.getQueryString()));
 *
 * // 从字节数组预览 body
 * String preview = LogSanitizer.bodyPreview(bytes, "UTF-8");
 * </pre>
 */
public final class LogSanitizer {

    /** 默认截断阈值：4KB（4096 字符/字节） */
    public static final int MAX_PAYLOAD_LOG_LENGTH = 4096;

    private LogSanitizer() {
        // 工具类禁止实例化
    }

    /**
     * 截断字符串，保留前 {@value #MAX_PAYLOAD_LOG_LENGTH} 个字符。
     *
     * @param value 待截断字符串
     * @return 截断后的字符串，超长时附加 "(truncated,N chars)" 后缀
     */
    public static String truncate(String value) {
        return truncate(value, MAX_PAYLOAD_LOG_LENGTH);
    }

    /**
     * 截断字符串到指定最大长度。
     *
     * @param value     待截断字符串
     * @param maxLength 最大允许长度
     * @return 截断后的字符串，超长时附加 "(truncated,原始长度 chars)" 后缀
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated," + value.length() + " chars)";
    }

    /**
     * 归一化字符串：将 \r、\n 替换为空格并 trim。
     *
     * 用于日志中的单行记录（如 queryString、URL），避免多行内容破坏日志格式。
     */
    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 从字节数组中解码并截断为字符串。
     *
     * @param payload             字节数组
     * @param characterEncoding   字符编码（可为 null，默认为 UTF-8）
     * @return 解码并截断后的字符串，空数组返回空字符串
     */
    public static String bodyPreview(byte[] payload, String characterEncoding) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        Charset charset = StandardCharsets.UTF_8;
        if (StringUtils.hasText(characterEncoding)) {
            try {
                charset = Charset.forName(characterEncoding);
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }
        }
        return truncate(new String(payload, charset));
    }
}
