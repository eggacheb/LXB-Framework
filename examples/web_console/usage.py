"""
Web Console Usage Example

This example demonstrates how to interact with the LXB-Framework web console
programmatically using its HTTP API.
"""

import requests
import json
import time

# Web console configuration
WEB_CONSOLE_URL = "http://localhost:5000"


class WebConsoleClient:
    """Client for interacting with the LXB-Framework web console."""

    def __init__(self, base_url: str = WEB_CONSOLE_URL):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()

    def get_status(self) -> dict:
        """Get the current status of the web console."""
        response = self.session.get(f"{self.base_url}/api/status")
        response.raise_for_status()
        return response.json()

    def connect_device(self, device_ip: str, device_port: int = 12345) -> dict:
        """Connect to an Android device."""
        response = self.session.post(
            f"{self.base_url}/api/device/connect",
            json={"ip": device_ip, "port": device_port}
        )
        response.raise_for_status()
        return response.json()

    def disconnect_device(self) -> dict:
        """Disconnect from the current device."""
        response = self.session.post(f"{self.base_url}/api/device/disconnect")
        response.raise_for_status()
        return response.json()

    def get_device_info(self) -> dict:
        """Get information about the connected device."""
        response = self.session.get(f"{self.base_url}/api/device/info")
        response.raise_for_status()
        return response.json()

    def take_screenshot(self, save_path: str = None) -> bytes:
        """Take a screenshot from the connected device.

        Args:
            save_path: Optional path to save the screenshot image

        Returns:
            Screenshot image bytes
        """
        response = self.session.get(f"{self.base_url}/api/device/screenshot")
        response.raise_for_status()

        # Handle JSON response with base64 image or direct image
        content_type = response.headers.get("Content-Type", "")
        if "application/json" in content_type:
            data = response.json()
            image_bytes = data.get("image", b"")
            if isinstance(image_bytes, str):
                import base64
                image_bytes = base64.b64decode(image_bytes)
        else:
            image_bytes = response.content

        if save_path:
            with open(save_path, "wb") as f:
                f.write(image_bytes)

        return image_bytes

    def submit_task(self, user_task: str, map_path: str = None) -> dict:
        """Submit an automation task to the Cortex engine.

        Args:
            user_task: Natural language description of the task
            map_path: Optional path to navigation map

        Returns:
            Task submission result with task_id
        """
        payload = {"user_task": user_task}
        if map_path:
            payload["map_path"] = map_path

        response = self.session.post(
            f"{self.base_url}/api/cortex/submit",
            json=payload
        )
        response.raise_for_status()
        return response.json()

    def get_task_status(self, task_id: str) -> dict:
        """Get the status of a submitted task.

        Args:
            task_id: ID of the task to query

        Returns:
            Task status information including state and results
        """
        response = self.session.get(f"{self.base_url}/api/cortex/task/{task_id}")
        response.raise_for_status()
        return response.json()

    def wait_for_task_completion(self, task_id: str, timeout: int = 120) -> dict:
        """Wait for a task to complete.

        Args:
            task_id: ID of the task to wait for
            timeout: Maximum time to wait in seconds

        Returns:
            Final task result
        """
        start_time = time.time()

        while time.time() - start_time < timeout:
            status = self.get_task_status(task_id)

            state = status.get("state")
            if state in {"FINISH", "FAIL", "completed", "failed"}:
                return status

            # Wait before polling again
            time.sleep(1)

        raise TimeoutError(f"Task {task_id} did not complete within {timeout} seconds")

    def get_maps(self) -> dict:
        """Get list of available navigation maps."""
        response = self.session.get(f"{self.base_url}/api/maps/list")
        response.raise_for_status()
        return response.json()

    def build_map(self, package_name: str, start_page: str = "home") -> dict:
        """Start building a navigation map for an app.

        Args:
            package_name: Android package name
            start_page: Starting page ID

        Returns:
            Map build task information
        """
        response = self.session.post(
            f"{self.base_url}/api/maps/build",
            json={
                "package_name": package_name,
                "start_page": start_page
            }
        )
        response.raise_for_status()
        return response.json()


def example_basic_usage():
    """Basic usage example."""
    print("=== Web Console Basic Usage ===\n")

    client = WebConsoleClient()

    # Check console status
    status = client.get_status()
    print(f"Console status: {status}")

    # Connect to device
    print("\nConnecting to device...")
    connect_result = client.connect_device("192.168.1.100", 12345)
    print(f"Connect result: {connect_result}")

    # Get device info
    device_info = client.get_device_info()
    print(f"Device info: {device_info}")

    # Take screenshot
    print("\nTaking screenshot...")
    screenshot = client.take_screenshot("screenshot_web.png")
    print(f"Screenshot saved: {len(screenshot)} bytes")

    # Disconnect
    print("\nDisconnecting...")
    disconnect_result = client.disconnect_device()
    print(f"Disconnect result: {disconnect_result}")


def example_task_execution():
    """Example: Submit and monitor a Cortex task."""
    print("\n=== Task Execution Example ===\n")

    client = WebConsoleClient()

    # Connect to device
    print("Connecting to device...")
    client.connect_device("192.168.1.100", 12345)

    # Submit task
    print("\nSubmitting task...")
    task_result = client.submit_task(
        user_task="Open settings and enable WiFi",
        map_path="maps/com.android.settings/nav_map.json"
    )

    task_id = task_result.get("task_id")
    print(f"Task submitted: {task_id}")

    # Wait for completion
    print("Waiting for task to complete...")
    final_result = client.wait_for_task_completion(task_id, timeout=120)

    print(f"\nTask completed!")
    print(f"Status: {final_result.get('status')}")
    print(f"State: {final_result.get('state')}")

    if final_result.get("status") == "success":
        print(f"Route trace: {final_result.get('route_trace')}")
    else:
        print(f"Reason: {final_result.get('reason')}")

    # Disconnect
    client.disconnect_device()


def example_map_building():
    """Example: Build navigation map via web console."""
    print("\n=== Map Building Example ===\n")

    client = WebConsoleClient()

    # Connect to device
    print("Connecting to device...")
    client.connect_device("192.168.1.100", 12345)

    # List available maps
    print("\nAvailable maps:")
    maps = client.get_maps()
    for pkg, map_info in maps.items():
        print(f"  {pkg}: {map_info}")

    # Build new map
    print("\nBuilding map for com.taobao.taobao...")
    build_result = client.build_map(
        package_name="com.taobao.taobao",
        start_page="home"
    )

    print(f"Build result: {build_result}")

    # Disconnect
    client.disconnect_device()


def main():
    """Run all examples."""
    example_basic_usage()
    # Uncomment to run task execution example
    # example_task_execution()
    # Uncomment to run map building example
    # example_map_building()


if __name__ == "__main__":
    main()
