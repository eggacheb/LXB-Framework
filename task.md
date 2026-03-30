# Script Replay 实现任务清单

## 后端 (lxb-core)

- [x] **P0 — CortexTaskPersistence: 脚本文件读写**
  - [x] 新增 `saveScript(scriptDir, scriptKey, script)` — 将脚本写入 `scriptDir/scriptKey.json`
  - [x] 新增 `loadScript(scriptDir, scriptKey)` → `Map<String, Object>` — 读取并解析脚本
  - [x] 新增 `listScripts(scriptDir)` → `List<Map<String, Object>>` — 遍历目录列出所有脚本
  - [x] 新增 `deleteScript(scriptDir, scriptKey)` → `boolean` — 删除脚本文件
  - [x] 新增单元测试 `script_roundTrip` / `script_listAndDelete`
  - 位置：`writeJsonAtomically()` 方法之前，`// ---- Script persistence ----` 注释下

- [x] **P0 — CommandIds: 新增协议 ID**
  - [x] `CMD_CORTEX_SCRIPT_EXPORT = 0x7E`
  - [x] `CMD_CORTEX_SCRIPT_LIST = 0x7F`
  - [x] `CMD_CORTEX_SCRIPT_DELETE = (byte) 0x80`
  - [x] `CMD_CORTEX_SCRIPT_GET = (byte) 0x81`

- [x] **P0 — ScriptReplayEngine: 回放引擎（新建文件）**
  - [x] 新建类 `ScriptReplayEngine`（~310行）
  - [x] `replay(script, taskId, screenW, screenH)` → `ReplayResult` 主方法
  - [x] 支持操作：TAP / SWIPE / INPUT / WAIT / BACK / DONE
  - [x] `launchApp(packageName, taskId)` — 通过 ExecutionEngine.handleLaunchApp
  - [x] `mapNormalized(normalized, screenDim)` — 归一化坐标 [0,1000] → 物理像素
  - [x] INPUT 方法：ASCII 先 ADB 后 Clipboard，非 ASCII 反过来（与 FSM 一致）
  - [x] 每步间等待 800ms (STEP_SETTLE_MS)
  - [x] 所有步骤通过 `trace.event()` 输出 trace 事件
  - [x] `ReplayResult` 内部类：success / reason / stepsExecuted
  - [x] `extractStepSummaries(script)` 静态工具方法
  - [x] 新增单元测试 `ScriptReplayEngineTest`（3个测试）

- [x] **P0 — CortexTaskManager: 脚本管理**
  - [x] 新增 `DEFAULT_SCRIPTS_DIR` 常量 + `scriptsDir` 字段 + `resolveScriptsDir()`
  - [x] `exportScriptFromTaskResult(taskId)` → `Map<String, Object>`
    - 从 taskRegistry 找 COMPLETED 任务 → 提取 command_log → 构建 script.v1 JSON → 持久化
    - scriptKey 格式: `"task_" + taskId前12位`
  - [x] `listScripts()` / `deleteScript(scriptKey)` / `getScript(scriptKey)`
  - [x] `findMatchingScript(userTask)` — 按 user_task 大小写不敏感精确匹配
  - [x] `submitTask()` 新增 `String scriptMode` 参数重载
  - [x] `submitTaskInternal()` 新增 scriptMode 参数（校验 auto/force/off，默认 auto）
  - [x] `FsmTaskRequest` 新增 `final String scriptMode` 字段
  - [x] `workerLoop()` 中在 `fsmEngine.run()` 之前插入脚本回放逻辑：
    - scriptMode != "off" 时，尝试 findMatchingScript
    - 找到 → ScriptReplayEngine.replay()
    - 成功 → 构建 success 结果 (script_replay=true)
    - 失败 + force模式 → 构建 failed 结果
    - 失败 + auto模式 → out=null，继续走 AI (自动降级)

- [x] **P0 — CortexFsmEngine: 暴露 getter 和屏幕探测**
  - [x] `getExecution()` / `getPerception()` / `getTrace()` — 给 ScriptReplayEngine 使用
  - [x] `probeScreenSize()` → `int[]{width, height}` — 单次 IPC 获取屏幕尺寸（第二轮审查合并）
  - 注意：回放逻辑在 CortexTaskManager 中，不在 FSM run() 内部

- [x] **P1 — CortexFacade: API 暴露**
  - [x] `handleCortexScriptExport(payload)` — Payload: `{"task_id":"..."}` → taskManager.exportScriptFromTaskResult()
  - [x] `handleCortexScriptList(payload)` — 无参数 → taskManager.listScripts()
  - [x] `handleCortexScriptDelete(payload)` — Payload: `{"script_key":"..."}` → taskManager.deleteScript()
  - [x] `handleCortexScriptGet(payload)` — Payload: `{"script_key":"..."}` → taskManager.getScript()
  - [x] `handleCortexFsmRun` 中解析 `script_mode` 字段，传递给 submitTask
  - 位置：`handleRouteRun()` 方法之前，`// ---- Script Replay API handlers ----` 注释下

