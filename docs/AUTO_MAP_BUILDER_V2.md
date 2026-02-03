# LXB Auto Map Builder v2 - 开发设计文档

## 1. 概述

### 1.1 目标
构建一个基于 VLM + XML 融合的 Android 应用自动建图系统，用于：
1. 自动探索应用的所有页面和可交互节点
2. 为每个页面生成自然语言描述，便于后续 LLM 路径规划
3. 记录页面间的跳转关系，构建应用导航图

### 1.2 核心设计决策
| 决策项 | 选择 | 理由 |
|--------|------|------|
| VLM 模型 | OpenAI 兼容 API (Qwen-VL 等) | 云端推理，支持视觉理解，API 稳定可靠 |
| 探索策略 | BFS 广度优先 | 保证覆盖率，避免陷入深层页面 |
| 页面描述 | 整页描述 | 简洁，足够路径规划使用 |
| 页面去重 | Activity + 结构哈希 | 准确区分不同页面，滚动后仍为同一页面 |
| 节点匹配 | IoU 空间匹配 | 简单可靠，VLM bbox 与 XML bounds 对齐 |
| 回退策略 | 路径记录 + 重启 | Back 失败时重启应用，按记录路径导航回目标页面 |

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                     AutoMapBuilder (主控)                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │  VLMEngine   │    │ FusionEngine │    │  BFSExplorer │       │
│  │ (OpenAI API) │───►│  (IoU Match) │───►│   (探索器)    │       │
│  └──────────────┘    └──────────────┘    └──────────────┘       │
│         │                                       │                │
│         ▼                                       ▼                │
│  ┌──────────────┐                       ┌──────────────┐        │
│  │ OD/OCR/Cap   │                       │ PageManager  │        │
│  │   Results    │                       │ (去重/哈希)   │        │
│  └──────────────┘                       └──────────────┘        │
│                                                │                 │
│                                                ▼                 │
│                                        ┌──────────────┐         │
│                                        │OutputGenerator│         │
│                                        │  (JSON 输出)  │         │
│                                        └──────────────┘         │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                      LXB-Link Client                             │
│  screenshot() | dump_actions() | get_activity() | tap/swipe     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 模块结构

```
src/auto_map_builder/
├── __init__.py           # 模块入口
├── models.py             # 数据结构定义
├── vlm_engine.py         # VLM API 推理引擎
├── fusion_engine.py      # XML-VLM IoU 融合
├── page_manager.py       # 页面去重与哈希
├── explorer.py           # BFS 探索引擎
└── output_generator.py   # JSON 输出生成
```

---

## 4. 核心数据结构

### 4.1 VLM 检测结果
```python
@dataclass
class VLMDetection:
    bbox: Tuple[int, int, int, int]  # (left, top, right, bottom)
    label: str                        # OD 标签: "button", "icon", "text"
    confidence: float                 # 置信度
    ocr_text: Optional[str]           # OCR 识别文本

@dataclass
class VLMPageResult:
    page_caption: str                 # 整页自然语言描述
    detections: List[VLMDetection]    # 检测结果列表
    inference_time_ms: float
    image_size: Tuple[int, int]
```

### 4.2 XML 节点 (来自 dump_actions)
```python
@dataclass
class XMLNode:
    node_id: str
    bounds: Tuple[int, int, int, int]
    class_name: str
    text: str
    resource_id: str
    content_desc: str
    clickable: bool
    editable: bool
    scrollable: bool
```

### 4.3 融合节点
```python
@dataclass
class FusedNode:
    node_id: str
    bounds: Tuple[int, int, int, int]

    # XML 属性
    class_name: str
    text: str
    resource_id: str
    clickable: bool
    editable: bool
    scrollable: bool

    # VLM 增强
    vlm_label: Optional[str]          # VLM 检测标签
    vlm_ocr_text: Optional[str]       # OCR 文本
    iou_score: float                  # 匹配分数
```

### 4.4 页面状态
```python
@dataclass
class PageState:
    page_id: str                      # 唯一标识: {activity}_{hash[:8]}
    activity: str
    package: str

    nodes: List[FusedNode]            # 融合节点列表
    page_description: str             # VLM 生成的整页描述
    structure_hash: str               # 结构哈希 (去重用)
```

---

## 5. 核心算法

### 5.1 VLM 推理流程

