# 反诈通智能分析服务

该模块是反诈通的独立智能分析服务，为 Android 客户端提供文本、图片、音频、视频、网页和文件的统一风险识别接口。服务使用规则引擎做高风险兜底，以本地知识库提供可追溯证据，并可选调用 DeepSeek 兼容模型生成解释和处置建议。

## 核心能力

- 普通对话与 SSE 流式对话
- 多附件会话和会话内证据聚合
- 图片 OCR、音频转写、视频关键帧与音轨联合分析
- 网页正文、TXT、PDF、DOCX 和常见文本文件提取
- 短信专项检测与风险报告建议
- BGE 向量检索、词法召回和二阶段启发式重排
- 新型案例候选队列、人工审核和知识库重建
- 模型不可用时的规则引擎与本地知识库降级

## 运行环境

- Python 3.11
- FFmpeg
- Playwright Chromium
- 首次启用 OCR、语音识别或向量检索时需要下载模型文件

完整依赖较大，生产环境建议使用 Docker 构建，并至少预留 8 GB 内存和 20 GB 可用磁盘。视频并发较高时应使用独立任务队列，不建议仅增加 Uvicorn Worker 数量。

## 本地启动

```bash
cd ai-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m playwright install chromium
cp .env.example .env
python run.py
```

Windows PowerShell 激活命令为：

```powershell
.\.venv\Scripts\Activate.ps1
```

服务默认监听 `http://127.0.0.1:8000`：

- 健康检查：`GET /health`
- OpenAPI：`GET /docs`
- 助手接口：`/api/assistant/*`
- 人工复核接口：`/api/admin/review/*`

未填写 `DEEPSEEK_API_KEY` 时，服务仍可使用规则引擎和本地知识库返回降级结果。启用人工复核接口前必须设置高强度 `ADMIN_REVIEW_TOKEN`。

## Docker 部署

```bash
cd ai-service
cp .env.production.example .env.production
docker compose up --build -d
curl http://127.0.0.1/health
```

Compose 会启动 FastAPI 和 Nginx，并将上传文件、模型缓存、Qdrant 数据、审核队列和知识库版本记录保存在本地挂载目录中。这些运行数据已被 Git 忽略。

## 测试

轻量单元测试不会下载模型：

```bash
pip install -r requirements-test.txt
python -m unittest discover -s tests -p "test_*.py"
```

`tests/retrieval_cases.json` 和 `tests/test_cases_120.json` 是可复用的检索与风险规则评测用例；评测结果属于运行产物，不纳入版本控制。

## 目录结构

```text
ai-service/
├── app/
│   ├── routers/             # 助手与人工复核 API
│   ├── services/            # OCR、ASR、风险、检索和多模态流水线
│   ├── scripts/             # 知识库维护命令
│   └── data/                # 可审查的知识库种子与检索基线
├── deploy/                  # 容器入口和 Nginx 配置
├── tests/                   # 单元测试与评测用例
├── tools/                   # 工程辅助脚本
├── Dockerfile
├── docker-compose.yml
└── requirements.txt
```

## 安全边界

- 上传内容、会话内容和短信正文不得写入普通日志。
- `/api/admin/review/*` 只应暴露在可信网络或受认证网关之后。
- 生产环境应启用 TLS、请求限流、上传大小与媒体类型限制、恶意文件检测和数据保留策略。
- 风险分数和模型输出仅用于辅助判断，不替代公安、银行或平台风控结论。