- [x] **P1 — CommandDispatcher: 注册路由**
  - [x] 在 switch-case 中新增 4 个 case（CMD_CORTEX_SCHEDULE_UPDATE 之后、default 之前）

## 前端 (LXB-Ignition Android)

- [x] **P1 — TaskModels: 新增 ScriptSummary**
  - [x] `data class ScriptSummary(scriptKey, userTask, packageName, packageLabel, targetPage, stepCount, createdAt)`

- [x] **P1 — CoreApiParser: 响应解析**
  - [x] `parseScriptList(payload)` → `Pair<String, List<ScriptSummary>>` — 解析 scripts 数组，按 createdAt 降序
  - [x] `parseScriptExport(payload)` → `Pair<String, String>` — 返回 (消息, scriptKey)
  - [x] `parseScriptDelete(payload, scriptKey)` → `String` — 返回操作结果消息
  - [x] `parseScriptGet(payload)` → `Pair<String, JSONObject?>` — 返回 (消息, 脚本JSON)
  - 位置：`parseSystemControl()` 方法之前

- [x] **P1 — TraceEventMapper: trace 事件映射**
  - [x] `fsm_script_replay_begin` → "Script replay started..." + RuntimeUpdate("SCRIPT_REPLAY", ...)
  - [x] `fsm_script_replay_success` → "Script replay completed successfully!" + RuntimeUpdate("DONE", ..., stopAfter=true)
  - [x] `fsm_script_replay_step_failed` + `fsm_script_replay_step_error` → "Script replay failed..." + RuntimeUpdate("SCRIPT_FALLBACK", ...)
  - [x] `fsm_script_exported` → "Script saved successfully: scriptKey"
  - 位置：`exec_back_start` case 之后

- [x] **P1 — MainViewModel: 脚本管理逻辑**
  - [x] 新增 StateFlow: `_scriptList` / `showSaveScriptDialog` / `lastCompletedTaskId`
  - [x] `refreshScriptList()` — TCP CMD_CORTEX_SCRIPT_LIST → parseScriptList → 更新 _scriptList
  - [x] `exportScript(taskId)` — TCP CMD_CORTEX_SCRIPT_EXPORT → 成功后 refreshScriptList()
  - [x] `deleteScript(scriptKey)` — TCP CMD_CORTEX_SCRIPT_DELETE → 完成后 refreshScriptList()
  - [x] `promptSaveScript(taskId)` / `dismissSaveScriptDialog()`
  - [x] `observeTaskCompletion()` — init 中启动，监听 taskRuntimeUiStatus，phase 变 DONE 时自动弹对话框
  - 位置：`// ----- Script management -----` 和 `// ----- LLM config and test -----` 之间

- [x] **P2 — MainActivity: UI 界面**
  - [x] TasksTab 新增 scripts / showSaveDialog / lastCompletedTaskId 状态收集
  - [x] "保存为脚本？" AlertDialog（showSaveDialog 时弹出，Save/Skip 两个按钮）
  - [x] LaunchedEffect 新增 refreshScriptList()
  - [x] "Refresh All" 按钮新增 refreshScriptList()
  - [x] page 0 新增 "Scripts" 入口卡片（显示脚本数量）→ 点击进入 page 4
  - [x] **page 4（Scripts 列表页）**：
    - Back + "Scripts" 标题 + Refresh 按钮
    - LazyColumn + ScriptRow 卡片
    - 空状态提示："No scripts yet..."
    - 删除确认 AlertDialog
  - [x] **ScriptRow Composable**（在 TaskRow 之后）：
    - 卡片展示：任务描述 + (App名 | 步骤数 | 创建时间) + 删除按钮（error 颜色）

## 验证

- [x] 运行 `gradlew :lxb-core:test` 通过 ✅
- [ ] 端到端测试（需真机）：
  1. AI 模式执行一次任务 → UI 自动弹出"保存为脚本？"对话框 → 确认保存（或手动点击绿色 Save as Script 按钮）
  2. 再次执行同样任务 → 应优先使用脚本回放（不调用 AI）
  3. 回放失败场景（App UI 变化）→ 验证自动降级回 AI
  4. Scripts 页面查看脚本列表 → 删除脚本

---

## 第二轮代码审查修复

