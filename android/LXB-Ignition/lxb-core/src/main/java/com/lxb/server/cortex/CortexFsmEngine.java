package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Java port of the Python CortexFSMEngine skeleton.
 *
 * Goal for now:
 * - Mirror the high-level FSM structure and state transitions:
 *   INIT -> APP_RESOLVE -> ROUTE_PLAN -> ROUTING -> VISION_ACT -> FINISH/FAIL
 * - Provide a Context object and run() signature compatible with Python:
 *   status/state/package_name/target_page/route_result/command_log/llm_history/lessons/reason
 * - Gradually fill internal behavior with end-side engines (LLM planner, routing, VLM actions).
 */
public class CortexFsmEngine {

    public enum State {
        INIT,
        APP_RESOLVE,
        ROUTE_PLAN,
        ROUTING,
        VISION_ACT,
        FINISH,
        FAIL
    }

    /**
     * Simple cancellation checker used by the FSM run loop. The implementation
     * is provided by CortexTaskManager so that a separate command can request
     * cancellation of the currently running task.
     */
    public interface CancellationChecker {
        boolean isCancelled();
    }

    /**
     * Execution context for a single Cortex automation task.
     * Mirrors the Python CortexContext dataclass in a minimal form.
     */
    public static class Context {
        public final String taskId;
        public String userTask = "";
        public String mapPath = null;
        public String startPage = null;

        public String selectedPackage = "";
        public String targetPage = "";

        public final List<String> routeTrace = new ArrayList<>();
        public final List<Map<String, Object>> commandLog = new ArrayList<>();
        public final Map<String, Object> routeResult = new LinkedHashMap<>();

        public String error = "";
        public final Map<String, Object> output = new LinkedHashMap<>();

        public int visionTurns = 0;
        public final List<Map<String, Object>> llmHistory = new ArrayList<>();
        public final List<String> lessons = new ArrayList<>();

        // Vision loop tracking (simplified port from Python)
        public String lastCommand = "";
        public int sameCommandStreak = 0;
        public String lastActivitySig = "";
        public int sameActivityStreak = 0;

        // INIT-related fields
        public final Map<String, Object> deviceInfo = new LinkedHashMap<>();
        public final Map<String, Object> currentActivity = new LinkedHashMap<>();
        public final List<Map<String, Object>> appCandidates = new ArrayList<>();
        public final List<Map<String, Object>> pageCandidates = new ArrayList<>();
        public final Map<String, Object> coordProbe = new LinkedHashMap<>();

        public Context(String taskId) {
            this.taskId = taskId;
        }
    }

    private final PerceptionEngine perception;
    private final ExecutionEngine execution;
    private final TraceLogger trace;
    private final LlmClient llmClient;
    private final MapManager mapManager;
    private final MapPromptPlanner mapPlanner;

    // Allowed ops per state, mirroring Python _ALLOWED_OPS
    private static final java.util.Set<String> VISION_ALLOWED_OPS = new java.util.HashSet<>();

    static {
        VISION_ALLOWED_OPS.add("TAP");
        VISION_ALLOWED_OPS.add("SWIPE");
        VISION_ALLOWED_OPS.add("INPUT");
        VISION_ALLOWED_OPS.add("WAIT");
        VISION_ALLOWED_OPS.add("BACK");
        VISION_ALLOWED_OPS.add("DONE");
        VISION_ALLOWED_OPS.add("FAIL");
    }

    public CortexFsmEngine(PerceptionEngine perception,
                           ExecutionEngine execution,
                           MapManager mapManager,
                           TraceLogger trace) {
        this.perception = perception;
        this.execution = execution;
        this.trace = trace;
        this.llmClient = new LlmClient();
        this.mapManager = mapManager;
        this.mapPlanner = new MapPromptPlanner(llmClient);
    }

