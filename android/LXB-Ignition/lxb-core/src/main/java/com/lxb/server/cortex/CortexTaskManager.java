package com.lxb.server.cortex;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Task manager shell for Cortex FSM tasks.
 *
 * Current design:
 * - Async submission via submitTask(...), returning task_id immediately.
 * - Single worker thread executes FSM tasks in queue order.
 * - Minimal in-memory TaskInstance registry with bounded retention.
 *
 * Later phases:
 * - Maintain richer task registry and async submission/status APIs.
 * - Support scheduled tasks and trace push.
 */
public class CortexTaskManager {

    private final CortexFsmEngine fsmEngine;

    // Simple ready queue for FSM tasks.
    private final BlockingQueue<FsmTaskRequest> readyQueue =
            new LinkedBlockingQueue<FsmTaskRequest>();

    // Minimal in-memory registry of tasks, keyed by taskId.
    private final ConcurrentHashMap<String, TaskInstance> taskRegistry =
            new ConcurrentHashMap<String, TaskInstance>();

    // Insertion order of taskIds for simple LRU-style eviction.
    private final Deque<String> taskOrder = new ArrayDeque<String>();
    private static final int MAX_TASKS = 200;

    // Dedicated worker thread that executes CortexFsmEngine.run(...).
    private final Thread workerThread;

    // Simple global cancellation flag for the single-worker FSM. When true,
    // the current FSM run will notice and exit at the next state boundary.
    private volatile boolean cancelRequested = false;

