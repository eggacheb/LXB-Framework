package com.lxb.server.cortex;

import com.lxb.server.cortex.dump.DumpActionsParser;
import com.lxb.server.cortex.dump.DumpHierarchyParser;
import com.lxb.server.perception.PerceptionEngine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staged locator resolution:
 * 1) strict match by self features (resource_id/text/content_desc/class) as a conjunction
 * 2) if still multiple and locator has parent_rid, use it only as a tightening condition
 * 3) if still multiple and locator has bounds_hint, pick nearest by center distance
 * 4) if still multiple and locator has index/count, pick by stable ordering (future)
 *
 * Fail-fast: if no candidates after stage 1, throw.
 */
public class LocatorResolver {

    private static final String TAG = "[LXB][Locator]";

    private final PerceptionEngine perceptionEngine;
    private final TraceLogger trace;

    public LocatorResolver(PerceptionEngine perceptionEngine, TraceLogger trace) {
        this.perceptionEngine = perceptionEngine;
        this.trace = trace;
    }

    public ResolvedNode resolve(Locator locator) throws Exception {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("rid", locator.resourceId);
        begin.put("text", locator.text);
        begin.put("desc", locator.contentDesc);
        begin.put("class", locator.className);
        begin.put("parent_rid", locator.parentRid);
        trace.event("resolve_begin", begin);

        // 1) Dump action nodes (rich text pool)
        byte[] da = perceptionEngine.handleDumpActions(new byte[0]);
        List<DumpActionsParser.ActionNode> actions = DumpActionsParser.parse(da);

        // 2) Dump hierarchy nodes (parent relationship)
        byte[] dhPayload = buildDumpHierarchyReqPayload();
        byte[] dh = perceptionEngine.handleDumpHierarchy(dhPayload);
        List<DumpHierarchyParser.HierNode> hierarchy = DumpHierarchyParser.parse(dh);

        // 3) Enrich action nodes with parent_rid from hierarchy via best-effort matching.
        List<Candidate> candidates = enrich(actions, hierarchy);

        // Stage 1: strict self match (AND all non-empty fields)
        List<Candidate> stage1 = new ArrayList<>();
        for (Candidate c : candidates) {
            if (matchesSelf(locator, c)) stage1.add(c);
        }
        if (stage1.isEmpty()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("stage", "self");
            f.put("reason", "no_candidates");
            trace.event("resolve_fail", f);
            throw new IllegalStateException("locator match: no candidates (self features)");
        }
        if (stage1.size() == 1) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("stage", "self");
            ok.put("candidates", 1);
            trace.event("resolve_ok", ok);
            return new ResolvedNode(stage1.get(0).bounds, 1, "self");
        }

