# LXB-Link

## 1. Scope
LXB-Link 是 PC 侧到 Android 端的协议客户端层，负责可靠发送命令、接收响应。

## 2. Architecture
代码目录：`src/lxb_link/`

```
src/lxb_link/
├── __init__.py
├── client.py               # 主客户端 API
├── transport.py            # 可靠 UDP 传输 (Stop-and-Wait ARQ)
├── protocol.py             # 协议帧编解码
└── constants.py            # 命令常量定义
```

### 架构层次

```
应用层 (Cortex, MapBuilder)
        ↓
  LXBLinkClient (统一 API)
        ↓
  ProtocolFrame (协议编解码)
        ↓
  Transport (可靠 UDP 传输)
        ↓
  UDP Socket (网络通信)
```

## 3. Core Flow

### 3.1 命令执行流程

```
client.tap(500, 800)
    │
    v
编码为协议帧 (MAGIC + CMD + LEN + SEQ + PAYLOAD + CHECKSUM)
    │
    v
发送 + 等待 ACK (超时重传，最多 MAX_RETRIES)
    │
    v
接收响应帧 + 校验
    │
    v
解析结果并返回
```

### 3.2 可靠传输原理

Stop-and-Wait ARQ 协议：
1. 发送数据帧后启动定时器
2. 收到 ACK 后确认发送成功
3. 超时未收到 ACK 则自动重传

## 4. Command Categories

### 感知层 (Sense)
- `get_activity` - 获取当前 Activity
- `get_screen_size` - 获取屏幕尺寸
- `find_node` - 单字段节点查找
- `find_node_compound` - 多条件组合查找
- `dump_actions` - 导出可操作节点
- `dump_hierarchy` - 导出完整 UI 树

### 输入层 (Input)
- `tap` - 点击
- `swipe` - 滑动
- `long_press` - 长按
- `input_text` - 输入文本
- `key_event` - 按键事件 (BACK/HOME)

### 生命周期 (Lifecycle)
- `launch_app` - 启动应用
- `stop_app` - 停止应用
- `list_apps` - 列出应用
- `wake` / `unlock` - 唤醒/解锁

## 5. Design Principles

### 5.1 可靠性设计
- 超时重传机制 (MAX_RETRIES=3)
- Checksum 校验数据完整性
- 序列号机制防止重复处理

### 5.2 检索优先策略
- `find_node_compound` 优先 (多条件组合，定位准确)
- `find_node` 兜底 (单字段，兼容性好)

## 6. Code Structure

| 文件 | 职责 | 关键内容 |
|------|------|----------|
| `client.py` | 统一 API 入口 | `LXBLinkClient` 类 |
| `transport.py` | 传输层实现 | `_send_frame`, `_recv_frame` |
| `protocol.py` | 协议编解码 | `ProtocolFrame.encode/decode` |
| `constants.py` | 命令/常量定义 | CMD_* 常量 |

## 7. Cross References
- `docs/zh/lxb_server.md` - Android 端实现
- `docs/zh/lxb_cortex.md` - Cortex 使用示例
