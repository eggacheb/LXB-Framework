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
 * "pull" model: PC/debugger asks for last N lines, device returns JSONL.
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
        Map<String, Object> o = (fields != null) ? new LinkedHashMap<>(fields) : new LinkedHashMap<String, Object>();
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
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String line : ring) {
            if (i++ < skip) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
