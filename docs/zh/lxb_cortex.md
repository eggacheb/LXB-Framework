# LXB-Cortex

## 1. Scope
LXB-Cortex 实现 Route-Then-Act 自动化：先用地图路由到目标页面，再执行任务动作。

## 2. Architecture
代码目录：`src/cortex/`

```
src/cortex/
├── __init__.py
├── fsm_runtime.py          # FSM 状态机引擎
├── route_then_act.py       # Route-Then-Act 核心逻辑
└── fsm_instruction.py      # 指令解析器
```

### 模块关系

```
LXB-Link (设备通信)
       │
       v
LXB-Cortex
   ├── Routing Phase   → 路由阶段 (确定性导航)
   └── Action Phase    → 执行阶段 (VLM 指导)
```

## 3. Core Flow

### 3.1 三阶段执行

```
┌─────────────────────────────────────────────────────────┐
│ Phase 1: Planning (规划阶段)                             │
│                                                          │
│  APP_RESOLVE → 选择目标应用                              │
│  ROUTE_PLAN  → 规划目标页面                                │
└─────────────────────────────────────────────────────────┘
                         │
                         v
┌�─────────────────────────────────────────────────────────┐
│ Phase 2: Routing (路由阶段)                             │
│                                                          │
│  1. BFS 路径规划：在地图中查找从首页到目标页的最短路径 │
│  2. 路径重放：依次点击路径上的节点                         │
│  3. 路由恢复：处理弹窗、节点缺失等异常                     │
└─────────────────────────────────────────────────────────┘
                         │
                         v
┌─────────────────────────────────────────────────────────┐
│ Phase 3: Action (执行阶段)                               │
│                                                          │
│  FSM 状态机循环 (VISION_ACT):                              │
│    a. 截图 → VLM 分析 → 生成动作                            │
│    b. 执行动作 (TAP/SWIPE/INPUT/BACK)                      │
│    c. 循环检测 → 防止重复无效动作                          │
│    d. DONE → 任务完成                                        │
└─────────────────────────────────────────────────────────┘
```

### 3.2 FSM 状态机

```
         ┌─────────┐
         │  INIT   │  初始化、探测坐标空间
         └────┬────┘
              │
    ┌─────────┴─────────┐
    │   APP_RESOLVE       │  LLM 选择应用
    │   ROUTE_PLAN         │  LLM 规划页面
    └───┬───────────────┬─┘
       │              │
       │              └──> ROUTING
       │
       └──> ROUTING      路由到目标页
              │
              v
         VISION_ACT      视觉执行 (循环)
              │
         ┌──┴──┐
         │DONE │  成功
         └────┘
```

### 3.3 坐标空间探测原理

**问题**：VLM 输出的坐标是模型内部坐标系，与设备屏幕像素不匹配

**解决**：发送校准图像（四角彩色标记）→ VLM 识别 → 计算映射范围 → 运行时映射

## 4. Key Design Principles

### 4.1 路由阶段确定性
- 使用 BFS 算法确保找到最短路径
- 基于 XML 层次结构定位，不依赖坐标硬编码
- 路径可重现、可验证

### 4.2 执行阶段反思机制
- LLM 每回合输出结构化分析（step_review、reflection）
- 收集 lessons（经验教训）反馈给后续回合
- 防止重复无效动作（循环检测）

### 4.3 分离关注点
- Routing 负责到达目标页（确定性）
- Action 负责执行具体任务（VLM 引导）
- 失败恢复与主流程解耦

## 5. Code Structure

| 文件 | 职责 | 关键类/函数 |
|------|------|--------------|
| `fsm_runtime.py` | FSM 引擎 | `CortexFSMEngine`, `_run_vision_state` |
| `route_then_act.py` | 路由核心 | `RouteThenActCortex`, `_bfs_path`, `_execute_route` |
| `fsm_instruction.py` | 指令解析 | `parse_instructions`, `validate_allowed` |

## 6. Data Flow

```
用户任务
   │
   v
┌─────────────────┐
│ LLM Planner     │ ← 选择应用、规划页面
└────────┬────────┘
         │
         v
┌─────────────────┐
│ BFS 路径查找     │
│ + 路径重放       │
│ + 路由恢复       │
└────────┬────────┘
         │
         v
┌─────────────────┐
│ FSM 状态机执行    │ ← 视觉分析 + 动作生成
└─────────────────┘
         │
         v
      执行结果
```

## 7. Cross References
- `docs/zh/lxb_map_builder.md` - 地图构建
- `docs/zh/lxb_link.md` - 设备通信
