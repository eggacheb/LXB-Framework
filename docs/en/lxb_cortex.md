# LXB-Cortex

## 1. Scope
`LXB-Cortex` provides a route-then-act runtime: route to target page first, then execute task actions.

## 2. Architecture
- Code path: `src/cortex`
- Main modules: `route_then_act.py`, `fsm_runtime.py`
- Dependencies: map outputs from `LXB-MapBuilder` and device APIs from `LXB-Link`

### System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     User Task Input                              │
│                 "Open settings and enable WiFi"                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              v
┌─────────────────────────────────────────────────────────────────┐
│                      Phase 1: Planning                           │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │ App Resolve  │ -> │ Route Plan  │ -> │  Target ID   │      │
│  │(Select App)  │    │(Plan Target) │    │(Target Page) │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              v
┌─────────────────────────────────────────────────────────────────┐
│                      Phase 2: Routing                            │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   BFS Path   │ -> │  Route Replay│ -> │ Page Arrived │      │
│  │(Path Finder) │    │(Exec Route)  │    │(At Target)   │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              Route Recovery (Route Recovery)          │    │
│  │  - Popup Detection (Pop-up Detection)                 │    │
│  │  - VLM Takeover (VLM-based Recovery)                  │    │
│  │  - App Restart (Application Restart)                  │    │
│  └──────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              v
┌─────────────────────────────────────────────────────────────────┐
│                      Phase 3: Action Execution                   │
│  ┌──────────────────────────────────────────────────────┐    │
│  │              FSM Engine (State Machine Engine)           │    │
│  │                                                       │    │
│  │  ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐│    │
│  │  │ INIT│ -> │PLAN │ -> │ROUTE│ -> │ACT  │ -> │DONE ││    │
│  │  └─────┘    └─────┘    └─────┘    └─────┘    └─────┘│    │
│  │                                                       │    │
│  │  LLM Planner: Generate next action for each state     │    │
│  │  - Context: task, page, screenshot, history            │    │
│  │  - Structured Output: <analysis><command>             │    │
│  └──────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
                              │
                              v
┌─────────────────────────────────────────────────────────────────┐
│                       Execution Result Output                   │
│  {status, route_trace, command_log, lessons, ...}            │
└─────────────────────────────────────────────────────────────────┘
```

## 3. Core Flow

### 3.1 Complete Execution Flow

**Stage 1: INIT (Initialization)**

```
┌─────────────────────────────────────────────────────────┐
│ 1. Handshake verification                                 │
│    - client.handshake()                                │
│    - Get device info (width, height, density)           │
│                                                         │
│ 2. Get current state                                      │
│    - client.get_activity() -> (package, activity)       │
│    - client.list_apps("user") -> application list        │
│                                                         │
│ 3. Coordinate space probing (optional)                   │
│    - Generate calibration image (four corner colored markers)│
│    - VLM recognizes four corner coordinates               │
│    - Calculate VLM coordinate range (x_min, x_max, ...)   │
│    - Save to context.coord_probe                          │
└─────────────────────────────────────────────────────────┘
              │
              v
         APP_RESOLVE
```

**Stage 2: APP_RESOLVE (Application Selection)**

```
┌─────────────────────────────────────────────────────────┐
│ Input: User task, app candidates list                     │
│                                                         │
│ Process:                                                │
│ 1. Build Prompt with:                                   │
│    - UserTask                                          │
│    - AppCandidates (package, name)                      │
│    - DeviceInfo                                        │
│    - CurrentActivity                                   │
│                                                         │
│ 2. LLM generates decision:                               │
│    <app_analysis>                                      │
│      <user_intent>Check into Tieba</user_intent>      │
│      <candidates>com.baidu.tieba</candidates>          │
│      <decision>Tieba matches best</decision>            │
│    </app_analysis>                                     │
│    <command>SET_APP com.baidu.tieba</command>          │
│                                                         │
│ 3. Parse command: context.selected_package = ...      │
└─────────────────────────────────────────────────────────┘
              │
              v
         ROUTE_PLAN
```

**Stage 3: ROUTE_PLAN (Route Planning)**

```
┌─────────────────────────────────────────────────────────┐
│ Input: User task, selected app, page candidates         │
│                                                         │
│ Process:                                                │
│ 1. Load navigation map (RouteMap)                        │
│    - pages: {page_id: {name, features, aliases}}        │
│    - transitions: [{from, to, locator, description}]    │
│                                                         │
│ 2. Build Prompt with:                                   │
│    - SelectedPackage                                   │
│    - PageCandidates (page_id, name, description)        │
│                                                         │
│ 3. LLM generates decision:                               │
│    <route_plan_analysis>                               │
│      <selected_app>com.baidu.tieba</selected_app>       │
│      <target_page_candidates>home, sign</...>          │
│      <decision>Go to home first</decision>               │
│    </route_plan_analysis>                              │
│    <command>ROUTE com.baidu.tieba home</command>        │
│                                                         │
│ 4. Target page resolution:                               │
│    - Handle home-like targets ("", "home", "main")       │
│    - Alias mapping                                       │
│    - Legacy page_id compatibility                         │
└─────────────────────────────────────────────────────────┘
              │
              v
          ROUTING
