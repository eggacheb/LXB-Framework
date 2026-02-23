# LXB-Server

## 1. Scope
LXB-Server 是 Android 端服务核心，接收协议命令并执行输入注入、节点检索、状态获取。

## 2. Architecture
代码目录：`android/LXB-Ignition/lxb-core/`

```
lxb-core/
├── protocol/               # 协议解析与分发
├── dispatcher/            # 命令分发器
├── perception/            # 感知引擎
└── executors/             # 执行器实现
```

### 服务架构

```
Shizuku IPC
       │
       v
LXB-Server Core
       │
       ├──> Perception Engine (Accessibility Service)
       │      └── 获取 UI 树、节点属性
       │
       └──> Executors (Input/Lifecycle)
              └── 注入输入、应用控制
```

## 3. Core Flow

### 3.1 命令处理流程

```
接收 UDP 帧
    │
    v
解析帧 (CMD ID + Payload)
    │
    v
分发到对应引擎
    │
    ├──> Perception Commands → AccessibilityService → UI 树数据
    │
    └──> Execution Commands → Input Manager → 设备操作
    │
    v
生成响应帧
```

### 3.2 感知引擎原理

**AccessibilityService 机制**：
- 继承 Android AccessibilityService
- 监听 UI 变化事件
- 遍历 UI 树提取节点信息

**节点属性提取**：
- `getText()` - 可见文本
- `getResourceName()` - Resource ID
- `getBoundsInScreen()` - 屏幕坐标
- `isClickable()` - 可点击性

### 3.3 输入注入方式

| 方式 | 实现原理 | 优先级 |
|------|----------|--------|
| Accessibility API | `performAction(ACTION_CLICK)` | 最高 |
| Clipboard | 设置剪贴板 + 粘贴 | 中 |
| Shell input | `input text` 命令 | 最低 (降级) |

## 4. Node Matching

### 4.1 单字段查找 (FIND_NODE)
1. 获取当前 UI 树
2. 根据 match_type 选择匹配字段
3. 遍历节点，执行匹配逻辑
4. 收集所有匹配节点

### 4.2 多条件查找 (FIND_NODE_COMPOUND)
1. 构建条件三元组：(field, operator, value)
2. 对所有节点逐条件验证
3. 返回满足所有条件的节点

## 5. Failure Modes

| 失败类型 | 原因 | 处理 |
|----------|------|------|
| Service 断开 | 系统回收服务 | 返回错误码 |
| UI 树为空 | 页面加载中 | 返回空列表 |
| 权限不足 | Shizuku 未授权 | 返回权限错误 |

## 6. Code Structure

| Java 文件 | 职责 |
|----------|------|
| `PerceptionEngine.java` | 感知引擎入口 |
| `CommandDispatcher.java` | 命令路由分发 |
| `NodeFinder.java` | 节点查找逻辑 |

## 7. Cross References
- `docs/zh/lxb_link.md` - 协议定义
- `docs/zh/lxb_web_console.md` - Web 控制台
