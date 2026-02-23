# LXB-Link

## 1. Scope
LXB-Link is the PC-side to Android-side protocol client layer, responsible for reliably sending commands and receiving responses.

## 2. Architecture
Code directory: `src/lxb_link/`

```
src/lxb_link/
├── __init__.py
├── client.py               # Main client API
├── transport.py            # Reliable UDP transport (Stop-and-Wait ARQ)
├── protocol.py             # Protocol frame encoding/decoding
└── constants.py            # Command constants definition
```

### Architecture Layers

```
Application Layer (Cortex, MapBuilder)
        ↓
  LXBLinkClient (Unified API)
        ↓
  ProtocolFrame (Protocol Encoding)
        ↓
  Transport (Reliable UDP)
        ↓
  UDP Socket (Network)
```

## 3. Core Flow

### 3.1 Command Execution Flow

```
client.tap(500, 800)
    │
    v
Encode to protocol frame (MAGIC + CMD + LEN + SEQ + PAYLOAD + CHECKSUM)
    │
    v
Send + Wait ACK (timeout retransmit, max MAX_RETRIES)
    │
    v
Receive response frame + verify
    │
    v
Parse result and return
```

### 3.2 Reliable Transport Principle

Stop-and-Wait ARQ protocol:
1. Start timer after sending data frame
2. Confirm send success upon receiving ACK
3. Auto retransmit on timeout without ACK

## 4. Command Categories

### Perception Layer (Sense)
- `get_activity` - Get current Activity
- `get_screen_size` - Get screen size
- `find_node` - Single-field node search
- `find_node_compound` - Multi-condition combined search
- `dump_actions` - Export operable nodes
- `dump_hierarchy` - Export complete UI tree

### Input Layer (Input)
- `tap` - Tap
- `swipe` - Swipe
- `long_press` - Long press
- `input_text` - Input text
- `key_event` - Key event (BACK/HOME)

### Lifecycle (Lifecycle)
- `launch_app` - Launch app
- `stop_app` - Stop app
- `list_apps` - List apps
- `wake` / `unlock` - Wake/unlock

## 5. Design Principles

### 5.1 Reliability Design
- Timeout retransmission mechanism (MAX_RETRIES=3)
- Checksum verifies data integrity
- Sequence number prevents duplicate processing

### 5.2 Retrieval-First Strategy
- `find_node_compound` priority (multi-condition combination, accurate positioning)
- `find_node` fallback (single field, good compatibility)

## 6. Code Structure

| File | Responsibility | Key Content |
|------|----------------|--------------|
| `client.py` | Unified API entry | `LXBLinkClient` class |
| `transport.py` | Transport layer implementation | `_send_frame`, `_recv_frame` |
| `protocol.py` | Protocol encoding/decoding | `ProtocolFrame.encode/decode` |
| `constants.py` | Command/constants definition | CMD_* constants |

## 7. Cross References
- `docs/en/lxb_server.md` - Android-side implementation
- `docs/en/lxb_cortex.md` - Cortex usage examples
