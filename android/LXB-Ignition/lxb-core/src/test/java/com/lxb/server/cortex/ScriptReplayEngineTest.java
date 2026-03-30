package com.lxb.server.cortex;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScriptReplayEngineTest {

    @Test
    public void extractStepSummaries_basic() {
        Map<String, Object> script = new LinkedHashMap<>();
        List<Object> steps = new ArrayList<>();

        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("op", "TAP");
        step1.put("args", Arrays.asList("500", "300"));
        step1.put("raw", "TAP 500 300");
        steps.add(step1);

        Map<String, Object> step2 = new LinkedHashMap<>();
        step2.put("op", "INPUT");
        step2.put("args", Arrays.asList("hello"));
        step2.put("raw", "INPUT \"hello\"");
        steps.add(step2);

        Map<String, Object> step3 = new LinkedHashMap<>();
        step3.put("op", "DONE");
        step3.put("args", new ArrayList<>());
        step3.put("raw", "");
        steps.add(step3);

        script.put("steps", steps);

        List<String> summaries = ScriptReplayEngine.extractStepSummaries(script);
        Assert.assertEquals(3, summaries.size());
        Assert.assertEquals("TAP 500 300", summaries.get(0));
        Assert.assertEquals("INPUT \"hello\"", summaries.get(1));
        Assert.assertEquals("DONE", summaries.get(2));
    }

    @Test
    public void extractStepSummaries_empty() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("steps", new ArrayList<>());
        List<String> summaries = ScriptReplayEngine.extractStepSummaries(script);
        Assert.assertTrue(summaries.isEmpty());
    }

    @Test
    public void extractStepSummaries_nullScript() {
        List<String> summaries = ScriptReplayEngine.extractStepSummaries(null);
        Assert.assertTrue(summaries.isEmpty());
    }
}
