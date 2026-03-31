package com.example.adapter.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;

/**
 * Unifies local validation and adapter failures into endpoint-specific error shapes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            JsonProcessingException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<String> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Request validation failed, uri={}", request.getRequestURI(), ex);
        return buildErrorResponse(request, HttpStatus.BAD_REQUEST, "invalid_request_error", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex, HttpServletRequest request) {
        log.error("Request processing failed, uri={}", request.getRequestURI(), ex);
        return buildErrorResponse(request, HttpStatus.BAD_GATEWAY, "api_error", ex.getMessage());
    }

    private ResponseEntity<String> buildErrorResponse(HttpServletRequest request,
                                                      HttpStatus status,
                                                      String errorType,
                                                      String message) {
        ObjectNode payload = isAnthropicRequest(request)
                ? buildAnthropicError(errorType, message)
                : buildOpenAiError(errorType, message);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload.toString());
    }

    private boolean isAnthropicRequest(HttpServletRequest request) {
        return request != null
                && request.getRequestURI() != null
                && request.getRequestURI().startsWith("/v1/messages");
    }

    private ObjectNode buildAnthropicError(String errorType, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "error");

        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", errorType);
        error.put("message", message);
        payload.set("error", error);
        return payload;
    }

    private ObjectNode buildOpenAiError(String errorType, String message) {
        ObjectNode payload = objectMapper.createObjectNode();

        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", errorType);
        error.put("message", message);
        payload.set("error", error);
        return payload;
    }
}
