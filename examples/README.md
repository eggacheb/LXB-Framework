# LXB-Framework Examples

This directory contains example code demonstrating various features of LXB-Framework.

## Directory Structure

```
examples/
├── basic/              # Basic device operations
│   ├── device_connection.py    # Connect to device and get info
│   └── screen_interaction.py   # Tap, swipe, text input examples
├── cortex/             # Cortex automation examples
│   └── simple_automation.py     # Route-Then-Act automation
├── map_builder/        # Map building examples
│   └── build_app_map.py        # Build navigation maps with VLM
├── advanced/           # Advanced usage examples
│   └── custom_planner.py       # Custom planners and action engines
└── web_console/        # Web console API examples
    └── usage.py                # Interact with web console programmatically
```

## Getting Started

1. **Configure your device** - Update the `DEVICE_IP` constant in each example
2. **Configure API keys** - Update API configuration for VLM/LLM features
3. **Run examples** - Execute examples with Python: `python examples/basic/device_connection.py`

## Examples Overview

### Basic Examples

Learn fundamental operations:
- Connecting to Android devices
- Taking screenshots
- Tapping and swiping
- Text input
- Key events (back, home, etc.)

### Cortex Examples

Explore automation with the Route-Then-Act engine:
- Setting up LLM planners
- Running FSM-based automation
- Handling task results
- Customizing FSM behavior

### Map Builder Examples

Build navigation maps automatically:
- Initialize VLM engine
- Explore app pages
- Save navigation maps
- Custom map building options

### Advanced Examples

Extend the framework for your needs:
- Implement custom task planners
- Create custom action engines
- Integrate with external APIs
- Build specialized automation workflows

### Web Console Examples

Use the web console programmatically:
- Connect via HTTP API
- Submit tasks remotely
- Monitor task status
- Build maps via web interface

## Prerequisites

- Android device with Shizuku installed
- Python 3.8+
- LXB-Framework installed
- API keys for VLM/LLM features (where applicable)

## Common Issues

**Connection Failed**
- Check device IP address
- Ensure Shizuku is running
- Verify ADB connection: `adb devices`

**API Errors**
- Verify API key is valid
- Check API base URL
- Increase timeout values

**Map Building Issues**
- Ensure app is installed on device
- Start app before building map
- Check VLM API quota

## Contributing

Have an example to share? Please submit a PR!

## Additional Resources

- [Documentation](../docs/)
- [Configuration Guide](../docs/en/configuration.md)
- [Quick Start Guide](../docs/en/quickstart.md)
