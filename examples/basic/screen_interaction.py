"""
Screen Interaction Example

This example demonstrates basic screen interaction operations including:
- Tapping at coordinates
- Swiping gestures
- Text input
- Key events (back, home, etc.)
"""

from lxb_link import LXBLinkClient

# Device configuration
DEVICE_IP = "192.168.1.100"  # Change to your device IP
DEVICE_PORT = 12345


def main():
    print("=== Screen Interaction Example ===\n")

    with LXBLinkClient(DEVICE_IP, DEVICE_PORT) as client:
        client.handshake()

        # Get screen size for reference
        ok, width, height, density = client.get_screen_size()
        print(f"Screen size: {width}x{height}")
        print(f"Density: {density}")

        # Example 1: Simple tap at screen center
        print("\n--- Example 1: Tap at screen center ---")
        center_x = width // 2
        center_y = height // 2
        print(f"Tapping at ({center_x}, {center_y})")
        client.tap(center_x, center_y)

        # Example 2: Swipe gesture (scroll down)
        print("\n--- Example 2: Swipe down ---")
        start_x, start_y = width // 2, height * 3 // 4
        end_x, end_y = width // 2, height // 4
        duration_ms = 500
        print(f"Swiping from ({start_x}, {start_y}) to ({end_x}, {end_y})")
        client.swipe(start_x, start_y, end_x, end_y, duration_ms)

        # Example 3: Long press
        print("\n--- Example 3: Long press ---")
        long_press_x, long_press_y = width // 2, height // 3
        print(f"Long pressing at ({long_press_x}, {long_press_y})")
        client.long_press(long_press_x, long_press_y, duration_ms=1000)

        # Example 4: Text input
        print("\n--- Example 4: Text input ---")
        test_text = "Hello, LXB-Framework!"
        print(f"Inputting text: {test_text}")
        status, method = client.input_text(test_text)
        print(f"Input status: {status}, method used: {method}")

        # Example 5: Key events
        print("\n--- Example 5: Key events ---")

        # Press Back key
        print("Pressing BACK key")
        client.key_event(client.KEY_BACK)

        # Press Home key
        print("Pressing HOME key")
        client.key_event(client.KEY_HOME)

        # Example 6: Wait
        print("\n--- Example 6: Wait ---")
        print("Waiting 2 seconds...")
        import time
        time.sleep(2)
        print("Done waiting")

        # Example 7: Wake up device
        print("\n--- Example 7: Wake device ---")
        client.wake()
        print("Device woken up")

        # Example 8: Unlock device
        print("\n--- Example 8: Unlock device ---")
        client.unlock()
        print("Device unlocked")

    print("\n=== All examples completed ===")


if __name__ == "__main__":
    main()
