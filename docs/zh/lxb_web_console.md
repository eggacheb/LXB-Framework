# LXB-WebConsole

## 1. Scope
LXB-WebConsole 是统一的 Web 调试入口，提供连接管理、命令调试、地图构建、地图查看和 Cortex 执行的界面。

## 2. Architecture
代码目录：`web_console/`

```
web_console/
├── app.py                 # Flask 后端服务
├── templates/             # HTML 模板
│   ├── index.html         # 连接状态页
│   ├── command_studio.html
│   ├── map_builder.html
│   ├── map_viewer.html
│   └── cortex_route.html
└── static/
    └── js/
        └── main.js        # 前端交互逻辑
```

### 模块关系

```
Web Browser (用户界面)
       │
       v
Flask Backend (app.py)
       │
       ├──> LXB-Link (设备通信)
       ├──> LXB-Cortex (自动化执行)
       └──> LXB-MapBuilder (地图构建)
```

## 3. Core Flow

### 3.1 连接管理流程

```
用户输入设备信息 (IP + 端口)
       │
       v
创建 LXBLinkClient 实例
       │
       v
握手验证 (handshake)
       │
       v
获取设备信息并显示
```

### 3.2 命令调试流程

```
用户选择命令类型 (TAP/SWIPE/INPUT/...)
       │
       v
前端表单填充参数
       │
       v
POST /api/command/execute
       │
       v
后端执行命令
       │
       v
前端显示结果
```

### 3.3 地图构建流程

```
用户配置建图参数
       │
       v
启动 NodeMapBuilder
       │
       v
实时进度推送
       │
       v
前端更新 UI (进度、截图、节点)
       │
       v
完成并保存地图 JSON
```

### 3.4 Cortex 执行流程

```
用户输入任务描述
       │
       v
选择或上传地图
       │
       v
创建 CortexFSMEngine
       │
       v
实时日志推送 (FSM 状态、路由轨迹)
       │
       v
前端可视化展示
```

## 4. Key Interfaces

### 4.1 主要页面

| 页面 | 路由 | 功能 |
|------|------|------|
| 连接状态 | `/` | 设备连接、状态显示 |
| 命令调试 | `/command_studio` | 发送命令、查看结果 |
| 地图构建 | `/map_builder` | 自动建图、进度监控 |
| 地图查看 | `/map_viewer` | 地图可视化、编辑 |
| 路由执行 | `/cortex_route` | 任务提交、执行监控 |

### 4.2 API 分类

**设备连接 API**
- `/api/device/connect` - 连接设备
- `/api/device/disconnect` - 断开连接
- `/api/device/status` - 获取状态

**命令执行 API**
- `/api/command/tap` - 点击
- `/api/command/swipe` - 滑动
- `/api/command/input_text` - 输入文本

**地图构建 API**
- `/api/explore/start` - 开始建图
- `/api/explore/progress` - 获取进度
- `/api/maps/list` - 列出地图

**Cortex 执行 API**
- `/api/cortex/submit` - 提交任务
- `/api/cortex/status/{task_id}` - 获取状态
- `/api/cortex/logs/{task_id}` - 获取日志

## 5. Design Principles

### 5.1 统一入口
- 所有功能集成在一个 Web 界面
- 统一的导航栏和状态显示
- 一致的用户体验

### 5.2 实时反馈
- 轮询机制获取任务进度
- 实时显示截图和日志
- 可视化状态机流转

### 5.3 模块解耦
- 前端通过 HTTP API 与后端通信
- 后端调用各模块核心功能
- 模块间独立，易于维护

## 6. Code Structure

| 文件 | 职责 | 关键内容 |
|------|------|----------|
| `app.py` | Flask 后端服务 | API 路由、设备管理 |
| `main.js` | 前端交互逻辑 | DOM 操作、AJAX 请求 |
| `templates/*.html` | 页面模板 | 各功能页面 UI |

## 7. Cross References
- `docs/zh/lxb_link.md` - 设备通信
- `docs/zh/lxb_map_builder.md` - 地图构建
- `docs/zh/lxb_cortex.md` - 自动化执行
