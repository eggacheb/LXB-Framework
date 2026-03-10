package com.lxb.server.cortex.dump;

import com.lxb.server.cortex.Bounds;
import com.lxb.server.cortex.Util;
import com.lxb.server.protocol.StringPoolConstants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Decoder for PerceptionEngine.handleDumpHierarchy() binary payload.
 *
 * Payload format:
 *   version[1B] + compress[1B] + original_size[4B] + compressed_size[4B] +
 *   node_count[2B] + string_pool_size[2B] + data...
 *
 * If compress==1, data is zlib-compressed bytes for:
 *   StringPool + NodesArray
 *
 * StringPool (dynamic only):
 *   count[2B] + entries[count * (id[1B] + len[1B] + data[len])]
 *
 * Node (15B each):
 *   parent_index[1B] + child_count[1B] + flags[1B] +
 *   left[2B] + top[2B] + right[2B] + bottom[2B] +
 *   class_id[1B] + text_id[1B] + res_id[1B] + desc_id[1B]
 */
public class DumpHierarchyParser {

    public static class HierNode {
        public final int index;
        public final Integer parentIndex; // null for root
        public final int flags;
        public final Bounds bounds;
        public final String className;
        public final String text;
        public final String resourceId;
        public final String contentDesc;

        public HierNode(int index, Integer parentIndex, int flags, Bounds bounds,
                        String className, String text, String resourceId, String contentDesc) {
            this.index = index;
            this.parentIndex = parentIndex;
            this.flags = flags;
            this.bounds = bounds;
            this.className = className;
            this.text = text;
            this.resourceId = resourceId;
            this.contentDesc = contentDesc;
        }

        public boolean clickable() { return (flags & 0x01) != 0; }
        public boolean visible() { return (flags & 0x02) != 0; }
        public boolean scrollable() { return (flags & 0x10) != 0; }
        public boolean editable() { return (flags & 0x20) != 0; }
    }

    public static List<HierNode> parse(byte[] payload) throws Exception {
        if (payload == null || payload.length < 14) {
            throw new IllegalArgumentException("dump_hierarchy payload too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        int version = buf.get() & 0xFF;
        int compress = buf.get() & 0xFF;
        int originalSize = buf.getInt();
        int compressedSize = buf.getInt();
        int nodeCount = buf.getShort() & 0xFFFF;
        int poolSize = buf.getShort() & 0xFFFF; // dynamic count (for info only)

        byte[] data;
        if (compress == 1) {
            byte[] compressed = new byte[buf.remaining()];
            buf.get(compressed);
            data = inflateZlib(compressed, originalSize);
        } else if (compress == 0) {
            data = new byte[buf.remaining()];
            buf.get(data);
        } else {
            throw new IllegalArgumentException("dump_hierarchy unsupported compress=" + compress);
        }

        ByteBuffer db = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        StringPool pool = StringPool.unpack(db);

        List<HierNode> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            if (db.remaining() < 15) {
                throw new IllegalArgumentException("dump_hierarchy nodes truncated at " + i);
            }
            int parentIdxRaw = db.get() & 0xFF;
            db.get(); // child_count
            int flags = db.get() & 0xFF;
            int left = db.getShort() & 0xFFFF;
            int top = db.getShort() & 0xFFFF;
            int right = db.getShort() & 0xFFFF;
            int bottom = db.getShort() & 0xFFFF;
            int classId = db.get() & 0xFF;
            int textId = db.get() & 0xFF;
            int resId = db.get() & 0xFF;
            int descId = db.get() & 0xFF;

            Integer parentIndex = (parentIdxRaw == 0xFF) ? null : parentIdxRaw;

            String cls = pool.get(classId);
            String text = pool.get(textId);
            String rid = pool.get(resId);
            String desc = pool.get(descId);

            cls = Util.normalizeClass(cls);
            rid = Util.normalizeResourceId(rid);
            text = Util.normalizeText(text);
            desc = Util.normalizeText(desc);

            nodes.add(new HierNode(i, parentIndex, flags, new Bounds(left, top, right, bottom), cls, text, rid, desc));
        }

        return nodes;
    }

    private static byte[] inflateZlib(byte[] compressed, int expectedSize) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] out = new byte[Math.max(32, expectedSize)];
        int len = inflater.inflate(out);
        inflater.end();
        if (len == out.length) {
            // If output buffer was too small, retry with a bigger one.
            // (expectedSize is best-effort only; keep it safe).
            Inflater inflater2 = new Inflater();
            inflater2.setInput(compressed);
            byte[] out2 = new byte[Math.max(out.length * 4, expectedSize * 2)];
            int len2 = inflater2.inflate(out2);
            inflater2.end();
            byte[] finalOut = new byte[len2];
            System.arraycopy(out2, 0, finalOut, 0, len2);
            return finalOut;
        }
        byte[] finalOut = new byte[len];
        System.arraycopy(out, 0, finalOut, 0, len);
        return finalOut;
    }

    /**
     * String pool implementation aligned with com.lxb.server.protocol.StringPoolConstants.
     * The binary only includes dynamic entries; predefined strings are known by ID.
     */
    private static class StringPool {
        private final Map<Integer, String> dynamic = new HashMap<>();

        static StringPool unpack(ByteBuffer buf) {
            StringPool p = new StringPool();
            int count = buf.getShort() & 0xFFFF;
            for (int i = 0; i < count; i++) {
                int id = buf.get() & 0xFF;
                int len = buf.get() & 0xFF;
                byte[] b = new byte[len];
                buf.get(b);
                p.dynamic.put(id, new String(b, StandardCharsets.UTF_8));
            }
            return p;
        }

        String get(int id) {
            if (id == StringPoolConstants.NULL_MARKER) return "";
            if (id >= 0x00 && id <= 0x3F) {
                if (id < StringPoolConstants.PREDEFINED_CLASSES.length) {
                    return StringPoolConstants.PREDEFINED_CLASSES[id];
                }
                return "";
            }
            if (id >= 0x40 && id <= 0x7F) {
                int idx = id - 0x40;
                if (idx >= 0 && idx < StringPoolConstants.PREDEFINED_TEXTS.length) {
                    return StringPoolConstants.PREDEFINED_TEXTS[idx];
                }
                return "";
            }
            if (dynamic.containsKey(id)) return dynamic.get(id);
            return "";
        }
    }
}