    public CortexTaskManager(CortexFsmEngine fsmEngine) {
        this.fsmEngine = fsmEngine;
        this.workerThread = new Thread(this::workerLoop, "CortexFsmWorker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /**
     * Request cancellation of the currently running FSM task, if any. Since
     * there is only a single worker thread, this flag applies to "the current"
     * task and is cleared when that task finishes.
     */
    public void requestCancel() {
        cancelRequested = true;
    }

    /**
     * Submit a Cortex FSM task for asynchronous execution.
     *
     * Returns the generated task_id immediately; the actual FSM run will be
     * performed by the worker thread. Callers should use CMD_CORTEX_TASK_STATUS
     * or trace push to observe progress and final result.
     */
    public String submitTask(
            String userTask,
            String packageName,
            String mapPath,
            String startPage,
            String traceMode,
            Integer traceUdpPort
    ) {
        long now = System.currentTimeMillis();
        TaskInstance instance = new TaskInstance();
        instance.taskId = UUID.randomUUID().toString();
        instance.userTask = userTask != null ? userTask : "";
        instance.state = TaskState.PENDING;
        instance.createdAt = now;

        FsmTaskRequest req = new FsmTaskRequest(
                userTask,
                packageName,
                mapPath,
                startPage,
                traceMode,
                traceUdpPort,
                instance
        );
        try {
            readyQueue.put(req);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while enqueuing FSM task", e);
        }

        registerTaskInstance(instance);

        return instance.taskId;
    }

    private void workerLoop() {
        for (; ; ) {
            try {
                FsmTaskRequest req = readyQueue.take();
                TaskInstance instance = req.instance;
                instance.state = TaskState.RUNNING;
                instance.startedAt = System.currentTimeMillis();
                cancelRequested = false;

                CortexFsmEngine.CancellationChecker checker =
                        new CortexFsmEngine.CancellationChecker() {
                            @Override
                            public boolean isCancelled() {
                                return cancelRequested;
                            }
                        };

                try {
                    Map<String, Object> out = fsmEngine.run(
                            req.userTask,
                            req.packageName,
                            req.mapPath,
                            req.startPage,
                            req.traceMode,
                            req.traceUdpPort,
                            instance.taskId,
                            checker
                    );
                    instance.finishedAt = System.currentTimeMillis();
                    Object finalState = out.get("state");
                    if (finalState != null) {
                        instance.finalState = String.valueOf(finalState);
                    }
                    Object reason = out.get("reason");
                    if (reason != null) {
                        instance.reason = String.valueOf(reason);
                    }
                    String status = String.valueOf(out.get("status"));
                    if ("success".equalsIgnoreCase(status)) {
                        instance.state = TaskState.COMPLETED;
                    } else if (instance.reason != null && instance.reason.contains("cancelled_by_user")) {
                        instance.state = TaskState.CANCELLED;
                    } else {
                        instance.state = TaskState.FAILED;
                    }
                    Object pkg = out.get("package_name");
                    if (pkg != null) {
                        instance.packageName = String.valueOf(pkg);
                    }
                    Object target = out.get("target_page");
                    if (target != null) {
                        instance.targetPage = String.valueOf(target);
                    }
                    instance.resultSummary = out;

                } catch (Exception e) {
                    instance.finishedAt = System.currentTimeMillis();
                    instance.state = TaskState.FAILED;
                    instance.reason = String.valueOf(e);
                }
            } catch (InterruptedException e) {
                // Allow graceful shutdown if ever needed.
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void registerTaskInstance(TaskInstance instance) {
        if (instance.taskId == null || instance.taskId.isEmpty()) {
            return;
        }
        taskRegistry.put(instance.taskId, instance);
        synchronized (taskOrder) {
            taskOrder.addLast(instance.taskId);
            while (taskOrder.size() > MAX_TASKS) {
                String evictId = taskOrder.pollFirst();
                if (evictId != null) {
                    taskRegistry.remove(evictId);
                }
            }
        }
    }

    /**
     * Minimal per-request container for FSM tasks executed by the worker
     * thread. This is intentionally small; TaskInstance carries lifecycle
     * information for later inspection.
     */
    private static class FsmTaskRequest {
        final String userTask;
        final String packageName;
        final String mapPath;
        final String startPage;
        final String traceMode;
        final Integer traceUdpPort;
        final TaskInstance instance;

        FsmTaskRequest(String userTask,
                       String packageName,
                       String mapPath,
                       String startPage,
                       String traceMode,
                       Integer traceUdpPort,
                       TaskInstance instance) {
            this.userTask = userTask;
            this.packageName = packageName;
            this.mapPath = mapPath;
            this.startPage = startPage;
            this.traceMode = traceMode;
            this.traceUdpPort = traceUdpPort;
            this.instance = instance;
        }
    }

    /**
     * Internal task lifecycle state for the minimal registry.
     */
    private enum TaskState {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    /**
     * Minimal TaskInstance snapshot for one FSM run.
     *
     * This captures only lifecycle timestamps and a few summary fields.
     * Future phases can extend this with TaskDefinition links, schedule_at,
     * and richer outcome summaries.
     */
    private static class TaskInstance {
        String taskId;           // Prefer FSM's task_id when available
        String userTask;

        TaskState state;
        long createdAt;
        long startedAt;
        long finishedAt;

        String finalState;       // FSM final state name, e.g. FINISH/FAIL
        String reason;           // Error or explanation, if any
        String packageName;
        String targetPage;
        Map<String, Object> resultSummary;
    }

    /**
     * Minimal status snapshot for a given task_id. This is an internal helper
     * for future CMD_TASK_STATUS wiring; it is not exposed on the wire yet.
     *
     * Returns a map with:
     *   - found: boolean
     *   - task_id, user_task
     *   - state: PENDING/RUNNING/COMPLETED/FAILED
     *   - created_at, started_at, finished_at (epoch millis)
     *   - final_state, reason (may be null)
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        if (taskId == null || taskId.isEmpty()) {
            out.put("found", false);
            return out;
        }
        TaskInstance inst = taskRegistry.get(taskId);
        if (inst == null) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.put("task_id", inst.taskId);
        out.put("user_task", inst.userTask);
        out.put("state", inst.state != null ? inst.state.name() : null);
        out.put("created_at", inst.createdAt);
        out.put("started_at", inst.startedAt);
        out.put("finished_at", inst.finishedAt);
        out.put("final_state", inst.finalState);
        out.put("reason", inst.reason);
        out.put("package_name", inst.packageName);
        out.put("target_page", inst.targetPage);
        if (inst.resultSummary != null) {
            out.put("summary", inst.resultSummary);
        }
        return out;
    }

    /**
     * List up to 'limit' most recent tasks in reverse chronological order.
     * Each entry is a shallow snapshot similar to getTaskStatus(), but
     * without the 'found' flag.
     */
    public java.util.List<Map<String, Object>> listRecentTasks(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, MAX_TASKS));
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        java.util.List<String> ids;
        synchronized (taskOrder) {
            ids = new java.util.ArrayList<String>(taskOrder);
        }
        // Iterate from newest to oldest
        for (int i = ids.size() - 1; i >= 0 && result.size() < effectiveLimit; i--) {
            String id = ids.get(i);
            TaskInstance inst = taskRegistry.get(id);
            if (inst == null) {
                continue;
            }
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("task_id", inst.taskId);
            row.put("user_task", inst.userTask);
            row.put("state", inst.state != null ? inst.state.name() : null);
            row.put("created_at", inst.createdAt);
            row.put("started_at", inst.startedAt);
            row.put("finished_at", inst.finishedAt);
            row.put("final_state", inst.finalState);
            row.put("reason", inst.reason);
            row.put("package_name", inst.packageName);
            row.put("target_page", inst.targetPage);
            result.add(row);
        }
        return result;
    }

    // Future public methods (not implemented yet):
    // - submitTask(...) -> task_id (async)
    // - listTasks()
}
