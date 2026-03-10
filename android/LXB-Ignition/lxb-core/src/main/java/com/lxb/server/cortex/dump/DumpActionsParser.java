package com.lxb.server.cortex.dump;

import com.lxb.server.cortex.Bounds;
import com.lxb.server.cortex.Util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for PerceptionEngine.handleDumpActions() binary payload.
 *
 * Format:
 *   version[1B] + count[2B] + nodes[count * 20B] + string_pool
 *
 * Node (20B):
 *   type[1B] + left[2B] + top[2B] + right[2B] + bottom[2B]
 *   class_id[1B] + text_id[2B] + res_id[1B] + desc_id[1B] + reserved[6B]
 *
 * String pool:
 *   short_count[1B] + short_entries[...] + long_count[2B] + long_entries[...]
 *   Short entry: len[1B] + data[UTF-8]
 *   Long entry:  len[2B] + data[UTF-8]
 *
 * Notes:
 * - class/res/desc IDs use the short pool (0xFF is NULL marker)
 * - text IDs use the long pool (0xFFFF is NULL marker)
 */
public class DumpActionsParser {

    public static class ActionNode {
        public final byte type;
        public final Bounds bounds;
        public final String className;
        public final String text;
        public final String resourceId;
        public final String contentDesc;

        public ActionNode(byte type, Bounds bounds, String className, String text, String resourceId, String contentDesc) {
            this.type = type;
            this.bounds = bounds;
            this.className = className;
            this.text = text;
            this.resourceId = resourceId;
            this.contentDesc = contentDesc;
        }
    }

    public static List<ActionNode> parse(byte[] payload) throws Exception {
        if (payload == null || payload.length < 3) {
            throw new IllegalArgumentException("dump_actions payload too short");
        }

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = buf.get() & 0xFF;
        if (version != 0x01) {
            throw new IllegalArgumentException("dump_actions unsupported version=" + version);
        }

        int count = buf.getShort() & 0xFFFF;
        int nodesBytes = count * 20;
        if (buf.remaining() < nodesBytes + 1 + 2) {
            throw new IllegalArgumentException("dump_actions truncated");
        }

        // Read node records first (IDs unresolved yet).
        byte[] nodeRaw = new byte[nodesBytes];
        buf.get(nodeRaw);

        // Parse string pool
        int shortCount = buf.get() & 0xFF;
        List<String> shortPool = new ArrayList<>(shortCount);
        for (int i = 0; i < shortCount; i++) {
            if (buf.remaining() < 1) throw new IllegalArgumentException("dump_actions short pool truncated");
            int len = buf.get() & 0xFF;
            if (buf.remaining() < len) throw new IllegalArgumentException("dump_actions short pool entry truncated");
            byte[] b = new byte[len];
            buf.get(b);
            shortPool.add(new String(b, StandardCharsets.UTF_8));
        }

        if (buf.remaining() < 2) throw new IllegalArgumentException("dump_actions long pool header truncated");
        int longCount = buf.getShort() & 0xFFFF;
        List<String> longPool = new ArrayList<>(longCount);
        for (int i = 0; i < longCount; i++) {
            if (buf.remaining() < 2) throw new IllegalArgumentException("dump_actions long pool entry header truncated");
            int len = buf.getShort() & 0xFFFF;
            if (buf.remaining() < len) throw new IllegalArgumentException("dump_actions long pool entry truncated");
            byte[] b = new byte[len];
            buf.get(b);
            longPool.add(new String(b, StandardCharsets.UTF_8));
        }

        // Decode nodes
        ByteBuffer nb = ByteBuffer.wrap(nodeRaw).order(ByteOrder.BIG_ENDIAN);
        List<ActionNode> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte type = nb.get();
            int left = nb.getShort() & 0xFFFF;
            int top = nb.getShort() & 0xFFFF;
            int right = nb.getShort() & 0xFFFF;
            int bottom = nb.getShort() & 0xFFFF;
            int classId = nb.get() & 0xFF;
            int textId = nb.getShort() & 0xFFFF;
            int resId = nb.get() & 0xFF;
            int descId = nb.get() & 0xFF;
            nb.position(nb.position() + 6); // reserved

            String cls = (classId == 0xFF) ? "" : safeGet(shortPool, classId);
            String text = (textId == 0xFFFF) ? "" : safeGet(longPool, textId);
            String rid = (resId == 0xFF) ? "" : safeGet(shortPool, resId);
            String desc = (descId == 0xFF) ? "" : safeGet(shortPool, descId);

            // Normalize to match map-style fields (simple class name, short rid, trimmed text/desc).
            cls = Util.normalizeClass(cls);
            rid = Util.normalizeResourceId(rid);
            text = Util.normalizeText(text);
            desc = Util.normalizeText(desc);

            out.add(new ActionNode(type, new Bounds(left, top, right, bottom), cls, text, rid, desc));
        }

        return out;
    }

    private static String safeGet(List<String> pool, int id) {
        if (id < 0 || id >= pool.size()) return "";
        return pool.get(id);
    }
}

