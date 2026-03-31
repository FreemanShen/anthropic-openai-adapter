# Anthropic Adapter

将 Anthropic `POST /v1/messages` 请求转换为 OpenAI Compatible `POST /v1/chat/completions` 请求，并把响应再转换回 Anthropic 格式。

## 项目目标

适用于这样的场景：

- 模型供应商只提供 OpenAI Compatible 接口
- 你的上层应用只支持 Anthropic Messages API
- 你希望在中间加一层轻量适配器，而不是修改上层应用

## 当前能力

- OpenAI 直通接口：`POST /v1/chat/completions`
- Anthropic 适配接口：`POST /v1/messages`
- 文本对话
- 流式 SSE
- 工具调用
- `tool_result` 回传
- 图片输入
- Anthropic / OpenAI 错误格式转换
- `request_id` 全链路日志追踪

## 快速开始

### 1. 打包

```bash
mvn clean package
```

### 2. 启动

```bash
java -jar target/anthropic-adapter-1.0.0.jar
```

### 3. 常用环境变量

```bash
UPSTREAM_BASE_URL=https://api.minimaxi.com/v1
UPSTREAM_CHAT_COMPLETIONS_PATH=/chat/completions
UPSTREAM_API_KEY=your_api_key
DEFAULT_MODEL=MiniMax-M2.1
LOG_PATH=logs
```

## 文档索引

- API 报文参考与 OpenAI / Anthropic 差异说明：
  [docs/api-format-reference.md](docs/api-format-reference.md)
- `java -jar` 部署、日志与排障说明：
  [docs/deployment.md](docs/deployment.md)

## 目录说明

```text
src/main/java      Java 源码
src/main/resources 配置与日志配置
src/test/java      单元测试
docs/              交付文档
scripts/           启动脚本
```

## 主要代码位置

- 控制器：`src/main/java/com/example/adapter/controller/ApiProxyController.java`
- Anthropic / OpenAI 映射：`src/main/java/com/example/adapter/service/AnthropicOpenAiMapper.java`
- Anthropic SSE 翻译：`src/main/java/com/example/adapter/service/AnthropicStreamTranslator.java`
- 上游代理：`src/main/java/com/example/adapter/service/OpenAiProxyService.java`
- 请求链路日志：`src/main/java/com/example/adapter/logging/RequestTracingFilter.java`
- 上游调用日志：`src/main/java/com/example/adapter/logging/UpstreamLoggingInterceptor.java`

## 开发命令

```bash
mvn test
mvn clean package
```
