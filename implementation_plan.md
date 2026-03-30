# AI 执行成功后保存为固定脚本（脱离 AI 回放）

## 背景

LXB-Framework 的 VISION_ACT 阶段依赖 VLM 实时截图分析+决策。用户希望：**AI 成功执行一次后，将操作序列保存为固定脚本，后续可脱离 AI 直接回放。**

框架已有 Task Memory（给 AI 参考的 hint）和 Playbook（文本操作说明书），但都**仍依赖 AI 做决策**，不是确定性回放。

## 核心设计

新增 **Script Replay** 模式：
- **手动保存**：任务成功后，用户在 UI 点击"保存为脚本"确认
- **自动降级**：有脚本时优先尝试脚本回放，失败时自动降级回 AI 模式
- **完善 UI**：脚本列表、删除管理、保存确认对话框

```
成功执行 → UI 提示"保存为脚本？" → 用户确认 → 持久化到 /data/local/tmp/lxb/scripts/
后续执行 → 检测到匹配 Script → 尝试回放 → 失败则自动降级回 AI
```

> [!WARNING]
> **坐标回放的局限**：脚本使用归一化坐标 `[0,1000]`，可适配不同分辨率。但 App UI 布局变化（版本更新等）可能导致回放失败，此时会自动降级回 AI 模式。

---

## 实现状态：已完成 ✅

所有代码已写入项目中，lxb-core 单元测试全部通过。

---

## 代码审查后修复（第二轮）

以下 4 项优化已在审查后完成：

### ✅ Fix 1 — ScriptReplayEngine.sleepQuiet 中断处理

- `sleepQuiet()` 中 `InterruptedException` 不再被静默吞掉，现在恢复中断标志 `Thread.currentThread().interrupt()`
- 同时移除了构造函数中未使用的 `PerceptionEngine` 参数，同步更新 `CortexTaskManager` 中的调用

### ✅ Fix 2 — ChatBubble 颜色逻辑在中文模式下失效

- 新增 `MessageSeverity` 枚举（`DEFAULT / SUCCESS / INFO / ERROR / WARNING`）添加到 `ChatMessage`
- `appendSystemMessage()` 在调用 `UiMessageLocalizer.localize()` **之前**，先根据英文原文推断 severity
- `ChatBubble` 基于 `severity` 枚举选择背景色，不再依赖文本匹配

### ✅ Fix 3 — probeScreenWidth/Height 合并为单次调用

- 原有 `probeScreenWidth()` 和 `probeScreenHeight()` 各自独立调用 `handleGetScreenSize()` 导致两次 IPC
- 合并为 `probeScreenSize()` 返回 `int[]{width, height}`，只需一次 IPC 调用
- `CortexTaskManager.workerLoop()` 中同步更新为使用新方法

### ✅ Fix 4 — findMatchingScript 缓存优化

- 新增 `volatile` 脚本内存缓存 `cachedScripts`
- `listScripts()` 优先返回缓存，首次调用时从磁盘加载
- `exportScriptFromTaskResult()` 和 `deleteScript()` 操作后自动失效缓存

### ✅ Fix 5 — 手动"保存为脚本"按钮（常驻 + 任务详情）

**TaskSessionCard 常驻按钮**：
- 在 Run/Stop 按钮下方常驻绿色 `Save as Script` 按钮
- 无成功任务时按钮置灰（`enabled = false`），有成功任务后变绿可点击
- 点击弹出保存确认对话框，保存最近一次成功任务为脚本

**最近执行 — 任务详情弹窗**：
- 点击"最近执行"中的任意一条任务，打开详情弹窗
- 当该任务状态为 `COMPLETED` 时，弹窗底部显示绿色 `Save as Script` 按钮
- 点击后调用 `exportScript(taskId)` 保存

### ✅ Fix 6 — 版本号更新

- `versionCode` 从 `407` 更新为 `408`
- `versionName` 从 `"0.4.7"` 更新为 `"0.4.8"`
- 文件：`app/build.gradle.kts` → `defaultConfig` 块

### ✅ Fix 7 — resultSummary 持久化

- `snapshotTaskRun()` 现在将 `resultSummary`（含 `command_log`）序列化到磁盘（仅在非空时写入）
- `parseTaskRunRow()` 加载任务时恢复 `resultSummary`
- 修复文件：`CortexTaskManager.java`（`snapshotTaskRun` +3 行、`parseTaskRunRow` +5 行）
- 效果：lxb-core 重启后，历史已完成任务仍可从详情弹窗导出脚本

