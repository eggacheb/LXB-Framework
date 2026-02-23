# 配置文档

本文档详细说明了 LXB-Framework 的各项配置参数。

## 配置文件位置

配置文件位于项目根目录：`.cortex_llm_planner.json`

## 配置参数

### LLM API 配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `api_base_url` | string | - | LLM API 基础 URL |
| `api_key` | string | - | LLM API 密钥 |
| `model_name` | string | "qwen3.5-plus" | 使用的模型名称 |
| `temperature` | float | 0.1 | 采样温度，越低越确定性 |
| `timeout` | int | 30 | API 请求超时时间（秒） |
| `vision_jpeg_quality` | int | 10 | VLM 视觉输入 JPEG 质量 (1-100) |

### 路由配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `node_exists_retries` | int | 3 | 节点存在性检查重试次数 |
| `node_exists_interval_sec` | float | 3.0 | 节点检查重试间隔（秒） |
| `route_recovery_enabled` | bool | false | 是否启用路由恢复机制 |
| `max_route_restarts` | int | 0 | 最大应用重启次数（路由失败时） |
| `use_vlm_takeover` | bool | false | 是否使用 VLM 进行弹窗检测处理 |

### FSM 执行配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `fsm_max_turns` | int | 40 | FSM 最大状态转换次数 |
| `fsm_max_commands_per_turn` | int | 1 | 每回合最大命令数 |
| `fsm_max_vision_turns` | int | 200 | 最大视觉/操作回合数 |
| `fsm_action_interval_sec` | float | 0.8 | 动作执行间隔时间（秒） |
| `fsm_screenshot_settle_sec` | float | 0.6 | 首次视觉回合截图等待时间（秒） |

### 触控操作配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `fsm_tap_bind_clickable` | bool | false | 是否将点击绑定到最近的可点击元素 |
| `fsm_tap_jitter_sigma_px` | float | 2.0 | 点击坐标高斯抖动标准差（像素） |
| `fsm_swipe_jitter_sigma_px` | float | 4.0 | 滑动坐标高斯抖动标准差（像素） |
| `fsm_swipe_duration_jitter_ratio` | float | 0.12 | 滑动持续时间抖动比率 |
| `touch_mode` | string | "shell_first" | 触控模式：shell_first/accessibility/auto |

### XML 稳定性检测配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `fsm_xml_stable_interval_sec` | float | 0.3 | XML 稳定性检查间隔（秒） |
| `fsm_xml_stable_samples` | int | 4 | 需要连续稳定的样本数 |
| `fsm_xml_stable_timeout_sec` | float | 4.0 | XML 稳定性检测超时时间（秒） |

### 其他配置

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `map_filepath` | string | "" | 导航地图文件路径 |
| `package_name` | string | "" | 目标应用包名 |
| `reconnect_before_run` | bool | true | 运行前是否重新连接设备 |
| `use_llm_planner` | bool | true | 是否使用 LLM 进行任务规划 |

## 推荐配置

### 快速响应模式
适用于网络条件良好、需要快速响应的场景：
```json
{
  "temperature": 0.1,
  "timeout": 15,
  "node_exists_retries": 2,
  "fsm_action_interval_sec": 0.5
}
```

### 稳定可靠模式
适用于需要高成功率的场景：
```json
{
  "temperature": 0.1,
  "timeout": 30,
  "node_exists_retries": 4,
  "route_recovery_enabled": true,
  "use_vlm_takeover": true,
  "fsm_xml_stable_samples": 6
}
```

### 调试模式
适用于开发和调试：
```json
{
  "temperature": 0.3,
  "timeout": 60,
  "fsm_tap_jitter_sigma_px": 0.0,
  "fsm_swipe_jitter_sigma_px": 0.0,
  "fsm_action_interval_sec": 1.5
}
```

## 参数调优建议

1. **温度 (temperature)**: 保持较低值 (0.0-0.2) 以获得更确定性的输出
2. **节点检查重试**: 根据设备性能调整，性能好的设备可以减少重试次数
3. **动作间隔**: 根据应用响应速度调整，快速响应的应用可以减少间隔
4. **XML 稳定性样本数**: 增加可以提高稳定性但会降低执行速度
5. **坐标抖动**: 适用于需要模拟人类操作的场景，自动化场景可设置为 0
