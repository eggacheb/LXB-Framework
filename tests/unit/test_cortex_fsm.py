import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent / "src"))

from cortex import CortexFSMEngine, CortexState, InstructionError, parse_instructions, validate_allowed


class _DummyClient:
    def __init__(self):
        self.taps = []
        self.swipes = []
        self.inputs = []

    def tap(self, x, y):
        self.taps.append((x, y))

    def swipe(self, x1, y1, x2, y2, duration=300):
        self.swipes.append((x1, y1, x2, y2, duration))

    def input_text(self, text):
        self.inputs.append(text)

    def request_screenshot(self):
        return b"fake_jpeg"


class _ScriptPlanner:
    def __init__(self, script):
        self.script = script
        self._idx = {}

    def plan(self, state, prompt, context):
        val = self.script[state]
        if isinstance(val, list):
            i = self._idx.get(state, 0)
            out = val[min(i, len(val) - 1)]
            self._idx[state] = i + 1
            return out
        return val


class _FakeFSMEngine(CortexFSMEngine):
    def _run_routing_state(self, context):
        context.route_trace = ["mock_step"]
        context.route_result = {"status": "success", "route_only": True}
        return CortexState.VISION_ACT


class TestInstructionParser(unittest.TestCase):
    def test_parse_single(self):
        out = parse_instructions('TAP 100 200')
        self.assertEqual(out[0].op, "TAP")
        self.assertEqual(out[0].args, ["100", "200"])

    def test_parse_quotes(self):
        out = parse_instructions('INPUT "hello world"')
        self.assertEqual(out[0].op, "INPUT")
        self.assertEqual(out[0].args, ["hello world"])

    def test_not_allowed(self):
        out = parse_instructions("WAIT 100")
        with self.assertRaises(InstructionError):
            validate_allowed(out, {"TAP"})


class TestFSMRuntime(unittest.TestCase):
    def test_happy_path(self):
        client = _DummyClient()
        planner = _ScriptPlanner(
            {
                CortexState.APP_RESOLVE: "SET_APP com.test.app",
                CortexState.ROUTE_PLAN: "ROUTE com.test.app home__main",
                CortexState.VISION_ACT: ["TAP 123 456", "DONE"],
            }
        )
        engine = _FakeFSMEngine(client=client, planner=planner)
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fp:
            json.dump({"package": "com.test.app", "pages": {"home__main": {}}, "transitions": []}, fp)
            map_path = fp.name
        result = engine.run("open home and tap", map_path)
        self.assertEqual(result["status"], "success")
        self.assertIn((123, 456), client.taps)


if __name__ == "__main__":
    unittest.main(verbosity=2)
