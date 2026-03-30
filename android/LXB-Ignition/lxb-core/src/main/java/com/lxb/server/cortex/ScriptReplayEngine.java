package com.lxb.server.cortex;

import com.lxb.server.execution.ExecutionEngine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic script replay engine. Executes a saved sequence of
 * TAP/SWIPE/INPUT/WAIT/BACK operations without calling any LLM/VLM.
 *
 * Coordinates are in normalized [0,1000] space, mapped to physical pixels
 * using device screen dimensions.
 */
public class ScriptReplayEngine {

    private static final int INPUT_METHOD_ADB = 0;
    private static final int INPUT_METHOD_CLIPBOARD = 1;
    private static final long STEP_SETTLE_MS = 800L;

    private final ExecutionEngine execution;
    private final TraceLogger trace;

    public ScriptReplayEngine(ExecutionEngine execution,
                              TraceLogger trace) {
        this.execution = execution;
        this.trace = trace;
    }

    public static class ReplayResult {
        public final boolean success;
        public final String reason;
        public final int stepsExecuted;

        public ReplayResult(boolean success, String reason, int stepsExecuted) {
            this.success = success;
            this.reason = reason;
            this.stepsExecuted = stepsExecuted;
        }
    }

    /**
     * Replay a saved script. Launches the target app, then executes each
     * step in order.
     *
     * @param script   The script JSON map (schema_version=script.v1)
     * @param taskId   For trace logging
     * @param screenW  Device screen width in physical pixels
     * @param screenH  Device screen height in physical pixels
     * @return ReplayResult indicating success/failure
     */
    @SuppressWarnings("unchecked")
    public ReplayResult replay(Map<String, Object> script, String taskId,
                               int screenW, int screenH) {
        if (script == null || script.isEmpty()) {
            return new ReplayResult(false, "empty_script", 0);
        }

        String packageName = stringOrEmpty(script.get("package_name"));
        Object stepsObj = script.get("steps");
        if (!(stepsObj instanceof List)) {
            return new ReplayResult(false, "no_steps_in_script", 0);
        }
        List<Object> steps = (List<Object>) stepsObj;
        if (steps.isEmpty()) {
            return new ReplayResult(false, "empty_steps", 0);
        }

        Map<String, Object> beginEv = new LinkedHashMap<>();
        beginEv.put("task_id", taskId);
        beginEv.put("package_name", packageName);
        beginEv.put("step_count", steps.size());
        beginEv.put("script_key", stringOrEmpty(script.get("script_key")));
        trace.event("fsm_script_replay_begin", beginEv);

        if (!packageName.isEmpty()) {
            boolean launched = launchApp(packageName, taskId);
            if (!launched) {
                return new ReplayResult(false, "app_launch_failed:" + packageName, 0);
            }
            sleepQuiet(1200L);
        }

        int executed = 0;
        for (int i = 0; i < steps.size(); i++) {
            Object stepObj = steps.get(i);
            if (!(stepObj instanceof Map)) {
                return new ReplayResult(false, "invalid_step_at:" + i, executed);
            }
            Map<String, Object> step = (Map<String, Object>) stepObj;
            String op = stringOrEmpty(step.get("op")).toUpperCase();

            List<String> args = new ArrayList<>();
            Object argsObj = step.get("args");
            if (argsObj instanceof List) {
                for (Object a : (List<Object>) argsObj) {
                    args.add(a != null ? String.valueOf(a) : "");
                }
            }

            Object delayObj = step.get("delay_before");
            if (delayObj instanceof Number) {
                long delayMs = ((Number) delayObj).longValue();
                if (delayMs > 0) {
                    sleepQuiet(delayMs);
                }
            }

            boolean ok;
            try {
                switch (op) {
                    case "TAP":
                        ok = execTap(args, taskId, screenW, screenH);
                        break;
                    case "SWIPE":
                        ok = execSwipe(args, taskId, screenW, screenH);
                        break;
                    case "INPUT":
                        ok = execInput(args, taskId);
                        break;
                    case "WAIT":
                        ok = execWait(args, taskId);
                        break;
                    case "BACK":
                        ok = execBack(taskId);
                        break;
                    case "DONE":
                        Map<String, Object> doneEv = new LinkedHashMap<>();
                        doneEv.put("task_id", taskId);
                        doneEv.put("step", i);
                        trace.event("fsm_script_replay_done_step", doneEv);
                        executed++;
                        Map<String, Object> successEv = new LinkedHashMap<>();
                        successEv.put("task_id", taskId);
                        successEv.put("steps_executed", executed);
                        trace.event("fsm_script_replay_success", successEv);
                        return new ReplayResult(true, "done", executed);
                    default:
                        return new ReplayResult(false, "unsupported_op:" + op + "_at:" + i, executed);
                }
            } catch (Exception e) {
                Map<String, Object> errEv = new LinkedHashMap<>();
                errEv.put("task_id", taskId);
                errEv.put("step", i);
                errEv.put("op", op);
                errEv.put("error", String.valueOf(e));
                trace.event("fsm_script_replay_step_error", errEv);
                return new ReplayResult(false, "step_error:" + op + ":" + e.getMessage(), executed);
            }

            if (!ok) {
                Map<String, Object> failEv = new LinkedHashMap<>();
                failEv.put("task_id", taskId);
                failEv.put("step", i);
                failEv.put("op", op);
                trace.event("fsm_script_replay_step_failed", failEv);
                return new ReplayResult(false, "step_failed:" + op + "_at:" + i, executed);
            }
            executed++;

            if (!"WAIT".equals(op)) {
                sleepQuiet(STEP_SETTLE_MS);
            }
        }

        Map<String, Object> successEv = new LinkedHashMap<>();
        successEv.put("task_id", taskId);
        successEv.put("steps_executed", executed);
        trace.event("fsm_script_replay_success", successEv);
        return new ReplayResult(true, "all_steps_completed", executed);
    }

