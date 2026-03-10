package com.lxb.server.cortex;

import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.cortex.json.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Cortex bootstrap facade (Milestone 1):
 * - burn map (gzip json) to device local filesystem
 * - resolve locator using staged matching (self -> parent_rid -> bounds_hint)
 * - tap resolved node
 * - trace pull (jsonl ring buffer)
 *
 * This intentionally keeps the surface small; full end-side Cortex planner/LLM comes later.
 */
public class CortexFacade {

    private static final String TAG = "[LXB][Cortex]";

    private final ExecutionEngine executionEngine;
    private final PerceptionEngine perceptionEngine;

    private final MapManager mapManager;
    private final TraceLogger trace;
    private final LocatorResolver locatorResolver;

    public CortexFacade(PerceptionEngine perceptionEngine, ExecutionEngine executionEngine) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.mapManager = new MapManager();
        this.trace = new TraceLogger(300);
        this.locatorResolver = new LocatorResolver(perceptionEngine, trace);
    }

    public byte[] handleMapSetGz(byte[] payload) {
        // payload: package_len[2B] + package[UTF-8] + gzipped_map_json[...]
        try {
            if (payload.length < 2) {
                return err("payload too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            int pkgLen = buf.getShort() & 0xFFFF;
            if (pkgLen <= 0 || pkgLen > 256 || payload.length < 2 + pkgLen + 8) {
                return err("invalid package_len=" + pkgLen);
            }
            byte[] pkgBytes = new byte[pkgLen];
            buf.get(pkgBytes);
            String pkg = new String(pkgBytes, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) {
                return err("empty package");
            }

            byte[] gz = new byte[buf.remaining()];
            buf.get(gz);
            String json = gunzipToString(gz);
            mapManager.setCurrentMap(pkg, json);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("package", pkg);
            ev.put("bytes_gz", gz.length);
            ev.put("bytes_json", json.getBytes(StandardCharsets.UTF_8).length);
            trace.event("map_set", ev);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("package", pkg);
            out.put("path", mapManager.getCurrentMapFile(pkg).getAbsolutePath());
            out.put("size", json.length());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("map_set_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleMapGetInfo(byte[] payload) {
        // payload: package[UTF-8]
        try {
            String pkg = new String(payload, StandardCharsets.UTF_8).trim();
            if (pkg.isEmpty()) return err("empty package");
            File f = mapManager.getCurrentMapFile(pkg);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", f.exists());
            out.put("package", pkg);
            out.put("path", f.getAbsolutePath());
            out.put("exists", f.exists());
            out.put("bytes", f.exists() ? f.length() : 0);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            return err(String.valueOf(e));
        }
    }

    public byte[] handleResolveLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("bounds", node.bounds.toList());
            out.put("candidates", node.candidateCount);
            out.put("picked_stage", node.pickedStage);
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("resolve_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTapLocator(byte[] payload) {
        // payload: locator json (UTF-8)
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> obj = Json.parseObject(s);
            Locator locator = Locator.fromMap(obj);

            ResolvedNode node = locatorResolver.resolve(locator);
            int cx = (node.bounds.left + node.bounds.right) / 2;
            int cy = (node.bounds.top + node.bounds.bottom) / 2;

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("x", cx);
            ev.put("y", cy);
            ev.put("stage", node.pickedStage);
            ev.put("candidates", node.candidateCount);
            trace.event("tap", ev);

            // Reuse existing tap handler (binary protocol expects >HH).
            ByteBuffer tapPayload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
            tapPayload.putShort((short) cx);
            tapPayload.putShort((short) cy);
            byte[] resp = executionEngine.handleTap(tapPayload.array());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("tap_resp_len", resp != null ? resp.length : 0);
            out.put("bounds", node.bounds.toList());
            return ok(Json.stringify(out));
        } catch (Exception e) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("err", String.valueOf(e));
            trace.event("tap_err", ev);
            return err(String.valueOf(e));
        }
    }

    public byte[] handleTracePull(byte[] payload) {
        // payload: max_lines[2B] (optional, default 200)
        int max = 200;
        if (payload != null && payload.length >= 2) {
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            max = Math.max(1, Math.min(1000, buf.getShort() & 0xFFFF));
        }
        String data = trace.dumpLastLines(max);
        return ok(data);
    }

    private static String gunzipToString(byte[] gz) throws Exception {
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = gis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        gis.close();
        return baos.toString("UTF-8");
    }

    private static byte[] ok(String s) {
        // response is plain UTF-8 (JSON or JSONL)
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] err(String msg) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("ok", false);
        o.put("err", msg);
        return Json.stringify(o).getBytes(StandardCharsets.UTF_8);
    }
}
