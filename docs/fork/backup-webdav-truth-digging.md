# 备份/WebDAV 真机缺陷排查（2026-10-03）

> **状态**：三处缺陷已定位并修复，门禁通过（`2277 tests / 1 既有失败`、`assembleRelease` 成功）。
> **真机复验**：**未做**（修复后未再上机）。
>
> 起因：用户按文档配了一条「自动备份」工作流（获取当前时间 → 导出备份 → WebDAV 上传），
> 两次执行都失败，贴来了日志 `.mindfs/upload/2026-10-03/vflow_log_20261003_224738.txt`。

---

## 0. 三处缺陷一览

| # | 现象 | 根因 | 归属 |
|---|---|---|---|
| 1 | 文件名里的 `{{now.time}}` **没被解析**，原样落盘 | `BackupExportModule.execute()` 把 `file_name` 直接交给 `sanitizeBackupFileName`，**没过 `VariableResolver`** | fork 新增代码（T5） |
| 2 | 下一步引用本步骤输出 ⇒ 报「本地文件不存在：…/自动备份_22:47:11test.json」 | 上一条的**连带**：输出 `file_path` 是**已解析**的（读的是 `file.absolutePath`），而磁盘上那个文件叫 `自动备份_{{now.time}}test.json` ⇒ 两边不一致 | 同 #1 |
| 3 | 上传 409 `AncestorsNotFound`，错误串只有「HTTP 409 + 服务器 XML」，**无法判断错在哪** | 错误信息里**没有实际请求的 URL** | fork 新增代码（T4） |
| 4 | 通知先闪「失败: …」又被覆盖成「执行完毕」，且是 `setOngoing`（划不掉） | `WorkflowExecutor.execute()` 在主流程返回后**无条件**写 `Completed`，而 `executeWorkflowInternal` 失败时是 `return null`（不抛异常） | **上游既有**的实现形态，fork 首次修 |

---

## 1. 缺陷 1/2：`file_name` 没走模板解析

### 实机证据

```
22:47:12.055  [进度] 导出备份: 备份已写入：/storage/emulated/0/vFlow/backups/自动备份_{{now.time}}test.json
22:47:12.084  模块执行失败: WebDAV 操作失败 - 本地文件不存在：/storage/emulated/0/vFlow/backups/自动备份_22:47:11test.json
```

两条日志的**文件名不同** —— 这是本缺陷最刺眼的特征。

### 根因

```kotlin
// 修前
val fileName = sanitizeBackupFileName(step.parameters[PARAM_FILE_NAME] as? String)
```

而**同一函数里**的口令参数是走解析的：

```kotlin
val password = if (rawPassword.isBlank()) "" else VariableResolver.resolve(rawPassword, context)
```

⇒ 唯独 `file_name` 漏了。参数声明里 `acceptsMagicVariable = true` 也就形同虚设。

### 修法

```kotlin
// 修后
val rawFileName = (step.parameters[PARAM_FILE_NAME] as? String).orEmpty()
val fileName = sanitizeBackupFileName(
    if (rawFileName.isBlank()) rawFileName else VariableResolver.resolve(rawFileName, context)
)
```

⚠️⚠️ **顺序不能反**（解析在前、sanitize 在后）：

- sanitize 只做「剥目录 + 防空」。若先 sanitize 再解析，模板里的 `/`（如 `{{vars.dir}}/x.json`）
  会在解析阶段才出现，**绕过了目录剥离**。
- 反过来，解析结果含 `/` 正是 sanitize 要剥的 —— 这才是正确顺序。

⚠️ **不加「解析失败就报错」**：`VariableResolver` 对解析不掉的模板**原样返回**，
这是仓库里所有 STRING 参数的既有语义（`FileOperationModule` 等一样）。本模块单独拦会让它成异类。
已用 `sanitize does not validate template syntax` 把这条**已知行为**写成断言，免得将来有人当缺陷"修"掉。

### 测试

| 用例 | 锁什么 |
|---|---|
| `a template in file_name is resolved before sanitizing` | 正序给出 `自动备份_22:47:11test.json` |
| `sanitizing before resolving would leak the template into the file name` | **反序会残留 `{{now.time}}`**，并与正序结果**不同** |
| `sanitize does not validate template syntax` | 已知行为（不校验模板） |
| `execute resolves the file name template before sanitizing` | **源码扫描型接线锚定** |

⚠️⚠️ **最后那条是必需的**：前三条测的是**纯函数语义**，对「生产代码有没有真的调 `VariableResolver`」
**完全无感**。我实测验证过：把 `execute()` 里的解析删掉，前三条**照样全绿**（**3 个失败用例数 = 0**）。
这与本仓库反复踩的盲区同源（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、
`XposedDiagnostics.messageFor` 零调用点）—— 只能靠源码扫描锁调用点。
**反证已做**：删掉解析 ⇒ 该条变红。

---

## 2. 缺陷 3：409 的报错没有任何可操作信息

### 实机证据

```
22:47:34.091  模块执行失败: 上传失败 - HTTP 409: <?xml version="1.0" ...>
              <s:exception>AncestorsNotFound</s:exception>
              <s:message>The ancestors of this location d…
```

