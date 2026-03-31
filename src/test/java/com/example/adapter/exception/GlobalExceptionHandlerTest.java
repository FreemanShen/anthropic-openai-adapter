package com.example.adapter.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(objectMapper);

    @Test
    void shouldReturnAnthropicErrorShapeForMessagesEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/messages");

        ResponseEntity<String> response = handler.handleBadRequest(
                new IllegalArgumentException("bad request"), request);
        JsonNode body = objectMapper.readTree(response.getBody());

        Assertions.assertEquals(400, response.getStatusCodeValue());
        Assertions.assertEquals("error", body.path("type").asText());
        Assertions.assertEquals("invalid_request_error", body.path("error").path("type").asText());
        Assertions.assertEquals("bad request", body.path("error").path("message").asText());
    }

    @Test
    void shouldReturnOpenAiErrorShapeForChatCompletionsEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/v1/chat/completions");

        ResponseEntity<String> response = handler.handleException(
                new RuntimeException("upstream failure"), request);
        JsonNode body = objectMapper.readTree(response.getBody());

        Assertions.assertEquals(502, response.getStatusCodeValue());
        Assertions.assertEquals("api_error", body.path("error").path("type").asText());
        Assertions.assertEquals("upstream failure", body.path("error").path("message").asText());
    }
}
