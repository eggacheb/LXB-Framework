package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal route map model: only pages + transitions needed for routing.
 *
 * Map JSON 兼容现有 nav_map_*.json:
 * {
 *   "package": "tv.danmaku.bili",
 *   "pages": { ... },
 *   "transitions": [
 *     {
 *       "from": "home",
 *       "to": "search",
 *       "action": { "type": "tap", "locator": { ... } },
 *       "description": "点击搜索"
 *     },
 *     ...
 *   ]
 * }
 */
public class RouteMap {

    public static class Transition {
        public final String fromPage;
        public final String toPage;
        public final String description;
        public final RouteAction action;

        public Transition(String fromPage, String toPage, String description, RouteAction action) {
            this.fromPage = fromPage;
            this.toPage = toPage;
            this.description = description != null ? description : "";
            this.action = action;
        }
    }

    public static class RouteAction {
        public final String type;
        public final Locator locator;

        public RouteAction(String type, Locator locator) {
            this.type = type != null ? type : "tap";
            this.locator = locator;
        }
    }

    public final String packageName;
    /** Raw pages map from nav_map_*.json; keys are page IDs. */
    public final Map<String, Map<String, Object>> pages;
    public final List<Transition> transitions;

    public RouteMap(String packageName, Map<String, Map<String, Object>> pages, List<Transition> transitions) {
        this.packageName = packageName;
        this.pages = pages != null ? pages : new LinkedHashMap<String, Map<String, Object>>();
        this.transitions = transitions != null ? transitions : new ArrayList<Transition>();
    }