用户看到这句**无从下手**：是路径拼错了？还是那个目录不存在？两种的处置完全不同
（改路径 vs 先去服务器上建目录）。

### 根因

`WebDavResult.HttpError(code, detail)` 只有状态码 + 服务器正文，**没有「请求打到哪个 URL」**。

### 修法

`HttpError` 增加 `url` 字段（带默认值 `null`，向后兼容）：

```kotlin
data class HttpError(val code: Int, val detail: String?, val url: String? = null) : WebDavResult
```

主循环构造时把**跟随过重定向后的最终 `url`** 带上（不是最初的 —— 否则重定向后报错指向一个没被请求的地方）。

模块侧 5 处 `HttpError` 分支统一走 `httpErrorDetail(result)`，输出形如：

```
上传失败 - HTTP 409: <XML>（目标：https://dav.jianguoyun.com/dav/自动备份_test.json）
```

⚠️ **展示前必须解码**：`HttpUrl.toString()` 把中文段编成 `%E8%87%AA%E5%8A%A8...`，
而备份文件名**默认就带中文** ⇒ 直接给用户看等于没给。新增
`WebDavUrlBuilder.readableHttpUrl(url)` 专做这件事。

⚠️ **它只用于展示，绝不能用于请求** —— 解码后的串不再是合法 URL
（空格 / `#` / `?` 会改变语义）。有 `readable url is not a valid request form` 一条断言把
「请求形式」与「展示形式」**必须不同**锁住。

⚠️ **URL 里不含凭据**（Basic Auth 走 header，不拼进 URL）⇒ 可安全进工作流日志。有断言。

### 附带诊断（未改代码的部分）

从 409 的路径 `…/dav/自动备份_test.json` 看，**`remoteBasePath` 大概是空的** ——
而坚果云的 `/dav/` 是**虚拟根**，真实可写的只有 `/dav/<用户名>/`。
⇒ 实测建议：把 WebDAV 配置的「远端基础路径」填成自己的用户名目录。

### 关于 409：已补「自动建父目录」（**用户指出后补做**）

第一轮我只做了「把 URL 告诉用户」这一半，理由是：

- **语义上有歧义**：409 也可能是 `If-None-Match` 冲突等其它原因；
- **需要递归探测**：要逐级 PROPFIND 才知道哪一级缺，请求数从 1 涨到 N；
- **权限问题会被掩盖**。

**用户指出这不够**（「你没有加新建文件夹的逻辑吗」）—— 对。我漏了：409 在本项目的实际情境里
**绝大多数就是「祖先目录不存在」**（用户配的是「备份到 `backups/`」，而那个目录是新建的），
只报错不改，等于把「应该自动完成的事」推给用户手工做。

**补做的实现**（`WebDavClient.ensureCollectionsFor`）：

1. **不逐级 PROPFIND 探测** —— 直接对**每一级**都 MKCOL。已存在的回 405
   （RFC 4918：对已存在的集合做 MKCOL ⇒ 405）—— **那是成功**，继续下一级。
   请求数从「1 + 2N」降到「N」，且不需要判断「哪一级缺」。
2. **只往上建到 `remoteBasePath` 之后** —— 段列表由 `WebDavUrlBuilder.splitSegmentsForAncestors`
   产出（与 `resolve` **共用同一套** `splitSegments`：滤空段、拦 `..`、拦控制字符），故不穿越。
3. **不建最后一段** —— 那是文件本身，是 PUT 的活。
4. **接入点**：`upload` 遇 409 时补建后**重传一次**（恰一次，防死循环）；
   `mkdir` 遇 409 时补建父级后**重试一次**。
5. **失败语义**：某一级建不出来（权限/配额）⇒ 立刻停手、返回 false，
   **不再继续往上建**（避免产生一串失败请求与半截目录树），并给出明确文案
   「父目录不存在，且无法自动创建：<path>」。

⚠️ 三条**没变**的顾虑仍成立，故实现上做了规避：
`If-None-Match` 冲突时 **`overwrite=false`** 走的是 412（不是 409），**不会**误触发补建；
权限问题**不掩盖** —— 补建失败会明确说「父目录不存在，且无法自动创建」，
而不是笼统的「上传失败」。

⚠️ 测试 6 例，**反证 2 条**：不 `dropLast`（会多建文件那段）⇒ **4 条红**；
405 不算成功 ⇒ **2 条红**。

---

## 3. 缺陷 4：失败被执行覆盖成「执行完毕」

### 实机证据

```
22:47:12.084  模块执行失败: WebDAV 操作失败 - 本地文件不存在：…
22:47:12.088  尝试 1: 直接调用 startActivity...        ← 错误弹窗
22:47:15.416  已附加岛参数 state=COMPLETED              ← 3 秒后被覆盖成"完成"
```

（三次执行逐次复现。）

### 根因

```kotlin
// 修前 —— WorkflowExecutor.execute()
if (!isTimeout) {
    ExecutionNotificationManager.updateState(workflow, ExecutionNotificationState.Completed("执行完毕"))
}
```