    /**
     * Run a Cortex FSM task.
     *
     * For now this only wires state transitions; internal behaviors are stubs:
     * - INIT       -> APP_RESOLVE
     * - APP_RESOLVE: if package_name provided -> ROUTE_PLAN else FAIL
     * - ROUTE_PLAN : if target_page already set -> ROUTING else FAIL (placeholder)
     * - ROUTING    -> VISION_ACT (no real routing yet)
     * - VISION_ACT -> FINISH (no real vision actions yet)
     */
    public Map<String, Object> run(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort,
            String taskIdOverride,
            CancellationChecker cancellationChecker
    ) {
        String effectiveTaskId = (taskIdOverride != null && !taskIdOverride.isEmpty())
                ? taskIdOverride
                : java.util.UUID.randomUUID().toString();
        Context ctx = new Context(effectiveTaskId);
        ctx.userTask = userTask != null ? userTask : "";
        ctx.selectedPackage = packageName != null ? packageName : "";
        ctx.mapPath = mapPath;
        ctx.startPage = startPage;

        boolean enablePush = "push".equalsIgnoreCase(traceMode)
                && traceUdpPort != null
                && traceUdpPort.intValue() > 0;
        if (enablePush) {
            // Push to localhost; the Android front-end listens on this port.
            trace.setPushTarget("127.0.0.1", traceUdpPort.intValue(), ctx.taskId);
        }

        State state = State.INIT;

        try {
            for (int i = 0; i < 30; i++) {
                if (cancellationChecker != null && cancellationChecker.isCancelled()) {
                    ctx.error = "cancelled_by_user";
                    Map<String, Object> cancelEv = new LinkedHashMap<>();
                    cancelEv.put("task_id", ctx.taskId);
                    trace.event("fsm_task_cancelled", cancelEv);
                    state = State.FAIL;
                    break;
                }
                if (state == State.INIT) {
                    state = runInitState(ctx);
                    continue;
                }
                if (state == State.APP_RESOLVE) {
                    state = runAppResolveState(ctx);
                    continue;
                }
                if (state == State.ROUTE_PLAN) {
                    state = runRoutePlanState(ctx);
                    continue;
                }
                if (state == State.ROUTING) {
                    state = runRoutingState(ctx);
                    continue;
                }
                if (state == State.VISION_ACT) {
                    state = runVisionActState(ctx);
                    continue;
                }
                if (state == State.FINISH || state == State.FAIL) {
                    break;
                }
                // Safety: unknown state
                ctx.error = "unknown_state:" + state.name();
                state = State.FAIL;
                break;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", state == State.FINISH ? "success" : "failed");
            out.put("task_id", ctx.taskId);
            out.put("state", state.name());
            out.put("package_name", ctx.selectedPackage);
            out.put("target_page", ctx.targetPage != null ? ctx.targetPage : "");
            out.put("route_trace", new ArrayList<>(ctx.routeTrace));
            out.put("route_result", new LinkedHashMap<>(ctx.routeResult));
            out.put("command_log", new ArrayList<>(ctx.commandLog));
            out.put("llm_history", new ArrayList<>(ctx.llmHistory));
            out.put("lessons", new ArrayList<>(ctx.lessons));
            if (ctx.error != null && !ctx.error.isEmpty()) {
                out.put("reason", ctx.error);
            }
            if (!ctx.output.isEmpty()) {
                out.put("output", new LinkedHashMap<>(ctx.output));
            }
            return out;
        } finally {
            if (enablePush) {
                trace.clearPushTarget(ctx.taskId);
            }
        }
    }

    private State runInitState(Context ctx) {
        Map<String, Object> enterEv = new LinkedHashMap<>();
        enterEv.put("task_id", ctx.taskId);
        enterEv.put("state", State.INIT.name());
        enterEv.put("user_task", ctx.userTask);
        trace.event("fsm_state_enter", enterEv);

        // 1) Screen size (width/height/density), via PerceptionEngine.GET_SCREEN_SIZE
        int width = 0, height = 0, density = 0;
        try {
            byte[] resp = perception != null ? perception.handleGetScreenSize() : null;
            if (resp != null && resp.length >= 7) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                if (status != 0) {
                    width = buf.getShort() & 0xFFFF;
                    height = buf.getShort() & 0xFFFF;
                    density = buf.getShort() & 0xFFFF;
                }
            }
        } catch (Exception ignored) {
        }
        ctx.deviceInfo.put("width", width);
        ctx.deviceInfo.put("height", height);
        ctx.deviceInfo.put("density", density);

        // 2) Current activity, via PerceptionEngine.GET_ACTIVITY
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            boolean ok = false;
            String pkg = "";
            String act = "";
            if (resp != null && resp.length >= 5) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                ok = status != 0;
                if (resp.length >= 5) {
                    int pkgLen = buf.getShort() & 0xFFFF;
                    if (pkgLen >= 0 && buf.remaining() >= pkgLen + 2) {
                        byte[] pkgBytes = new byte[pkgLen];
                        buf.get(pkgBytes);
                        pkg = new String(pkgBytes, StandardCharsets.UTF_8);
                        int actLen = buf.getShort() & 0xFFFF;
                        if (actLen >= 0 && buf.remaining() >= actLen) {
                            byte[] actBytes = new byte[actLen];
                            buf.get(actBytes);
                            act = new String(actBytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", ok);
            ctx.currentActivity.put("package", pkg != null ? pkg : "");
            ctx.currentActivity.put("activity", act != null ? act : "");
        } catch (Exception e) {
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", false);
            ctx.currentActivity.put("package", "");
            ctx.currentActivity.put("activity", "");
        }

        // 3) App list (user apps) as candidates, via ExecutionEngine.LIST_APPS(filter=1)
        if (ctx.appCandidates.isEmpty()) {
            try {
                byte[] payload = new byte[]{0x01}; // filter=1 (user apps)
                byte[] resp = execution != null ? execution.handleListApps(payload) : null;
                if (resp != null && resp.length >= 3) {
                    ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                    byte status = buf.get();
                    int jsonLen = buf.getShort() & 0xFFFF;
                    if (status != 0 && jsonLen > 0 && buf.remaining() >= jsonLen) {
                        byte[] jsonBytes = new byte[jsonLen];
                        buf.get(jsonBytes);
                        String json = new String(jsonBytes, StandardCharsets.UTF_8);
                        Object parsed = Json.parse(json);
                        List<Map<String, Object>> candidates = normalizeAppCandidates(parsed);
                        int limit = Math.min(200, candidates.size());
                        ctx.appCandidates.addAll(candidates.subList(0, limit));
                    }
                }
            } catch (Exception ignored) {
                // Keep appCandidates empty on error.
            }
        }

        // 4) Coordinate probe: send a synthetic calibration image to the VLM-style endpoint
        //    and let it report corner coordinates, mirroring Python _probe_coordinate_space.
        Map<String, Object> probe = runCoordProbe(ctx);
        if (!probe.isEmpty()) {
            ctx.coordProbe.clear();
            ctx.coordProbe.putAll(probe);
            ctx.output.put("coord_probe", new LinkedHashMap<>(probe));
        }

        Map<String, Object> readyEv = new LinkedHashMap<>();
        readyEv.put("task_id", ctx.taskId);
        readyEv.put("device_info", new LinkedHashMap<>(ctx.deviceInfo));
        readyEv.put("current_activity", new LinkedHashMap<>(ctx.currentActivity));
        readyEv.put("app_candidates", ctx.appCandidates.size());
        readyEv.put("page_candidates", ctx.pageCandidates.size());
        readyEv.put("coord_probe", ctx.coordProbe.isEmpty() ? null : new LinkedHashMap<>(ctx.coordProbe));
        trace.event("fsm_init_ready", readyEv);

        return State.APP_RESOLVE;
    }

    /**
     * Coordinate-space probe, Java port of Python _probe_coordinate_space.
     *
     * This is a lightweight text-only variant: we ask the LLM for a synthetic 4-corner
     * coordinate system and treat it as the model's native range. If VLM is added later,
     * this can be upgraded to use a real image probe.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runCoordProbe(Context ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        // For now always enabled; can be made configurable if needed.

        // We do a simple logical probe: assume the model uses a 0~999 square.
        // This mirrors the Python behavior enough for _map_point_by_probe semantics.
        try {
            String prompt = "Coordinate Calibration Task.\n"
                    + "You are calibrating a logical 2D coordinate space for a mobile screen.\n"
                    + "Return ONLY JSON with this exact schema:\n"
                    + "{\"tl\":[x,y],\"tr\":[x,y],\"bl\":[x,y],\"br\":[x,y]}\n"
                    + "Rules:\n"
                    + "1) Use a square coordinate range where top-left is near (0,0).\n"
                    + "2) top-right has max X and min Y; bottom-left has min X and max Y; bottom-right has max X and max Y.\n"
                    + "3) Use integers, no decimals.\n"
                    + "4) Do NOT add markdown or comments.\n";

            LlmConfig cfg = LlmConfig.loadDefault();
            String raw = llmClient.chatOnce(cfg, null, prompt);

            Map<String, Object> parsed = extractJsonObjectFromText(raw);
            if (parsed.isEmpty()) {
                return result;
            }
            Map<String, List<Number>> pts = new LinkedHashMap<>();
            for (String key : new String[]{"tl", "tr", "bl", "br"}) {
                Object v = parsed.get(key);
                if (!(v instanceof List)) {
                    return result;
                }
                List<?> arr = (List<?>) v;
                if (arr.size() < 2) {
                    return result;
                }
                Number x = toNumber(arr.get(0));
                Number y = toNumber(arr.get(1));
                if (x == null || y == null) {
                    return result;
                }
                List<Number> pair = new ArrayList<>(2);
                pair.add(x);
                pair.add(y);
                pts.put(key, pair);
            }

            double xMin = (pts.get("tl").get(0).doubleValue() + pts.get("bl").get(0).doubleValue()) / 2.0;
            double xMax = (pts.get("tr").get(0).doubleValue() + pts.get("br").get(0).doubleValue()) / 2.0;
            double yMin = (pts.get("tl").get(1).doubleValue() + pts.get("tr").get(1).doubleValue()) / 2.0;
            double yMax = (pts.get("bl").get(1).doubleValue() + pts.get("br").get(1).doubleValue()) / 2.0;
            double spanX = Math.max(1e-6, xMax - xMin);
            double spanY = Math.max(1e-6, yMax - yMin);

            double maxX = Math.max(
                    Math.max(pts.get("tl").get(0).doubleValue(), pts.get("tr").get(0).doubleValue()),
                    Math.max(pts.get("bl").get(0).doubleValue(), pts.get("br").get(0).doubleValue())
            );
            double maxY = Math.max(
                    Math.max(pts.get("tl").get(1).doubleValue(), pts.get("tr").get(1).doubleValue()),
                    Math.max(pts.get("bl").get(1).doubleValue(), pts.get("br").get(1).doubleValue())
            );

            Map<String, Object> points = new LinkedHashMap<>();
            for (Map.Entry<String, List<Number>> e : pts.entrySet()) {
                List<Number> p = e.getValue();
                List<Double> coord = new ArrayList<>(2);
                coord.add(p.get(0).doubleValue());
                coord.add(p.get(1).doubleValue());
                points.put(e.getKey(), coord);
            }

            result.put("max_x", round4(maxX));
            result.put("max_y", round4(maxY));
            result.put("x_min", round4(xMin));
            result.put("x_max", round4(xMax));
            result.put("y_min", round4(yMin));
            result.put("y_max", round4(yMax));
            result.put("span_x", round4(spanX));
            result.put("span_y", round4(spanY));
            result.put("points", points);
            Map<String, Object> probeSize = new LinkedHashMap<>();
            probeSize.put("width", 1000);
            probeSize.put("height", 1000);
            result.put("probe_size", probeSize);

            Map<String, Object> ev = new LinkedHashMap<>(result);
            ev.put("task_id", ctx.taskId);
            trace.event("coord_probe_done", ev);
            return result;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("reason", String.valueOf(e));
            trace.event("coord_probe_failed", ev);
            return result;
        }
    }

    private static Number toNumber(Object o) {
        if (o instanceof Number) {
            return (Number) o;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeAppCandidates(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            List<?> arr = (List<?>) raw;
            for (Object item : arr) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String pkg = stringOrEmpty(m.get("package"));
                    if (pkg.isEmpty()) continue;
                    String name = stringOrEmpty(m.get("name"));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("package", pkg);
                    row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                    out.add(row);
                    continue;
                }
                String pkg = stringOrEmpty(item);
                if (pkg.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        } else if (raw instanceof Map) {
            // Fallback: a single map, try to interpret as one app entry.
            Map<String, Object> m = (Map<String, Object>) raw;
            String pkg = stringOrEmpty(m.get("package"));
            if (!pkg.isEmpty()) {
                String name = stringOrEmpty(m.get("name"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("package", pkg);
                row.put("name", !name.isEmpty() ? name : pkg.substring(pkg.lastIndexOf('.') + 1));
                out.add(row);
            }
        }
        return out;
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /**
     * Build LLM prompt for APP_RESOLVE, similar in spirit to Python PromptBuilder(APP_RESOLVE).
     */
    private String buildAppResolvePrompt(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : ctx.appCandidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            String pkg = stringOrEmpty(c.get("package"));
            String name = stringOrEmpty(c.get("name"));
            if (pkg.isEmpty()) continue;
            row.put("package", pkg);
            row.put("name", name);
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apps", rows);

        StringBuilder sb = new StringBuilder();
        sb.append("You are an assistant that selects the best Android app to handle a task.\n");
        sb.append("User task (Chinese or English):\n");
        sb.append(ctx.userTask != null ? ctx.userTask : "").append("\n\n");
        sb.append("Installed apps (JSON array with {\"package\",\"name\"}):\n");
        sb.append(Json.stringify(payload)).append("\n\n");
        sb.append("Output JSON only, no extra text:\n");
        sb.append("{\"package_name\":\"one_package_from_apps\"}\n");
        sb.append("Rules:\n");
        sb.append("1) package_name MUST be exactly one of the \"package\" values above.\n");
        sb.append("2) If the task clearly refers to a specific brand (e.g., Bilibili, Taobao), map it to that app.\n");
        sb.append("3) If ambiguous, choose the app that typical users would most likely use.\n");
        sb.append("4) Do NOT explain, do NOT add markdown, do NOT add comments.\n");
        return sb.toString();
    }

    /**
     * System prompt for APP_RESOLVE to mirror Python-side LLM usage:
     * - clearly separates system role from user prompt
     * - enforces JSON-only output with package_name field.
     */
    private String buildAppResolveSystemPrompt() {
        return "You are an assistant that selects the best Android app to handle a task.\n"
                + "You MUST output strict JSON only with a single field: package_name.\n"
                + "Do not output markdown or any extra commentary.";
    }

    /**
     * Extract package_name from LLM JSON response.
     */
    @SuppressWarnings("unchecked")
    private String extractPackageFromResponse(String raw) {
        Map<String, Object> obj = extractJsonObjectFromText(raw);
        if (obj.isEmpty()) {
            return "";
        }
        String pkg = stringOrEmpty(obj.get("package_name"));
        if (pkg.isEmpty()) {
            pkg = stringOrEmpty(obj.get("package"));
        }
        return pkg;
    }

    /**
     * Best-effort extraction of a JSON object from arbitrary text.
     *
     * Mirrors the Python-side _extract_json_object helper and the Java MapPromptPlanner
     * implementation so that we can recover even when the model wraps JSON in prose
     * or markdown/code fences.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractJsonObjectFromText(String text) {
        String s = text != null ? text.trim() : "";
        if (s.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        // 1) Direct parse as an object.
        try {
            Map<String, Object> obj = Json.parseObject(s);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }

        // 2) Fallback: slice between first '{' and last '}' and parse that.
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start) {
            return new LinkedHashMap<String, Object>();
        }
        String slice = s.substring(start, end + 1);
        try {
            Map<String, Object> obj = Json.parseObject(slice);
            if (obj != null) {
                return obj;
            }
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<String, Object>();
    }

    // ===== Instruction parsing (Java port of fsm_instruction.py) =====
    private static class Instruction {
        final String op;
        final List<String> args;
        final String raw;

        Instruction(String op, List<String> args, String raw) {
            this.op = op;
            this.args = args;
            this.raw = raw;
        }
    }

    private static class InstructionError extends Exception {
        InstructionError(String msg) {
            super(msg);
        }
    }

    private static final Map<String, int[]> INSTRUCTION_ARITY = new LinkedHashMap<>();

    static {
        INSTRUCTION_ARITY.put("SET_APP", new int[]{1, 1});
        INSTRUCTION_ARITY.put("ROUTE", new int[]{2, 2});
        INSTRUCTION_ARITY.put("TAP", new int[]{2, 2});
        INSTRUCTION_ARITY.put("SWIPE", new int[]{5, 5});
        INSTRUCTION_ARITY.put("INPUT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("WAIT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("BACK", new int[]{0, 0});
        INSTRUCTION_ARITY.put("DONE", new int[]{0, 0});
        INSTRUCTION_ARITY.put("FAIL", new int[]{1, 9999});
    }

    private static List<Instruction> parseInstructions(String text, int maxCommands) throws InstructionError {
        String[] lines = (text != null ? text : "").split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        if (nonEmpty.isEmpty()) {
            throw new InstructionError("empty instruction output");
        }
        if (nonEmpty.size() > maxCommands) {
            throw new InstructionError("too many instructions: " + nonEmpty.size() + " > " + maxCommands);
        }

        List<Instruction> out = new ArrayList<>();
        for (String line : nonEmpty) {
            List<String> parts = shellSplit(line);
            if (parts.isEmpty()) continue;
            String op = parts.get(0).trim().toUpperCase();
            List<String> args = parts.subList(1, parts.size());
            validateArity(op, args);
            out.add(new Instruction(op, new ArrayList<>(args), line));
        }
        if (out.isEmpty()) {
            throw new InstructionError("no valid instructions parsed");
        }
        return out;
    }

    private static void validateAllowed(List<Instruction> instructions, java.util.Set<String> allowedOps) throws InstructionError {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (String op : allowedOps) {
            allowed.add(op.toUpperCase());
        }
        for (Instruction ins : instructions) {
            if (!allowed.contains(ins.op)) {
                throw new InstructionError("op not allowed in this state: " + ins.op);
            }
        }
    }

    private static void validateArity(String op, List<String> args) throws InstructionError {
        int[] range = INSTRUCTION_ARITY.get(op);
        if (range == null) {
            throw new InstructionError("unknown instruction op: " + op);
        }
        int n = args.size();
        int min = range[0];
        int max = range[1];
        if (n < min || n > max) {
            String expected = (min == max) ? String.valueOf(min) : (min + ".." + max);
            throw new InstructionError(op + " expects " + expected + " args, got " + n);
        }
    }

    // Very small shell-like splitter that understands quotes for INPUT "text".
    private static List<String> shellSplit(String line) throws InstructionError {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'' || c == '\"') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuotes) {
            throw new InstructionError("invalid instruction quoting: " + line);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Extract content between XML-like tags, Java port of _extract_tag_text.
     */
    private static String extractTagText(String text, String tag) {
        if (text == null || text.isEmpty() || tag == null || tag.isEmpty()) {
            return "";
        }
        String pattern = "<" + tag + ">\\s*([\\s\\S]*?)\\s*</" + tag + ">";
        Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return "";
        }
        return m.group(1).trim();
    }

    /**
     * Normalize model output to DSL commands, Java port of Python _normalize_model_output.
     */
    private String normalizeModelOutput(String raw, State state, Context ctx) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty() || !text.startsWith("{")) {
            return text;
        }
        Map<String, Object> obj;
        try {
            obj = Json.parseObject(text);
        } catch (Exception e) {
            return text;
        }
        if (state == State.APP_RESOLVE) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = stringOrEmpty(obj.get("package"));
            }
            if (!pkg.isEmpty()) {
                return "SET_APP " + pkg;
            }
        }
        if (state == State.ROUTE_PLAN) {
            String pkg = stringOrEmpty(obj.get("package_name"));
            if (pkg.isEmpty()) {
                pkg = ctx.selectedPackage != null ? ctx.selectedPackage : "";
            }
            String target = stringOrEmpty(obj.get("target_page"));
            if (!pkg.isEmpty() && !target.isEmpty()) {
                return "ROUTE " + pkg + " " + target;
            }
        }
        if (state == State.VISION_ACT) {
            String action = stringOrEmpty(obj.get("action")).toUpperCase();
            if ("DONE".equals(action)) {
                return "DONE";
            }
            if ("BACK".equals(action)) {
                return "BACK";
            }
        }
        return text;
    }

    /**
     * Fallback when LLM is unavailable or returns invalid package.
     * Simple heuristic: match task text against app names, else pick first candidate.
     */
    private String heuristicPickPackage(Context ctx) {
        String task = ctx.userTask != null ? ctx.userTask.toLowerCase() : "";
        if (ctx.appCandidates.isEmpty()) return "";

        // Prefer name/label substring matches.
        for (Map<String, Object> c : ctx.appCandidates) {
            String pkg = stringOrEmpty(c.get("package"));
            String name = stringOrEmpty(c.get("name")).toLowerCase();
            if (!task.isEmpty() && !name.isEmpty() && task.contains(name)) {
                return pkg;
            }
        }
        // Fallback: first candidate.
        Map<String, Object> first = ctx.appCandidates.get(0);
        return stringOrEmpty(first.get("package"));
    }

    private State runAppResolveState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.APP_RESOLVE.name());
        ev.put("selected_package", ctx.selectedPackage);
        trace.event("fsm_state_enter", ev);
        // 1) Caller-specified package: accept directly and skip LLM.
        if (ctx.selectedPackage != null && !ctx.selectedPackage.trim().isEmpty()) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("source", "caller");
            trace.event("fsm_app_resolve_fixed_package", done);
            return State.ROUTE_PLAN;
        }

        // 2) Need app candidates collected in INIT.
        if (ctx.appCandidates.isEmpty()) {
            ctx.error = "app_resolve_no_candidates";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_no_candidates", fail);
            return State.FAIL;
        }

        // 3) Build prompt and call end-side LLM to choose package.
        String prompt = buildAppResolvePrompt(ctx);
        Map<String, Object> promptEv = new LinkedHashMap<>();
        promptEv.put("task_id", ctx.taskId);
        promptEv.put("state", State.APP_RESOLVE.name());
        promptEv.put("prompt", prompt);
        trace.event("llm_prompt_app_resolve", promptEv);

        String raw = null;
        String chosenPackage = "";
        boolean usedFallback = false;

        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            raw = llmClient.chatOnce(cfg, buildAppResolveSystemPrompt(), prompt);

            Map<String, Object> respEv = new LinkedHashMap<>();
            respEv.put("task_id", ctx.taskId);
            respEv.put("state", State.APP_RESOLVE.name());
            String snippet = raw != null && raw.length() > 800 ? raw.substring(0, 800) + "..." : raw;
            respEv.put("response", snippet != null ? snippet : "");
            trace.event("llm_response_app_resolve", respEv);

            chosenPackage = extractPackageFromResponse(raw);
        } catch (Exception e) {
            usedFallback = true;
            Map<String, Object> errEv = new LinkedHashMap<>();
            errEv.put("task_id", ctx.taskId);
            errEv.put("state", State.APP_RESOLVE.name());
            errEv.put("err", String.valueOf(e));
            trace.event("llm_error_app_resolve", errEv);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            usedFallback = true;
            chosenPackage = heuristicPickPackage(ctx);
        }

        if (chosenPackage == null || chosenPackage.trim().isEmpty()) {
            ctx.error = "app_resolve_failed:no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_app_resolve_failed", fail);
            return State.FAIL;
        }

        ctx.selectedPackage = chosenPackage.trim();
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", ctx.selectedPackage);
        done.put("source", usedFallback ? "fallback" : "llm");
        trace.event("fsm_app_resolve_done", done);

        return State.ROUTE_PLAN;
    }

    private State runRoutePlanState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTE_PLAN.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        // 1) Require selected package from APP_RESOLVE.
        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "route_plan_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        File mapFile = mapManager.getCurrentMapFile(pkg);

        // 2) No map for this package: keep pipeline, but mark as no-map mode.
        if (!mapFile.exists() || !mapFile.isFile() || mapFile.length() == 0) {
            ctx.mapPath = null;
            Map<String, Object> noMapEv = new LinkedHashMap<>();
            noMapEv.put("task_id", ctx.taskId);
            noMapEv.put("package", pkg);
            noMapEv.put("map_path", mapFile.getAbsolutePath());
            trace.event("fsm_route_plan_no_map", noMapEv);
            // 路由阶段将按照“无路径”模式执行：启动应用后直接进入 VISION_ACT。
            return State.ROUTING;
        }

        // 3) Map exists: load RouteMap and ask LLM to choose target_page.
        RouteMap routeMap;
        try {
            routeMap = RouteMap.loadFromFile(mapFile);
        } catch (Exception e) {
            ctx.mapPath = null;
            ctx.error = "route_plan_map_load_failed:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_map_load_failed", fail);
            // 不中断流水线，仍然进入 ROUTING，由 ROUTING 做无 map 模式处理。
            return State.ROUTING;
        }

        ctx.mapPath = mapFile.getAbsolutePath();

        String targetPage = null;
        boolean usedFallback = false;
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            MapPromptPlanner.PlanResult plan = mapPlanner.plan(cfg, ctx.userTask, routeMap);
            if (plan.packageName != null && !plan.packageName.trim().isEmpty()) {
                ctx.selectedPackage = plan.packageName.trim();
            }
            targetPage = plan.targetPage != null ? plan.targetPage.trim() : "";
            usedFallback = plan.usedFallback;

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("task_id", ctx.taskId);
            done.put("package", ctx.selectedPackage);
            done.put("target_page", targetPage);
            done.put("used_fallback", usedFallback);
            trace.event("fsm_route_plan_done", done);
        } catch (Exception e) {
            ctx.error = "route_plan_llm_error:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", pkg);
            fail.put("map_path", mapFile.getAbsolutePath());
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_llm_error", fail);
            return State.FAIL;
        }

        if (targetPage == null || targetPage.isEmpty()) {
            ctx.error = "route_plan_failed:no_target_page";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("package", ctx.selectedPackage);
            fail.put("map_path", ctx.mapPath);
            fail.put("reason", ctx.error);
            trace.event("fsm_route_plan_failed", fail);
            return State.FAIL;
        }

        ctx.targetPage = targetPage;
        return State.ROUTING;
    }

    private State runRoutingState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.ROUTING.name());
        ev.put("selected_package", ctx.selectedPackage);
        ev.put("target_page", ctx.targetPage);
        ev.put("map_path", ctx.mapPath);
        trace.event("fsm_state_enter", ev);

        if (ctx.selectedPackage == null || ctx.selectedPackage.trim().isEmpty()) {
            ctx.error = "routing_no_package";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("reason", ctx.error);
            trace.event("fsm_routing_failed", fail);
            return State.FAIL;
        }

        String pkg = ctx.selectedPackage.trim();
        boolean hasMap = ctx.mapPath != null && !ctx.mapPath.trim().isEmpty();
        RouteMap routeMap = null;
        List<RouteMap.Transition> path = null;
        String fromPage = "";
        String toPage = ctx.targetPage != null ? ctx.targetPage.trim() : "";

        // 1) If mapPath is available, try to load RouteMap and find BFS path.
        if (hasMap) {
            File mapFile = new File(ctx.mapPath);
            try {
                routeMap = RouteMap.loadFromFile(mapFile);
            } catch (Exception e) {
                hasMap = false;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("task_id", ctx.taskId);
                m.put("package", pkg);
                m.put("map_path", mapFile.getAbsolutePath());
                m.put("reason", "routing_map_load_failed:" + e.getMessage());
                trace.event("fsm_routing_map_load_failed", m);
            }
        }

        if (hasMap && routeMap != null && toPage != null && !toPage.isEmpty()) {
            int maxSteps = 64;

            if (ctx.startPage != null && !ctx.startPage.trim().isEmpty()) {
                fromPage = ctx.startPage.trim();
                path = routeMap.findPath(fromPage, toPage, maxSteps);
            } else {
                fromPage = routeMap.inferHomePage();
                if (fromPage == null || fromPage.isEmpty()) {
                    ctx.error = "routing_no_home_page";
                    Map<String, Object> fail = new LinkedHashMap<>();
                    fail.put("task_id", ctx.taskId);
                    fail.put("package", pkg);
                    fail.put("reason", ctx.error);
                    trace.event("fsm_routing_failed", fail);
                    return State.FAIL;
                }
                path = routeMap.findPathFromHome(toPage, maxSteps);
            }

            if (path == null) {
                ctx.error = "routing_no_path";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("from_page", fromPage);
                fail.put("to_page", toPage);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_no_path", fail);
                return State.FAIL;
            }
        } else {
            hasMap = false;
        }

        // 2) Launch app once for both map and no-map modes.
        boolean launchOk = launchAppForRouting(pkg);
        Map<String, Object> launchEv = new LinkedHashMap<>();
        launchEv.put("task_id", ctx.taskId);
        launchEv.put("package", pkg);
        launchEv.put("clear_task", true);
        launchEv.put("result", launchOk ? "ok" : "fail");
        trace.event("fsm_routing_launch_app", launchEv);

        // No-map mode: nothing to tap, route_result just records launch.
        if (!hasMap || path == null || path.isEmpty()) {
            ctx.routeTrace.clear();
            ctx.routeResult.clear();
            ctx.routeResult.put("ok", launchOk);
            ctx.routeResult.put("mode", "no_map");
            ctx.routeResult.put("package", pkg);
            ctx.routeResult.put("steps", new ArrayList<Map<String, Object>>());
            if (!launchOk) {
                ctx.error = "routing_launch_failed";
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                fail.put("package", pkg);
                fail.put("reason", ctx.error);
                trace.event("fsm_routing_failed", fail);
                return State.FAIL;
            }
            trace.event("fsm_routing_done", new LinkedHashMap<String, Object>() {{
                put("task_id", ctx.taskId);
                put("package", pkg);
                put("mode", "no_map");
                put("steps", 0);
            }});
            return State.VISION_ACT;
        }

        // Small settle delay before first tap.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
        }

        // 3) Execute route steps with locator resolution and tap.
        LocatorResolver resolver = new LocatorResolver(perception, trace);
        List<Map<String, Object>> stepSummaries = new ArrayList<>();
        boolean allOk = true;
        int index = 0;

        for (RouteMap.Transition t : path) {
            Map<String, Object> stepEv = new LinkedHashMap<>();
            stepEv.put("task_id", ctx.taskId);
            stepEv.put("package", pkg);
            stepEv.put("from_page", t.fromPage);
            stepEv.put("to_page", t.toPage);
            stepEv.put("index", index);
            stepEv.put("description", t.description);
            trace.event("fsm_routing_step_start", stepEv);

            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", index);
            step.put("from", t.fromPage);
            step.put("to", t.toPage);
            step.put("description", t.description);

            String result = "ok";
            String reason = "";
            String pickedStage = "";
            List<Object> pickedBounds = null;

            try {
                Locator locator = t.action != null ? t.action.locator : null;
                if (locator == null) {
                    result = "resolve_fail";
                    reason = "missing_locator";
                    allOk = false;
                } else {
                    ResolvedNode node = resolver.resolve(locator);
                    pickedStage = node.pickedStage;
                    pickedBounds = node.bounds.toList();

                    int cx = (node.bounds.left + node.bounds.right) / 2;
                    int cy = (node.bounds.top + node.bounds.bottom) / 2;

                    ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                    tapPayload.putShort((short) cx);
                    tapPayload.putShort((short) cy);
                    byte[] resp = execution.handleTap(tapPayload.array());

                    step.put("tap_resp_len", resp != null ? resp.length : 0);
                }
            } catch (Exception e) {
                allOk = false;
                String msg = String.valueOf(e);
                if (result.startsWith("resolve")) {
                    result = "resolve_fail";
                } else {
                    result = "tap_fail";
                }
                reason = msg;
            }

            step.put("picked_stage", pickedStage);
            if (pickedBounds != null) {
                step.put("picked_bounds", pickedBounds);
            }
            step.put("result", result);
            step.put("reason", reason);

            trace.event("fsm_routing_step_end", step);
            stepSummaries.add(step);

            if (!"ok".equals(result)) {
                break;
            }
            index++;

            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
        }

        // 4) Populate context route_result and route_trace.
        ctx.routeTrace.clear();
        if (fromPage != null && !fromPage.isEmpty()) {
            ctx.routeTrace.add(fromPage);
        }
        for (RouteMap.Transition t : path) {
            if (t.toPage != null && !t.toPage.isEmpty()) {
                ctx.routeTrace.add(t.toPage);
            }
        }

        ctx.routeResult.clear();
        ctx.routeResult.put("ok", allOk);
        ctx.routeResult.put("package", pkg);
        ctx.routeResult.put("from_page", fromPage);
        ctx.routeResult.put("to_page", toPage);
        ctx.routeResult.put("steps", stepSummaries);
        if (!allOk) {
            ctx.routeResult.put("reason", "step_failed");
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("task_id", ctx.taskId);
        done.put("package", pkg);
        done.put("from_page", fromPage);
        done.put("to_page", toPage);
        done.put("ok", allOk);
        done.put("steps", stepSummaries.size());
        trace.event("fsm_routing_done", done);

        if (!allOk) {
            ctx.error = "routing_step_failed";
            return State.FAIL;
        }
        return State.VISION_ACT;
    }

    /**
     * Launch app for routing, Java port of Python RouteThenActCortex._execute_route launch step.
     * Always uses CLEAR_TASK flag so each route starts from a clean task stack.
     */
    private boolean launchAppForRouting(String packageName) {
        try {
            byte[] pkgBytes = packageName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(1 + 2 + pkgBytes.length).order(ByteOrder.BIG_ENDIAN);
            int flags = 0x01; // CLEAR_TASK
            buf.put((byte) flags);
            buf.putShort((short) pkgBytes.length);
            buf.put(pkgBytes);
            byte[] resp = execution != null ? execution.handleLaunchApp(buf.array()) : null;
            boolean ok = resp != null && resp.length > 0 && resp[0] == 0x01;
            if (!ok) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("package", packageName);
                ev.put("status", resp != null && resp.length > 0 ? (int) resp[0] : 0);
                trace.event("fsm_routing_launch_status", ev);
            }
            return ok;
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", packageName);
            ev.put("err", String.valueOf(e));
            trace.event("fsm_routing_launch_err", ev);
            return false;
        }
    }

    /**
     * Refresh current activity info, a minimal port of Python _refresh_activity.
     */
    private void refreshActivity(Context ctx) {
        try {
            byte[] resp = perception != null ? perception.handleGetActivity() : null;
            boolean ok = false;
            String pkg = "";
            String act = "";
            if (resp != null && resp.length >= 5) {
                ByteBuffer buf = ByteBuffer.wrap(resp).order(ByteOrder.BIG_ENDIAN);
                byte status = buf.get();
                ok = status != 0;
                int pkgLen = buf.getShort() & 0xFFFF;
                if (pkgLen >= 0 && buf.remaining() >= pkgLen + 2) {
                    byte[] pkgBytes = new byte[pkgLen];
                    buf.get(pkgBytes);
                    pkg = new String(pkgBytes, StandardCharsets.UTF_8);
                    int actLen = buf.getShort() & 0xFFFF;
                    if (actLen >= 0 && buf.remaining() >= actLen) {
                        byte[] actBytes = new byte[actLen];
                        buf.get(actBytes);
                        act = new String(actBytes, StandardCharsets.UTF_8);
                    }
                }
            }
            ctx.currentActivity.clear();
            ctx.currentActivity.put("ok", ok);
            ctx.currentActivity.put("package", pkg != null ? pkg : "");
            ctx.currentActivity.put("activity", act != null ? act : "");

            String sig = stringOrEmpty(ctx.currentActivity.get("package")) + "/" + stringOrEmpty(ctx.currentActivity.get("activity"));
            if (sig.equals(ctx.lastActivitySig)) {
                ctx.sameActivityStreak += 1;
            } else {
                ctx.sameActivityStreak = 1;
                ctx.lastActivitySig = sig;
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("current_activity", new LinkedHashMap<>(ctx.currentActivity));
            ev.put("same_activity_streak", ctx.sameActivityStreak);
            trace.event("activity_refreshed", ev);
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("err", String.valueOf(e));
            trace.event("activity_refresh_failed", ev);
        }
    }

    /**
     * Result of extracting <command> and structured vision_analysis tags from model output.
     */
    private static class ExtractResult {
        final String commandText;
        final Map<String, Object> structured;

        ExtractResult(String commandText, Map<String, Object> structured) {
            this.commandText = commandText != null ? commandText : "";
            this.structured = structured != null ? structured : new LinkedHashMap<String, Object>();
        }
    }

    /**
     * Extract <command> and structured fields from a VISION_ACT response.
     * Java port of Python _extract_structured_command for CortexState.VISION_ACT.
     */
    private ExtractResult extractStructuredCommandForVision(String raw) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty()) {
            return new ExtractResult("", new LinkedHashMap<String, Object>());
        }

        String cmd = extractTagText(text, "command");
        if (cmd.isEmpty()) {
            // No command tag – keep raw text as command and no structured fields.
            return new ExtractResult(text, new LinkedHashMap<String, Object>());
        }

        String rootContent = extractTagText(text, "vision_analysis");
        if (rootContent.isEmpty()) {
            // Soft fallback: command only if top-level root is missing.
            return new ExtractResult(cmd.trim(), new LinkedHashMap<String, Object>());
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("root", "vision_analysis");
        String[] names = new String[]{
                "page_state",
                "step_review",
                "reflection",
                "next_step_reasoning",
                "completion_gate",
                "done_confirm",
                "lesson"
        };
        for (String name : names) {
            String fv = extractTagText(rootContent, name);
            if (!fv.isEmpty()) {
                fields.put(name, fv.trim());
            }
        }
        return new ExtractResult(cmd.trim(), fields);
    }

    /**
     * Collect lesson from structured vision output, Java port of Python _collect_lesson.
     */
    private void collectLesson(Context ctx, Map<String, Object> structured) {
        if (structured == null) {
            return;
        }
        Object val = structured.get("lesson");
        String raw = val == null ? "" : String.valueOf(val).trim();
        if (raw.isEmpty()) {
            return;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("n/a") || lower.equals("na")
                || lower.equals("null") || lower.equals("no lesson")) {
            return;
        }
        // Keep lesson concise to avoid prompt bloat.
        String lesson;
        if (raw.length() <= 180) {
            lesson = raw;
        } else {
            lesson = raw.substring(0, 180).replaceAll("\\s+$", "") + "...";
        }
        if (ctx.lessons.contains(lesson)) {
            return;
        }
        ctx.lessons.add(lesson);
    }

    /**
     * Append commands to context.commandLog and emit trace, Java port of _append_commands.
     */
    private void appendCommands(Context ctx, State state, List<Instruction> commands) {
        for (Instruction cmd : commands) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", state.name());
            row.put("op", cmd.op);
            row.put("args", new ArrayList<>(cmd.args));
            row.put("raw", cmd.raw);
            ctx.commandLog.add(row);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("state", state.name());
            ev.put("op", cmd.op);
            ev.put("args", new ArrayList<>(cmd.args));
            trace.event("fsm_command", ev);
        }
    }

    private State runVisionActState(Context ctx) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("task_id", ctx.taskId);
        ev.put("state", State.VISION_ACT.name());
        trace.event("fsm_state_enter", ev);

        // Turn limit (mirror Python FSMConfig.max_vision_turns, default 20)
        if (ctx.visionTurns >= 20) {
            ctx.error = "vision_turn_limit";
            return State.FAIL;
        }
        ctx.visionTurns += 1;

        // Optional one-time settle on first vision turn.
        if (ctx.visionTurns == 1) {
            try {
                Thread.sleep(600); // screenshot_settle_sec ~0.6s
            } catch (InterruptedException ignored) {
            }
        }

        // Screenshot: we still take it for parity with Python, but current LLM is text-only.
        byte[] shotResp = null;
        byte[] screenshotPng = null;
        int imageSize = 0;
        try {
            shotResp = perception != null ? perception.handleScreenshot() : null;
            if (shotResp != null && shotResp.length > 1 && shotResp[0] != 0x00) {
                // 协议约定：第 1 字节为状态，其余为 PNG 数据。
                screenshotPng = Arrays.copyOfRange(shotResp, 1, shotResp.length);
                imageSize = screenshotPng.length;
                Map<String, Object> readyEv = new LinkedHashMap<>();
                readyEv.put("task_id", ctx.taskId);
                readyEv.put("size", imageSize);
                readyEv.put("attached", true);
                trace.event("vision_screenshot_ready", readyEv);
            } else {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("task_id", ctx.taskId);
                trace.event("vision_screenshot_failed", fail);
            }
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("err", String.valueOf(e));
            trace.event("vision_screenshot_error", fail);
        }

        // Refresh activity + streak tracking
        refreshActivity(ctx);

        // Build full VISION_ACT prompt identical to Python PromptBuilder.build(..., VISION_ACT)
        String prompt = buildVisionPrompt(ctx);
        Map<String, Object> promptEv = new LinkedHashMap<>();
        promptEv.put("task_id", ctx.taskId);
        promptEv.put("state", State.VISION_ACT.name());
        promptEv.put("prompt", prompt);
        trace.event("llm_prompt_vision_act", promptEv);

        String raw;
        try {
            LlmConfig cfg = LlmConfig.loadDefault();
            // 如果是 /v1/responses 且支持 VLM，这里会把 screenshotPng 一并发给模型。
            raw = llmClient.chatOnce(cfg, null, prompt, screenshotPng);
        } catch (Exception e) {
            ctx.error = "planner_call_failed:VISION_ACT:" + e;
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("state", State.VISION_ACT.name());
            fail.put("err", String.valueOf(e));
            trace.event("planner_call_failed", fail);
            return State.FAIL;
        }

        Map<String, Object> respEv = new LinkedHashMap<>();
        respEv.put("task_id", ctx.taskId);
        respEv.put("state", State.VISION_ACT.name());
        String snippet = raw != null && raw.length() > 4000 ? raw.substring(0, 4000) + "..." : raw;
        respEv.put("response", snippet != null ? snippet : "");
        trace.event("llm_response_vision_act", respEv);

        // Extract <command> and structured tags (Java port of _extract_structured_command)
        ExtractResult er = extractStructuredCommandForVision(raw);
        String commandText = er.commandText;
        Map<String, Object> structured = er.structured;

        if (structured != null && !structured.isEmpty()) {
            Map<String, Object> hist = new LinkedHashMap<>();
            hist.put("state", State.VISION_ACT.name());
            hist.put("structured", structured);
            hist.put("command", commandText);
            ctx.llmHistory.add(hist);
            collectLesson(ctx, structured);
            Map<String, Object> stEv = new LinkedHashMap<>();
            stEv.put("task_id", ctx.taskId);
            stEv.put("state", State.VISION_ACT.name());
            stEv.put("data", structured);
            stEv.put("command", commandText);
            trace.event("llm_structured_vision_act", stEv);
        }

        // Normalize JSON outputs to DSL if needed (Java port of _normalize_model_output for VISION_ACT)
        String normalized = normalizeModelOutput(commandText != null && !commandText.isEmpty() ? commandText : raw, State.VISION_ACT, ctx);

        // Parse DSL instructions (single command per vision turn)
        List<Instruction> commands;
        try {
            commands = parseInstructions(normalized, 1);
            validateAllowed(commands, VISION_ALLOWED_OPS);
        } catch (InstructionError e) {
            ctx.error = "vision_instruction_invalid:" + e.getMessage();
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("error", e.getMessage());
            fail.put("raw", normalized);
            trace.event("vision_instruction_invalid", fail);
            return State.FAIL;
        }

        Instruction cmd0 = commands.get(0);
        String currentSig = cmd0.raw.trim();
        if (!currentSig.isEmpty() && currentSig.equals(ctx.lastCommand)) {
            ctx.sameCommandStreak += 1;
        } else {
            ctx.sameCommandStreak = 1;
        }
        ctx.lastCommand = currentSig;

        if (ctx.sameCommandStreak >= 3 && ctx.sameActivityStreak >= 3) {
            ctx.error = "vision_action_loop_detected:repeated_same_command";
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("task_id", ctx.taskId);
            fail.put("command", currentSig);
            fail.put("same_command_streak", ctx.sameCommandStreak);
            fail.put("same_activity_streak", ctx.sameActivityStreak);
            trace.event("vision_action_loop_detected", fail);
            return State.FAIL;
        }

        appendCommands(ctx, State.VISION_ACT, commands);

        for (Instruction cmd : commands) {
            if ("DONE".equals(cmd.op)) {
                return State.FINISH;
            }
            if ("FAIL".equals(cmd.op)) {
                String reason = cmd.args.isEmpty() ? "" : String.join(" ", cmd.args);
                ctx.error = "vision_fail:" + reason;
                return State.FAIL;
            }
            if (!execActionCommand(ctx, cmd)) {
                return State.FAIL;
            }
        }

        return State.VISION_ACT;
    }

    /**
     * Execute a single action command (TAP, SWIPE, INPUT, WAIT, BACK) using Java engines.
     * Java port of Python _exec_action_command.
     */
    private boolean execActionCommand(Context ctx, Instruction cmd) {
        try {
            if ("TAP".equals(cmd.op)) {
                double xf = Double.parseDouble(cmd.args.get(0));
                double yf = Double.parseDouble(cmd.args.get(1));
                int[] mapped = mapPointByProbe(ctx, xf, yf);
                int x = mapped[0];
                int y = mapped[1];

                int tx = x;
                int ty = y;

                // Jitter disabled for now (can be enabled later).
                ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
                buf.putShort((short) tx);
                buf.putShort((short) ty);
                trace.event("exec_tap_start", new LinkedHashMap<String, Object>() {{
                    put("task_id", ctx.taskId);
                    put("x", tx);
                    put("y", ty);
                }});
                execution.handleTap(buf.array());
                trace.event("exec_tap_done", new LinkedHashMap<String, Object>() {{
                    put("task_id", ctx.taskId);
                    put("x", tx);
                    put("y", ty);
                }});
                return true;
            }
            if ("SWIPE".equals(cmd.op)) {
                double x1f = Double.parseDouble(cmd.args.get(0));
                double y1f = Double.parseDouble(cmd.args.get(1));
                double x2f = Double.parseDouble(cmd.args.get(2));
                double y2f = Double.parseDouble(cmd.args.get(3));
                int dur = Integer.parseInt(cmd.args.get(4));

                int[] p1 = mapPointByProbe(ctx, x1f, y1f);
                int[] p2 = mapPointByProbe(ctx, x2f, y2f);
                int x1 = p1[0];
                int y1 = p1[1];
                int x2 = p2[0];
                int y2 = p2[1];

                ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
                buf.putShort((short) x1);
                buf.putShort((short) y1);
                buf.putShort((short) x2);
                buf.putShort((short) y2);
                buf.putShort((short) dur);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("x1", x1);
                ev.put("y1", y1);
                ev.put("x2", x2);
                ev.put("y2", y2);
                ev.put("duration", dur);
                trace.event("exec_swipe_start", ev);
                execution.handleSwipe(buf.array());
                trace.event("exec_swipe_done", ev);
                return true;
            }
            if ("INPUT".equals(cmd.op)) {
                String text = cmd.args.get(0);
                // Simple method: method=1, flags=0, targetX/targetY=0, delay=0.
                byte method = 0x01;
                byte flags = 0x00;
                short targetX = 0;
                short targetY = 0;
                short delayMs = 0;
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                short len = (short) textBytes.length;

                ByteBuffer buf = ByteBuffer.allocate(10 + textBytes.length).order(ByteOrder.BIG_ENDIAN);
                buf.put(method);
                buf.put(flags);
                buf.putShort(targetX);
                buf.putShort(targetY);
                buf.putShort(delayMs);
                buf.putShort(len);
                buf.put(textBytes);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("text", text);
                trace.event("exec_input_start", ev);
                byte[] resp = execution.handleInputText(buf.array());
                int status = (resp != null && resp.length >= 1) ? (resp[0] & 0xFF) : 0;
                ev.put("status", status);
                trace.event("exec_input_result", ev);
                return status != 0;
            }
            if ("WAIT".equals(cmd.op)) {
                int ms = Integer.parseInt(cmd.args.get(0));
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                ev.put("ms", ms);
                trace.event("exec_wait_start", ev);
                try {
                    Thread.sleep(Math.max(0, ms));
                } catch (InterruptedException ignored) {
                }
                trace.event("exec_wait_done", ev);
                return true;
            }
            if ("BACK".equals(cmd.op)) {
                // KEY_EVENT: keycode=4 (BACK), action=2 (click), meta=0.
                ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) 4);
                buf.put((byte) 2);
                buf.putInt(0);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("task_id", ctx.taskId);
                trace.event("exec_back_start", ev);
                execution.handleKeyEvent(buf.array());
                trace.event("exec_back_done", ev);
                return true;
            }

            ctx.error = "unsupported_action_op:" + cmd.op;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("op", cmd.op);
            trace.event("exec_action_unsupported", ev);
            return false;
        } catch (Exception e) {
            ctx.error = "action_exec_error:" + cmd.op + ":" + e;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("task_id", ctx.taskId);
            ev.put("op", cmd.op);
            ev.put("err", String.valueOf(e));
            trace.event("exec_action_error", ev);
            return false;
        }
    }

    /**
     * Build full VISION_ACT prompt, mirroring Python PromptBuilder.build(..., VISION_ACT).
     */
    private String buildVisionPrompt(Context ctx) {
        String activitySig = stringOrEmpty(ctx.currentActivity.get("package"))
                + "/" + stringOrEmpty(ctx.currentActivity.get("activity"));

        String deviceInfoJson = Json.stringify(ctx.deviceInfo);
        List<String> recentTrace = ctx.routeTrace.size() > 8
                ? ctx.routeTrace.subList(ctx.routeTrace.size() - 8, ctx.routeTrace.size())
                : new ArrayList<>(ctx.routeTrace);
        Map<String, Object> recent = new LinkedHashMap<>();
        recent.put("trace", recentTrace);
        String recentTraceJson = Json.stringify(recentTrace);

        // We do not yet maintain full llm_history/lessons; keep them empty arrays for now.
        String llmHistoryJson = Json.stringify(new ArrayList<>());
        String lessonsJson = Json.stringify(new ArrayList<>());

        StringBuilder sb = new StringBuilder();
        sb.append("State=VISION_ACT\n");
        sb.append("Output Contract:\n");
        sb.append("1) Output MUST follow the state XML-like format exactly.\n");
        sb.append("2) Output MUST contain exactly one <command>...</command>.\n");
        sb.append("3) No JSON, no markdown, no extra prose outside tags.\n");
        sb.append("4) If you cannot decide safely, command must be FAIL <reason>.\n");
        sb.append("Output Format (strict):\n");
        sb.append("<vision_analysis>\n");
        sb.append("<page_state>...</page_state>\n");
        sb.append("<step_review>...</step_review>\n");
        sb.append("<reflection>...</reflection>\n");
        sb.append("<next_step_reasoning>...</next_step_reasoning>\n");
        sb.append("<completion_gate>...</completion_gate>\n");
        sb.append("<done_confirm>...</done_confirm>\n");
        sb.append("<lesson>...</lesson>\n");
        sb.append("</vision_analysis>\n");
        sb.append("<command>DSL_COMMAND_HERE</command>\n");
        sb.append("DSL Semantics (only allowed ops):\n");
        sb.append("- TAP <x> <y>: tap at the target position.\n");
        sb.append("- SWIPE <x1> <y1> <x2> <y2> <duration_ms>: swipe from start to end with duration in ms.\n");
        sb.append("  SWIPE Rule: x1,y1 MUST be inside the main scrollable content container (not nav bar / title bar / edge controls).\n");
        sb.append("  Prefer small exploratory swipes near screen/content center.\n");
        sb.append("  Keep swipe distance small (about 8%~18% of screen height) unless a larger move is explicitly needed.\n");
        sb.append("  Prefer smoother/longer gesture duration (about 450~900ms).\n");
        sb.append("- INPUT \"<text>\": input text into focused field.\n");
        sb.append("- WAIT <ms>: wait milliseconds.\n");
        sb.append("- BACK: press Android back key once.\n");
        sb.append("- DONE: task complete.\n");
        sb.append("- FAIL <reason>: stop with explicit reason.\n");
        sb.append("Allowed: TAP, SWIPE, INPUT, WAIT, BACK, DONE, FAIL\n");
        sb.append("UserTask: ").append(ctx.userTask != null ? ctx.userTask : "").append("\n");
        sb.append("CurrentActivity(JSON): ").append(Json.stringify(ctx.currentActivity)).append("\n");
        sb.append("ActivitySignature: ").append(activitySig).append("\n");
        sb.append("SameActivityStreak: ").append(ctx.sameActivityStreak).append("\n");
        sb.append("LastCommand: ").append(ctx.lastCommand != null && !ctx.lastCommand.isEmpty() ? ctx.lastCommand : "<none>").append("\n");
        sb.append("SameCommandStreak: ").append(ctx.sameCommandStreak).append("\n");
        sb.append("RecentRouteTrace(JSON): ").append(recentTraceJson).append("\n");
        sb.append("RecentLLMHistory(JSON): ").append(llmHistoryJson).append("\n");
        sb.append("Lessons(JSON): ").append(lessonsJson).append("\n");
        sb.append("Screenshot: attached in this request.\n");
        sb.append("State Goal:\n");
        sb.append("Choose ONLY the next best single action.\n");
        sb.append("Important: one turn = one command. Do NOT output TAP then DONE in the same response.\n");
        sb.append("SWIPE Constraint: if using SWIPE, start point must be inside the scrollable content container, avoid starting from top/bottom bars.\n");
        sb.append("SWIPE Strategy: prefer small-distance swipe around center to probe nearby unseen content first.\n");
        sb.append("SWIPE Default Profile: distance about 8%~18% screen height, duration about 450~900ms.\n");
        sb.append("Reflection Contract (strict):\n");
        sb.append("1) You MUST review recent 3~6 steps, not only the last step.\n");
        sb.append("2) Use <step_review> to list per-step outcomes in order:\n");
        sb.append("   Step-1: command=..., page_change=..., result=...\n");
        sb.append("   Step-2: command=..., page_change=..., result=...\n");
        sb.append("3) In <reflection>, summarize what the agent is actually doing across steps,\n");
        sb.append("   identify repeated ineffective intents, and state what intent to avoid next.\n");
        sb.append("3.5) <lesson> is OPTIONAL. Only output it when there is a reusable cross-step rule.\n");
        sb.append("     Keep lesson concise (<= 1 sentence). If no stable lesson, omit it.\n");
        sb.append("4) <command> must be consistent with <reflection> (cannot repeat the avoided intent).\n");
        sb.append("5) If recent steps show repeated no-progress, prioritize changing action type.\n");
        sb.append("   Example: TAP -> small SWIPE near center / BACK / WAIT.\n");
        sb.append("If recent lessons indicate repeated failure, change action type (prefer SWIPE/BACK/WAIT over repeating same TAP intent).\n");
        sb.append("Anti-loop Rule: if activity/screen seems unchanged and last command already repeated, do NOT repeat same TAP.\n");
        sb.append("In that case, choose another action (SWIPE/WAIT/INPUT) or output FAIL with reason.\n");
        sb.append("DONE Gate (strict):\n");
        sb.append("You are NOT allowed to output DONE unless completion evidence and coverage verification both pass.\n");
        sb.append("Visible complete is NOT equal to global complete.\n");
        sb.append("Before DONE, <completion_gate> must include:\n");
        sb.append("- <completion_claim>: what goal is completed and visible evidence.\n");
        sb.append("- <coverage_check>: passed|failed + reason.\n");
        sb.append("- <verification_actions>: what checks were already executed.\n");
        sb.append("Coverage verification rule (universal):\n");
        sb.append("- If task may involve scrollable/unseen content, verify coverage first.\n");
        sb.append("- Coverage is considered passed only if one condition holds:\n");
        sb.append("  A) at least two exploratory swipes with no new actionable targets,\n");
        sb.append("  B) explicit end-of-list/end marker visible,\n");
        sb.append("  C) repeated explored states with no new targets after verification actions.\n");
        sb.append("If coverage_check is failed, command MUST NOT be DONE.\n");
        sb.append("DONE Confirm Contract (strict):\n");
        sb.append("You MUST provide <done_confirm> with all fields below:\n");
        sb.append("- <goal_match>: pass|fail + reason\n");
        sb.append("- <coverage_check>: pass|fail + reason\n");
        sb.append("- <new_info_check>: pass|fail + reason\n");
        sb.append("- <final_decision>: DONE|NOT_DONE\n");
        sb.append("Hard Rule: command=DONE is allowed ONLY when all three checks are pass and final_decision=DONE.\n");
        sb.append("If any check is fail, final_decision MUST be NOT_DONE and command MUST NOT be DONE.\n");
        sb.append("If finished now and DONE gate passed, output DONE only.\n");
        sb.append("Examples:\n");
        sb.append("<vision_analysis><page_state>当前在标签列表页，存在多个可操作入口</page_state>");
        sb.append("<step_review>Step-1: command=TAP 890 67, page_change=进入签到页, result=有效; ");
        sb.append("Step-2: command=TAP 720 420, page_change=无明显变化, result=尝试无效; ");
        sb.append("Step-3: command=TAP 720 420, page_change=无明显变化, result=重复无效</step_review>");
        sb.append("<reflection>最近步骤表明相同动作连续无效，当前策略停留在原地重复。");
        sb.append("应避免继续重复该动作意图，改为滑动探索更多可签入口。</reflection>");
        sb.append("<next_step_reasoning>先下滑一屏扩展可见区域，再选择新的可执行入口。</next_step_reasoning>");
        sb.append("<completion_gate><completion_claim>当前仅确认可见区域状态</completion_claim>");
        sb.append("<coverage_check>failed: 仍可能存在未展示内容</coverage_check>");
        sb.append("<verification_actions>尚未完成覆盖验证</verification_actions></completion_gate></vision_analysis>\n");
        sb.append("<command>SWIPE 640 800 640 700 650</command>\n");

        return sb.toString();
    }

    /**
     * Map logical (VLM) coordinates to screen pixels using coord_probe.
     * Java port of Python _map_point_by_probe.
     */
    private int[] mapPointByProbe(Context ctx, double xf, double yf) {
        int w = (int) (ctx.deviceInfo.getOrDefault("width", 0));
        int h = (int) (ctx.deviceInfo.getOrDefault("height", 0));
        Map<String, Object> probe = ctx.coordProbe;
        double maxX = toDouble(probe.get("max_x"));
        double maxY = toDouble(probe.get("max_y"));

        if (w <= 1 || h <= 1 || maxX <= 0.0 || maxY <= 0.0) {
            int rx = (int) Math.round(xf);
            int ry = (int) Math.round(yf);
            return new int[]{rx, ry};
        }

        // If looks like absolute screen coordinates that exceed model range, bypass scaling.
        if (xf >= 0.0 && xf <= (double) (w - 1) && yf >= 0.0 && yf <= (double) (h - 1)) {
            if (xf > maxX * 1.2 || yf > maxY * 1.2) {
                int rx = (int) Math.round(xf);
                int ry = (int) Math.round(yf);
                return new int[]{rx, ry};
            }
        }

        double xMin = toDouble(probe.get("x_min"));
        double xMax = toDouble(probe.get("x_max"));
        double yMin = toDouble(probe.get("y_min"));
        double yMax = toDouble(probe.get("y_max"));

        int rx;
        int ry;
        if (xMax > xMin && yMax > yMin) {
            double nx = (xf - xMin) / (xMax - xMin);
            double ny = (yf - yMin) / (yMax - yMin);
            rx = (int) Math.round(nx * (double) (w - 1));
            ry = (int) Math.round(ny * (double) (h - 1));
        } else {
            rx = (int) Math.round((xf / maxX) * (double) (w - 1));
            ry = (int) Math.round((yf / maxY) * (double) (h - 1));
        }

        rx = Math.max(0, Math.min(w - 1, rx));
        ry = Math.max(0, Math.min(h - 1, ry));
        return new int[]{rx, ry};
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