---

## 已完成的变更清单

### 后端 (lxb-core) — 修改/新建 7 个 Java 文件

---

#### ✅ [MODIFY] CortexTaskPersistence.java

**路径**: `lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskPersistence.java`

在 `writeJsonAtomically()` 方法之前新增了 4 个脚本文件操作方法：

- `saveScript(String scriptDir, String scriptKey, Map<String, Object> script)` — 将脚本 JSON 写入 `scriptDir/scriptKey.json`
- `loadScript(String scriptDir, String scriptKey)` → `Map<String, Object>` — 读取并解析脚本 JSON
- `listScripts(String scriptDir)` → `List<Map<String, Object>>` — 遍历目录中所有 `.json` 文件，返回解析后的脚本列表
- `deleteScript(String scriptDir, String scriptKey)` → `boolean` — 删除脚本文件

存储路径：`/data/local/tmp/lxb/scripts/{scriptKey}.json`，每个脚本一个独立文件。

---

#### ✅ [MODIFY] CommandIds.java

**路径**: `lxb-core/src/main/java/com/lxb/server/protocol/CommandIds.java`

在文件末尾新增 4 个协议 ID：

```java
public static final byte CMD_CORTEX_SCRIPT_EXPORT = 0x7E;   // export successful task as replay script
public static final byte CMD_CORTEX_SCRIPT_LIST   = 0x7F;   // list saved scripts
public static final byte CMD_CORTEX_SCRIPT_DELETE  = (byte) 0x80; // delete a script by key
public static final byte CMD_CORTEX_SCRIPT_GET     = (byte) 0x81; // get script detail by key
```

注意：0x80 和 0x81 超出 signed byte 范围，需要 `(byte)` 强转。

---

#### ✅ [NEW] ScriptReplayEngine.java

**路径**: `lxb-core/src/main/java/com/lxb/server/cortex/ScriptReplayEngine.java`

全新类，约 310 行。确定性脚本回放引擎，核心逻辑：

1. **构造**：接收 `ExecutionEngine`、`TraceLogger`（第二轮审查移除了未使用的 `PerceptionEngine` 参数）
2. **`replay(script, taskId, screenW, screenH)`** 主方法：
   - 从脚本读取 `package_name` → 调用 `launchApp()` 启动 App（通过 ExecutionEngine.handleLaunchApp）
   - 按顺序执行 `steps` 列表中的每个操作
   - 支持操作：TAP / SWIPE / INPUT / WAIT / BACK / DONE
   - 坐标映射：归一化 `[0,1000]` → 物理像素 `mapNormalized(normalized, screenDim)`
   - 每步执行后等待 800ms（STEP_SETTLE_MS）让 UI 稳定
   - 遇到 DONE 步骤 → 返回 success
   - 任何步骤失败 → 返回 `ReplayResult(false, reason, stepsExecuted)`
3. **`ReplayResult`** 内部类：`success` / `reason` / `stepsExecuted`
4. **`extractStepSummaries(script)`** 静态工具方法：提取步骤的 raw 文本摘要
5. **INPUT 方法**：先尝试 ADB，再尝试 Clipboard（非 ASCII 文本反过来），与 FSM 的逻辑一致
6. **完全不调用任何 LLM/VLM**，纯确定性执行
7. 每步都通过 `trace.event()` 输出 trace 事件

---

#### ✅ [MODIFY] CortexTaskManager.java

**路径**: `lxb-core/src/main/java/com/lxb/server/cortex/CortexTaskManager.java`

**变更 1 — 脚本存储路径**：
- 新增常量 `DEFAULT_SCRIPTS_DIR = "/data/local/tmp/lxb/scripts"`
- 新增字段 `scriptsDir`，在构造函数中通过 `resolveScriptsDir()` 初始化
- `resolveScriptsDir()` 支持 `System.getProperty("lxb.scripts.dir")` 覆盖

**变更 2 — 脚本管理方法**（在文件末尾，`// ---- Script Management ----` 注释下）：
- `exportScriptFromTaskResult(String taskId)` → `Map<String, Object>`
  - 从 `taskRegistry` 中找到对应的 COMPLETED 任务
  - 从 `resultSummary.command_log` 提取所有操作步骤
  - 构建 `script.v1` 格式的脚本 JSON
  - 生成 `scriptKey = "task_" + taskId前12位`
  - 调用 `persistence.saveScript()` 持久化
