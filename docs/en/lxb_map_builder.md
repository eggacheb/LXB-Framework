# LXB-MapBuilder

## 1. Scope
`LXB-MapBuilder` automatically builds app navigation maps through real device interaction, outputting pages, transitions, popups, and exception page information.

## 2. Architecture
- Code path: `src/auto_map_builder`
- Current engine: `node_explorer.py` (`NodeMapBuilder`)
- Archived: `src/auto_map_builder/legacy`

### System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                  LXB-MapBuilder System                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                Control Layer (Controller)                 │ │
│  │             NodeMapBuilder.explore()                      │ │
│  └───────────────────────────────────────────────────────────┘ │
│                            │                                   │
│                            v                                   │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                Coordination Layer (Orchestrator)         │ │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────────────┐  │ │
│  │  │  Page      │  │  Node      │  │   Fusion Engine   │  │ │
│  │  │  Manager   │->│  Explorer  │->│   (VLM + XML)     │  │ │
│  │  └────────────┘  └────────────┘  └────────────────────┘  │ │
│  │        │                │                    │             │ │
│  │        v                v                    v             │ │
│  │  ┌────────────────────────────────────────────────────┐ │ │
│  │  │            Queue Manager (Explore Queue)          │ │ │
│  │  │  pending_pages: [page_id, depth, path]            │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  └───────────────────────────────────────────────────────────┘ │
│                            │                                   │
│                            v                                   │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                  Execution Layer (Executor)              │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │ │
│  │  │  VLM Engine  │  │ LXB-Link     │  │  XML Parser  │  │ │
│  │  │ (Visual      │  │(Device       │  │ (Tree        │  │ │
│  │  │ Analysis)   │  │ Interaction)  │  │  Parsing)    │  │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
│  Output: NavigationMap (JSON)                                    │
└─────────────────────────────────────────────────────────────────┘
```

## 3. Core Flow

### 3.1 Map Building Overview

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. Initialize                                                     │
│    - Connect device (LXBLinkClient)                           │
│    - Launch target app (launch_app)                            │
│    - Configure VLM engine                                      │
└─────────────────────────────────────────────────────────────────┘
              │
              v
┌─────────────────────────────────────────────────────────────────┐
│ 2. Home Page Analysis                                           │
│    - Screenshot + dump_actions                                │
│    - VLM analyzes page structure                                │
│        - Identify page type (PAGE/NAV/POPUP/BLOCK)            │
│        - Extract interactive elements (NODE)                  │
│        - Identify page features                                │
│    - Bind XML nodes (Fusion Engine)                            │
│    - Create home page node (page_id = "home")                   │
└─────────────────────────────────────────────────────────────────┘
              │
              v
┌─────────────────────────────────────────────────────────────────┐
│ 3. Node Exploration                                              │
│    Iterate through NAV-type nodes on page:                     │
│    - For each NAV node:                                        │
│      a. Click node                                            │
│      b. Wait for page stability (XML stability check)         │
│      c. Screenshot + dump_actions                             │
│      d. VLM analyzes new page                                  │
│      e. Classify page type:                                   │
│         - PAGE: New distinct page -> enqueue for exploration   │
│         - NAV: Navigation element -> skip                      │
│         - POPUP: Popup -> record and close                      │
│         - BLOCK: Blocking state -> wait or retry                │
│      f. Bind XML, create node                                 │
│      g. Create transition edge (from_page, to_page, locator)  │
└─────────────────────────────────────────────────────────────────┘
              │
              v
┌─────────────────────────────────────────────────────────────────┐
│ 4. Depth-First Exploration (DFS)                                │
│    while queue not empty:                                     │
│      - Dequeue page (page, depth, path)                        │
│      - Check limits (depth <= max_depth, pages <= max_pages)   │
│      - Replay path from home to target page                    │
│      - Recursively explore NAV nodes on that page              │
│      - Discover new pages -> enqueue                           │
└─────────────────────────────────────────────────────────────────┘
              │
              v
┌─────────────────────────────────────────────────────────────────┐
│ 5. Export Map                                                    │
│    - Aggregate all discovered pages                             │
│    - Aggregate all transitions                                  │
│    - Aggregate discovered popups and blocks                     │
│    - Generate NavigationMap JSON                               │
│    - Save to file                                               │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 VLM Page Analysis Principle

**VLM Prompt Structure**:

```
Analyze this Android screenshot. Output ONLY one line:

PAGE|page_name|description
  if this is a main/distinct page with multiple interactive elements

NAV|label|description|x|y
  if this is a navigation element (tabs, buttons leading to other pages)

POPUP|label|description|x|y
  if this is a popup/ad overlay that should be closed

BLOCK|description
  if this is a loading/empty state that blocks interaction

NODE|label|description|x|y
  if this is an interactive element (button, input, etc.)

Rules:
- PAGE: A distinct screen with its own purpose and features
- NAV: Elements that navigate between pages
- POPUP: Overlays that need to be dismissed
- BLOCK: States preventing interaction
- NODE: Interactive elements on the page
```

### 3.3 XML-VLM Fusion Principle

```
Input:
  - VLM detections: [(bbox, label, ocr_text), ...]
  - XML nodes: [XMLNode(bounds, text, resource_id, ...), ...]

For each VLM detection:
  1. Calculate IoU (Intersection over Union)
  2. Filter: iou >= threshold, xml_idx not used
  3. Select best match: argmax(iou)
  4. Create FusedNode combining VLM + XML data
  5. Mark as used

Output: List[FusedNode]
  - Only successfully matched nodes
  - Unmatched VLM detections discarded (false positives)
```

## 4. Key Interfaces & Data Shapes

### 4.1 Core Classes

| Method | Function | Key Parameters |
|--------|----------|----------------|
| `explore()` | Main entry: execute map building | package_name, start_page_id |
| `_analyze_page()` | Single page analysis | screenshot, xml_nodes |
| `_explore_nodes()` | Explore page NAV nodes | page, vlm_detections |
| `_replay_path()` | Path replay from home | path (edge list) |
| `save_map()` | Save map to file | nav_map, file_path |

### 4.2 Exploration Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `max_pages` | int | 20 | Maximum pages to explore |
| `max_depth` | int | 3 | Maximum exploration depth from home |
| `max_time_seconds` | int | 300 | Maximum exploration time (seconds) |
| `action_delay_ms` | int | 800 | Delay after action (milliseconds) |
| `screenshot_timeout` | int | 10 | Screenshot timeout (seconds) |

## 5. Failure Modes & Recovery

| Failure Type | Trigger | Recovery Strategy |
|--------------|--------|------------------|
| Binding failed | VLM coordinates don't match XML | Lower threshold, relax matching |
| Node not found | find_node returns empty | Use fallback query, relax conditions |
| Popup interrupt | Popup detected | Use close_locator, re-explore |
| Path replay failed | Page structure changed | Re-analyze page, update locators |

## 6. Observability

| Metric | Description | Purpose |
|--------|-------------|--------|
| `pages_discovered` | Number of pages found | Map coverage |
| `transitions_discovered` | Number of transitions found | Map connectivity |
| `fusion_rate` | VLM-XML fusion success rate | Locator quality |
| `avg_explore_time` | Average time per page | Efficiency |

## 7. Configuration

### VLM Model Selection

| Model | Accuracy | Speed | Recommended For |
|-------|----------|-------|-----------------|
| GPT-4o | Highest | Slow | High-quality maps |
| Qwen-VL-Plus | Medium-High | Fast | Cost-sensitive |

### Exploration Strategy

| Scenario | max_pages | max_depth | Description |
|----------|-----------|-----------|-------------|
| Simple app | 10 | 2 | Main flow coverage |
| Medium app | 20 | 3 | Complete coverage |
| Complex app | 50 | 4 | Deep coverage |

## 8. Constraints & Compatibility

### Dependencies
- Strongly depends on XML node quality from dump_actions
- Uses retrieval-first positioning strategy, avoiding hardcoded coordinates
- Locator semantics must stay consistent with LXB-Cortex

## 9. Current Gaps

- Weak-feature nodes may be unstable on highly dynamic pages
- VLM and XML semantic drift still requires manual tuning and review
- Horizontal scroll elements (carousels, tab bars) handling needs improvement
- WebView embedded content perception is limited

## 10. Cross References

- `docs/en/lxb_link.md` - Device communication protocol
- `docs/en/lxb_cortex.md` - Automation execution engine
- `docs/en/lxb_web_console.md` - Web map building interface