```

**Stage 4: ROUTING**

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. Path Planning (BFS)                                        │
│    Input: start_page, target_page, RouteMap                  │
│    Algorithm:                                                │
│      queue = [(start, [])]                                  │
│      visited = {start}                                       │
│      while queue:                                           │
│        current, path = queue.pop(0)                          │
│        for edge in transitions_from(current):                │
│          if edge.to == target: return path + [edge]         │
│          if edge.to not in visited:                          │
│            visited.add(edge.to)                               │
│            queue.append((edge.to, path + [edge]))            │
│                                                              │
│ 2. Route Replay                                               │
│    for edge in path:                                         │
│      a) Launch app: client.launch_app(package)              │
│      b) Scan known interrupts (popups, blocks)              │
│         - Close popups using predefined close_locators      │
│         - Check block identifiers                            │
│      c) Check node existence: _node_exists(edge.locator)    │
│         - Retry mechanism (node_exists_retries)             │
│         - Composite search: XML + find_node                │
│      d) Execute tap: _tap_locator(edge.locator)            │
│         - Prefer bounds_hint                                │
│         - Fallback to find_node candidate center point       │
│      e) Wait for XML stability                               │
│                                                              │
│ 3. Route Recovery                                            │
│    if node_missing or tap_failed:                            │
│      if route_recovery_enabled:                               │
│        if _scan_known_interrupts(): resume                  │
│        if use_vlm_takeover:                                  │
│          kind, payload = _vlm_classify_interrupt(screenshot) │
│          if kind == "popup": close_popup(payload)           │
│        if fails > max_route_restarts: restart_app           │
└─────────────────────────────────────────────────────────────────┘
              │
              v
         VISION_ACT
```

**Stage 5: VISION_ACT (Vision Execution)**

```
Execute per turn (until max_vision_turns or DONE/FAIL):

1. Take current screenshot
   screenshot = client.screenshot()

2. Build Prompt with:
   - UserTask
   - CurrentActivity (package/activity)
   - LastCommand + SameCommandStreak
   - RouteTrace (recently visited pages)
   - LLMHistory (structured history)
   - Lessons (learned insights)
   - Screenshot (visual input)

3. LLM generates structured output:
   <vision_analysis>
     <page_state>Current page state description</page_state>
     <step_review>
       Step-1: command=TAP 890 67, page_change=Entered settings
       Step-2: command=TAP 720 420, page_change=No visible change
     </step_review>
     <reflection>Recent steps show same action repeatedly ineffective, should scroll</reflection>
     <next_step_reasoning>Scroll down to expand visible area</next_step_reasoning>
     <completion_gate>
       <completion_claim>Only confirmed visible area</completion_claim>
       <coverage_check>failed: still has unseen content</coverage_check>
     </completion_gate>
     <done_confirm>
       <goal_match>fail</goal_match>
       <final_decision>NOT_DONE</final_decision>
     </done_confirm>
   </vision_analysis>
   <command>SWIPE 640 1600 640 1400 650</command>

4. Parse and validate command
   - Extract <command> content
   - Verify operation is in allowed_ops
   - Check for loops: same_command_streak >= 3 + same_activity >= 3

5. Execute action
   - TAP: Map coordinates -> probe scale -> add jitter -> execute
   - SWIPE: Same processing -> execute swipe
   - INPUT/WAIT/BACK: Execute or state transition

6. Refresh state
   - Refresh activity
   - Update streak counters
   - Collect lesson (if any)
```

### 3.2 FSM State Machine

```
         ┌─────────────┐
         │    INIT     │  Initialize device, probe coordinate space
         └──────┬──────┘
                │
                v
    ┌───────────────────────┐
    │    APP_RESOLVE       │  LLM selects target app
    └───────┬───────────────┘
            │
            v
    ┌───────────────────────┐
    │    ROUTE_PLAN         │  LLM plans target page
    └───────┬───────────────┘
            │
            v
    ┌───────────────────────┐
    │      ROUTING          │  BFS pathfinding + route replay
    └───────┬───────────────┘
            │ Route successful
            v
    ┌───────────────────────┐
    │     VISION_ACT        │  Loop executing visual actions
    │  (with loop detection)  │
    └───────┬───────────────┘
            │
            ├──> DONE ──> FINISH
            └──> FAIL ──> FAIL
```