- `listScripts()` → `List<Map<String, Object>>` — 代理到 persistence
- `deleteScript(String scriptKey)` → `boolean`
- `getScript(String scriptKey)` → `Map<String, Object>`
- `findMatchingScript(String userTask)` → `Map<String, Object>` — 按 `user_task` 文本精确匹配（大小写不敏感）
- `getScriptsDir()` → `String`

**变更 3 — submitTask 增加 scriptMode 参数**：
- 新增 `submitTask()` 重载，增加 `String scriptMode` 参数
- `submitTaskInternal()` 新增同名参数，校验为 `"auto"` / `"force"` / `"off"`（默认 `"auto"`）
- `FsmTaskRequest` 类新增 `final String scriptMode` 字段

**变更 4 — workerLoop 集成脚本回放**：
- 在 `fsmEngine.run()` 调用之前，新增脚本回放尝试逻辑
- 流程：
  ```
  if (scriptMode != "off") {
      找匹配脚本 findMatchingScript(userTask)
      if (找到) {
          创建 ScriptReplayEngine
          获取屏幕尺寸 fsmEngine.probeScreenWidth/Height()
          执行 replayEngine.replay(script, taskId, screenW, screenH)
          if (replay 成功) → 构建 success 结果 (包含 script_replay=true)
          else if (scriptMode == "force") → 构建 failed 结果
          else → out=null, 继续走 AI 流水线 (自动降级)
      } else if (scriptMode == "force") {
          → 构建 no_matching_script_found 错误
      }
  }
  if (out == null) {
      out = fsmEngine.run(...)  // 正常 AI 执行
  }
  ```

**变更 5 — resultSummary 持久化**（Fix 7）：
- `snapshotTaskRun()` 新增：当 `resultSummary` 非空时，将其存入 `row.put("result_summary", inst.resultSummary)`
- `parseTaskRunRow()` 新增：从 `row.get("result_summary")` 恢复 `Map<String, Object>` 到 `inst.resultSummary`
- 使历史已完成任务在 lxb-core 重启后仍可导出脚本

---

#### ✅ [MODIFY] CortexFsmEngine.java

**路径**: `lxb-core/src/main/java/com/lxb/server/cortex/CortexFsmEngine.java`

在 `runSystemControl()` 方法之前新增公开方法：

```java
public ExecutionEngine getExecution()     // 暴露给 ScriptReplayEngine 使用
public PerceptionEngine getPerception()   // 暴露给 ScriptReplayEngine 使用
public TraceLogger getTrace()             // 暴露给 ScriptReplayEngine 使用
public int[] probeScreenSize()            // 单次 IPC 获取 {width, height}（第二轮审查合并）
```

注意：**脚本回放逻辑没有放在 CortexFsmEngine.run() 内部**，而是放在 CortexTaskManager.workerLoop() 中，在调用 `fsmEngine.run()` 之前拦截。这样设计更清晰，不侵入 FSM 状态机核心逻辑。

---

#### ✅ [MODIFY] CortexFacade.java

**路径**: `lxb-core/src/main/java/com/lxb/server/cortex/CortexFacade.java`

**变更 1 — 4 个新 API handler**（在 `handleRouteRun` 方法之前，`// ---- Script Replay API handlers ----` 注释下）：

- `handleCortexScriptExport(byte[] payload)` — Payload: `{ "task_id": "..." }` → 调用 `taskManager.exportScriptFromTaskResult()`
- `handleCortexScriptList(byte[] payload)` — 无需参数 → 调用 `taskManager.listScripts()`
- `handleCortexScriptDelete(byte[] payload)` — Payload: `{ "script_key": "..." }` → 调用 `taskManager.deleteScript()`
- `handleCortexScriptGet(byte[] payload)` — Payload: `{ "script_key": "..." }` → 调用 `taskManager.getScript()`

**变更 2 — handleCortexFsmRun 增加 script_mode**：
- 从请求 JSON 中解析 `script_mode` 字段
- 传递给 `taskManager.submitTask()` 的新重载

---

#### ✅ [MODIFY] CommandDispatcher.java

**路径**: `lxb-core/src/main/java/com/lxb/server/dispatcher/CommandDispatcher.java`

