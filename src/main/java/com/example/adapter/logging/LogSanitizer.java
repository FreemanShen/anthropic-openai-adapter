package com.example.adapter.logging;

import org.springframework.util.StringUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Small helpers to keep operational logs readable and safe.
 */
public final class LogSanitizer {

    public static final int MAX_PAYLOAD_LOG_LENGTH = 4096;

    private LogSanitizer() {
    }

    public static String truncate(String value) {
        return truncate(value, MAX_PAYLOAD_LOG_LENGTH);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated," + value.length() + " chars)";
    }

    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

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