`executeWorkflowInternal` 在模块失败且策略为 STOP 时是 **`return null`**（**不抛异常**）
⇒ 这条无条件分支照样执行。而终态通知有 `setOngoing(true)` ——
用户**划不掉**，只能等系统超时或手动清。

### 修法

```kotlin
// 修后 —— 读 failedExecutions，判「这次到底成没成」
val failed = failedExecutions[executionInstanceId] == true
if (!isTimeout && !failed) {
    ExecutionNotificationManager.updateState(workflow, ExecutionNotificationState.Completed("执行完毕"))
}
```

`failedExecutions` 由模块失败分支**置位**（`failedExecutions[executionInstanceId] = true`），
成功与用户主动停止都不置位 —— 正好是「要不要冒称成功」的分界。

⚠️⚠️ **读标记，绝不能 `remove`**：`failedExecutions.remove(...)` 在下面的
`finally`（`withContext(NonCancellable)`）里执行并赋给 `wasFailureHandled`。
若在这里 `remove`，那个标记会被吃掉 ⇒ `finally` 里的 `if (!wasFailureHandled)` 变成真
⇒ **再广播一次 `Finished`**，把失败状态在 `ExecutionStateBus` 上盖掉
（**比通知更难发现** —— 触发器与 UI 都看这条总线）。

### 测试

`ExecutionNotificationFinalStateWiringTest`（源码扫描，2 例）：

| 用例 | 反证结果 |
|---|---|
| `completed notification is guarded by the failure flag` | 把 `&& !failed` 拆掉 ⇒ **1 条红** |
| `the guard reads the failure flag without consuming it` | 把「读」改成 `remove` ⇒ **1 条红** |

⚠️ **写这条测试时踩了一个坑，值得记**：`SourceScan.functionBody` 的签名片段**只能用单行**。
`WorkflowExecutor.kt` 是 **CRLF 行尾**（`file` 报 `with CRLF line terminators`），
带 `\n` 的多行片段**匹配不到** ⇒ 测试拿到 `null`、断言失败。
用 `"    fun execute("`（连前导四空格）即可避开与 `executeSubWorkflow` / `executeWorkflowInternal` 撞名。
**这个坑正是「防空转断言」救回来的** —— 若没有 `assertTrue(body != null)`，那两条断言会在
`null` 上静默空转（`null!!` 抛异常 vs `contains` 恒 false，都是"红"，但原因会指向错误的方向）。

---

## 4. 一处**刻意不改**的观察（留给用户决策）

`WorkflowExecutor` 的失败分支里有：

```kotlin
ExecutionNotificationManager.updateState(workflow, ExecutionNotificationState.Failed("失败: ${result.errorMessage}"))
```

**这个标题里的 `失败: ` 前缀是错的** —— 执行器写的是原始 `errorMessage`
（真机上是「WebDAV 操作失败 - 本地文件不存在：…」），前缀一叠就成了
「失败: WebDAV 操作失败 - 本地文件不存在：…」。同一段里还有
`ExecutionNotificationManager.updateState(workflow, ExecutionNotificationState.Failed("执行超时（…）"))`
—— 那一处**没有**前缀。两处风格不一致。

⇒ 一个字的改动能修好，但本轮**没做**：它与本次三处缺陷无关，
且「通知标题要不要带『失败:』前缀」是个**产品表述**问题（有前缀更醒目，也说得通）。
**留给用户拍板**。

---

## 5. 门禁与反证

| 命令 | 结果 |
|---|---|
| `./gradlew test` | **2277 tests completed, 1 failed, 1 skipped**（唯一失败 = 既有 `VObjectPropertyTest`） |
| `./gradlew assembleRelease` | **BUILD SUCCESSFUL** |
| `apksigner verify` | `CN=vFlow Fork, O=nightking8342` |

**反证（5 条，全部实际执行并确认变红）**：

| # | 改坏 | 变红 |
|---|---|---|
| 1 | `execute()` 里删掉 `VariableResolver.resolve(rawFileName` | **1 条**（接线锚定） |
| 2 | `HttpError` 构造不带 `url` | **2 条**（真机 URL / 重定向后最终 URL） |
| 3 | `Completed` 的 `&& !failed` 拆掉 | **1 条** |
| 4 | 守卫里的「读」改成 `remove` | **1 条** |
| 5 | （副产物）`sanitize` 的纯函数用例对 #1 —— **全绿**，证明它们测不到调用点 | — |

⚠️ 第 5 条不是失败，是**发现**：它正是「接线锚定」这条测试存在的理由。

---

## 6. 未做 / 未验证

| 项 | 说明 |
|---|---|
| **真机复验** | 修复后**未再上机**。三处都只到「单测 + release 打包」这一层 |
| **409 的实测确认** | 「`remoteBasePath` 为空导致 PUT 到坚果云虚拟根」是**从日志路径推断**的，未经实测确认。修复后错误串会带上完整 URL，届时可直接判定 |
| 自动建祖先目录 | 见 §2 末 —— 语义歧义 + 递归探测 + 掩盖权限问题，本轮不做 |
| `失败: ` 前缀 | 见 §4 —— 产品表述，留给用户 |