在 `CMD_CORTEX_SCHEDULE_UPDATE` case 之后、`default` case 之前，新增 4 个 case：

```java
case CommandIds.CMD_CORTEX_SCRIPT_EXPORT:
    response = cortexFacade.handleCortexScriptExport(payload);
    break;
case CommandIds.CMD_CORTEX_SCRIPT_LIST:
    response = cortexFacade.handleCortexScriptList(payload);
    break;
case CommandIds.CMD_CORTEX_SCRIPT_DELETE:
    response = cortexFacade.handleCortexScriptDelete(payload);
    break;
case CommandIds.CMD_CORTEX_SCRIPT_GET:
    response = cortexFacade.handleCortexScriptGet(payload);
    break;
```

---

### 前端 (LXB-Ignition Android) — 修改 5 个 Kotlin 文件

---

#### ✅ [MODIFY] TaskModels.kt

**路径**: `app/src/main/java/com/example/lxb_ignition/model/TaskModels.kt`

在 `ScheduleSummary` 之后新增 `ScriptSummary` data class：

```kotlin
data class ScriptSummary(
    val scriptKey: String,
    val userTask: String,
    val packageName: String,
    val packageLabel: String,
    val targetPage: String,
    val stepCount: Int,
    val createdAt: Long
)
```

---

#### ✅ [MODIFY] CoreApiParser.kt

**路径**: `app/src/main/java/com/example/lxb_ignition/core/CoreApiParser.kt`

在 `parseSystemControl()` 方法之前新增 4 个解析方法：

- `parseScriptList(payload)` → `Pair<String, List<ScriptSummary>>` — 解析 `{"ok":true, "scripts":[...]}`，按 `createdAt` 降序排列
- `parseScriptExport(payload)` → `Pair<String, String>` — 返回 (消息, scriptKey)
- `parseScriptDelete(payload, scriptKey)` → `String` — 返回操作结果消息
- `parseScriptGet(payload)` → `Pair<String, JSONObject?>` — 返回 (消息, 脚本JSON对象)

---

#### ✅ [MODIFY] TraceEventMapper.kt

**路径**: `app/src/main/java/com/example/lxb_ignition/core/TraceEventMapper.kt`

在 `exec_back_start` case 之后新增 4 个 trace event 映射：

- `fsm_script_replay_begin` → "Script replay started (scriptKey), N steps, running without AI..." + RuntimeUpdate("SCRIPT_REPLAY", ...)
- `fsm_script_replay_success` → "Script replay completed successfully! (N steps executed)" + RuntimeUpdate("DONE", ..., stopAfter=true)
- `fsm_script_replay_step_failed` / `fsm_script_replay_step_error` → "Script replay failed at step N (OP), falling back to AI mode..." + RuntimeUpdate("SCRIPT_FALLBACK", ...)
- `fsm_script_exported` → "Script saved successfully: scriptKey"

---

#### ✅ [MODIFY] MainViewModel.kt

**路径**: `app/src/main/java/com/example/lxb_ignition/MainViewModel.kt`

**新增 StateFlow**（在 `_scheduleList` 之后）：
```kotlin
private val _scriptList = MutableStateFlow<List<ScriptSummary>>(emptyList())
val scriptList: StateFlow<List<ScriptSummary>> = _scriptList.asStateFlow()
val showSaveScriptDialog = MutableStateFlow(false)
val lastCompletedTaskId = MutableStateFlow("")
```

**新增方法**（在 `// ----- Script management -----` 注释下）：

- `refreshScriptList()` — 通过 TCP 发送 `CMD_CORTEX_SCRIPT_LIST`，解析后更新 `_scriptList`
- `exportScript(taskId)` — 发送 `CMD_CORTEX_SCRIPT_EXPORT`，成功后刷新脚本列表
- `deleteScript(scriptKey)` — 发送 `CMD_CORTEX_SCRIPT_DELETE`，完成后刷新脚本列表
- `promptSaveScript(taskId)` — 设置 `lastCompletedTaskId` 和 `showSaveScriptDialog = true`
- `dismissSaveScriptDialog()` — 关闭对话框

**自动弹出保存提示**：
- `observeTaskCompletion()` 方法在 `init` 中启动
- 监听 `taskRuntimeUiStatus` flow
- 当 phase 从非 "DONE" 变为 "DONE" 时，自动调用 `promptSaveScript(taskId)` 和 `refreshTaskListOnDevice()`

