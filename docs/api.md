# API 参考与兼容性矩阵

后端运行后，会在 `/v3/api-docs` 提供自动生成的 OpenAPI 文档，并在 `/swagger-ui.html` 提供 Swagger UI。本文件记录源码中的接口契约，并将其与仅存在于 Android 客户端中的接口需求区分开。

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

### AI 对话

| 方法 | 路径 | 请求字段 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/ai/chat` | `prompt`，可选 `system`、`model` | DeepSeek 兼容的非流式对话 |

运行时必须配置 `DEEPSEEK_API_KEY`。该接口不等同于 Android 客户端要求的 SSE 助手接口。

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
| 流式助手 | `api/assistant/chat/stream...` | 未实现 |
| 多模态分析 | `api/assistant/analyze`、`check-sms`、`report/advice` | 未实现 |

Android SSE 解析器要求服务端发送 `start`、`delta` 和 `done` 事件，并携带会话、建议和风险元数据。`/ai/chat` 只返回一次性 JSON 响应，不能直接替代这些接口。

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
3. 实现与 Android 助手 DTO 和 SSE 事件一致的适配层。
4. 在 Gradle 与 Maven CI 中增加 OpenAPI 契约测试。
5. 为数据入库增加幂等键，为 ETL 提供持久化任务资源。
