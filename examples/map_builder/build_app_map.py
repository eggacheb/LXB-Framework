"""
Build Navigation Map Example

This example demonstrates how to use the NodeMapBuilder to automatically
build a navigation map for an Android app using VLM.
"""

from lxb_link import LXBLinkClient
from auto_map_builder import NodeMapBuilder
from auto_map_builder.vlm_engine import VLMEngine, VLMConfig

# Device configuration
DEVICE_IP = "192.168.1.100"  # Change to your device IP
DEVICE_PORT = 12345

# VLM API configuration
API_BASE_URL = "https://your-api-provider.com/v1"
API_KEY = "your-api-key"
MODEL_NAME = "qwen-vl-plus"  # Vision-language model

# App configuration
PACKAGE_NAME = "com.example.app"  # Change to target app package
OUTPUT_PATH = "maps/com.example.app/nav_map.json"


def main():
    print("=== Navigation Map Builder Example ===\n")

    # Connect to device
    print("Connecting to device...")
    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()
    print("Connected successfully\n")

    # Configure VLM engine
    print("Configuring VLM engine...")
    vlm_config = VLMConfig(
        api_base_url=API_BASE_URL,
        api_key=API_KEY,
        model_name=MODEL_NAME,
        temperature=0.1,
        timeout=30,
        cache_enabled=True
    )

    vlm_engine = VLMEngine(config=vlm_config)
    print("VLM engine configured\n")

    # Create map builder
    print("Creating map builder...")
    builder = NodeMapBuilder(
        client=client,
        vlm_engine=vlm_engine
    )

    # Build navigation map
    print(f"Building navigation map for {PACKAGE_NAME}...")
    print("This may take several minutes depending on app complexity...\n")

    try:
        nav_map = builder.build_map(
            package_name=PACKAGE_NAME,
            start_page_id="home",  # Starting from home page
            max_depth=3,  # Explore up to 3 levels deep
            max_pages=20  # Maximum pages to explore
        )

        print(f"\nMap building completed!")
        print(f"Package: {nav_map.package}")
        print(f"Pages discovered: {len(nav_map.pages)}")
        print(f"Transitions: {len(nav_map.transitions)}")

        # Display page summary
        print("\n=== Discovered Pages ===")
        for page_id, page_data in nav_map.pages.items():
            print(f"- {page_id}: {page_data.get('name', 'Unnamed')}")
            print(f"  Aliases: {page_data.get('target_aliases', [])}")
            print(f"  Features: {page_data.get('features', [])[:3]}...")  # First 3 features

        # Save map to file
        print(f"\nSaving map to {OUTPUT_PATH}...")
        builder.save_map(nav_map, OUTPUT_PATH)
        print("Map saved successfully!")

    except Exception as e:
        print(f"\nError building map: {e}")
        import traceback
        traceback.print_exc()

    finally:
        # Disconnect
        client.disconnect()
        print("\nDisconnected from device")


def build_with_custom_pages():
    """
    Example: Build map with specific starting pages.

    Useful when you want to build maps starting from different entry points.
    """
    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)
    client.connect()
    client.handshake()

    vlm_engine = VLMEngine(
        api_base_url=API_BASE_URL,
        api_key=API_KEY,
        model_name=MODEL_NAME
    )

    builder = NodeMapBuilder(client=client, vlm_engine=vlm_engine)

    # Build from multiple starting points
    start_pages = ["home", "search", "profile"]

    for start_page in start_pages:
        print(f"\nBuilding map from {start_page}...")
        try:
            nav_map = builder.build_map(
                package_name=PACKAGE_NAME,
                start_page_id=start_page,
                max_depth=2
            )

            output_path = f"maps/{PACKAGE_NAME}/nav_map_{start_page}.json"
            builder.save_map(nav_map, output_path)
            print(f"Saved to {output_path}")

        except Exception as e:
            print(f"Failed to build from {start_page}: {e}")

    client.disconnect()


if __name__ == "__main__":
    main()
    # Uncomment to build from multiple starting points
    # build_with_custom_pages()
