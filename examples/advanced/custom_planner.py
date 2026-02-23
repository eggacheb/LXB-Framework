"""
Custom Planner Example

This example demonstrates how to implement custom planners and action engines
for specialized automation tasks.
"""

from lxb_link import LXBLinkClient
from cortex import CortexFSMEngine, MapTaskPlanner, VLMActionEngine
from cortex.route_then_act import RouteMap, RoutePlan
from typing import Dict, Any

# Device configuration
DEVICE_IP = "192.168.1.100"
DEVICE_PORT = 12345


# Example 1: Custom Map Task Planner
class KeywordBasedPlanner:
    """
    Custom planner that selects target page based on keyword matching.

    This demonstrates how to implement custom planning logic without LLM.
    """

    def __init__(self):
        self.keyword_to_page = {
            "settings": "settings",
            "home": "home",
            "profile": "profile",
            "search": "search",
            "cart": "cart"
        }

    def plan(self, user_task: str, route_map: RouteMap) -> RoutePlan:
        """Plan target page based on task keywords."""
        task_lower = user_task.lower()

        # Check for keyword matches
        for keyword, page_id in self.keyword_to_page.items():
            if keyword in task_lower:
                return RoutePlan(route_map.package, page_id)

        # Default to home page
        return RoutePlan(route_map.package, "home")


# Example 2: Custom VLM Action Engine
class SimpleVLMActionEngine:
    """
    Simple VLM-based action engine that performs visual task execution.

    This demonstrates a minimal implementation of the VLMActionEngine protocol.
    """

    def __init__(self, client, api_base_url: str, api_key: str, model_name: str):
        self.client = client
        self.api_base_url = api_base_url
        self.api_key = api_key
        self.model_name = model_name
        self.max_actions = 10

    def execute(self, user_task: str, context: Dict[str, Any]) -> Dict[str, Any]:
        """Execute task using VLM guidance."""
        import base64
        import requests
        import re

        print(f"Executing task: {user_task}")
        print(f"Context: target_page={context.get('target_page')}")

        for action_num in range(self.max_actions):
            # Take screenshot
            screenshot = self.client.request_screenshot()
            if not screenshot:
                return {"status": "failed", "reason": "screenshot_failed"}

            # Build prompt for VLM
            prompt = f"""
Task: {user_task}
Current page: {context.get('target_page')}
Action number: {action_num + 1} / {self.max_actions}

Analyze the screenshot and determine the next action.
Output in this format: ACTION|x|y or ACTION
Where ACTION can be: TAP, SWIPE_UP, SWIPE_DOWN, BACK, DONE

If the task is complete, output: DONE
"""

            # Call VLM API
            image_base64 = base64.b64encode(screenshot).decode("utf-8")
            response = requests.post(
                f"{self.api_base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self.api_key}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": self.model_name,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": prompt},
                                {
                                    "type": "image_url",
                                    "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}
                                }
                            ]
                        }
                    ],
                    "temperature": 0.1
                },
                timeout=30
            )

            response.raise_for_status()
            result = response.json()
            action_text = result["choices"][0]["message"]["content"]

            # Parse action
            print(f"VLM Response: {action_text}")

            if "DONE" in action_text.upper():
                print("Task completed!")
                return {"status": "success", "actions_taken": action_num + 1}

            parts = action_text.strip().split("|")
            action = parts[0].upper()

            if action == "TAP" and len(parts) >= 3:
                x, y = int(parts[1]), int(parts[2])
                print(f"Tapping at ({x}, {y})")
                self.client.tap(x, y)
            elif action == "SWIPE_UP":
                # Swipe up (scroll down)
                ok, w, h, _ = self.client.get_screen_size()
                cx, cy = w // 2, h // 2
                self.client.swipe(cx, cy + 200, cx, cy - 200, 500)
            elif action == "SWIPE_DOWN":
                # Swipe down (scroll up)
                ok, w, h, _ = self.client.get_screen_size()
                cx, cy = w // 2, h // 2
                self.client.swipe(cx, cy - 200, cx, cy + 200, 500)
            elif action == "BACK":
                print("Pressing back")
                self.client.key_event(4)

            # Wait for UI to settle
            import time
            time.sleep(1)

        return {"status": "failed", "reason": "max_actions_reached"}


# Example 3: Using custom planners with Route-Then-Act
def example_custom_planner():
    """Example using custom planner with Route-Then-Act."""
    from cortex.route_then_act import RouteThenActCortex

    print("=== Custom Planner Example ===\n")

    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()

    # Create custom planner
    planner = KeywordBasedPlanner()

    # Create route engine with custom planner
    engine = RouteThenActCortex(client, planner=planner)

    # Run routing
    result = engine.run(
        user_task="Go to settings",
        map_path="maps/com.example.app/nav_map.json"
    )

    print(f"Routing status: {result['status']}")
    print(f"Route trace: {result.get('route_trace', [])}")

    client.disconnect()


# Example 4: Using custom action engine
def example_custom_action_engine():
    """Example using custom VLM action engine."""
    print("\n=== Custom Action Engine Example ===\n")

    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()

    # Create custom action engine
    action_engine = SimpleVLMActionEngine(
        client=client,
        api_base_url="https://your-api.com/v1",
        api_key="your-api-key",
        model_name="qwen-vl-plus"
    )

    # Route to target page first
    from cortex.route_then_act import RouteThenActCortex, FixedPlanPlanner

    planner = FixedPlanPlanner("com.example.app", "search")

    route_engine = RouteThenActCortex(
        client=client,
        planner=planner,
        action_engine=action_engine
    )

    # Run complete automation
    result = route_engine.run(
        user_task="Search for 'phone case'",
        map_path="maps/com.example.app/nav_map.json"
    )

    print(f"Overall status: {result['status']}")
    if result.get('vlm_result'):
        print(f"VLM result: {result['vlm_result']}")

    client.disconnect()


def main():
    """Run all examples."""
    example_custom_planner()
    # Uncomment to run action engine example (requires VLM API)
    # example_custom_action_engine()


if __name__ == "__main__":
    main()