---

#### ✅ [MODIFY] MainActivity.kt

**路径**: `app/src/main/java/com/example/lxb_ignition/MainActivity.kt`

**TasksTab 函数变更**：

1. **新增状态收集**：`scripts`、`showSaveDialog`、`lastCompletedTaskId`
2. **"保存为脚本？"对话框**：在 `TasksTab` 顶部，当 `showSaveDialog && lastCompletedTaskId.isNotEmpty()` 时弹出 AlertDialog
3. **LaunchedEffect** 中新增 `viewModel.refreshScriptList()`
4. **"Refresh All"按钮** 中新增 `viewModel.refreshScriptList()`
5. **Task Manager 主页 (page 0)**：在 "Recent Runs" 卡片之后新增 "Scripts" 入口卡片，点击进入 page 4
6. **新增 page 4（Scripts 列表页）**：
   - 顶部：Back 按钮 + "Scripts" 标题 + Refresh 按钮
   - 列表：LazyColumn + ScriptRow 卡片
   - 空状态提示
   - 删除确认对话框

**新增 ScriptRow Composable**（在 `TaskRow` 函数之后）：
- 卡片展示：任务描述 + (App名 | 步骤数 | 创建时间) + 删除按钮
- 删除按钮用 error 颜色

---

### 单元测试 — 新增/修改 2 个测试文件

---

#### ✅ [MODIFY] CortexTaskPersistenceTest.java

**路径**: `lxb-core/src/test/java/com/lxb/server/cortex/CortexTaskPersistenceTest.java`

新增 2 个测试：

- `script_roundTrip()` — 创建脚本 → 保存 → 加载 → 验证字段正确
- `script_listAndDelete()` — 保存 2 个脚本 → listScripts 验证 2 个 → deleteScript → 验证剩 1 个 → 删除不存在的返回 false

#### ✅ [NEW] ScriptReplayEngineTest.java

**路径**: `lxb-core/src/test/java/com/lxb/server/cortex/ScriptReplayEngineTest.java`

新增 3 个测试：

- `extractStepSummaries_basic()` — 3 个步骤（TAP/INPUT/DONE），验证 raw 文本提取正确
- `extractStepSummaries_empty()` — 空 steps 列表 → 返回空
- `extractStepSummaries_nullScript()` — null 输入 → 返回空

**测试结果**：`gradlew :lxb-core:test` 全部通过 ✅

---

## 脚本 JSON 格式

```json
{
  "schema_version": "script.v1",
  "created_at": 1711700000000,
  "source_task_id": "uuid",
  "user_task": "今日校园查寝签到",
  "script_key": "task_abc12345",
  "package_name": "com.wozaixiaoyuan.net",
  "package_label": "今日校园",
  "target_page": "check_in_page",
  "use_map": true,
  "map_source": "stable",
  "route_trace": ["home", "check_in_page"],
  "steps": [
    { "op": "TAP", "args": ["500", "300"], "raw": "TAP 500 300" },
    { "op": "INPUT", "args": ["已到寝"], "raw": "INPUT \"已到寝\"" },
    { "op": "TAP", "args": ["800", "900"], "raw": "TAP 800 900" },
    { "op": "DONE", "args": [], "raw": "DONE" }
  ]
}
```

---

## 关键设计决策

### 1. 脚本回放在 workerLoop 中拦截，而非 FSM run() 内部

回放逻辑放在 `CortexTaskManager.workerLoop()` 中，在 `fsmEngine.run()` 之前执行。如果回放成功，直接构建结果，不进入 FSM；如果失败（auto 模式），将 `out` 设为 null，继续调用 `fsmEngine.run()` 走完整 AI 流水线。

好处：不侵入 FSM 状态机核心逻辑，保持 FSM 代码纯粹。

### 2. 脚本匹配策略：user_task 精确匹配

`findMatchingScript()` 使用 `user_task` 文本的大小写不敏感精确匹配。如果用户输入的任务描述和之前保存脚本时的描述一模一样，就会命中脚本。

### 3. scriptMode 三种模式

- `"auto"`（默认）：有匹配脚本就尝试回放，失败自动降级回 AI
- `"force"`：强制使用脚本，没有匹配或回放失败都直接报错
- `"off"`：完全跳过脚本回放，直接走 AI