        // Stage 2: parent_rid tightening (optional)
        List<Candidate> stage2 = stage1;
        String parentRid = Util.normalizeResourceId(locator.parentRid);
        if (!parentRid.isEmpty()) {
            List<Candidate> filtered = new ArrayList<>();
            for (Candidate c : stage1) {
                if (parentRid.equals(c.parentRid)) filtered.add(c);
            }
            if (!filtered.isEmpty()) {
                stage2 = filtered;
            }
            if (stage2.size() == 1) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "parent_rid");
                ok.put("candidates", 1);
                trace.event("resolve_ok", ok);
                return new ResolvedNode(stage2.get(0).bounds, stage1.size(), "parent_rid");
            }
        }

        // Stage 3: bounds_hint tie-break (optional)
        if (locator.boundsHint != null) {
            Candidate best = null;
            long bestDist = Long.MAX_VALUE;
            int hx = locator.boundsHint.centerX();
            int hy = locator.boundsHint.centerY();
            for (Candidate c : stage2) {
                long dx = (long) c.bounds.centerX() - hx;
                long dy = (long) c.bounds.centerY() - hy;
                long d2 = dx * dx + dy * dy;
                if (d2 < bestDist) {
                    bestDist = d2;
                    best = c;
                }
            }
            if (best != null) {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("stage", "bounds_hint");
                ok.put("candidates", stage2.size());
                ok.put("dist2", bestDist);
                trace.event("resolve_ok", ok);
                return new ResolvedNode(best.bounds, stage1.size(), "bounds_hint");
            }
        }

        // Stage 4: index/count (future). For now, fail to avoid random taps.
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("stage", "ambiguous");
        f.put("candidates", stage2.size());
        trace.event("resolve_fail", f);
        throw new IllegalStateException("locator match: ambiguous candidates=" + stage2.size());
    }

    private static byte[] buildDumpHierarchyReqPayload() {
        // format[1B]=2 (binary) + compress[1B]=1 (zlib) + max_depth[2B]=0 (unlimited)
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0x02);
        b.put((byte) 0x01);
        b.putShort((short) 0);
        return b.array();
    }

    private static boolean matchesSelf(Locator l, Candidate c) {
        String rid = Util.normalizeResourceId(l.resourceId);
        String cls = Util.normalizeClass(l.className);
        String text = Util.normalizeText(l.text);
        String desc = Util.normalizeText(l.contentDesc);

        if (!rid.isEmpty() && !rid.equals(c.resourceId)) return false;
        if (!cls.isEmpty() && !cls.equals(c.className)) return false;
        if (!text.isEmpty() && !text.equals(c.text)) return false;
        if (!desc.isEmpty() && !desc.equals(c.contentDesc)) return false;

        return true;
    }

    private static List<Candidate> enrich(
            List<DumpActionsParser.ActionNode> actions,
            List<DumpHierarchyParser.HierNode> hierarchy
    ) {
        // bounds -> list of hierarchy nodes (duplicates possible)
        Map<BoundsKey, List<DumpHierarchyParser.HierNode>> byBounds = new HashMap<>();
        for (DumpHierarchyParser.HierNode n : hierarchy) {
            BoundsKey k = new BoundsKey(n.bounds);
            List<DumpHierarchyParser.HierNode> lst = byBounds.get(k);
            if (lst == null) {
                lst = new ArrayList<>();
                byBounds.put(k, lst);
            }
            lst.add(n);
        }

        List<Candidate> out = new ArrayList<>(actions.size());
        for (DumpActionsParser.ActionNode a : actions) {
            BoundsKey k = new BoundsKey(a.bounds);
            List<DumpHierarchyParser.HierNode> hs = byBounds.get(k);

            String parentRid = "";
            if (hs != null && !hs.isEmpty()) {
                DumpHierarchyParser.HierNode best = pickBestHierarchyMatch(a, hs);
                if (best != null && best.parentIndex != null) {
                    int pIdx = best.parentIndex;
                    if (pIdx >= 0 && pIdx < hierarchy.size()) {
                        parentRid = hierarchy.get(pIdx).resourceId;
                    }
                }
            }

            out.add(new Candidate(
                    a.bounds,
                    a.className,
                    a.text,
                    a.resourceId,
                    a.contentDesc,
                    Util.normalizeResourceId(parentRid)
            ));
        }
        return out;
    }

    private static DumpHierarchyParser.HierNode pickBestHierarchyMatch(
            DumpActionsParser.ActionNode a,
            List<DumpHierarchyParser.HierNode> hs
    ) {
        DumpHierarchyParser.HierNode best = null;
        int bestScore = -1;
        for (DumpHierarchyParser.HierNode h : hs) {
            int score = 0;
            if (!a.resourceId.isEmpty() && a.resourceId.equals(h.resourceId)) score += 3;
            if (!a.className.isEmpty() && a.className.equals(h.className)) score += 2;
            if (!a.contentDesc.isEmpty() && a.contentDesc.equals(h.contentDesc)) score += 2;
            if (!a.text.isEmpty() && a.text.equals(h.text)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best;
    }

    private static class Candidate {
        final Bounds bounds;
        final String className;
        final String text;
        final String resourceId;
        final String contentDesc;
        final String parentRid;

        Candidate(Bounds bounds, String className, String text, String resourceId, String contentDesc, String parentRid) {
            this.bounds = bounds;
            this.className = className;
            this.text = text;
            this.resourceId = resourceId;
            this.contentDesc = contentDesc;
            this.parentRid = parentRid;
        }
    }

    private static class BoundsKey {
        final int l, t, r, b;

        BoundsKey(Bounds bounds) {
            this.l = bounds.left;
            this.t = bounds.top;
            this.r = bounds.right;
            this.b = bounds.bottom;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof BoundsKey)) return false;
            BoundsKey other = (BoundsKey) o;
            return l == other.l && t == other.t && r == other.r && b == other.b;
        }

        @Override
        public int hashCode() {
            int h = 17;
            h = 31 * h + l;
            h = 31 * h + t;
            h = 31 * h + r;
            h = 31 * h + b;
            return h;
        }
    }
}
