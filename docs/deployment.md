# 部署指南

## 可交付组件

本仓库可以构建两个交付物：

- Android 应用：支持 API 27 及以上版本，使用 JDK 17 和 Gradle Wrapper 构建。
- Spring Boot 后端：使用 Java 8 构建 JAR 或 OCI 容器镜像，依赖 MySQL 8，并可选接入 Chroma。

## 使用 Docker Compose 启动后端

环境要求：Docker Engine 与 Docker Compose v2。

```bash
git clone https://github.com/MagicVVu/FanZha.git
cd FanZha/backend
cp .env.example .env
```

在 `.env` 中为 `DB_PASSWORD` 和 `MYSQL_ROOT_PASSWORD` 设置不同的本地密码，然后启动：

```bash
docker compose up --build
```

该环境包含：

| 服务 | 默认端口 | 持久化方式 |
| --- | --- | --- |
| 后端 | `8080` | 无状态容器 |
| MySQL | `3306` | `mysql-data` 数据卷；使用 `database/schema.sql` 初始化 |
| Chroma | `8000` | `chroma-data` 数据卷 |

验证服务状态：

```bash
curl http://localhost:8080/actuator/health
```

在增加身份认证与授权前，不得在公网实例中启用 `ADMIN_ENDPOINTS_ENABLED` 或 `INGESTION_ENDPOINTS_ENABLED`。

## 不使用 Docker 启动后端

环境要求：JDK 8、Maven 3.8+ 和 MySQL 8。

1. 创建 `fanzha` 数据库并执行 `database/schema.sql`。
2. 设置 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。
3. 测试、打包并启动服务：

```bash
cd backend
./mvnw -B -ntp clean verify
java -jar target/fanzha-backend.jar
```

可选集成通过 `DEEPSEEK_API_KEY`、`CHROMA_*`、`EMBEDDING_*`、`PLAYWRIGHT_*` 和爬虫相关变量配置，具体见 `backend/README.md` 与 `application.yml`。

### 后端生产部署检查清单

1. 在可信代理后通过 TLS 暴露服务，并接入身份认证。
2. 使用最小权限 MySQL 账户和托管密钥服务。
3. 将 Actuator 限制为健康检查及必要指标。
4. 为登录、注册、AI 对话和上传接口增加限流。
5. 爬虫 Cookie、代理凭据和浏览器配置不得写入镜像。
6. 固定并扫描容器镜像与 Maven 依赖。
7. 备份 MySQL 和 Chroma 数据卷，并定期验证恢复流程。
8. 多实例发布前接入版本化数据库迁移。

## Android 客户端配置

环境要求：Android SDK 36、JDK 17，以及 API 27 及以上的设备或模拟器。

参考 `config/local.properties.example`，将所需配置写入仓库根目录被 Git 忽略的 `local.properties`：

```properties
api.base.url=http://10.0.2.2:8080/
ai.api.base.url=http://10.0.2.2:8080/
```

也可使用环境变量 `FANZHA_API_BASE_URL`、`FANZHA_AI_API_BASE_URL` 和仅供开发联调的 `FANZHA_REGISTRATION_OTP`。属性文件优先级更高。

本地后端覆盖 `auth/register`、`auth/login` 及其自身的 `/ai/chat` 接口。Android 客户端所需的 `/api/assistant/...` SSE、多模态、家庭守护和看板接口尚未实现。

Windows 下构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

macOS 或 Linux 下构建：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 正式版本构建

Android 签名材料必须保存在仓库之外，并由发布环境注入。更新 `versionCode`、强制使用 HTTPS 后构建：

```bash
./gradlew :app:bundleRelease
```

公开发布前还必须审查短信、通话记录、已安装应用、通知和后台执行相关权限及隐私政策。

## 验证清单

- 后端 Maven 测试与 Android JVM 测试全部通过。
- Docker Compose 配置中不包含内嵌凭据。
- MySQL 初始化完成后，`/actuator/health` 返回健康状态。
- 全新数据库环境中的注册和登录正常。
- 未配置凭据或可选服务时，AI 与向量操作能够安全失败。
- 上传和管理接口默认不存在。
- Android 权限被拒绝时，无关功能仍可使用。
- Git 变更中没有接口凭据、私有数据集、SDK 路径或签名文件。

## 常见问题

- **后端无法通过表结构校验：**执行 `database/schema.sql`；如果是无用的全新 Docker 数据卷，也可在确认没有数据后重建。
- **Android 模拟器无法访问后端：**使用 `10.0.2.2`，不要使用 `localhost`。
- **AI 对话提示配置不可用：**设置 `DEEPSEEK_API_KEY`，但不得写入 Git 跟踪文件。
- **Chroma 启动缓慢或不可用：**不测试向量功能时，保持 `CHROMA_AUTO_INIT=false`。
- **爬虫没有返回文章：**目标站点反爬策略可能发生变化；启用代理、浏览器或验证码功能前，应先评估网络、法律和授权要求。
