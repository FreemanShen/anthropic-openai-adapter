# OpenAI 与 Anthropic API 报文参考

本文档面向第一次接触 OpenAI Chat Completions 与 Anthropic Messages API 的同事。

官方文档：

- OpenAI Chat Completions: https://platform.openai.com/docs/api-reference/chat/create
- OpenAI Streaming: https://platform.openai.com/docs/api-reference/chat/streaming
- OpenAI Function Calling: https://platform.openai.com/docs/guides/function-calling
- Anthropic Messages API: https://docs.anthropic.com/en/api/messages
- Anthropic Streaming: https://docs.anthropic.com/en/api/messages-streaming
- Anthropic Tool Use: https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use

## 1. 本项目做了什么

本项目对外暴露两个接口：

- `POST /v1/chat/completions`
  直接透传 OpenAI Compatible 报文到上游。
- `POST /v1/messages`
  接收 Anthropic Messages 报文，转换为 OpenAI Compatible 请求，再将响应转换回 Anthropic 格式。

当前适配目标是：

- 非流式文本对话
- 流式 SSE 对话
- 工具调用
- `tool_result` 回传
- 图片输入
- 常见 usage / stop reason / error 的格式转换

## 2. OpenAI Chat Completions 报文参考

### 2.1 非流式请求

```json
{
  "model": "MiniMax-M2.1",
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful assistant."
    },
    {
      "role": "user",
      "content": "Reply with pong only."
    }
  ],
  "temperature": 0,
  "top_p": 1,
  "max_tokens": 32
}
```

### 2.2 非流式响应

```json
{
  "id": "chatcmpl_xxx",
  "object": "chat.completion",
  "model": "MiniMax-M2.1",
  "choices": [
    {
      "index": 0,
      "finish_reason": "stop",
      "message": {
        "role": "assistant",
        "content": "pong"
      }
    }
  ],
  "usage": {
    "prompt_tokens": 12,
    "completion_tokens": 1,
    "total_tokens": 13
  }
}
```

### 2.3 工具调用请求

```json
{
  "model": "MiniMax-M2.1",
  "messages": [
    {
      "role": "user",
      "content": "Call the weather tool for Shanghai."
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "weather",
        "description": "Get weather by city",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string"
            }
          },
          "required": ["city"]
        }
      }
    }
  ],
  "tool_choice": "required",
  "parallel_tool_calls": false,
  "max_tokens": 128
}
```

### 2.4 工具调用响应

```json
{
  "choices": [
    {
      "finish_reason": "tool_calls",
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_123",
            "type": "function",
            "function": {
              "name": "weather",
              "arguments": "{\"city\":\"Shanghai\"}"
            }
          }
        ]
      }
    }
  ]
}
```

### 2.5 工具结果回传

```json
{
  "model": "MiniMax-M2.1",
  "messages": [
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_123",
          "type": "function",
          "function": {
            "name": "weather",
            "arguments": "{\"city\":\"Shanghai\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_123",
      "content": "Shanghai is sunny, 23C."
    }
  ],
  "max_tokens": 128
}
```

### 2.6 流式响应

OpenAI 流式接口返回 SSE，事件名通常不区分类型，核心是多段 `data:`：

```text
data: {"id":"chatcmpl_xxx","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant"}}]}

data: {"id":"chatcmpl_xxx","object":"chat.completion.chunk","choices":[{"delta":{"content":"pong"}}]}

data: {"id":"chatcmpl_xxx","object":"chat.completion.chunk","choices":[{"finish_reason":"stop","delta":{}}]}

data: [DONE]
```

如果请求中带了：