- [x] **Fix 1** — `ScriptReplayEngine.sleepQuiet` 恢复中断标志 + 移除未使用的 `PerceptionEngine` 参数
- [x] **Fix 2** — `ChatBubble` 新增 `MessageSeverity` 枚举，修复中文模式下颜色判断失效
- [x] **Fix 3** — `CortexFsmEngine.probeScreenWidth/Height` 合并为 `probeScreenSize()` 单次 IPC
- [x] **Fix 4** — `CortexTaskManager.findMatchingScript` 添加内存缓存，save/delete 自动失效
- [x] **Fix 5** — "Save as Script" 按钮改为常驻 + 任务详情弹窗中为 COMPLETED 任务添加保存按钮
- [x] **Fix 6** — 版本号更新 `0.4.7` → `0.4.8` (versionCode 407 → 408)
- [x] **Fix 7** — `resultSummary` 持久化到磁盘，重启后历史已完成任务仍可导出脚本

---

## 构建与打包命令

### 环境要求

- Java 11+（测试通过 Java 23）
- Android SDK API 36+、Build-Tools 35+
- NDK 可选（仅编译 lxb-starter 原生二进制，缺失跳过）

### 首次配置

```powershell
# 进入项目目录
cd d:\code\LXB-Framework\android\LXB-Ignition

# 生成签名密钥（仅首次）
keytool -genkeypair -v -keystore lxb-release.jks -keyalg RSA -keysize 2048 -validity 36500 -alias lxb-key -storepass lxb123456 -keypass lxb123456 -dname "CN=LXB, OU=Dev, O=LXB, L=CN, ST=CN, C=CN"
```

`local.properties` 内容（SDK 路径 + 签名配置）：

```properties
sdk.dir=C:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
LXB_RELEASE_STORE_FILE=lxb-release.jks
LXB_RELEASE_STORE_PASSWORD=lxb123456
LXB_RELEASE_KEY_ALIAS=lxb-key
LXB_RELEASE_KEY_PASSWORD=lxb123456
```

> ⚠️ `local.properties` 和 `lxb-release.jks` 不要提交到 Git。

### 运行单元测试

```powershell
cd d:\code\LXB-Framework\android\LXB-Ignition
.\gradlew.bat :lxb-core:test
```

### 打包 Release APK

```powershell
cd d:\code\LXB-Framework\android\LXB-Ignition
.\gradlew.bat assembleRelease
```

产物位置：`app/build/outputs/apk/release/app-release.apk`

遇到 lint-cache 锁定错误时：

```powershell
.\gradlew.bat --stop
Remove-Item -Recurse -Force app\build\intermediates\lint-cache -ErrorAction SilentlyContinue
.\gradlew.bat assembleRelease
```

### 安装到手机

```powershell
adb install app/build/outputs/apk/release/app-release.apk
```

> 签名不同时需先卸载旧版：`adb uninstall com.example.lxb_ignition`

### 验证 APK 签名

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 文件变更汇总

| 文件 | 类型 | 行数变化 | 第二轮修复 |
|------|------|----------|-----------|
| `CortexTaskPersistence.java` | MODIFY | +56 行（4 个方法） | — |
| `CommandIds.java` | MODIFY | +5 行（4 个常量） | — |
| `ScriptReplayEngine.java` | **NEW** | **~310 行**（整个类） | Fix 1: sleepQuiet 中断 + 移除 PerceptionEngine 参数 |
| `CortexTaskManager.java` | MODIFY | +193 行 | Fix 4: 脚本缓存 + Fix 7: resultSummary 持久化 |
| `CortexFsmEngine.java` | MODIFY | +25 行 | Fix 3: probeScreenSize() 合并 |
| `CortexFacade.java` | MODIFY | +90 行（4 个 handler） | — |
| `CommandDispatcher.java` | MODIFY | +12 行（4 个 case） | — |
| `TaskModels.kt` | MODIFY | +10 行（ScriptSummary） | — |
| `CoreApiParser.kt` | MODIFY | +55 行（4 个解析方法） | — |
| `TraceEventMapper.kt` | MODIFY | +30 行（4 个 event） | — |
| `MainViewModel.kt` | MODIFY | +110 行 | Fix 2: MessageSeverity + inferMessageSeverity |
| `MainActivity.kt` | MODIFY | +150 行 | Fix 2: ChatBubble severity + Fix 5: 常驻按钮 + 详情保存按钮 |
| `build.gradle.kts (app)` | MODIFY | 版本号 | Fix 6: 0.4.7 → 0.4.8 |
| `CortexTaskPersistenceTest.java` | MODIFY | +60 行（2 个测试） | — |
| `ScriptReplayEngineTest.java` | **NEW** | **~60 行**（3 个测试） | — |
| **合计** | **15 个文件** | **约 +1170 行** | **7 项修复** |
