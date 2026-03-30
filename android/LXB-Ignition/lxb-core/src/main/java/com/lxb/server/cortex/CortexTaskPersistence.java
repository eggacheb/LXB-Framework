package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File persistence helpers for task memory/schedules/task runs.
 */
public final class CortexTaskPersistence {

    @SuppressWarnings("unchecked")
    public void loadTaskMemory(
            String taskMemoryPath,
            Map<String, Map<String, Object>> memoryByTaskKey,
            Map<String, Map<String, Object>> memoryByScheduleId
    ) {
        try {
            File f = new File(taskMemoryPath);
            if (!f.exists() || !f.isFile()) {
                return;
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map)) {
                return;
            }
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object byTaskObj = root.get("memory_by_task_key");
            if (byTaskObj instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) byTaskObj;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        memoryByTaskKey.put(e.getKey(), new LinkedHashMap<String, Object>((Map<String, Object>) e.getValue()));
                    }
                }
            }
            Object byScheduleObj = root.get("memory_by_schedule_id");
            if (byScheduleObj instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) byScheduleObj;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        memoryByScheduleId.put(e.getKey(), new LinkedHashMap<String, Object>((Map<String, Object>) e.getValue()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void saveTaskMemory(
            String taskMemoryPath,
            Map<String, Map<String, Object>> memoryByTaskKey,
            Map<String, Map<String, Object>> memoryByScheduleId
    ) {
        try {
            File f = new File(taskMemoryPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("memory_by_task_key", new LinkedHashMap<String, Object>(memoryByTaskKey));
            root.put("memory_by_schedule_id", new LinkedHashMap<String, Object>(memoryByScheduleId));
            Files.write(f.toPath(), Json.stringify(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public List<Object> loadRows(String path, String rowsKey) {
        Map<String, Object> root = loadJsonRootWithBackup(path);
        if (root == null) {
            return null;
        }
        Object rowsObj = root.get(rowsKey);
        if (!(rowsObj instanceof List)) {
            return null;
        }
        return (List<Object>) rowsObj;
    }

    public void saveRows(String path, String schemaVersion, String rowsKey, List<Object> rows) {
        try {
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            root.put("schema_version", schemaVersion);
            root.put("updated_at", System.currentTimeMillis());
            root.put(rowsKey, rows != null ? rows : new ArrayList<Object>());
            writeJsonAtomically(path, Json.stringify(root));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadJsonRootWithBackup(String path) {
        Object primary = parseJsonFile(path);
        if (primary instanceof Map) {
            return (Map<String, Object>) primary;
        }
        Object backup = parseJsonFile(path + ".bak");
        if (backup instanceof Map) {
            return (Map<String, Object>) backup;
        }
        return null;
    }

    private Object parseJsonFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) {
                return null;
            }
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return Json.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- Script persistence ----

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadScript(String scriptDir, String scriptKey) {
        Object parsed = parseJsonFile(scriptDir + "/" + scriptKey + ".json");
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        return null;
    }

    public void saveScript(String scriptDir, String scriptKey, Map<String, Object> script) {
        try {
            String path = scriptDir + "/" + scriptKey + ".json";
            File dir = new File(scriptDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            Files.write(new File(path).toPath(), Json.stringify(script).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listScripts(String scriptDir) {
        List<Map<String, Object>> result = new ArrayList<>();
        File dir = new File(scriptDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return result;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return result;
        }
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".json")) {
                continue;
            }
            try {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Object parsed = Json.parse(json);
                if (parsed instanceof Map) {
                    result.add((Map<String, Object>) parsed);
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public boolean deleteScript(String scriptDir, String scriptKey) {
        File f = new File(scriptDir + "/" + scriptKey + ".json");
        return f.exists() && f.delete();
    }

    private void writeJsonAtomically(String path, String json) throws Exception {
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(path + ".tmp");
        File bak = new File(path + ".bak");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        if (target.exists()) {
            bak.delete();
            target.renameTo(bak);
        }
        if (!tmp.renameTo(target)) {
            Files.write(target.toPath(), json.getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }
}