### 4. 任务成功后的保存提示

通过 `observeTaskCompletion()` 监听 `taskRuntimeUiStatus` flow，当 phase 变为 "DONE" 时自动弹出"保存为脚本？"对话框。用户确认后调用 `exportScript(taskId)`。

---

## 构建与打包

### 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| **Java** | 11+ (测试通过 Java 23) | Gradle 编译和 d8 转换 |
| **Android SDK** | API 36+ | `compileSdk = release(36)` |
| **Build Tools** | 35.0.0+ | d8 dex 转换 |
| **NDK** | 可选 | 仅用于编译 `lxb-starter` 原生二进制，缺失会跳过 |
| **Gradle** | Wrapper 自带 | 项目已包含 `gradlew`/`gradlew.bat` |

### 首次配置

```powershell
# 1. 创建 local.properties（SDK 路径 + 签名配置）
# 文件位置：android/LXB-Ignition/local.properties
```

```properties
sdk.dir=C:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
LXB_RELEASE_STORE_FILE=lxb-release.jks
LXB_RELEASE_STORE_PASSWORD=lxb123456
LXB_RELEASE_KEY_ALIAS=lxb-key
LXB_RELEASE_KEY_PASSWORD=lxb123456
```

```powershell
# 2. 生成签名密钥（仅首次，之后复用同一个 jks 文件）
cd d:\code\LXB-Framework\android\LXB-Ignition
keytool -genkeypair -v -keystore lxb-release.jks -keyalg RSA -keysize 2048 -validity 36500 -alias lxb-key -storepass lxb123456 -keypass lxb123456 -dname "CN=LXB, OU=Dev, O=LXB, L=CN, ST=CN, C=CN"
```

> [!IMPORTANT]
> `local.properties` 和 `lxb-release.jks` 包含签名密钥，**不要提交到 Git**。

### 运行单元测试

```powershell
cd d:\code\LXB-Framework\android\LXB-Ignition
.\gradlew.bat :lxb-core:test
```

测试用例：
- `CortexTaskPersistenceTest`: `script_roundTrip` / `script_listAndDelete`
- `ScriptReplayEngineTest`: `extractStepSummaries_basic` / `extractStepSummaries_empty` / `extractStepSummaries_nullScript`

### 打包 Release APK

```powershell
cd d:\code\LXB-Framework\android\LXB-Ignition
.\gradlew.bat assembleRelease
```

构建产物位置：

```
app/build/outputs/apk/release/app-release.apk
```

> 如果遇到 lint-cache 文件锁定错误，先停止 Gradle Daemon 再重试：
> ```powershell
> .\gradlew.bat --stop
> Remove-Item -Recurse -Force app\build\intermediates\lint-cache -ErrorAction SilentlyContinue
> .\gradlew.bat assembleRelease
> ```

### 安装到手机

```powershell
# 方式一：ADB 安装（手机需连接电脑并开启 USB 调试）
adb install app/build/outputs/apk/release/app-release.apk

# 方式二：直接传到手机上点击安装
```

> [!WARNING]
> 如果手机上已安装旧版本且签名不同，需要**先卸载旧版本**再安装。同一签名的 APK 可以直接覆盖升级。

### 验证签名