```json
{
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

那么很多 OpenAI Compatible 提供商会在最后额外补一条 usage chunk。

## 3. Anthropic Messages 报文参考

### 3.1 非流式请求

```json
{
  "model": "claude-sonnet-4-5",
  "system": "You are a helpful assistant.",
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Reply with pong only."
        }
      ]
    }
  ],
  "max_tokens": 32,
  "temperature": 0,
  "top_p": 1
}
```

### 3.2 非流式响应

```json
{
  "id": "msg_xxx",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-5",
  "content": [
    {
      "type": "text",
      "text": "pong"
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 12,
    "output_tokens": 1
  }
}
```

### 3.3 工具调用请求

```json
{
  "model": "claude-sonnet-4-5",
  "max_tokens": 128,
  "tools": [
    {
      "name": "weather",
      "description": "Get weather by city",
      "input_schema": {
        "type": "object",
        "properties": {
          "city": {
            "type": "string"
          }
        },
        "required": ["city"]
      }
    }
  ],
  "tool_choice": {
    "type": "any",
    "disable_parallel_tool_use": true
  },
  "messages": [
    {
      "role": "user",
      "content": [
        {
          "type": "text",
          "text": "Call the weather tool for Shanghai. Do not answer directly."
        }
      ]
    }
  ]
}
```

### 3.4 工具调用响应

```json
{
  "type": "message",
  "role": "assistant",
  "content": [
    {
      "type": "tool_use",
      "id": "toolu_123",
      "name": "weather",
      "input": {
        "city": "Shanghai"
      }
    }
  ],
  "stop_reason": "tool_use"
}
```

### 3.5 工具结果回传

Anthropic 不是单独的 `role=tool` 消息，而是把工具结果放进下一条 `user` 消息的 `tool_result` block 中：

```json
{
  "model": "claude-sonnet-4-5",
  "max_tokens": 128,
  "messages": [
    {
      "role": "assistant",
      "content": [
        {
          "type": "tool_use",
          "id": "toolu_123",
          "name": "weather",
          "input": {
            "city": "Shanghai"
          }
        }
      ]
    },
    {
      "role": "user",
      "content": [
        {
          "type": "tool_result",
          "tool_use_id": "toolu_123",
          "content": [
            {
              "type": "text",
              "text": "Shanghai is sunny, 23C."
            }
          ]
        }
      ]
    }
  ]
}
```

### 3.6 流式响应

Anthropic 流式接口同样使用 SSE，但事件类型是显式的：

```text
event: message_start
data: {"type":"message_start", ...}

event: content_block_start
data: {"type":"content_block_start", "index":0, ...}

event: content_block_delta
data: {"type":"content_block_delta", "index":0, "delta":{"type":"text_delta","text":"pong"}}

event: content_block_stop
data: {"type":"content_block_stop", "index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

event: message_stop
data: {"type":"message_stop"}
```

工具流式时，工具输入会通过 `input_json_delta` 分段返回：

```text
event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Shanghai\"}"}}
```

## 4. OpenAI 与 Anthropic 的关键差异

| 维度 | OpenAI Chat Completions | Anthropic Messages |
| --- | --- | --- |
| 入口路径 | `/v1/chat/completions` | `/v1/messages` |
| 鉴权 | `Authorization: Bearer ...` | `x-api-key: ...`，通常还会带 `anthropic-version` |
| system prompt | `messages` 里的 `role=system` | 顶层 `system` 字段，不使用 `system` role |
| 消息内容 | `content` 可以是字符串或内容数组 | `content` 可以是字符串或内容 block 数组 |
| 图片输入 | `image_url` | `image` + `source` |
| 工具定义 | `tools[].function.parameters` | `tools[].input_schema` |
| 工具选择 | `"auto"`, `"none"`, `"required"`, 或指定 `function` | `tool_choice.type` 常见为 `auto`, `any`, `tool`, `none` |
| 并行工具 | `parallel_tool_calls` | `disable_parallel_tool_use` |
| 工具调用结果 | `assistant.tool_calls` | `assistant.content[].tool_use` |
| 工具结果回传 | `role=tool` | `user.content[].tool_result` |
| 流式协议 | 主要是连续 `data:` chunk | 显式 `event:` + `data:` |
| usage 字段 | `prompt_tokens`, `completion_tokens` | `input_tokens`, `output_tokens` |
| 缓存 token | 常见在 `prompt_tokens_details.cached_tokens` | `cache_read_input_tokens`, `cache_creation_input_tokens` |
| 结束原因 | `stop`, `length`, `tool_calls`, `content_filter` | `end_turn`, `max_tokens`, `tool_use`, `stop_sequence`, `refusal` |
| 错误结构 | `{"error": {...}}` | `{"type":"error","error": {...}}` |
| metadata / user | 常用 `user` | 常用 `metadata` |

## 5. 本适配器中的映射规则

### 5.1 请求方向：Anthropic -> OpenAI

- `model` 直接透传
- `system` 转为一条 `role=system` 消息
- `messages[].content[].text` 转为 OpenAI 文本内容
- `messages[].content[].image` 转为 `image_url`
- `tools[].input_schema` 转为 `tools[].function.parameters`
- `tool_choice.type=any` 转为 OpenAI `tool_choice="required"`
- `tool_choice.type=tool` 转为 OpenAI 指定函数调用格式
- `tool_choice.disable_parallel_tool_use=true` 转为 `parallel_tool_calls=false`
- `stop_sequences` 转为 OpenAI `stop`
- 流式请求自动补 `stream_options.include_usage=true`
- `tool_result` 转为 OpenAI `role=tool`

### 5.2 响应方向：OpenAI -> Anthropic

- `message.content` 转为 `content[].text`
- `message.tool_calls` 转为 `content[].tool_use`
- `finish_reason=tool_calls` 转为 `stop_reason=tool_use`
- `finish_reason=length` 转为 `stop_reason=max_tokens`
- `finish_reason=stop` 转为 `stop_reason=end_turn`
- `finish_reason=content_filter` 转为 `stop_reason=refusal`
- `prompt_tokens` / `completion_tokens` 转为 `input_tokens` / `output_tokens`
- `prompt_tokens_details.cached_tokens` 转为 `cache_read_input_tokens`

### 5.3 流式转换规则

- OpenAI 普通文本 delta 转为 Anthropic `text_delta`
- OpenAI 工具参数增量转为 Anthropic `input_json_delta`
- OpenAI 流结束后补齐 `content_block_stop`、`message_delta`、`message_stop`
- 如果上游提供最终 usage chunk，则写入 Anthropic `message_delta.usage`

## 6. 适配器当前的已知约束

- OpenAI Compatible 提供商对 `tool_choice` 的兼容程度不完全一致，MiniMax 实测下更适合使用 OpenAI 原生语义：
  - `auto`
  - `required`
  - `{ "type": "function", "function": { "name": "..." } }`
- 某些 OpenAI Compatible 提供商会把推理过程放进普通文本中，例如 `<think>...</think>`。本适配器默认会在返回 Anthropic 报文前做过滤，也可通过 `adapter.upstream.filter-reasoning-text=false` 或 `FILTER_REASONING_TEXT=false` 关闭。
- Anthropic 的复杂 `tool_result` 内容无法在 OpenAI `role=tool` 中完全 1:1 表达。本适配器会尽量保留为结构化 JSON 字符串，避免信息静默丢失。

## 7. 开发和排障建议

- 先看入口路径是否正确。
- 再看鉴权头是否正确。
- 再看 `request_id` 对应的一整条链路日志。
- 如果是工具调用问题，先确认 `tool_choice` 是否被正确映射。
- 如果是流式问题，先确认 SSE 事件顺序是否完整。
- 如果是 usage 对不上，确认上游是否真的返回了最终 usage chunk。
