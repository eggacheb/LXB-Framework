# Configuration Reference

This document provides detailed information about LXB-Framework configuration parameters.

## Configuration File Location

The configuration file is located at the project root: `.cortex_llm_planner.json`

## Configuration Parameters

### LLM API Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `api_base_url` | string | - | LLM API base URL |
| `api_key` | string | - | LLM API key |
| `model_name` | string | "qwen3.5-plus" | Model name to use |
| `temperature` | float | 0.1 | Sampling temperature, lower = more deterministic |
| `timeout` | int | 30 | API request timeout in seconds |
| `vision_jpeg_quality` | int | 10 | VLM vision input JPEG quality (1-100) |

### Routing Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `node_exists_retries` | int | 3 | Number of retries for node existence checks |
| `node_exists_interval_sec` | float | 3.0 | Interval between node check retries in seconds |
| `route_recovery_enabled` | bool | false | Enable route recovery mechanism |
| `max_route_restarts` | int | 0 | Maximum app restarts on route failure |
| `use_vlm_takeover` | bool | false | Use VLM for popup detection and handling |

### FSM Execution Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `fsm_max_turns` | int | 40 | Maximum FSM state transitions |
| `fsm_max_commands_per_turn` | int | 1 | Maximum commands per turn |
| `fsm_max_vision_turns` | int | 200 | Maximum vision/action turns |
| `fsm_action_interval_sec` | float | 0.8 | Delay between action executions in seconds |
| `fsm_screenshot_settle_sec` | float | 0.6 | Screenshot wait time on first vision turn |

### Touch Operation Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `fsm_tap_bind_clickable` | bool | false | Bind taps to nearest clickable element |
| `fsm_tap_jitter_sigma_px` | float | 2.0 | Gaussian sigma for tap coordinate jitter in pixels |
| `fsm_swipe_jitter_sigma_px` | float | 4.0 | Gaussian sigma for swipe coordinate jitter in pixels |
| `fsm_swipe_duration_jitter_ratio` | float | 0.12 | Swipe duration jitter ratio |
| `touch_mode` | string | "shell_first" | Touch mode: shell_first/accessibility/auto |

### XML Stability Detection Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `fsm_xml_stable_interval_sec` | float | 0.3 | Interval between XML stability checks in seconds |
| `fsm_xml_stable_samples` | int | 4 | Number of stable samples required |
| `fsm_xml_stable_timeout_sec` | float | 4.0 | XML stability detection timeout in seconds |

### Other Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `map_filepath` | string | "" | Navigation map file path |
| `package_name` | string | "" | Target app package name |
| `reconnect_before_run` | bool | true | Reconnect device before running |
| `use_llm_planner` | bool | true | Use LLM for task planning |

## Recommended Configurations

### Fast Response Mode
For scenarios with good network conditions requiring quick response:
```json
{
  "temperature": 0.1,
  "timeout": 15,
  "node_exists_retries": 2,
  "fsm_action_interval_sec": 0.5
}
```

### Stable Reliable Mode
For scenarios requiring high success rate:
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

### Debug Mode
For development and debugging:
```json
{
  "temperature": 0.3,
  "timeout": 60,
  "fsm_tap_jitter_sigma_px": 0.0,
  "fsm_swipe_jitter_sigma_px": 0.0,
  "fsm_action_interval_sec": 1.5
}
```

## Tuning Recommendations

1. **Temperature**: Keep low (0.0-0.2) for more deterministic outputs
2. **Node Check Retries**: Adjust based on device performance; high-performance devices may need fewer retries
3. **Action Interval**: Adjust based on app response speed; fast apps can use shorter intervals
4. **XML Stability Samples**: Increase for better stability but slower execution
5. **Coordinate Jitter**: Use for human-like behavior simulation; set to 0 for pure automation
