# 反诈通后端服务

反诈通后端是 FanZha 单仓库中的 Spring Boot 服务，提供账户注册与登录、非流式大模型对话、资料入库、反诈资讯 ETL、MySQL 持久化和 Chroma 向量索引。

## 能力边界

| 能力 | 状态 |
| --- | --- |
| 邮箱或手机号注册与登录 | 已实现；使用 BCrypt 密码哈希，尚未签发令牌 |
| DeepSeek 兼容对话 | 已实现于 `POST /ai/chat`；需要 API Key |
| 文本与文件入库 | 已实现；默认关闭 |
| 定时知识库 ETL | 已实现；默认关闭 |
| 反诈资讯抓取与抽取 | 已实现；默认关闭 |
| Chroma 向量索引 | 已实现；按需启用 |
| Milvus 适配器 | 试验性实现，尚未接入当前 ETL 主链路 |
| Android SSE 与多模态助手契约 | 当前服务尚未实现 |

服务目前不会签发 JWT，也未提供角色授权。生产部署在增加身份认证层之前，必须保持管理和资料入库接口关闭。

## 环境要求

- JDK 8
- Maven 3.8+
- MySQL 8
- 可选：Chroma、DeepSeek 兼容接口、BGE 向量化服务、Playwright 和 Tesseract

## 本地运行

首先使用 `../database/schema.sql` 初始化 MySQL，然后至少配置以下环境变量：

```bash
export DB_URL='jdbc:mysql://localhost:3306/fanzha?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USERNAME='fanzha'
export DB_PASSWORD='your-local-password'
./mvnw spring-boot:run
```

Windows PowerShell 中分别使用 `$env:DB_URL`、`$env:DB_USERNAME` 和 `$env:DB_PASSWORD` 设置相同参数。

服务状态和接口文档地址：

- `GET /actuator/health`
- `GET /swagger-ui.html`
- `GET /v3/api-docs`

运行测试：

```bash
./mvnw -B -ntp verify
```

## Docker Compose 部署

```bash
cp .env.example .env
# 修改 .env 中的两个数据库密码
docker compose up --build
```

该环境会启动 MySQL、Chroma 和后端服务。外部 AI、向量化、爬虫、资料入库与管理操作仍保持关闭，只有显式配置后才会启用。

## 重要配置

| 环境变量 | 用途 | 默认值 |
| --- | --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接信息 | 本地 MySQL、空密码 |
| `DEEPSEEK_API_KEY` | 允许 `/ai/chat` 调用外部模型 | 空 |
| `CHROMA_AUTO_INIT` | 启动时创建或检查集合 | `false` |
| `KNOWLEDGE_ETL_ENABLED` | 启用旧版定时知识 ETL | `false` |
| `CRAWLER_ENABLED` | 启用定时资讯爬虫 | `false` |
| `DEEPSEEK_EXTRACT_ENABLED` | 启用爬虫链路中的大模型抽取 | `false` |
| `INGESTION_ENDPOINTS_ENABLED` | 注册 `/upload` 接口 | `false` |
| `ADMIN_ENDPOINTS_ENABLED` | 注册手动 ETL 和爬虫接口 | `false` |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | 逗号分隔的浏览器来源 | 仅本机地址 |

完整配置位于 `src/main/resources/application.yml`。不得提交已填写的 `.env`、爬虫 Cookie、Playwright 用户目录、数据集或向量数据库文件。

## 源码结构

```text
backend/
├── src/main/java/com/magicvvu/fanzha/backend/
│   ├── config/       # 类型化配置与基础设施 Bean
│   ├── controller/   # HTTP 接口
│   ├── dao/          # Spring Data 仓储
│   ├── entity/       # JPA 实体
│   ├── service/      # 认证、入库、爬虫与向量工作流
│   └── util/         # 外部客户端、解析和脱敏工具
├── src/test/         # ETL 过滤、重试与向量计算测试
├── scripts/          # Chroma 诊断与维护脚本
├── Dockerfile
└── docker-compose.yml
```
