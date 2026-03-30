package com.lxb.server.cortex;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CortexTaskPersistenceTest {

    @Test
    public void taskMemory_roundTrip() throws Exception {
        File tmp = Files.createTempFile("task-memory", ".json").toFile();
        tmp.deleteOnExit();

        CortexTaskPersistence persistence = new CortexTaskPersistence();
        Map<String, Map<String, Object>> byTask = new ConcurrentHashMap<String, Map<String, Object>>();
        Map<String, Map<String, Object>> bySchedule = new ConcurrentHashMap<String, Map<String, Object>>();

        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("summary_text", "ok");
        byTask.put("task:demo", row);

        persistence.saveTaskMemory(tmp.getAbsolutePath(), byTask, bySchedule);

        Map<String, Map<String, Object>> outTask = new ConcurrentHashMap<String, Map<String, Object>>();
        Map<String, Map<String, Object>> outSchedule = new ConcurrentHashMap<String, Map<String, Object>>();
        persistence.loadTaskMemory(tmp.getAbsolutePath(), outTask, outSchedule);

        Assert.assertTrue(outTask.containsKey("task:demo"));
        Assert.assertEquals("ok", String.valueOf(outTask.get("task:demo").get("summary_text")));
    }

    @Test
    public void rows_roundTrip() throws Exception {
        File tmp = Files.createTempFile("schedule-rows", ".json").toFile();
        tmp.deleteOnExit();

        CortexTaskPersistence persistence = new CortexTaskPersistence();
        List<Object> rows = new ArrayList<Object>();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("schedule_id", "sid-1");
        rows.add(row);

        persistence.saveRows(tmp.getAbsolutePath(), "schedules.v1", "schedules", rows);
        List<Object> loaded = persistence.loadRows(tmp.getAbsolutePath(), "schedules");

        Assert.assertNotNull(loaded);
        Assert.assertEquals(1, loaded.size());
    }

    @Test
    public void script_roundTrip() throws Exception {
        File tmpDir = Files.createTempDirectory("lxb-scripts").toFile();
        tmpDir.deleteOnExit();

        CortexTaskPersistence persistence = new CortexTaskPersistence();
        Map<String, Object> script = new LinkedHashMap<String, Object>();
        script.put("schema_version", "script.v1");
        script.put("script_key", "test_abc");
        script.put("user_task", "test task");
        script.put("package_name", "com.test.app");
        List<Object> steps = new ArrayList<Object>();
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("op", "TAP");
        List<String> args = new ArrayList<String>();
        args.add("500");
        args.add("300");
        step.put("args", args);
        steps.add(step);
        script.put("steps", steps);

        persistence.saveScript(tmpDir.getAbsolutePath(), "test_abc", script);
        Map<String, Object> loaded = persistence.loadScript(tmpDir.getAbsolutePath(), "test_abc");

        Assert.assertNotNull(loaded);
        Assert.assertEquals("script.v1", loaded.get("schema_version"));
        Assert.assertEquals("test_abc", loaded.get("script_key"));
        Assert.assertEquals("test task", loaded.get("user_task"));

        // Clean up
        new File(tmpDir, "test_abc.json").delete();
        tmpDir.delete();
    }

    @Test
    public void script_listAndDelete() throws Exception {
        File tmpDir = Files.createTempDirectory("lxb-scripts").toFile();
        tmpDir.deleteOnExit();

        CortexTaskPersistence persistence = new CortexTaskPersistence();

        Map<String, Object> script1 = new LinkedHashMap<String, Object>();
        script1.put("script_key", "key1");
        script1.put("user_task", "task1");
        persistence.saveScript(tmpDir.getAbsolutePath(), "key1", script1);

        Map<String, Object> script2 = new LinkedHashMap<String, Object>();
        script2.put("script_key", "key2");
        script2.put("user_task", "task2");
        persistence.saveScript(tmpDir.getAbsolutePath(), "key2", script2);

        List<Map<String, Object>> all = persistence.listScripts(tmpDir.getAbsolutePath());
        Assert.assertEquals(2, all.size());

        boolean deleted = persistence.deleteScript(tmpDir.getAbsolutePath(), "key1");
        Assert.assertTrue(deleted);

        List<Map<String, Object>> remaining = persistence.listScripts(tmpDir.getAbsolutePath());
        Assert.assertEquals(1, remaining.size());
        Assert.assertEquals("key2", remaining.get(0).get("script_key"));

        boolean notFound = persistence.deleteScript(tmpDir.getAbsolutePath(), "nonexistent");
        Assert.assertFalse(notFound);

        // Clean up
        new File(tmpDir, "key2.json").delete();
        tmpDir.delete();
    }
}