## 4. Key Interfaces & Data Shapes

### 4.1 Core Data Structures

#### RouteMap (Navigation Map)

```python
{
  "package": "com.example.app",              # App package name
  "pages": {                                    # Page definitions
    "home": {
      "name": "Home Page",
      "target_aliases": ["main", "index"],   # Aliases
      "features": ["Search bar", "Navigation"], # Feature description
      "legacy_page_id": "home"              # Backward compatibility
    },
    "settings": { ... }
  },
  "transitions": [                              # Transition definitions
    {
      "from": "home",
      "to": "settings",
      "locator": {                             # Trigger locator
        "text": "Settings",
        "resource_id": "com.app:id/settings"
      },
      "description": "Click settings button to enter settings page",
      "legacy_from": "home",
      "legacy_to": "settings"
    }
  ],
  "popups": [                                   # Known popups
    {
      "type": "ad",
      "close_locator": {
        "text": "Close",
        "resource_id": "com.app:id/close"
      },
      "description": "Ad popup"
    }
  ],
  "blocks": [                                   # Blocking states
    {
      "type": "loading",
      "identifiers": ["com.app:id/progress_bar"],
      "description": "Loading state"
    }
  ],
  "metadata": {                                  # Metadata
    "page_id_map": {"home": "home__v2"}   # Page ID mapping
  }
}
```

#### FSMConfig (FSM Configuration)

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `max_turns` | int | 30 | Maximum state transitions |
| `max_commands_per_turn` | int | 1 | Maximum commands per turn |
| `max_vision_turns` | int | 20 | Maximum vision/action turns |
| `action_interval_sec` | float | 0.8 | Delay between actions (seconds) |
| `screenshot_settle_sec` | float | 0.6 | Screenshot wait on first vision turn |
| `tap_bind_clickable` | bool | false | Bind taps to clickable elements |
| `tap_jitter_sigma_px` | float | 0.0 | Tap jitter standard deviation |
| `swipe_jitter_sigma_px` | float | 0.0 | Swipe jitter standard deviation |
| `xml_stable_interval_sec` | float | 0.3 | XML stability check interval |
| `xml_stable_samples` | int | 4 | XML stability samples needed |
| `xml_stable_timeout_sec` | float | 4.0 | XML stability timeout (seconds) |
| `init_coord_probe_enabled` | bool | true | Enable coordinate probing |

## 5. Failure Modes & Recovery

| Failure Type | Trigger | Recovery Strategy |
|--------------|--------|------------------|
| Path not found | BFS finds no path | Check map completeness, target page aliases |
| Node unreachable | _node_exists fails | Enable route_recovery, VLM takeover |
| Tap unresponsive | _tap_locator fails | Retry, app restart, re-route |
| Popup interrupt | Popup/block detected | Scan known popups, VLM classify |
| App crash | Activity disappears | Restart app, begin route again |

## 6. Observability

### Log Event Structure

```python
{
  "ts": "2024-02-20T12:00:00.000Z",   # Timestamp
  "task_id": "uuid",                   # Task ID
  "stage": "fsm|route|exec|llm",      # Stage identifier
  "event": "event_name",               # Event name
  "state": "INIT|ROUTING|VISION_ACT",  # Current state
  "prompt": "...",                     # LLM input (llm stage)
  "response": "...",                   # LLM output (llm stage)
  "structured": {...},                 # Structured output (llm stage)
  "command": "TAP 500 800",            # Executed command
  "error": "error_reason"              # Error (if failed)
}
```

## 7. Configuration

| Configuration | Recommended Value | Description |
|---------------|-------------------|-------------|
| LLM Model | qwen-plus or gpt-4o | Planning model |
| Temperature | 0.1 | Low temperature for determinism |
| Timeout | 30 | API timeout (seconds) |
| Route Recovery | true | Enable route recovery for production |

## 8. Constraints & Compatibility

### Map Quality Requirements
- Must contain "home" or home entry point
- Target pages must be defined in pages
- Key route transitions must be complete

### LLM Capability Requirements
- Follow structured output format
- Understand task intent and page semantics
- Reflection and summarization capability

## 9. Current Gaps

- High-dynamic pages still challenge stable page arrival checks
- Interrupt handling quality depends on recovery strategy tuning
- Cross-app navigation not yet supported
- Concurrent task execution not yet implemented

## 10. Cross References

- `docs/en/lxb_map_builder.md` - Map building documentation
- `docs/en/lxb_link.md` - Device communication documentation
- `docs/en/lxb_web_console.md` - Web console documentation
