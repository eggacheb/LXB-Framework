"""
Simple Automation Example Using LXB-Cortex

This example demonstrates how to use the CortexFSMEngine to perform
Route-Then-Act automation tasks.
"""

from lxb_link import LXBLinkClient
from cortex import CortexFSMEngine, LLMPlanner, FSMConfig

# Device configuration
DEVICE_IP = "192.168.1.100"  # Change to your device IP
DEVICE_PORT = 12345

# LLM API configuration
API_BASE_URL = "https://your-api-provider.com/v1"
API_KEY = "your-api-key"
MODEL_NAME = "qwen-plus"  # or your preferred model


def create_llm_planner():
    """Create an LLM planner for the Cortex engine."""

    def llm_complete(prompt: str) -> str:
        """Call LLM API with the given prompt."""
        import requests

        response = requests.post(
            f"{API_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": MODEL_NAME,
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.1
            },
            timeout=60
        )

        response.raise_for_status()
        result = response.json()
        return result["choices"][0]["message"]["content"]

    def llm_complete_with_image(prompt: str, image: bytes) -> str:
        """Call LLM API with prompt and image for vision tasks."""
        import requests
        import base64

        # Encode image to base64
        image_base64 = base64.b64encode(image).decode("utf-8")

        response = requests.post(
            f"{API_BASE_URL}/chat/completions",
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Content-Type": "application/json"
            },
            json={
                "model": MODEL_NAME,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}}
                        ]
                    }
                ],
                "temperature": 0.1
            },
            timeout=60
        )

        response.raise_for_status()
        result = response.json()
        return result["choices"][0]["message"]["content"]

    return LLMPlanner(
        complete=llm_complete,
        complete_with_image=llm_complete_with_image
    )


def main():
    print("=== LXB-Cortex Simple Automation Example ===\n")

    # Connect to device
    print("Connecting to device...")
    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()
    print("Connected successfully\n")

    # Create LLM planner
    print("Creating LLM planner...")
    planner = create_llm_planner()

    # Create FSM config with custom settings
    fsm_config = FSMConfig(
        max_turns=30,
        max_vision_turns=20,
        action_interval_sec=0.8,
        screenshot_settle_sec=0.6,
        tap_jitter_sigma_px=2.0
    )

    # Create Cortex engine
    print("Creating Cortex engine...")
    engine = CortexFSMEngine(
        client=client,
        planner=planner,
        fsm_config=fsm_config
    )

    # Define automation task
    user_task = "Open settings and enable dark mode"
    map_path = "maps/com.android.settings/nav_map.json"  # Update with your map path

    print(f"\nRunning task: {user_task}")
    print(f"Map: {map_path}\n")

    # Execute automation
    result = engine.run(
        user_task=user_task,
        map_path=map_path,
        start_page=None  # Auto-detect starting page
    )

    # Display results
    print("\n=== Execution Results ===")
    print(f"Status: {result['status']}")
    print(f"State: {result['state']}")

    if result['status'] == 'success':
        print(f"Package: {result['package_name']}")
        print(f"Target Page: {result['target_page']}")
        print(f"Route Trace: {result['route_trace']}")
        print(f"Vision Turns: {result.get('llm_history', [{}])}")
    else:
        print(f"Reason: {result.get('reason', 'Unknown')}")

    # Disconnect
    client.disconnect()
    print("\nDisconnected from device")


if __name__ == "__main__":
    main()
