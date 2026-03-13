package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * JSONL trace ring buffer for on-device Cortex bootstrap.
 *
 * "Pull" model: PC/debugger asks for last N lines, device returns JSONL.
 */
public class TraceLogger {

    private final ArrayDeque<String> ring;
    private final int capacity;
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    public TraceLogger(int capacity) {
        this.capacity = Math.max(10, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
    }

    public synchronized void event(String event, Map<String, Object> fields) {
        Map<String, Object> o = (fields != null)
                ? new LinkedHashMap<>(fields)
                : new LinkedHashMap<String, Object>();
        o.put("ts", tsFmt.format(new Date()));
        o.put("event", event);
        appendLine(Json.stringify(o));
    }

    public synchronized void event(String event) {
        event(event, null);
    }

    private void appendLine(String line) {
        while (ring.size() >= capacity) {
            ring.pollFirst();
        }
        ring.addLast(line);
    }

    public synchronized String dumpLastLines(int maxLines) {
        int n = Math.max(1, Math.min(maxLines, capacity));
        int skip = Math.max(0, ring.size() - n);

        // UDP datagrams have MTU limits; avoid returning an oversized JSONL blob that causes EMSGSIZE.
        // Use character length as an approximation of byte length (JSON content is mostly ASCII).
        final int maxChars = 60000;

        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean truncated = false;
        for (String line : ring) {
            if (i++ < skip) {
                continue;
            }
            if (sb.length() + line.length() + 1 > maxChars) {
                truncated = true;
                break;
            }
            sb.append(line).append('\n');
        }

        if (truncated) {
            // Append a simple marker line so upstream can tell the trace was truncated.
            String marker = Json.stringify(new LinkedHashMap<String, Object>() {{
                put("event", "trace_truncated");
                put("reason", "trace_dump_exceeds_limit");
            }});
            // Ensure marker itself also fits into the remaining budget.
            int remaining = maxChars - sb.length();
            if (marker.length() + 1 > remaining) {
                marker = marker.substring(0, Math.max(0, remaining - 1));
            }
            sb.append(marker).append('\n');
        }

        return sb.toString();
    }
}

