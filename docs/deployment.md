# 部署与运维文档

本文档说明如何用 `java -jar` 部署本项目，并介绍日志、排障和基本验收方式。

## 1. 环境要求

- JDK 8 或更高
- Maven 3.8 或更高
- 能访问上游 OpenAI Compatible 提供商

## 2. 打包

在项目根目录执行：

```bash
mvn clean package
```

打包成功后会生成：

```text
target/anthropic-adapter-1.0.0.jar
```

## 3. `java -jar` 部署方式

### 3.1 Windows

```bat
set SPRING_PROFILES_ACTIVE=prod
set UPSTREAM_BASE_URL=https://api.minimaxi.com/v1
set UPSTREAM_CHAT_COMPLETIONS_PATH=/chat/completions
set UPSTREAM_API_KEY=your_api_key
set DEFAULT_MODEL=MiniMax-M2.1
set LOG_PATH=logs
java -jar target\anthropic-adapter-1.0.0.jar
```

### 3.2 Linux / macOS

```bash
export SPRING_PROFILES_ACTIVE=prod
export UPSTREAM_BASE_URL=https://api.minimaxi.com/v1
export UPSTREAM_CHAT_COMPLETIONS_PATH=/chat/completions
export UPSTREAM_API_KEY=your_api_key
export DEFAULT_MODEL=MiniMax-M2.1
export LOG_PATH=logs
java -jar target/anthropic-adapter-1.0.0.jar
```

### 3.3 常见 JVM 启动参数

```bash
java -Xms256m -Xmx512m -jar target/anthropic-adapter-1.0.0.jar
```

## 4. 配置项说明

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 服务端口 |
| `SPRING_PROFILES_ACTIVE` | 空 | 建议线上使用 `prod` |
| `UPSTREAM_BASE_URL` | `https://api.minimaxi.com/v1` | 上游 OpenAI Compatible 基础地址 |
| `UPSTREAM_CHAT_COMPLETIONS_PATH` | `/chat/completions` | 上游接口路径 |
| `UPSTREAM_API_KEY` | 空 | 上游 API Key |
| `DEFAULT_MODEL` | `MiniMax-M2.1` | 默认模型名 |
| `LOG_PATH` | `logs` | 日志目录 |

## 5. 对外接口

### 5.1 OpenAI 直通接口

```text
POST /v1/chat/completions
```

### 5.2 Anthropic 适配接口

```text
POST /v1/messages
```

默认监听地址：

```text
http://127.0.0.1:8080
```

## 6. 日志系统说明

当前项目使用 Spring Boot 默认的 Logback，并补充了以下运维能力：

- 控制台日志
- 滚动文件日志
- 每个请求自动分配 `request_id`
- 入口请求、出口响应、上游调用、异常处理四段链路日志
- 错误时自动记录截断后的请求体，便于排障

### 6.1 日志文件位置

默认日志目录：

```text
logs/
```

主日志文件：

```text
logs/anthropic-adapter.log
```

归档日志文件：

```text
logs/archive/anthropic-adapter.yyyy-MM-dd.i.log.gz
```

### 6.2 关键日志字段

- `req:<request_id>`：整条链路关联键
- `Inbound request`：入口请求摘要
- `Request completed`：请求结束摘要
- `Upstream request`：对上游 OpenAI Compatible 服务的调用
- `Upstream response`：上游返回摘要
- `Request validation failed`：本地参数错误
- `Request processing failed`：本地处理异常

### 6.3 如何追踪一条请求

1. 从客户端响应头拿 `X-Request-Id`
2. 在日志中搜索这个值
3. 按顺序查看：
   - Inbound request
   - 适配或代理日志
   - Upstream request
   - Upstream response
   - Request completed

### 6.4 日志性能策略

- 默认只打印摘要，不打印全量成功响应体
- 请求体只在 `DEBUG` 或错误时打印
- 文件日志使用异步写入，降低对主请求线程的影响
- 单条 payload 日志默认截断，避免超长日志

## 7. 基本验收

### 7.1 OpenAI 直通

```bash
curl -i -X POST "http://127.0.0.1:8080/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your_api_key" \
  -d "{\"model\":\"MiniMax-M2.1\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with pong only\"}],\"max_tokens\":16}"
```

### 7.2 Anthropic 非流式

```bash
curl -i -X POST "http://127.0.0.1:8080/v1/messages" \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -d "{\"model\":\"MiniMax-M2.1\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Reply with pong only\"}]}]}"
```

### 7.3 Anthropic 流式

```bash
curl -N -i -X POST "http://127.0.0.1:8080/v1/messages" \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -d "{\"model\":\"MiniMax-M2.1\",\"stream\":true,\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Reply with pong only\"}]}]}"
```

### 7.4 Anthropic 工具调用

```bash
curl -i -X POST "http://127.0.0.1:8080/v1/messages" \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -d "{\"model\":\"MiniMax-M2.1\",\"max_tokens\":128,\"tools\":[{\"name\":\"weather\",\"description\":\"Get weather by city\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}],\"tool_choice\":{\"type\":\"any\",\"disable_parallel_tool_use\":true},\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Call the weather tool for Shanghai. Do not answer directly.\"}]}]}"
```

## 8. 常见排障路径

### 8.1 返回 400

- 先确认 JSON 是否合法
- 再确认是 OpenAI 还是 Anthropic 接口
- 再看日志中的 `request_body`

### 8.2 返回 401 / 403

- 检查 `Authorization` 或 `x-api-key`
- 检查 `UPSTREAM_API_KEY`
- 检查是否把请求头透传到了错误的接口

### 8.3 工具调用没有触发

- 先确认请求是否真的带了 `tools`
- 再确认 `tool_choice`
- 再确认上游兼容层是否支持 `required` / `parallel_tool_calls`

### 8.4 流式 usage 不对

- 先确认上游是否支持 `stream_options.include_usage`
- 再确认是否收到了最终 usage chunk
- 如果上游本身不返回 usage，适配器也无法补出真实值
