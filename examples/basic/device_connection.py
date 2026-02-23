"""
Basic Device Connection Example

This example demonstrates how to connect to an Android device using LXB-Link
and perform basic operations like handshake, getting device info, and taking screenshots.
"""

from lxb_link import LXBLinkClient

# Device configuration
DEVICE_IP = "192.168.1.100"  # Change to your device IP
DEVICE_PORT = 12345

def main():
    print("=== LXB-Link Basic Connection Example ===\n")

    # Method 1: Using context manager (recommended)
    print("Method 1: Using context manager")
    with LXBLinkClient(DEVICE_IP, DEVICE_PORT) as client:
        print(f"Connecting to {DEVICE_IP}:{DEVICE_PORT}...")

        # Perform handshake
        result = client.handshake()
        print(f"Handshake result: {result}")

        # Get screen size
        ok, width, height, density = client.get_screen_size()
        if ok:
            print(f"Screen size: {width}x{height}, density: {density}")
        else:
            print("Failed to get screen size")

        # Get current activity
        ok, package, activity = client.get_activity()
        if ok:
            print(f"Current activity: {package}/{activity}")
        else:
            print("Failed to get current activity")

        # Take screenshot
        screenshot = client.screenshot()
        if screenshot:
            print(f"Screenshot captured: {len(screenshot)} bytes")
            # Save screenshot to file
            with open("screenshot.png", "wb") as f:
                f.write(screenshot)
            print("Screenshot saved to screenshot.png")

    print("\nClient automatically disconnected.")

    # Method 2: Manual connection management
    print("\nMethod 2: Manual connection management")
    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)

    try:
        client.connect()
        print("Connected to device")

        # Perform operations...
        ok, width, height, density = client.get_screen_size()
        print(f"Screen size: {width}x{height}")

    finally:
        client.disconnect()
        print("Disconnected from device")


if __name__ == "__main__":
    main()