```python
def vlm_infer(screenshot_bytes: bytes) -> VLMPageResult:
    """
    VLM API 推理流程:
    1. OD 任务 - 检测 UI 元素位置和类型
    2. OCR 任务 - 识别文本内容
    3. Caption 任务 - 生成整页描述
    """
    # 调用 VLM API (支持 Qwen-VL 等兼容 OpenAI 的模型)
    # 返回解析后的检测结果
    return VLMPageResult(page_caption=caption, detections=detections)
```

### 5.2 IoU 空间匹配

```python
def compute_iou(box1, box2) -> float:
    """计算两个边界框的 IoU"""
    x1, y1 = max(box1[0], box2[0]), max(box1[1], box2[1])
    x2, y2 = min(box1[2], box2[2]), min(box1[3], box2[3])

    if x2 <= x1 or y2 <= y1:
        return 0.0

    inter = (x2 - x1) * (y2 - y1)
    area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
    area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
    return inter / (area1 + area2 - inter)
```

### 5.3 BFS 探索算法

```python
def explore(package_name: str, config: ExplorationConfig):
    """
    BFS 广度优先探索

    队列元素: (page_id, depth, path_to_here)
    path_to_here: 从首页到达该页面的动作序列
    """
    # 启动应用
    client.launch_app(package_name, clear_task=True)

    # 初始化队列
    queue = deque()
    first_page = analyze_current_page()
    queue.append((first_page.page_id, 0, []))

    while queue and len(pages) < config.max_pages:
        current_page_id, depth, path = queue.popleft()

        for node in current_page.clickable_nodes:
            # 执行点击
            client.tap(*node.center)

            # 分析新页面
            new_page = analyze_current_page()

            if new_page.page_id not in pages:
                queue.append((new_page.page_id, depth + 1, path + [action]))

            # 返回当前页面
            navigate_back()
```

---

## 6. 输出格式

### 6.1 目录结构
```
maps/{package_name}/
├── app_overview.json      # 应用概览
└── pages/                  # 页面详情目录
    ├── {page_id_1}.json
    └── ...
```

### 6.2 app_overview.json
```json
{
  "package": "com.example.app",
  "total_pages": 12,
  "pages": [
    {
      "page_id": "MainActivity_a1b2c3d4",
      "description": "主页，顶部有搜索栏，中间是商品列表",
      "node_count": 15
    }
  ]
}
```

---

## 7. 配置

### 7.1 VLM API 配置

```python
from src.auto_map_builder import set_config
from src.auto_map_builder.vlm_engine import VLMConfig

set_config(VLMConfig(
    api_base_url="https://api.example.com/v1",
    api_key="your-api-key",
    model_name="qwen-vl-plus"
))
```

或通过环境变量：
```bash
export VLM_API_BASE_URL="https://api.example.com/v1"
export VLM_API_KEY="your-api-key"
export VLM_MODEL_NAME="qwen-vl-plus"
```

### 7.2 探索配置

```python
@dataclass
class ExplorationConfig:
    # 基础配置
    max_pages: int = 50
    max_depth: int = 10
    max_time_seconds: int = 1800

    # VLM 功能开关
    enable_od: bool = True
    enable_ocr: bool = True
    enable_caption: bool = True

    # 融合配置
    iou_threshold: float = 0.5

    # 探索配置
    action_delay_ms: int = 1000
    scroll_enabled: bool = True
    max_scrolls_per_page: int = 5

    # 输出配置
    save_screenshots: bool = True
    output_dir: str = "./maps"
```

---

## 8. 使用示例

```python
from src.auto_map_builder import AutoMapBuilder, ExplorationConfig, set_config
from src.auto_map_builder.vlm_engine import VLMConfig
from src.lxb_link.client import LXBLinkClient

# 配置 VLM API
set_config(VLMConfig(
    api_base_url="https://api.example.com/v1",
    api_key="your-api-key",
    model_name="qwen-vl-plus"
))

# 连接设备
client = LXBLinkClient("192.168.1.100", 12345)
client.connect()
client.handshake()

# 配置探索
config = ExplorationConfig(
    max_pages=30,
    max_depth=5
)

# 执行探索
builder = AutoMapBuilder(client, config)
result = builder.explore("com.example.app")

# 保存结果
builder.save("./maps")

print(f"探索完成: {result.page_count} 个页面, {result.transition_count} 个跳转")
```

### Web Console 使用

访问 `http://localhost:5000/map_builder`

1. 配置 VLM API (Base URL, API Key, 模型名称)
2. 点击 "保存配置"
3. 输入应用包名
4. 点击 "开始探索"

---

## 9. 依赖安装

```bash
# 基础依赖
pip install openai pillow

# 可选: 本地 VLM (已废弃，改用 API)
# pip install torch transformers
```
