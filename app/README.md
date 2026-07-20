# Android 应用模块

`app` 是反诈通 Android 客户端，包含 Compose 界面、视图模型、本地持久化、Retrofit 接口契约、多模态请求编排、本地 OCR 和设备侧风险信息采集。

主要包结构：

- `data`：本地持久化、API 契约、数据模型和仓储
- `domain`：安全指数计算
- `security`：经用户授权的短信、通话记录、剪贴板和已安装应用采集
- `notifications`：风险指令轮询与本地通知协调
- `ui`：Compose 页面、组件、状态持有者和主题
- `util`：OCR、媒体处理与 Android 工具代码

服务端实现不属于本模块。客户端接口契约见 `../docs/api.md`，系统边界见 `../docs/architecture.md`。
