import io
import os

ROOT = os.path.dirname(__file__)
APP = os.path.join(ROOT, "web_console", "app.py")

def main():
    with open(APP, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()

    indices = [i for i, l in enumerate(lines) if "def cmd_cortex_route_run" in l]
    print("cmd_cortex_route_run indices:", indices)
    if len(indices) < 2:
        print("Nothing to fix; only one definition found.")
        return

    # Remove the second definition block (legacy from_page/to_page 版本)
    start = indices[1] - 1  # include decorator line
    end = start
    # consume until first completely blank line after the function
    while end < len(lines) and lines[end].strip():
        end += 1
    while end < len(lines) and not lines[end].strip():
        end += 1

    print(f"Removing lines {start+1}..{end}")
    new_lines = lines[:start] + lines[end:]
    with open(APP, "w", encoding="utf-8", newline="") as f:
        f.writelines(new_lines)

if __name__ == "__main__":
    main()