```powershell
# 查看 APK 签名信息
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

---

## v0.4.11 更新：脚本详情查看 + 自定义步骤延时 + NDK 编译修复

### 背景

v0.4.10 存在两个问题：
1. **lxb-starter 缺失**：构建环境缺少 NDK，导致原生启动器未编译进 APK，Core 进程无法启动
2. **脚本回放过快**：回放固定延时 800ms 不够灵活，某些 UI 加载慢的场景会在元素出现前就执行点击

### 构建环境修复

- 安装 Android SDK cmdline-tools（版本 14742923）
- 通过 `sdkmanager` 安装 NDK r26d（26.3.11579264）
- `lxb-starter` 原生启动器现在能正确编译进 APK assets

### ✅ 变更 1 — 移除 TaskSessionCard 上的"保存为脚本"按钮

**文件**: `app/src/main/java/com/example/lxb_ignition/MainActivity.kt`

- 移除 `TaskSessionCard` 中始终灰色不可用的 `Save as Script` OutlinedButton
- 保留最近执行列表中已完成任务详情弹窗里的 `Save as Script` 按钮（该按钮功能正常）
- 保留任务完成时的自动弹窗保存提示

### ✅ 变更 2 — 脚本详情页：显示具体步骤 + 自定义延时

点击脚本列表中的脚本卡片 → 弹出详情对话框 → 显示每一步操作 → 可为每一步设置执行前延时

**新增 UI 组件**（`MainActivity.kt`）：

- `ScriptRow` 新增 `onClick` 参数，点击后加载脚本详情
- `ScriptDetailDialog`：弹窗展示脚本信息和步骤列表
  - 标题区：任务描述 + App 名称
  - 步骤列表：LazyColumn，每步显示序号 + 操作类型（颜色区分）+ 描述
  - 操作类型颜色：TAP 蓝 / SWIPE 紫 / INPUT 橙 / WAIT 灰 / BACK 红 / DONE 绿
  - 每步（除 DONE）有 `Delay before` 输入框，填入毫秒数
  - 修改后出现绿色 `Save Delays` 按钮
- `ScriptStepRow`：单个步骤行组件

**新增数据模型**（`TaskModels.kt`）：

```kotlin
data class ScriptStepInfo(
    val index: Int,
    val op: String,
    val args: List<String>,
    val raw: String,
    val delayBefore: Int
)

data class ScriptDetail(
    val scriptKey: String,
    val userTask: String,
    val packageName: String,
    val packageLabel: String,
    val steps: List<ScriptStepInfo>
)
```

**新增解析方法**（`CoreApiParser.kt`）：

- `parseScriptDetail(payload)` → `Pair<String, ScriptDetail?>` — 解析完整脚本 JSON 为 `ScriptDetail`，包含每步的 `delay_before`
- `parseScriptUpdate(payload)` → `String` — 解析更新响应

**新增 ViewModel 方法**（`MainViewModel.kt`）：

- `currentScriptDetail: StateFlow<ScriptDetail?>` — 当前查看的脚本详情
- `loadScriptDetail(scriptKey)` — 通过 `CMD_CORTEX_SCRIPT_GET` 加载完整脚本
- `clearScriptDetail()` — 关闭详情对话框
- `updateScriptStepDelays(scriptKey, delays)` — 通过 `CMD_CORTEX_SCRIPT_UPDATE` 保存步骤延时

### ✅ 变更 3 — 后端：脚本更新命令 + 回放引擎读取延时

**新增协议 ID**（`CommandIds.java`）：

```java
public static final byte CMD_CORTEX_SCRIPT_UPDATE = (byte) 0x82; // update script step delays
```

**新增路由**（`CommandDispatcher.java`）：

```java
case CommandIds.CMD_CORTEX_SCRIPT_UPDATE:
    response = cortexFacade.handleCortexScriptUpdate(payload);
    break;
```

**新增 API handler**（`CortexFacade.java`）：

- `handleCortexScriptUpdate(byte[] payload)` — Payload: `{ "script_key": "...", "step_delays": [0, 2000, ...] }`
  - 解析延时数组
  - 调用 `taskManager.updateScriptStepDelays()` 持久化

**新增方法**（`CortexTaskManager.java`）：

- `updateScriptStepDelays(String scriptKey, List<Number> delays)` → `boolean`
  - 加载脚本 → 遍历 steps → 设置 `delay_before` 字段（为 0 则移除）→ 保存 → 失效缓存

**回放引擎改造**（`ScriptReplayEngine.java`）：

在每步执行前读取 `delay_before` 字段：

```java
Object delayObj = step.get("delay_before");
if (delayObj instanceof Number) {
    long delayMs = ((Number) delayObj).longValue();
    if (delayMs > 0) {
        sleepQuiet(delayMs);
    }
}
```

此延时叠加在原有 800ms `STEP_SETTLE_MS` 之上，用户可按需为特定步骤增加额外等待时间。

### 脚本 JSON 格式（v0.4.11 扩展）

`steps` 数组中每个步骤新增可选字段 `delay_before`（单位 ms）：

```json
{
  "steps": [
    { "op": "TAP", "args": ["500", "300"], "raw": "TAP 500 300", "delay_before": 2000 },
    { "op": "INPUT", "args": ["已到寝"], "raw": "INPUT \"已到寝\"" },
    { "op": "TAP", "args": ["800", "900"], "raw": "TAP 800 900", "delay_before": 1500 },
    { "op": "DONE", "args": [], "raw": "DONE" }
  ]
}
```

### 构建与打包

环境要求更新：

| 工具 | 版本 | 说明 |
|------|------|------|
| **NDK** | r26d (26.3.11579264) | **必需**，用于编译 `lxb-starter` 原生启动器 |

安装 NDK（如未安装）：

```powershell
# 1. 安装 cmdline-tools（如未安装）
curl.exe -L -o "$env:TEMP\cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip"
$dest = "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools"
Expand-Archive "$env:TEMP\cmdline-tools.zip" "$dest\tmp" -Force
Move-Item "$dest\tmp\cmdline-tools" "$dest\latest" -Force
Remove-Item "$dest\tmp" -Recurse -Force

