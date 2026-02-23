# LXB-MapBuilder

## 1. Scope
LXB-MapBuilder 负责基于真实设备交互构建应用导航图，输出页面、跳转边、弹窗信息。

## 2. Architecture
代码目录：`src/auto_map_builder/`

```
src/auto_map_builder/
├── __init__.py
├── node_explorer.py        # 主引擎：节点驱动建图 (v5)
├── fusion_engine.py        # VLM-XML 融合引擎
├── vlm_engine.py          # VLM API 封装
├── models.py               # 数据结构定义
└── legacy/                 # 归档策略 (v1-v4)
```

### 模块关系

```
LXB-Link (设备交互)
       │
       v
NodeMapBuilder
       │
       ├──> VLM Engine (视觉分析)
       │      └── 识别页面类型、可交互元素
       │
       └──> Fusion Engine
              └── VLM+XML 节点融合
```

## 3. Core Flow

### 3.1 建图主流程

```
1. 启动应用 → 首页分析
   │
   v
2. VLM 分析首页 → 识别 NAV 节点 (导航元素)
   │
   v
3. 遍历 NAV 节点 → 点击 → 分析新页面
   │
   v
4. 判断页面类型：
   - PAGE → 入队待探索
   - NAV → 跳过
   - POPUP → 记录并关闭
   - BLOCK → 等待或重试
   │
   v
5. 路径重放 → 从首页回到目标页
   │
   v
6. 递归探索 → 深度优先 (DFS)
   │
   v
7. 生成地图 JSON → 保存文件
```

### 3.2 VLM 页面分类

| 类型 | 说明 | 处理 |
|------|------|------|
| PAGE | 独立功能页面，有多个可交互元素 | 创建页面节点，探索 NAV 节点 |
| NAV | 导航元素 (标签、菜单、按钮) | 点击进入其他页面 |
| POPUP | 弹窗、广告遮罩 | 记录定位符并关闭 |
| BLOCK | 加载中、空状态 | 等待或重试 |
| NODE | 可交互元素 (按钮、输入框) | 绑定 XML，创建定位符 |

### 3.3 VLM-XML 融合原理

```
VLM 检测 (bbox + label)
        +
XML 节点 (bounds + resource_id)
        ↓
    IoU 计算 (重叠度)
        ↓
   选择最佳匹配
        ↓
  FusedNode (VLM 语义 + XML 属性)
```

**融合意义**：
- VLM 提供语义理解 (这是什么按钮)
- XML 提供精确定位 (resource_id, bounds)
- 结合两者得到可靠的自动化定位符

## 4. Exploration Strategy

### 4.1 深度优先搜索 (DFS)
```
从首页开始：
  for nav_node in page.nav_nodes:
    click nav_node
    new_page = analyze()
    if new_page.type == PAGE:
      explore(new_page, depth + 1)
```

### 4.2 路径重放机制

回到已探索页面时：
1. 重放从首页到该页的路径
2. 依次点击路径上的节点
3. 失败时标记边为无效

## 5. Data Structures

### 5.1 NavigationMap (输出地图)

```json
{
  "package": "com.example.app",
  "pages": {
    "home": {"name": "首页", "target_aliases": ["main"]},
    "settings": {"name": "设置", "features": ["搜索框"]}
  },
  "transitions": [
    {
      "from": "home",
      "to": "settings",
      "locator": {"text": "设置", "resource_id": "..."}
    }
  ],
  "popups": [{"type": "ad", "close_locator": {...}}],
  "blocks": [{"type": "loading", "identifiers": [...]}]
}
```

### 5.2 Locator (定位符)

```python
{
  "resource_id": "com.app:id/button",  # 精确 ID
  "text": "提交",                      # 辅助文本
  "bounds_hint": [100, 200, 500, 250],  # 坐标提示
  "class_name": "android.widget.Button"  # 类名
}
```

## 6. Design Decisions

### 6.1 节点驱动 (v5)
- **原因**：坐标硬编码不通用，不同设备/分辨率会失效
- **方案**：使用 VLM 理解语义 + XML 提供精确定位

### 6.2 Retrieval-First 定位
- **原因**：减少对坐标的依赖
- **方案**：优先用 resource_id/text 检索，坐标仅作 hint

### 6.3 探索限制
- `max_pages` - 防止无限探索
- `max_depth` - 控制探索深度
- `max_time_seconds` - 超时停止

## 7. Code Structure

| 文件 | 职责 | 关键类/函数 |
|------|------|--------------|
| `node_explorer.py` | 主建图引擎 | `NodeMapBuilder.explore()` |
| `fusion_engine.py` | 融合引擎 | `compute_iou()`, `fuse()` |
| `vlm_engine.py` | VLM 封装 | `_call_api()`, `_run_od()` |
| `models.py` | 数据结构 | `XMLNode`, `FusedNode`, `NavigationMap` |

## 8. Cross References
- `docs/zh/lxb_link.md` - 设备通信
- `docs/zh/lxb_cortex.md` - 自动化执行