    private boolean execTap(List<String> args, String taskId, int screenW, int screenH) {
        if (args.size() < 2) return false;
        double xf = Double.parseDouble(args.get(0));
        double yf = Double.parseDouble(args.get(1));
        int x = mapNormalized(xf, screenW);
        int y = mapNormalized(yf, screenH);

        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) x);
        buf.putShort((short) y);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId);
        ev.put("x", x);
        ev.put("y", y);
        trace.event("exec_tap_start", ev);
        execution.handleTap(buf.array());
        trace.event("exec_tap_done", ev);
        return true;
    }

    private boolean execSwipe(List<String> args, String taskId, int screenW, int screenH) {
        if (args.size() < 5) return false;
        int x1 = mapNormalized(Double.parseDouble(args.get(0)), screenW);
        int y1 = mapNormalized(Double.parseDouble(args.get(1)), screenH);
        int x2 = mapNormalized(Double.parseDouble(args.get(2)), screenW);
        int y2 = mapNormalized(Double.parseDouble(args.get(3)), screenH);
        int dur = Integer.parseInt(args.get(4));

        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) x1);
        buf.putShort((short) y1);
        buf.putShort((short) x2);
        buf.putShort((short) y2);
        buf.putShort((short) dur);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId);
        ev.put("x1", x1);
        ev.put("y1", y1);
        ev.put("x2", x2);
        ev.put("y2", y2);
        ev.put("duration", dur);
        trace.event("exec_swipe_start", ev);
        execution.handleSwipe(buf.array());
        trace.event("exec_swipe_done", ev);
        sleepQuiet(1500L);
        return true;
    }

    private boolean execInput(List<String> args, String taskId) {
        if (args.isEmpty()) return false;
        String text = args.get(0);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId);
        ev.put("text", text);
        trace.event("exec_input_start", ev);

        int[] methods = containsNonAscii(text)
                ? new int[]{INPUT_METHOD_CLIPBOARD, INPUT_METHOD_ADB}
                : new int[]{INPUT_METHOD_ADB, INPUT_METHOD_CLIPBOARD};

        for (int method : methods) {
            byte[] resp = sendInputText(method, (byte) 0, (short) 0, (short) 0, (short) 0, text);
            int status = (resp != null && resp.length >= 1) ? (resp[0] & 0xFF) : 0;
            if (status == 1) {
                trace.event("exec_input_done", ev);
                return true;
            }
        }
        return false;
    }

    private boolean execWait(List<String> args, String taskId) {
        int ms = args.isEmpty() ? 1000 : Integer.parseInt(args.get(0));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId);
        ev.put("ms", ms);
        trace.event("exec_wait_start", ev);
        sleepQuiet(Math.max(0, ms));
        trace.event("exec_wait_done", ev);
        return true;
    }

    private boolean execBack(String taskId) {
        ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 4); // KEYCODE_BACK
        buf.put((byte) 2); // ACTION_CLICK
        buf.putInt(0);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", taskId);
        trace.event("exec_back_start", ev);
        execution.handleKeyEvent(buf.array());
        trace.event("exec_back_done", ev);
        return true;
    }

    private boolean launchApp(String packageName, String taskId) {
        try {
            byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(3 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) 0x00); // flags
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            byte[] resp = execution.handleLaunchApp(buf.array());
            boolean ok = resp != null && resp.length > 0 && (resp[0] & 0xFF) != 0;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", taskId);
            ev.put("package", packageName);
            ev.put("ok", ok);
            trace.event("script_replay_launch_app", ev);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private static int mapNormalized(double normalized, int screenDim) {
        if (screenDim <= 1) return (int) Math.round(normalized);
        int mapped = (int) Math.round((normalized / 1000.0d) * (double) (screenDim - 1));
        return Math.max(0, Math.min(screenDim - 1, mapped));
    }

    private byte[] sendInputText(int method, byte flags, short targetX, short targetY,
                                 short delayMs, String text) {
        byte[] textBytes = text != null ? text.getBytes(StandardCharsets.UTF_8) : new byte[0];
        short len = (short) textBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(10 + textBytes.length).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) method);
        buf.put(flags);
        buf.putShort(targetX);
        buf.putShort(targetY);
        buf.putShort(delayMs);
        buf.putShort(len);
        buf.put(textBytes);
        return execution.handleInputText(buf.array());
    }

    private static boolean containsNonAscii(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return true;
        }
        return false;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Extract step summaries from a script for display purposes.
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractStepSummaries(Map<String, Object> script) {
        List<String> result = new ArrayList<>();
        if (script == null) return result;
        Object stepsObj = script.get("steps");
        if (!(stepsObj instanceof List)) return result;
        for (Object o : (List<Object>) stepsObj) {
            if (o instanceof Map) {
                Map<String, Object> step = (Map<String, Object>) o;
                String raw = stringOrEmpty(step.get("raw"));
                if (!raw.isEmpty()) {
                    result.add(raw);
                } else {
                    String op = stringOrEmpty(step.get("op"));
                    result.add(op);
                }
            }
        }
        return result;
    }
}