# 2. 安装 NDK
echo "y" | & "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "ndk;26.3.11579264"
```

---

## v0.4.13 修复：脚本回放完成信号 + AI 任务完成信号 + 回放后返回桌面

### 背景

v0.4.12 及之前版本存在两个问题：
1. **脚本回放成功后无信号**：回放在 `fsmEngine.run()` 之前拦截，但 trace push（UDP 事件推送）仅在 `fsmEngine.run()` 内部配置，导致回放的 trace 事件不会推送到 Android App，UI 无法收到 DONE 信号
2. **AI 执行成功后 UI 也不会立即更新**：FSM 引擎在 FINISH/FAIL 终态时从未发出 `fsm_state_enter` 事件，尽管 `TraceEventMapper` 已有对应处理代码。UI 只能在用户手动切换页面触发 `refreshTaskListOnDevice()` 时才能看到任务完成

### ✅ Fix 1 — 脚本回放 trace push 配置 + 返回桌面

**文件**: `CortexTaskManager.java` — `workerLoop()`

- 回放前：当检测到匹配脚本时，配置 `trace.setPushTarget()` 使回放事件能推送到 Android App
- 回放成功后：调用 `fsmEngine.goHomeAndStopApp()` 按 HOME 键返回桌面并停止 App（与 AI 执行成功后的行为一致）
- 回放处理完毕后：调用 `trace.clearPushTarget()` 清理推送目标
- 若回放失败走 AI 降级（auto 模式），不清理推送 — `fsmEngine.run()` 会自行管理

### ✅ Fix 2 — CortexFsmEngine 新增 goHomeAndStopApp 公开方法

**文件**: `CortexFsmEngine.java`

- 新增 `public void goHomeAndStopApp(String taskId, String packageName)`
- 从 `safeResetToHomeAndStopApp()` 提取相同逻辑：按 HOME 键 + force-stop App
- 供 `CortexTaskManager` 在脚本回放成功后调用

### ✅ Fix 3 — FSM FINISH/FAIL 终态发出 trace 事件

**文件**: `CortexFsmEngine.java` — `run()` 方法

- 在 `tryAutoLockAfterTask()` 之后、构建返回结果之前，新增 `trace.event("fsm_state_enter", {task_id, state: "FINISH"/"FAIL"})`
- 这使得 `TraceEventMapper` 中已有的 FINISH→DONE / FAIL→FAILED 映射能正确触发
- 事件在 `clearPushTarget()` 之前发出，确保能通过 UDP 推送到 Android App
- 修复了 AI 执行成功后 UI 不会立即更新的问题（现在 `observeTaskCompletion()` 能正确检测到 DONE 阶段，自动刷新任务列表和弹出保存脚本对话框）

### 版本号

- `versionCode`: 411 → 413
- `versionName`: `"0.4.11"` → `"0.4.13"`

---

## Manual Verification（端到端测试）

1. AI 模式执行一次任务 → UI 应自动显示"Task finished successfully" + 弹出"保存为脚本？"对话框 + 任务列表自动刷新
2. 保存为脚本后，再次执行同样任务 → 应优先使用脚本回放（不调用 AI）→ 回放完成后应自动返回桌面 + UI 显示 DONE 状态
3. 断开 AI（填错 API Key）→ 再次执行同样任务，验证脚本回放成功
4. 修改 App 导致回放失败 → 验证自动降级回 AI 模式
5. 在 Scripts 区域查看脚本列表 → 点击脚本查看步骤详情 → 为需要等待的步骤添加延时 → Save Delays
6. 再次执行脚本回放 → 验证延时生效，不再出现 UI 未加载就点击的问题
7. 删除脚本 → 验证列表刷新