    public static RouteMap loadFromFile(File file) throws Exception {
        String json = readUtf8(file);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = Json.parseObject(json);

        String pkg = stringOrEmpty(root.get("package"));

        // Load pages (page_id -> metadata), kept raw for home-page inference.
        Map<String, Map<String, Object>> pages = new LinkedHashMap<>();
        Object pagesObj = root.get("pages");
        if (pagesObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawPages = (Map<String, Object>) pagesObj;
            for (Map.Entry<String, Object> entry : rawPages.entrySet()) {
                String pageId = entry.getKey();
                Object v = entry.getValue();
                Map<String, Object> meta;
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) v;
                    meta = new LinkedHashMap<>(cast);
                } else {
                    meta = new LinkedHashMap<>();
                    if (v != null) {
                        meta.put("value", v);
                    }
                }
                pages.put(pageId, meta);
            }
        }

        List<Transition> transitions = new ArrayList<>();

        Object txArrObj = root.get("transitions");
        if (txArrObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> arr = (List<Object>) txArrObj;
            for (Object o : arr) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> tx = (Map<String, Object>) o;
                String from = stringOrEmpty(tx.get("from"));
                String to = stringOrEmpty(tx.get("to"));
                String desc = stringOrEmpty(tx.get("description"));

                RouteAction action = null;
                Object actionObj = tx.get("action");
                if (actionObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> a = (Map<String, Object>) actionObj;
                    String type = stringOrEmpty(a.get("type"));
                    Locator locator = null;
                    Object locObj = a.get("locator");
                    if (locObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> locMap = (Map<String, Object>) locObj;
                        locator = Locator.fromMap(locMap);
                    }
                    action = new RouteAction(type, locator);
                }

                if (!from.isEmpty() && !to.isEmpty() && action != null && action.locator != null) {
                    transitions.add(new Transition(from, to, desc, action));
                }
            }
        }

        return new RouteMap(pkg, pages, transitions);
    }

    private static String stringOrEmpty(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static String readUtf8(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    /**
     * BFS 计算 from -> to 的一条最短路径，返回 Transition 列表。
     */
    public List<Transition> findPath(String fromPage, String toPage, int maxSteps) {
        String from = fromPage != null ? fromPage.trim() : "";
        String to = toPage != null ? toPage.trim() : "";
        if (from.isEmpty() || to.isEmpty()) return null;
        if (from.equals(to)) return new ArrayList<>();

        // 简单 BFS，状态里直接携带 path，够用且易读。
        List<PathNode> queue = new ArrayList<>();
        int qHead = 0;
        queue.add(new PathNode(from, new ArrayList<Transition>()));
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        visited.add(from);

        while (qHead < queue.size()) {
            PathNode node = queue.get(qHead++);
            String current = node.page;
            List<Transition> path = node.path;
            if (path.size() >= maxSteps && maxSteps > 0) {
                continue;
            }

            for (Transition t : transitions) {
                if (!current.equals(t.fromPage)) continue;
                String nextPage = t.toPage;
                List<Transition> newPath = new ArrayList<>(path);
                newPath.add(t);
                if (nextPage.equals(to)) {
                    return newPath;
                }
                if (!visited.contains(nextPage)) {
                    visited.add(nextPage);
                    queue.add(new PathNode(nextPage, newPath));
                }
            }
        }
        return null;
    }

    /**
     * 按 Python RouteThenActCortex._bfs_path 语义，从推断的 home 页到 target_page 做 BFS。
     *
     * - 起点：inferHomePage()
     * - 找不到路径时，如果 target_page 没有任何入边，则返回空路径（仅启动应用，不点击节点）。
     */
    public List<Transition> findPathFromHome(String targetPage, int maxSteps) {
        String target = targetPage != null ? targetPage.trim() : "";
        if (target.isEmpty()) return null;

        String start = inferHomePage();
        if (start == null || start.isEmpty()) return null;
        if (start.equals(target)) {
            // Already at target in logical graph: caller 只需要启动应用即可。
            return new ArrayList<>();
        }

        List<Transition> path = findPath(start, target, maxSteps);
        if (path != null) {
            return path;
        }

        // 没有路径，检查 target 是否为“根页”（没有任何入边）；
        // 若是，返回空路径，让上层只做 LAUNCH_APP，和 Python 行为保持一致。
        Set<String> pageIds = new LinkedHashSet<>();
        if (pages != null && !pages.isEmpty()) {
            pageIds.addAll(pages.keySet());
        } else {
            for (Transition t : transitions) {
                if (t.fromPage != null && !t.fromPage.isEmpty()) pageIds.add(t.fromPage);
                if (t.toPage != null && !t.toPage.isEmpty()) pageIds.add(t.toPage);
            }
        }
        if (!pageIds.contains(target)) {
            return null;
        }
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String pid : pageIds) {
            indegree.put(pid, 0);
        }
        for (Transition t : transitions) {
            if (indegree.containsKey(t.toPage)) {
                indegree.put(t.toPage, indegree.get(t.toPage) + 1);
            }
        }
        Integer in = indegree.get(target);
        if (in != null && in == 0) {
            return new ArrayList<>();
        }
        return null;
    }

    /**
     * 参考 Python 端 _infer_home_page：从 pages/transitions 推断 home 页。
     */
    public String inferHomePage() {
        // 1) legacy_page_id == "home"
        if (pages != null && !pages.isEmpty()) {
            for (Map.Entry<String, Map<String, Object>> entry : pages.entrySet()) {
                String pageId = entry.getKey();
                Map<String, Object> meta = entry.getValue();
                Object legacy = meta != null ? meta.get("legacy_page_id") : null;
                if ("home".equals(String.valueOf(legacy))) {
                    return pageId;
                }
            }

            // 2) 没有 __n_ 后缀的原始 root 页，优先包含 "home" 的
            List<String> noHash = new ArrayList<>();
            for (String pid : pages.keySet()) {
                if (!pid.contains("__n_")) {
                    noHash.add(pid);
                }
            }
            if (!noHash.isEmpty()) {
                for (String pid : noHash) {
                    if (pid.toLowerCase().contains("home")) {
                        return pid;
                    }
                }
                return noHash.get(0);
            }
        }

        // 3) 用入度为 0 的 root 页，并在 root 中选出出度最大的一个
        Set<String> allPages = new LinkedHashSet<>();
        if (pages != null && !pages.isEmpty()) {
            allPages.addAll(pages.keySet());
        }
        for (Transition t : transitions) {
            if (t.fromPage != null && !t.fromPage.isEmpty()) allPages.add(t.fromPage);
            if (t.toPage != null && !t.toPage.isEmpty()) allPages.add(t.toPage);
        }
        if (allPages.isEmpty()) {
            return "";
        }

        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String pid : allPages) {
            indegree.put(pid, 0);
        }
        for (Transition t : transitions) {
            if (indegree.containsKey(t.toPage)) {
                indegree.put(t.toPage, indegree.get(t.toPage) + 1);
            }
        }
        List<String> roots = new ArrayList<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                roots.add(e.getKey());
            }
        }
        if (!roots.isEmpty()) {
            Map<String, Integer> outDegree = new LinkedHashMap<>();
            for (String pid : allPages) {
                outDegree.put(pid, 0);
            }
            for (Transition t : transitions) {
                if (outDegree.containsKey(t.fromPage)) {
                    outDegree.put(t.fromPage, outDegree.get(t.fromPage) + 1);
                }
            }
            String best = roots.get(0);
            int bestOut = outDegree.get(best);
            for (String r : roots) {
                int out = outDegree.get(r);
                if (out > bestOut) {
                    bestOut = out;
                    best = r;
                }
            }
            return best;
        }

        // 4) 回退到第一个页
        return allPages.iterator().next();
    }

    private static class PathNode {
        final String page;
        final List<Transition> path;

        PathNode(String page, List<Transition> path) {
            this.page = page;
            this.path = path;
        }
    }
}

