# LXB-Server

## 1. Scope
LXB-Server is the Android-side service core, receiving protocol commands and executing input injection, node retrieval, and state acquisition.

## 2. Architecture
Code directory: `android/LXB-Ignition/lxb-core/`

```
lxb-core/
├── protocol/               # Protocol parsing and dispatch
├── dispatcher/            # Command dispatcher
├── perception/            # Perception engine
└── executors/             # Executor implementation
```

### Service Architecture

```
Shizuku IPC
       │
       v
LXB-Server Core
       │
       ├──> Perception Engine (Accessibility Service)
       │      └── Get UI tree, node attributes
       │
       └──> Executors (Input/Lifecycle)
              └── Inject input, app control
```

## 3. Core Flow

### 3.1 Command Processing Flow

```
Receive UDP frame
    │
    v
Parse frame (CMD ID + Payload)
    │
    v
Dispatch to corresponding engine
    │
    ├──> Perception Commands → AccessibilityService → UI tree data
    │
    └──> Execution Commands → Input Manager → Device operations
    │
    v
Generate response frame
```

### 3.2 Perception Engine Principle

**AccessibilityService Mechanism**:
- Inherits Android AccessibilityService
- Listens to UI change events
- Traverses UI tree to extract node information

**Node Attribute Extraction**:
- `getText()` - Visible text
- `getResourceName()` - Resource ID
- `getBoundsInScreen()` - Screen coordinates
- `isClickable()` - Clickability

### 3.3 Input Injection Methods

| Method | Implementation Principle | Priority |
|--------|------------------|----------|
| Accessibility API | `performAction(ACTION_CLICK)` | Highest |
| Clipboard | Set clipboard + paste | Medium |
| Shell input | `input text` command | Lowest (fallback) |

## 4. Node Matching

### 4.1 Single Field Search (FIND_NODE)
1. Get current UI tree
2. Select matching field based on match_type
3. Traverse nodes, execute matching logic
4. Collect all matching nodes

### 4.2 Multi-Condition Search (FIND_NODE_COMPOUND)
1. Build condition triples: (field, operator, value)
2. Verify all conditions for each node
3. Return nodes satisfying all conditions

## 5. Failure Modes

| Failure Type | Cause | Handling |
|--------------|-------|----------|
| Service disconnect | System reclaims service | Return error code |
| UI tree empty | Page loading | Return empty list |
| Insufficient permissions | Shizuku unauthorized | Return permission error |

## 6. Code Structure

| Java File | Responsibility |
|-----------|----------------|
| `PerceptionEngine.java` | Perception engine entry |
| `CommandDispatcher.java` | Command routing dispatch |
| `NodeFinder.java` | Node search logic |

## 7. Cross References
- `docs/en/lxb_link.md` - Protocol definition
- `docs/en/lxb_web_console.md` - Web console
