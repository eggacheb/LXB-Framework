# Quick Start Guide

This guide will help you get started with LXB-Framework quickly.

## Prerequisites

### Required

- **Python 3.8+** - Ensure Python 3.8 or higher is installed
- **Android Device** - Device or emulator running Android 7.0+
- **Network Connection** - For accessing LLM API

### Android Device Setup

1. **Install Shizuku**

   LXB-Framework requires Shizuku for system-level operations:

   - Download [Shizuku APK](https://shizuku.rikka.app/)
   - Install and open Shizuku on your Android device
   - Start Shizuku service (requires ADB access or root)

2. **Connect ADB**

   ```bash
   # Connect device via USB
   adb devices

   # Start Shizuku (first time only)
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```

### LLM API Setup

1. Get an LLM API key (OpenAI-compatible interface supported)
2. Edit `.cortex_llm_planner.json` in project root:

```json
{
  "api_base_url": "https://your-api-provider.com/v1",
  "api_key": "your-api-key-here",
  "model_name": "your-model-name"
}
```

## Installation

### 1. Clone Repository

```bash
git clone https://github.com/your-org/LXB-Framework.git
cd LXB-Framework
```

### 2. Install Dependencies

```bash
# Core dependencies (without VLM features)
pip install -r requirements.txt

# Full dependencies (including VLM features)
pip install -r requirements-vlm.txt
```

### 3. Configure Device Connection

Edit `.cortex_llm_planner.json` and set device IP:

```json
{
  "device_ip": "192.168.1.100",
  "device_port": 12345
}
```

## Basic Usage

### Start Web Console

```bash
cd web_console
python app.py
```

Visit `http://localhost:5000` to use the web console.

### Command Line Basic Operations

```python
from lxb_link import LXBLinkClient
from cortex import CortexFSMEngine

# Connect to device
client = LXBLinkClient('192.168.1.100', 12345)
client.connect()
client.handshake()

# Take screenshot
screenshot = client.screenshot()

# Tap screen
client.tap(500, 800)

# Swipe
client.swipe(540, 1600, 540, 1400, 500)
```

### Route-Then-Act Automation

```python
from cortex import CortexFSMEngine, LLMPlanner

# Create LLM planner
def my_llm_complete(prompt):
    # Call your LLM API
    return your_llm_api.generate(prompt)

planner = LLMPlanner(complete=my_llm_complete)

# Create FSM engine
engine = CortexFSMEngine(client, planner=planner)

# Execute automation task
result = engine.run(
    user_task="Open settings and enable WiFi",
    map_path="maps/com.android.settings.json"
)

print(result["status"])  # "success" or "failed"
```

### Build Navigation Map

```python
from auto_map_builder import NodeMapBuilder
from auto_map_builder.vlm_engine import VLMEngine

# Initialize
vlm = VLMEngine(
    api_base_url="https://your-api.com/v1",
    api_key="your-key",
    model_name="qwen-vl-plus"
)

builder = NodeMapBuilder(
    client=client,
    vlm_engine=vlm
)

# Build map
nav_map = builder.build_map(
    package_name="com.example.app",
    start_page_id="home"
)

# Save map
builder.save_map(nav_map, "maps/com.example.app.json")
```

## Common Workflows

### 1. Connect Device and Test

```python
from lxb_link import LXBLinkClient

# Using context manager (recommended)
with LXBLinkClient('192.168.1.100', 12345) as client:
    # Handshake
    client.handshake()

    # Get device info
    ok, width, height, density = client.get_screen_size()
    print(f"Screen size: {width}x{height}")

    # Screenshot test
    screenshot = client.screenshot()
    print(f"Screenshot size: {len(screenshot)} bytes")
```

### 2. Find UI Elements

```python
# Find by text
status, nodes = client.find_node(
    "Settings",
    match_type=1,  # MATCH_EXACT_TEXT
    return_mode=1  # RETURN_BOUNDS
)

# Find by resource ID
status, nodes = client.find_node(
    "com.android.settings:id/button",
    match_type=3,  # MATCH_RESOURCE_ID
    return_mode=1
)

# Tap found element
if status == 1 and nodes:
    x, y = nodes[0]  # Get coordinates of first match
    client.tap(x, y)
```

### 3. Execute Automation Task

```python
from cortex import CortexFSMEngine, LLMPlanner

# Configure LLM
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

# Run automation
engine = CortexFSMEngine(client, planner=planner)
result = engine.run(
    user_task="Search for 'phone' on Taobao homepage",
    map_path="maps/com_taobao_taobao/nav_map.json"
)

# Check result
if result["status"] == "success":
    print("Task completed successfully!")
    print(f"Route trace: {result['route_trace']}")
else:
    print(f"Task failed: {result.get('reason')}")
```

## Troubleshooting

### Device Connection Issues

```bash
# Check ADB connection
adb devices

# Check Shizuku service status
adb shell ps | grep shizuku

# Restart Shizuku
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

### API Call Issues

1. Check `.cortex_llm_planner.json` configuration
2. Verify API key is valid
3. Increase `timeout` value
4. Check network connection

### Navigation Map Issues

```python
# Verify map format
import json

with open("maps/com.example.app.json") as f:
    nav_map = json.load(f)

print(f"Package: {nav_map['package']}")
print(f"Pages: {len(nav_map['pages'])}")
print(f"Transitions: {len(nav_map['transitions'])}")
```

## Next Steps

- Read full documentation:
  - [LXB-Link Documentation](./lxb_link.md)
  - [LXB-Cortex Documentation](./lxb_cortex.md)
  - [Map Builder Documentation](./lxb_map_builder.md)
- Check example code:
  - [examples/](../../examples/) directory
- Join community discussion

## Get Help

If you encounter issues:
1. Check [FAQ](../faq.md)
2. Search existing [Issues](https://github.com/your-org/LXB-Framework/issues)
3. Submit a new Issue
