# LXB-WebConsole

## 1. Scope
LXB-WebConsole is the unified web entry point providing interfaces for connection management, command debugging, map building, map viewing, and Cortex execution.

## 2. Architecture
Code directory: `web_console/`

```
web_console/
├── app.py                 # Flask backend service
├── templates/             # HTML templates
│   ├── index.html         # Connection status page
│   ├── command_studio.html
│   ├── map_builder.html
│   ├── map_viewer.html
│   └── cortex_route.html
└── static/
    └── js/
        └── main.js        # Frontend interaction logic
```

### Module Relationships

```
Web Browser (User Interface)
       │
       v
Flask Backend (app.py)
       │
       ├──> LXB-Link (Device Communication)
       ├──> LXB-Cortex (Automation Execution)
       └──> LXB-MapBuilder (Map Building)
```

## 3. Core Flow

### 3.1 Connection Management Flow

```
User enters device info (IP + Port)
       │
       v
Create LXBLinkClient instance
       │
       v
Handshake verification
       │
       v
Display device info
```

### 3.2 Command Debug Flow

```
User selects command type (TAP/SWIPE/INPUT/...)
       │
       v
Frontend form fills parameters
       │
       v
POST /api/command/execute
       │
       v
Backend executes command
       │
       v
Frontend displays results
```

### 3.3 Map Building Flow

```
User configures map building parameters
       │
       v
Start NodeMapBuilder
       │
       v
Real-time progress push
       │
       v
Frontend updates UI (progress, screenshots, nodes)
       │
       v
Complete and save map JSON
```

### 3.4 Cortex Execution Flow

```
User enters task description
       │
       v
Select or upload map
       │
       v
Create CortexFSMEngine
       │
       v
Real-time log push (FSM state, route trace)
       │
       v
Frontend visualization display
```

## 4. Key Interfaces

### 4.1 Main Pages

| Page | Route | Function |
|------|-------|----------|
| Connection Status | `/` | Device connection, status display |
| Command Studio | `/command_studio` | Send commands, view results |
| Map Builder | `/map_builder` | Auto map building, progress monitoring |
| Map Viewer | `/map_viewer` | Map visualization, editing |
| Route Executor | `/cortex_route` | Task submission, execution monitoring |

### 4.2 API Categories

**Device Connection API**
- `/api/device/connect` - Connect device
- `/api/device/disconnect` - Disconnect device
- `/api/device/status` - Get status

**Command Execution API**
- `/api/command/tap` - Tap
- `/api/command/swipe` - Swipe
- `/api/command/input_text` - Input text

**Map Building API**
- `/api/explore/start` - Start map building
- `/api/explore/progress` - Get progress
- `/api/maps/list` - List maps

**Cortex Execution API**
- `/api/cortex/submit` - Submit task
- `/api/cortex/status/{task_id}` - Get status
- `/api/cortex/logs/{task_id}` - Get logs

## 5. Design Principles

### 5.1 Unified Entry Point
- All functions integrated in one web interface
- Unified navigation bar and status display
- Consistent user experience

### 5.2 Real-time Feedback
- Polling mechanism for task progress
- Real-time display of screenshots and logs
- Visual state machine flow

### 5.3 Module Decoupling
- Frontend communicates with backend via HTTP API
- Backend calls module core functions
- Modules are independent and easy to maintain

## 6. Code Structure

| File | Responsibility | Key Content |
|------|----------------|--------------|
| `app.py` | Flask backend service | API routes, device management |
| `main.js` | Frontend interaction logic | DOM operations, AJAX requests |
| `templates/*.html` | Page templates | UI for each function page |

## 7. Cross References
- `docs/en/lxb_link.md` - Device communication
- `docs/en/lxb_map_builder.md` - Map building
- `docs/en/lxb_cortex.md` - Automation execution
