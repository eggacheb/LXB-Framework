# 快速开始指南

本指南将帮助您快速上手 LXB-Framework。

## 前置条件

### 必需条件

- **Python 3.8+** - 确保已安装 Python 3.8 或更高版本
- **Android 设备** - 运行 Android 7.0+ 的设备或模拟器
- **网络连接** - 用于访问 LLM API

### Android 设备设置

1. **安装 Shizuku**

   LXB-Framework 依赖 Shizuku 进行系统级权限操作：

   - 下载 [Shizuku APK](https://shizuku.rikka.app/)
   - 在 Android 设备上安装并打开 Shizuku
   - 启动 Shizuku 服务（需要ADB权限或root权限）

2. **连接 ADB**

   ```bash
   # 通过 USB 连接设备
   adb devices

   # 启动 Shizuku（首次使用）
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```

### LLM API 设置

1. 获取 LLM API 密钥（支持 OpenAI 兼容接口）
2. 编辑项目根目录的 `.cortex_llm_planner.json`：

```json
{
  "api_base_url": "https://your-api-provider.com/v1",
  "api_key": "your-api-key-here",
  "model_name": "your-model-name"
}
```

## 安装

### 1. 克隆仓库

```bash
git clone https://github.com/your-org/LXB-Framework.git
cd LXB-Framework
```

### 2. 安装依赖

```bash
# 核心依赖（不需要VLM功能）
pip install -r requirements.txt

# 完整依赖（包含VLM功能）
pip install -r requirements-vlm.txt
```

### 3. 配置设备连接

编辑 `.cortex_llm_planner.json`，设置设备 IP 地址：

```json
{
  "device_ip": "192.168.1.100",
  "device_port": 12345
}
```

## 基本使用

### 启动 Web 控制台

```bash
cd web_console
python app.py
```

访问 `http://localhost:5000` 即可使用 Web 控制台。

### 命令行基本操作

```python
from lxb_link import LXBLinkClient
from cortex import CortexFSMEngine

# 连接设备
client = LXBLinkClient('192.168.1.100', 12345)
client.connect()
client.handshake()

# 截图
screenshot = client.screenshot()

# 点击屏幕
client.tap(500, 800)

# 滑动
client.swipe(540, 1600, 540, 1400, 500)
```

### Route-Then-Act 自动化

```python
from cortex import CortexFSMEngine, LLMPlanner

# 创建 LLM 规划器
def my_llm_complete(prompt):
    # 调用您的 LLM API
    return your_llm_api.generate(prompt)

planner = LLMPlanner(complete=my_llm_complete)

# 创建 FSM 引擎
engine = CortexFSMEngine(client, planner=planner)

# 执行自动化任务
result = engine.run(
    user_task="打开设置并开启WiFi",
    map_path="maps/com.android.settings.json"
)

print(result["status"])  # "success" 或 "failed"
```

### 构建导航地图

```python
from auto_map_builder import NodeMapBuilder
from auto_map_builder.vlm_engine import VLMEngine

# 初始化
vlm = VLMEngine(
    api_base_url="https://your-api.com/v1",
    api_key="your-key",
    model_name="qwen-vl-plus"
)

builder = NodeMapBuilder(
    client=client,
    vlm_engine=vlm
)

# 构建地图
nav_map = builder.build_map(
    package_name="com.example.app",
    start_page_id="home"
)

# 保存地图
builder.save_map(nav_map, "maps/com.example.app.json")
```

## 常见工作流程

### 1. 连接设备并测试

```python
from lxb_link import LXBLinkClient

# 使用上下文管理器（推荐）
with LXBLinkClient('192.168.1.100', 12345) as client:
    # 握手
    client.handshake()

    # 获取设备信息
    ok, width, height, density = client.get_screen_size()
    print(f"屏幕尺寸: {width}x{height}")

    # 截图测试
    screenshot = client.screenshot()
    print(f"截图大小: {len(screenshot)} bytes")
```

### 2. 查找 UI 元素

```python
# 按文本查找
status, nodes = client.find_node(
    "设置",
    match_type=1,  # MATCH_EXACT_TEXT
    return_mode=1  # RETURN_BOUNDS
)

# 按资源 ID 查找
status, nodes = client.find_node(
    "com.android.settings:id/button",
    match_type=3,  # MATCH_RESOURCE_ID
    return_mode=1
)

# 点击找到的元素
if status == 1 and nodes:
    x, y = nodes[0]  # 获取第一个匹配项的坐标
    client.tap(x, y)
```

### 3. 执行自动化任务

```python
from cortex import CortexFSMEngine, LLMPlanner

# 配置 LLM
def llm_complete(prompt):
    import requests
    response = requests.post(
        "https://your-api.com/v1/chat/completions",
        json={
            "model": "your-model",
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.1
        }
    )
    return response.json()["choices"][0]["message"]["content"]

planner = LLMPlanner(complete=llm_complete)

# 运行自动化
engine = CortexFSMEngine(client, planner=planner)
result = engine.run(
    user_task="在淘宝首页搜索'手机'",
    map_path="maps/com_taobao_taobao/nav_map.json"
)

# 检查结果
if result["status"] == "success":
    print("任务成功完成！")
    print(f"路由轨迹: {result['route_trace']}")
else:
    print(f"任务失败: {result.get('reason')}")
```

## 故障排查

### 设备连接问题

```bash
# 检查 ADB 连接
adb devices

# 检查 Shizuku 服务状态
adb shell ps | grep shizuku

# 重启 Shizuku
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

### API 调用问题

1. 检查 `.cortex_llm_planner.json` 配置
2. 确认 API 密钥有效
3. 增加 `timeout` 值
4. 检查网络连接

### 导航地图问题

```python
# 验证地图格式
import json

with open("maps/com.example.app.json") as f:
    nav_map = json.load(f)

print(f"Package: {nav_map['package']}")
print(f"Pages: {len(nav_map['pages'])}")
print(f"Transitions: {len(nav_map['transitions'])}")
```

## 下一步

- 阅读完整文档：
  - [LXB-Link 文档](./lxb_link.md)
  - [LXB-Cortex 文档](./lxb_cortex.md)
  - [地图构建文档](./lxb_map_builder.md)
- 查看示例代码：
  - [examples/](../../examples/) 目录
- 加入社区讨论

## 获取帮助

如遇到问题，请：
1. 查看 [FAQ](../faq.md)
2. 搜索已有 [Issues](https://github.com/your-org/LXB-Framework/issues)
3. 提交新的 Issue
