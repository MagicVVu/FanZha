# API 参考与兼容性矩阵

Spring Boot 后端在 `/v3/api-docs` 提供 OpenAPI，并在 `/swagger-ui.html` 提供 Swagger UI；FastAPI 智能分析服务在 `/openapi.json` 和 `/docs` 提供对应文档。本文件记录两套服务的接口边界，并将其与仅存在于 Android 客户端中的契约区分开。

## 响应结构

多数已实现接口采用以下响应结构：

```json
{
  "success": true,
  "message": "成功",
  "data": {}
}
```

稳定的机器可读错误码、链路追踪 ID 和令牌认证尚未实现。

## 已实现的后端接口

### 身份认证

| 方法 | 路径 | 请求字段 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/auth/register` | `account`、`password`，可选 `confirmPassword` | 账户必须是邮箱或中国大陆手机号 |
| `POST` | `/auth/login` | `account`、`password` | 返回用户身份信息，但不签发令牌 |

Android 客户端发送的其他注册字段不会由该接口持久化。

### Spring Boot AI 对话

| 方法 | 路径 | 请求字段 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/ai/chat` | `prompt`，可选 `system`、`model` | DeepSeek 兼容的非流式对话 |

运行时必须配置 `DEEPSEEK_API_KEY`。该接口不等同于 Android 客户端要求的 SSE 助手接口。

## 智能分析服务接口

智能分析服务默认监听 `8000`；Docker Compose 通过 Nginx 暴露 `80`。普通 JSON 和 multipart 接口如下：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/assistant/chat` | 带会话 ID 的文本对话 |
| `POST` | `/api/assistant/chat/multimodal` | 文本、链接和多附件对话 |
| `POST` | `/api/assistant/analyze` | 文本、图片、音频、视频、网页或文件风险分析 |
| `POST` | `/api/assistant/check-sms` | 短信发送方与正文专项检测 |
| `POST` | `/api/assistant/report/advice` | 根据拦截概览和风险行为生成报告建议 |
| `GET` | `/health` | 进程健康检查 |

流式接口为 `POST /api/assistant/chat/stream` 和 `POST /api/assistant/chat/stream/multimodal`，响应媒体类型为 `text/event-stream`，事件包括 `start`、`delta`、风险元数据更新和 `done`。

人工复核接口位于 `/api/admin/review/*`，通过 `X-Admin-Token` 请求头校验。该接口应只允许管理网络或认证网关访问。

### 资料入库接口（默认关闭）

设置 `INGESTION_ENDPOINTS_ENABLED=true` 后，才会注册以下接口。

| 方法 | 路径 | 请求 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/upload` | multipart 字段 `files` | 最多 200 个文件，支持格式由解析器决定 |
| `POST` | `/upload/text` | JSON 字段 `content` | 统一编码、脱敏，并返回指纹摘要 |

当前入库响应只返回处理元数据，不属于 Android 设备风险数据上报接口。

### 运维接口（默认关闭）

只有在可信控制网络中，才应设置 `ADMIN_ENDPOINTS_ENABLED=true`。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/admin/etl/knowledge/run` | 启动配置的知识库 ETL |
| `POST` | `/admin/crawler/run` | 启动一次反诈资讯抓取 |

这些操作当前使用进程内守护线程执行，尚未返回持久化任务 ID。

### 平台接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/actuator/health` | 服务与依赖健康状态 |
| `GET` | `/actuator/prometheus` | Prometheus 指标 |
| `GET` | `/v3/api-docs` | 自动生成的 OpenAPI JSON |

## 当前后端尚未实现的 Android 接口

| 契约分组 | Android 路径 | 后端状态 |
| --- | --- | --- |
| 用户资料 | `user/profile`、`user/occupations`、`user/security-score` | 未实现 |
| 家庭守护 | `family/member/add`、`family/member/list` | 未实现 |
| 风险拦截 | `intercept/...`、`intercept/ingest/batch` | 未实现 |
| 风险指令 | `api/alerts/commands/...` | 未实现 |
| 答题积分 | `quiz/score` | 未实现 |
| 流式助手 | `api/assistant/chat/stream...` | 智能分析服务已实现 |
| 多模态分析 | `api/assistant/analyze`、`check-sms`、`report/advice` | 智能分析服务已实现 |

Android 助手接口使用独立的智能分析服务地址。Spring Boot 的 `/ai/chat` 仍是一次性 JSON 接口，不应配置为 Android 助手地址。

## 安全约束

当前代码尚未实现 Bearer Token 或刷新令牌。在完善身份体系前：

- 不得把用户 ID 当作资源所有权证明；
- 管理和资料入库接口应保持关闭或限制在可信网络内；
- 应在外层网关限制注册、登录和 AI 对话频率；
- 代理层和应用层都应限制上传大小与媒体类型；
- 模型输出必须视为辅助建议，并校验其结构和取值范围。

## 后续演进建议

1. 引入版本化的 `/api/v1` 接口和统一错误码。
2. 增加访问令牌、刷新令牌和资源级授权。
3. 为智能分析服务增加版本化路由、统一错误码和契约回归测试。
4. 在 Gradle 与 Maven CI 中增加 OpenAPI 契约测试。
5. 为数据入库增加幂等键，为 ETL 提供持久化任务资源。
