# 全局备份/恢复 + WebDAV 设计

> **版本**：v1.0 · 2026-10-03（**已实现；真机验证未做**，见 §8）
> **对应分支**：`dev`（基于 `versionName 1.5.4` / `versionCode 50`）
> **目录归属**：fork 独有文档 → 冲突归**我方**（上游无此文件）
> **状态**：✅ 已实现（`core/backup/**` + `core/webdav/**` + 两个设置页二级页 + 两个工作流模块），
> 2260 例单测通过（1 例既有失败`VObjectPropertyTest`，与本批无关），release 打包通过。
> ⚠️ **真机验证 0 项**——本机 `adb devices` 为空，全部验收只到「单测 + release 打包」这一层。
> ✅ 2026-10-03 集成期收敛了 `WebDavProbe` / `WebDavClient` 的重复 HTTP 装配（§3.7.1）。

---

## 0. 一句话方案

把「备份」从「只备工作流 + 文件夹」扩展成**按范围勾选的全局备份**（**范围注册表**驱动 UI 与分发，杜绝清单硬编码），
敏感密钥走**用户口令加密**（PBKDF2 + AES-GCM，可跨设备解密）；同时新增 **WebDAV 配置管理 + 工作流内 WebDAV 模块**，
让备份能送到设备之外。

---

## 1. 关键事实（全部实测/源码核实）

### 1.1 现状：备份只覆盖两个键

`ui/workflow_list/WorkflowListRoute.kt` 的既有「备份」只导出 `workflow_list` + `folder_list` 两个 prefs 键。
设置、全局变量、已装模块、WebDAV 配置一概不在内 —— 换机/重装后用户要手工重建大半配置。

### 1.2 六项已拍板决策（来自需求澄清）

| # | 决策 |
|---|---|
| 1 | 备份范围**多选**，导出时勾选；设置页与模块**共用同一份范围清单** |
| 2 | 导入**覆盖式与合并式都支持**，导入时让用户选 |
| 3 | WebDAV **只做手动能力**，不做定时/自动备份、不做触发器 |
| 4 | 导出模块**写到固定目录并输出路径**（不弹 SAF，保证无人值守可跑） |
| 5 | 备份中的敏感密钥**可选包含，且包含时必须加密** —— 用**用户输入的备份口令**（PBKDF2 + AES-GCM）。**只有口令方案能跨设备解密**（设备 Keystore 换机解不开，与备份目的相悖） |
| 6 | WebDAV 密码用**自建 Keystore 封装**（AndroidKeyStore + AES-GCM）加密，**不引入 `androidx.security`**（已 deprecated）。零新依赖 |

### 1.3 覆盖式导入需要的上游 API（已核实并追加）

| 数据 | 既有 API 的语义 | 处置 |
|---|---|---|
| 全局变量 | `GlobalVariableStore.replaceAll` 已是**替换**语义 | 直接复用 |
| 工作流 | `saveAllWorkflows` 是**合并**语义（误用于覆盖 ⇒ 旧工作流残留） | **追加** `WorkflowManager.replaceAllWorkflows(list)`（+21 行，单次原子写） |
| 文件夹 | `saveAllFolders` 是 **private** | **追加** `FolderManager.replaceAllFolders(list)`（+13 行，委托既有 private 方法） |

⚠️ **`replaceAllWorkflows` 必须是单次原子写**：`clear + save` 两步的话，中途崩溃 = 工作流全灭（本项目没有版本历史）。

### 1.4 `SecretFieldScrubber` 的实际规则（**与本仓库的三段式**）

「不含密钥」时，工作流步骤与触发器参数里的凭证会被清成空串。规则是**三段式**（子串 + 排除 + 精确）：

```kotlin
SCRUB_SUBSTRINGS = ["token", "secret", "password", "device_key", "api_key"]
SCRUB_EXCLUDED   = {"page_token", "key_code", "key_encoding", "key_action", "auth_mode"}
SCRUB_EXACT      = {"key"}
shouldScrub(id) = id in SCRUB_EXACT ||
                  (SCRUB_SUBSTRINGS.any { id.contains(it, ignoreCase = true) } && id !in SCRUB_EXCLUDED)
```

⚠️⚠️ **这条规则直接决定了「导出备份」模块的参数 id 必须叫 `backup_password`**：

| 参数 id | 是否被清洗 |
|---|---|
| `passphrase` | ❌ **不清洗 ⇒ 明文落进备份文件** |
| `backup_password` | ✅ 清洗 |

若照设计初稿命名为 `passphrase`，会出现极其讽刺的失效：**用来加密别人口令的那个口令，自己明文躺在同一份备份里**。
（取名 `backup_password` 后，口令被现有规则自动清掉，**零额外改动**。`BackupExportNamingTest` 与 `BackupExportModuleTest`
有 3 条断言锁住这一点，含一条**反向锁**「参数 id 不得叫 `passphrase`」。）

### 1.5 OkHttp 重定向的**更正**（⚠️ 一处被推翻的断言）

设计初稿写的「**OkHttp 默认把 301/302/303 的非 GET 降级为 GET ⇒ PROPFIND/PUT 静默降级**」**是错的**。

实测（直调 `okhttp3.internal.http.HttpMethod`，okhttp 4.12.0）：

```
PROPFIND   redirectsToGet=false  redirectsWithBody=true    ← 唯一被特判为不降级的方法
GET/POST/PUT/DELETE/PATCH/MKCOL/PROPPATCH/REPORT/MOVE/COPY/HEAD   redirectsToGet=true
```

⇒ **`PROPFIND` 恰恰是 OkHttp 唯一不降级的方法**，方向被写反了。

**结论没变（仍要 `followRedirects(false)`），但理由完全不同**：不是防降级，而是**拿回跳数判断权** ——
自动跟随会吞掉重定向链，调用方拿不到「经过几跳」与「跳数超限」的明确结论。
WebDAV 客户端自行处理 301/302/307/308，**保方法保 body** 重发，跨 host 丢 `Authorization`，上限 5 跳。

⚠️ 这条教训已入册：**写进任务书/文档的「某库会做 X」断言，先实跑一次再落笔**。

---

## 2. 范围

### 2.1 八个备份 scope（`BackupScopeRegistry.all()`）

| id | group | 默认勾选 | sensitive | importOrder | dependsOn | 来源 |
|---|---|---|---|---|---|---|
| `folders` | USER_CONTENT | ✅ | ✗ | 0 | — | T1 |
| `global_variables` | USER_CONTENT | ✅ | ✗ | 5 | — | T1 |
| `workflows` | USER_CONTENT | ✅ | ✗ | 10 | `folders` | T1 |
| `modules` | CONFIG | ✅ | ✗ | 8 | — | T6 |
| `tiles` | CONFIG | ✅ | ✗ | 20 | `workflows` | T6 |
| `settings` | CONFIG | ✅ | ✗ | 40 | — | T6 |
| `chat` | CONFIG | ❌ | ✗ | 50 | — | T6 |
| `secrets` | SECRET | ❌ | ✅ | 100 | — | T2 |

⚠️ **`chat` 默认不勾**（与 `secrets` 的理由**不同**）：`secrets` 是**敏感**；`chat` 是**体积 + 隐私**（含对话历史）。
⚠️ **`secrets` 的 `importOrder = 100`**（不是设计初稿写的 30）—— 密钥必须**最后**导入。

### 2.2 各 scope 的键白名单（**逐键人工判定，这是本次交付最需要复核的部分**）

#### `settings`（prefs `vFlowPrefs`，20 键）

```
dynamicColorEnabled, colorfulWorkflowCardsEnabled, appScale, liquidGlassNavBarEnabled,
workflow_sort_mode, workflow_layout_mode, hideFromRecents, enableTypeFilter,
allowShowOnLockScreen, allowPopupKeepScreenOn, keepDeviceAwakeDuringWorkflow,
defaultErrorPolicy, defaultRetryCount, defaultRetryInterval,
progressNotificationEnabled, backgroundServiceNotificationEnabled, autoCheckUpdatesEnabled,
telemetryEnabled, accessibilityDisguiseEnabled, sherpa_ncnn_download_source
```

**有意排除**（逐条理由）：

| 排除的键 | 理由 |
|---|---|
| `forceKeepAliveEnabled` / `autoEnableAccessibility` / `accessibilityGuardEnabled` / `default_shell_mode` | **与设备能力绑定** —— 新设备的权限/服务状态不同，复制过去会产生「看着开了实际没开」的错觉 |
| `core_*` 全家 | Core 部署状态是**设备的**，不是配置 |
| `is_first_run` / `disclaimer_accepted` | **设备/用户级的确认状态**，跨设备复制语义错误（新设备的用户可能不是同一个人） |
| `vflow_api_tokens`（独立 prefs） | **默认排除且不提供勾选** —— 可再生、安全敏感 |

#### `chat`（3 个 prefs 文件，7 键）

| prefs | 键 |
|---|---|
| `module_config_prefs` | `chat_default_preset_id`、`chat_auto_approve_tools`、`chat_auto_approval_scope` |
| `chat_session_prefs` | `chat_session_state_json` |
| `ai_config` | `provider`、`base_url`、`model` |

⚠️ **`chat_provider_configs_json` / `chat_presets_json`（内含 `apiKey`）不在本 scope** —— 它们归 `secrets`。
⚠️ **`ai_config.api_key` 同样归 `secrets`**（本 scope 只收 `provider`/`base_url`/`model` 三个非敏感键）。
这就是「只勾聊天漏勾密钥 ⇒ 泄漏」那条静默失效点的处置：**结构上分开，不靠用户记得同时勾两个**。

#### `modules`（2 个 prefs 文件，13 个精确键 + 1 个前缀）

`module_config_prefs` 的 13 个模块参数键（`backtap_sensitivity`、`app_start_*` ×3、`screen_operation_pointer_*` ×2、`voice_trigger_*` ×6）
+ `external_module_providers` 的 `provider_enabled_` 前缀。

#### `tiles`（prefs `vflow_tiles`，1 键）

`tile_list` —— `[{tileIndex, workflowId}]` 的 JSON。⚠️ **MERGE 时按 `tileIndex` 求并集**（备份优先、本地独有保留）；
坏 JSON 或元素读不到 `tileIndex` 时**退化为整键替换并记 W**，不整体失败。
走**文本层合并**（不引 `WorkflowTile` 类）⇒ 保住 `core/backup` 的纯 JVM 纯度。

#### `secrets`（10 个精确槽位 + WebDAV 配置，**sensitive**）

`module_config_prefs` 的飞书七件套（`feishu_app_secret` / `feishu_app_access_token` / `feishu_access_token` /
`feishu_user_access_token` / `feishu_user_refresh_token` / `feishu_user_auth_code` / `feishu_user_code_verifier`）
+ `chat_provider_configs_json` + `chat_presets_json` + `ai_config.api_key` + **`webdav_configs`（T6 新增）**。

⚠️ **这是精确清单，不是规则**。新加的集成若把凭证写到别处，**不会自动被备份** —— 新增集成时要回到 `SECRET_SLOTS` 补一行。
`SecretsScopeTest` 有源码扫描锁住「这些键在当前代码库里确实存在」（防僵尸条目）。

### 2.3 不做（明确划出）

- ❌ 定时/自动备份、WebDAV 触发器（决策 3）
- ❌ 跨设备**同步**（只做一次性的导出/导入，不是双向同步）
- ❌ `vflow_api_tokens` 的备份（安全边界）

---

## 3. 架构

### 3.1 范围注册表（核心机制，防漂移）

```kotlin
interface BackupScope {
    val id: String            // 稳定，落进 JSON，一经发布不改
    val group: ScopeGroup     // USER_CONTENT / CONFIG / SECRET
    val sensitive: Boolean    // true ⇒ 仅在「包含密钥」且有口令时才写
    val defaultIncluded: Boolean
    val importOrder: Int
    val dependsOn: List<String>

    /** null 仅表示「本次不适用（未勾选）」。空数据集必须返回 count=0 的 payload，
     *  绝不能用 null —— 否则用户以为备了其实没备（静默失效）。 */
    fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload?
    fun import(env: BackupEnvironment, payload: ScopePayload?, mode: ImportMode): ScopeImportResult
}
```

- `BackupEnvironment` 是 **Android-free 接缝**（读写 prefs / 读写工作流与文件夹 / `reloadTriggers()`）；
  Android 实现 `AndroidBackupEnvironment` 委托 `WorkflowManager`/`FolderManager`/`GlobalVariableStore`，
  测试用 `FakeBackupEnvironment`（纯 JVM，`InMemoryPrefs` / `FakeWebDavBackup`）。
- `BackupScopeRegistry`（object）持唯一清单；**设置页勾选、模块勾选、导入分发三处全部从 `all()` 派生**。
  新增一类数据 = 加一个 scope 文件 + 一行 `register`。
- `export`/`import` 在同一个实现里 ⇒ **结构上不可能「只注册导出没写导入」**。

### 3.2 备份信封

```json
{
  "schema": "vflow.backup", "schemaVersion": 1,
  "createdAt": 1712345678000,
  "app": { "versionName": "1.5.4", "versionCode": 50 },
  "scopes": { "workflows": {"count":12,"data":[...]},
              "secrets":   {"count":5,"data":{"$enc":{...}}} },
  "encryption": { "algorithm":"AES-256-GCM", "kdf":"PBKDF2WithHmacSHA256",
                  "iterations":210000, "salt":"b64", "verifier":"b64", "verifierHash":"b64" },
  "summary": { "includedScopes":[...], "excludedScopes":[...], "scrubbedFields":["step_1.api_key"] }
}
```

- **legacy 兼容**：无 `schema` 但含顶层 `workflows`/`folders` ⇒ 走 `LegacyBackupAdapter`（复用既有
  `WorkflowJsonImportParser`，认 4 种形状），**旧备份文件仍可导入**且**不试解密**。
- **缺 scope**（旧文件）：跳过并记入 summary，**不整体失败**。
- **未知 scope id**（更新版产出）：跳过 + 警告，**不崩溃**。
- **`schemaVersion` 高于本机**：拒绝并明确提示「备份来自更新版本」，**不做半解析**。

### 3.3 加密（三层可测接缝）

```
纯 JVM  BackupCrypto.kt    PBKDF2 派生 + AES-256-GCM + 口令错/损坏判定
        SecretEnvelope.kt  字段标记 {"$enc":"aes-gcm-v1","data":"b64(iv‖ct‖tag)"}
        AesGcmEngine.kt    接口 seal/open(key, plaintext, aad)（可注入假实现）
Android KeystoreCryptoBox.kt / KeystoreGcmEngine.kt（**仅 WebDAV 密码用**）
        AliasGcmEngine.kt  WebDAV 侧的按别名取密钥接缝（见 §3.5）
```

- **参数**：PBKDF2-HmacSHA256 / salt 16B 随机 / **iterations 210000**（OWASP 下限） / 密钥 256bit /
  AES/GCM/NoPadding / IV 12B 每次随机前置 / tag 128bit。
- **口令错 vs 数据损坏必须能区分**（二者都抛 `AEADBadTagException`）：信封存 `verifier`（用派生密钥加密的已知常量
  `vflow.backup.verifier.v1`）**+ `verifierHash`**（verifier 密文的 SHA-256，不参与密钥派生）。判定链四支：

  | 条件 | 判定 |
  |---|---|
  | `verifierHash` 对不上 | **CORRUPTED**（不派生密钥 —— 密文本身被改过） |
  | verifier 解不开 | **WRONG_PASSPHRASE** |
  | 解得开但明文 ≠ `VERIFIER_PLAINTEXT` | **CORRUPTED** |
  | 通过 | ok，继续解字段 |

  ⚠️ 只靠 GCM tag 无法区分「口令错」与「verifier 本身被篡改」—— 两者都表现为 verifier 解不开。加 `verifierHash` 后判定链才确定。
- **逐字段加密，不整段** ⇒ 信封可读、可审计「备了哪些键」、损坏面小。
- **Keystore**：`KeyGenParameterSpec(PURPOSE_ENCRYPT|PURPOSE_DECRYPT, GCM, NONE, 256)`；
  **不设** `setUserAuthenticationRequired`（否则后台工作流解锁不了）；IV 用 `cipher.iv` 前置。

### 3.4 敏感字段跨切面清洗

⚠️ **密钥也存在于工作流步骤参数里**，会随 `workflow_list` 明文导出。`SecretFieldScrubber` 在「不含密钥」时把
已知密钥参数 id 的值清成空串，**并把被清洗的位置（`<stepId>.<paramId>`）记进 `summary.scrubbedFields`**（不静默丢弃）。

**导入侧必须展示它**（这是 UI 的硬要求，见 §3.6）—— 用户在**导入那一刻**才真正体会到数据缺失：
REPLACE 导入一份未含密钥的备份后，工作流的 `api_key` 是空的，而 summary 只说「导入 12 · 跳过 0」，
用户无从得知。**导出侧展示、导入侧遗漏是不对称的**，已补齐（UI 在**选模式之前**也提示）。

### 3.5 `AliasGcmEngine` —— 一处必须解释的命名

`core/security/` 下**曾有**两个同名 `AesGcmEngine` 接口（备份侧与 WebDAV 侧各自写的，方法面根本不同）：

| | 备份侧 | WebDAV 侧 |
|---|---|---|
| 方法 | `seal/open(key: ByteArray, plaintext, aad)` | `encrypt/decrypt(alias: String, …)` |
| 语义 | key **字节**由 PBKDF2 派生 | **别名**由 AndroidKeyStore 取密钥 |

同包同名 interface **无法共存** ⇒ WebDAV 侧改名 `AliasGcmEngine`（名字反而更贴切：它按 **alias** 取密钥）。
`CryptoKeyUnavailableException` 原样保留（有 6 处生产引用）。

⚠️ **不合并成一个接口**：两者方法面不同，合并要引入一层「alias → key」的间接，收益不抵风险。

### 3.6 WebDAV 配置的**转档**

WebDAV 密码在本机是**设备 Keystore 密文**（换机解不开）⇒ 导出时必须**转档**成备份口令密文：

```
导出：WebDavConfigStore.getAll() → KeystoreCryptoBox.decryptFromBase64 解出**明文**
      → 随整份 secrets plaintext 被 BackupCrypto 的密钥一次加密（不落盘中间态）

导入：BackupPipeline 先解密 → SecretsScope 拿到的已是明文
      → AndroidWebDavBackup.writeAll → WebDavConfigStore.upsert(ctx, config, password)
```

⚠️⚠️ **`WebDavBackupEntry` 结构上没有 `encryptedPassword` 字段** ⇒ 不可能把旧设备的 Keystore 密文搬过去。
⚠️ **`upsert` 的 `null` ≠ `""`**：`password == null` ⇒ **保留本机已存密码**；`""` ⇒ 设成空（会**抹掉**本机已存的密码）。
导入侧必须显式区分这两种情形。`WebDavSecretsTranscodeTest` 有源码扫描断言锁住「生产写入路径把 null 原样透传，不 `?: ""`」。
⚠️ 单条配置解不开（Keystore 密钥失效）时**不整体失败**：保留该条配置（地址/用户名仍在）但**不带密码**，
并把 `webdav.<名称>.password` 记进 `scrubbedFields`。

### 3.7 WebDAV 协议层

- **LIST** = `PROPFIND` + `Depth: 1`，响应 207 Multi-Status。XML 解析**按 namespace URI 取元素，绝不按前缀字符串匹配**
  （服务器前缀有 `D:`/`d:`/`ns0:`/默认 ns）。识别 `<d:collection/>` 为目录。畸形 XML **不崩**，回 `Malformed`。
- **路径拼接用 `HttpUrl` 逐段 `addPathSegment`**，禁止字符串拼 `baseUrl + "/" + path`。
  ⚠️ **实测**：`addPathSegment("..")` **静默上跳一级**、`addPathSegment(".")` **静默丢弃**，两者都无报错
  ⇒ 防穿越**必须我方拦截**。
- **`followRedirects(false)`**（理由见 §1.5），自行处理 301/302/307/308，**保方法保 body** 重发，跨 host 丢 `Authorization`，上限 5 跳。
- 目录 URL **尾斜杠**对部分服务器（nginx dav / 坚果云）是语义要求。
- Basic Auth：`Authorization: Basic base64(user:pass)`，显式 `Charsets.UTF_8`（中文凭据）。
- `allowInsecureTls` **默认关**；开启时**保留默认 HostnameVerifier**（只放宽信任链，不放行 hostname）。

#### 3.7.1 两侧共享的 HTTP 装配（`WebDavHttpSupport`，2026-10-03 收敛）

`WebDavProbe`（测试连接）与 `WebDavClient`（模块五动词）是**两条独立链路**，
收敛前下列三样**各写了一份**（`trustAllTrustManager` 两份逐字相同）：

| 项 | 重复的代价 |
|---|---|
| `REDIRECT_CODES = setOf(301, 302, 303, 307, 308)` | 两处各写一个集合 ⇒ **改一处忘另一处**，表现是「测试连接能过、模块执行报错」（或反过来），而两边看着都对 |
| `trustAllTrustManager()` | 8 行匿名类；将来改一份（例如加证书过期日志）另一份不动 |
| `SSLContext` + `sslSocketFactory` 装配 | 同上，且这段的正确形状（**只换 TrustManager、保留 HostnameVerifier**）是**安全相关**的 |

⇒ 收敛进 `core/webdav/WebDavHttpSupport.kt`（`internal object`）。
⚠️ **刻意没收敛**的两处：① `followRedirects(false)` / `followSslRedirects(false)` **留在各自的
`buildClient()` 里** —— 它是**意图声明**，读代码的人应当在装配 OkHttp 的地方直接看见它；
② `MAX_REDIRECTS` / `resolveLocation` / `sameHost` 仍留在 `WebDavProbe`（取值约束与纯函数各有独立断言）。

**四条源码扫描断言**（`WebDavSettingsEntryTest`，含**三条反证**）：

| 断言 | 反证（改坏 ⇒ 变红） |
|---|---|
| 集合字面量只在 `WebDavHttpSupport` 出现一次 | 在 `WebDavProbe` 里重写一份 ⇒ **1 条红** |
| `trustAllTrustManager` 只定义一次 + 两侧都经 helper 装配 | 拆掉 `WebDavClient` 的 helper 调用 ⇒ **1 条红** |
| **任何一侧都不得出现 `hostnameVerifier`** | 加 `builder.hostnameVerifier { _, _ -> true }` ⇒ **1 条红** |
| `followRedirects(false)` 必须在**各自**的装配点可见 | （防「有人把它藏进 helper」） |

⚠️ 第三条是**安全不变量**：加 `hostnameVerifier` 之后**没有任何行为测试会变红**（没有测试能覆盖「中间人」），
是典型的静默劣化 —— 「允许自签名」与「允许任意中间人」是两件事。

### 3.8 导入顺序

`BackupScopeRegistry.importOrder()` 按 `dependsOn` **拓扑排序**，`importOrder` 只在「依赖已就绪的候选」之间做 tie-break：

```
folders(0) → global_variables(5) → modules(8) → workflows(10, deps=folders)
  → tiles(20, deps=workflows) → settings(40) → chat(50) → secrets(100)
```

⚠️ **恢复时文件夹必须保 id**，否则 `workflow.folderId` 全断、工作流散落
（既有 `WorkflowImportHelper` 是「导入」语义，会 `UUID.randomUUID()` 并重名加后缀，**不能照抄**）。
`FolderScope` 全程不做任何 id 重生成；**`id` 缺失时跳过该条而不是发明一个新 id**。另需按 `parentId` 让 parent 先于 child 写入。

### 3.9 导入后必须 `reloadTriggers()`

否则工作流进来了但**触发器不调度**（「能看见不触发」）。
调用点在 `WorkflowScope.import` 体内（`env.reloadTriggers()`），由 `BackupRestoreWiringTest` 的**三跳源码扫描**锁住：

```
BackupRestoreScreen.kt 含 BackupPipeline.import(
  + 同文件含 AndroidBackupEnvironment(
    + WorkflowScope.kt 的 import 体内含 env.reloadTriggers()
```

中间那一跳是刻意的：它堵住「UI 误用 Fake/stub env」这个盲区。

---

## 4. 文件级改动

### 4.1 新增（fork 独有，冲突归我方）

| 目录/文件 | 职责 |
|---|---|
| `core/backup/BackupScope.kt` | 接口 + `ScopeGroup` / `ImportMode` / `ImportStatus` / `ScopePayload` / `SecretContext` |
| `core/backup/BackupEnvironment.kt` | Android-free 接缝 |
| `core/backup/AndroidBackupEnvironment.kt` | Android 实现（唯一允许 import `android.*` 的 backup 文件，有白名单断言） |
| `core/backup/BackupEnvelope.kt` | 信封读写 + `EncryptionSection` + `scrubbedFieldsOf(...)` 只读访问器 |
| `core/backup/BackupScopeRegistry.kt` | 唯一清单 + 拓扑排序 + `exportAll`/`importAll` |
| `core/backup/BackupPipeline.kt` | 导出/导入编排 + `ImportOutcome` 五态 |
| `core/backup/BackupCrypto.kt` / `SecretEnvelope.kt` | 口令加密与字段信封 |
| `core/backup/SecretFieldScrubber.kt` | 三段式清洗规则 |
| `core/backup/SecretStore.kt` / `BackupPrefs.kt` / `WebDavBackupStore.kt` | prefs 读写接缝 + WebDAV 转档接缝 |
| `core/backup/scopes/*.kt`（8 个） | 各范围实现（含逐键白名单表） |
| `core/security/AesGcmEngine.kt` / `AliasGcmEngine.kt` / `KeystoreGcmEngine.kt` / `KeystoreCryptoBox.kt` | 加密引擎与 Keystore 封装 |
| `core/webdav/WebDavConfig.kt` / `WebDavConfigStore.kt` / `WebDavProbe.kt` / `WebDavClient.kt` / `WebDavXmlParser.kt` / `WebDavUrlBuilder.kt` | WebDAV 配置与协议层 |
| `core/workflow/module/network/WebDavModule.kt` | 模块 `vflow.network.webdav` |
| `core/workflow/module/data/BackupExportModule.kt` + `BackupExportUIProvider.kt` | 模块 `vflow.data.export_backup` |
| `ui/settings/BackupRestoreActivity.kt` / `BackupRestoreScreen.kt` / `BackupScopeLabels.kt` | 备份恢复二级页 |
| `ui/settings/WebDavConfigActivity.kt` / `WebDavConfigScreen.kt` | WebDAV 配置二级页 |
| `res/drawable/rounded_{backup,backup_export,cloud_sync}_24.xml`、`res/layout/partial_backup_export_editor.xml` | 资源 |
| 测试 24 个（`core/backup/**`）+ 5 个（`core/webdav/**`）+ 2 个（`ui/settings/**`）+ 3 个（模块） | 单测 |

### 4.2 修改的上游文件（**全部纯追加，0 删除**）

| 文件 | 追加 |
|---|---|
| `core/workflow/WorkflowManager.kt` | `replaceAllWorkflows(list)`（+21） |
| `core/workflow/FolderManager.kt` | `replaceAllFolders(list)`（+13） |
| `core/workflow/module/ModuleRegistry.kt` | 网络段末 `register(WebDavModule(), context)`、数据段末 `register(BackupExportModule(), context)`（+2，不重排） |
| `ui/settings/SettingsScreen.kt` | `onOpenBackupRestore` / `onOpenWebDavConfig` 两个 action + 两行 `NativeEntryRow`（均取 `Middle` ⇒ 既有各行 position 不用改）+ **四条文案进 `matchesSearch`** + **`globalVariablesTitle/Subtitle` 的既有缺陷修复**（+22） |
| `ui/settings/SettingsRoute.kt` | 两处 `startActivity` 接线（+8） |
| `AndroidManifest.xml` | 两个 Activity 声明（+11） |
| 三语 `strings.xml` ×3 | 备份页 45 条 + WebDAV 页 29 条（各 +74） |
| 三语 `strings_module.xml` ×3 | 模块文案 20 条 + WebDAV 模块 64 条（各 +84） |

> **集成规模**：92 文件 / +19473 行 / **0 删除**（`git diff 3b7df805..HEAD --numstat` 实测）。

---

## 5. 验证

### 5.1 门禁（实测）

| 命令 | 结果 |
|---|---|
| `./gradlew test` | **2260 tests completed, 1 failed, 1 skipped**。唯一失败 = `VObjectPropertyTest > test VFile properties from absolute path`（`android.net.Uri.parse` 未 mock，**AGENTS.md 点名的既有失败**）；skipped = 既有 `LogcatTriggerBenchmarkTest` |
| `./gradlew assembleRelease` | **BUILD SUCCESSFUL**，5 个 ABI 全产出 |
| `apksigner verify --print-certs` | `CN=vFlow Fork, OU=Fork, O=nightking8342` ✅ **未降级**；构建日志无「Release 签名文件未找到」 |
| `aapt2 dump resources` | 新资源键全部存活（`settings_webdav` / `webdav_config_title` / `module_vflow_network_webdav_name` / `module_vflow_data_export_backup_name` / `backup_scope_*`） |

### 5.2 测试覆盖（按模块）

| 模块 | 用例数 |
|---|---|
| `core/webdav/**` | 186（Client 25 / XmlParser 21 / UrlBuilder 21 / Probe 29 / Config 20 / WebDavModule 42 + 28） |
| `core/backup/**` | 191（含 `BackupScopeRegistryTest` 17 / `PrefsScopeContractTest` 12 / `BackupPrefsLeakTest` 7 / `WebDavSecretsTranscodeTest` 16 …） |
| `ui/settings/**` | 接线锁（`BackupRestoreWiringTest` 24 + `WebDavSettingsEntryTest` …） |

**重点用例类型**（「改错了不报错、只静默变差」的地方）：

- **反向断言**：`shouldScrub(passphrase) == false`（锁住「必须叫 `backup_password`」这条约束的理由）；
  `payload_too_large` 的文案**不得**等于 `channel_down`（防用户被引去改本来正确的配置）
- **结构断言**：`WebDavBackupEntry` **没有** `encryptedPassword` 字段
- **源码扫描型**（照 `CoreDexFingerprintTest` 形态）：`BackupRestoreScreen.kt` 含 `BackupScopeRegistry.all()`
  且**连单个 scope id 字面量都没有**；`SecretLayerPurityTest` 的 `android.` 引用白名单**恰好一个文件**且防空转
- **契约断言**：`InMemoryPrefs` 与 `AndroidPrefs` 的语义一致（防「测试过了、生产不一样」）

### 5.3 反证记录（各任务实际执行，逐条「改坏 → 确认变红 → 还原 → 复绿」）

T1 3 条 / T2 12 条 / T3 6 条 / T4 6 条 / T5 11 条 / T6 6 条，共 **44 条**。
其中 **3 条第一版反证没变红**、据此改了测试后重做（如实记录）：

| 任务 | 反证 | 首版没红的原因 |
|---|---|---|
| T2 | R4 `SCRUB_EXCLUDED` 清空 | 测试没覆盖到排除集的每个词 |
| T2 | R9 `BackupPipeline` 去掉「嵌套处明文是裸字符串」的不对称判据 | 缺对应用例 |
| T6 | ④' 源码扫描「生产写入路径传 null 而非空串」 | **测试经过的是类型、不是生产调用点** |

---

## 6. 风险与静默失效点

### 6.1 设计期列出的 16 条，逐条核对结论

| # | 静默失效点 | 状态 | 处置/证据 |
|---|---|---|---|
| 1 | `saveAllWorkflows` 的**合并**语义被误用于覆盖 ⇒ 旧工作流残留 | ✅ 已处置 | 追加原子 `replaceAllWorkflows`；`WorkflowScope` REPLACE 分支只删白名单内的 id |
| 2 | 合并时既有条目 `folderId` 被覆盖回旧值 | ✅ 已处置 | `WorkflowScope.mergeValue` 显式合并语义 |
| 3 | 文件夹恢复重生成 id ⇒ `workflow.folderId` 全断 | ✅ 已处置 | `FolderScope` 全程不重生成；`id` 缺失则**跳过该条**而非发明 |
| 4 | REPLACE 用 clear+save **非原子** ⇒ 中途崩溃 = 数据全灭 | ✅ 已处置 | `replaceAllWorkflows` 是**单次** `prefs.edit().putString(...)` |
| 5 | 导入后**不调 `reloadTriggers`** ⇒ 触发器不调度 | ✅ 已处置 | `WorkflowScope.import` 内调用，三跳源码扫描锁住 |
| 6 | 工作流步骤 `api_key` 随 `workflow_list` 明文导出 | ✅ 已处置 | `SecretFieldScrubber` 三段式清洗 + `scrubbedFields` 上报 |
| 7 | 只勾「聊天」漏勾「密钥」而 `chat_*_json` 内含 apiKey | ✅ 已处置 | **结构上分开**：`chat_*_json` 归 `secrets`、`chat` scope 只收非敏感键 |
| 8 | 口令错与密文损坏都抛 `AEADBadTagException` ⇒ 误报「口令错」 | ✅ 已处置 | `verifier` + `verifierHash` 两支独立证据 ⇒ 四支判定链 |
| 9 | Keystore 密钥失效 ⇒ WebDAV 密码解不出 ⇒ 表现像「服务器挂了」 | ✅ 已处置 | `CryptoKeyUnavailableException` 单独分支 + 文案指向「重新输入密码」 |
| 10 | OkHttp 自动重定向把 PROPFIND 降级为 GET ⇒ 列表静默为空 | ⚠️ **前提已作废** | 实测 PROPFIND 恰是唯一不降级的方法（§1.5）。仍用 `followRedirects(false)`，理由改为**可观测性** |
| 11 | XML 按前缀匹配 ⇒ 换服务器就解析不到 | ✅ 已处置 | 按 namespace URI 取元素；四前缀（`D:`/`d:`/`ns0:`/默认）+ 一致性断言 |
| 12 | 空数据集返回 `null` 而非空 payload ⇒ 以为备了其实没备 | ✅ 已处置 | 契约写进 `BackupScope.export` KDoc；`BackupScopeRegistryTest` 有「空数据集 count=0」用例 |
| 13 | scope 注册了但 UI 勾选清单硬编码第二份 ⇒ 新 scope 永不出现 | ✅ 已处置 | UI 从 `BackupScopeRegistry.all()` 派生；源码扫描断言「连单个 id 字面量都没有」 |
| 14 | 设置页新入口文案没进 `matchesSearch` ⇒ 搜索时整组消失 | ✅ 已处置（**且修了一处既有缺陷**） | 四条新文案已登记；顺带修了**既有的** `globalVariablesTitle/Subtitle` 漏登记（有断言锁） |
| 15 | `appContext` 未注入时 `getDynamicInputs` 抛异常 ⇒ 模块列表崩 | ✅ 已处置 | `safeConfigNames()` 一律 `catch (Throwable)` 回落空列表 |
| 16 | 导出到 `/sdcard` 未申请 STORAGE ⇒ Q+ 写入静默失败 | ✅ 已处置 | `BackupExportModule.requiredPermissions = listOf(PermissionManager.STORAGE)`，有断言 + 反证 |

### 6.2 本次新增的风险（如实登记）

| # | 风险 | 现状 |
|---|---|---|
| R1 | **口令以明文存放在 `workflow_list` 里** | 用户可用 `{{vars.xxx}}` 引用全局变量规避；该明文**不会**进入导出模块产出的备份（已被 `SecretFieldScrubber` 清洗），但会随**设置页导出的备份**一起走（除非用户在设置页导出时也勾了包含密钥 —— 此时工作流里的口令仍被清洗，属预期）。**用户必须自行记住口令，本应用不提供找回**。缓解措施：参数 id 取名 `backup_password` ⇒ 自动进清洗规则 |
| R2 | `ImportMode.REPLACE` **是破坏性操作** | 真的会删掉本地「不在备份里」的数据，本项目**没有版本历史、没有撤销**。UI 有二次确认（`BackupRestoreWiringTest` 断言锁住） |
| R3 | 设置页导入会真的改动用户数据 | 同上。UI 在**选模式之前**就展示被清洗字段提示 |
| R4 | `chat` scope 体积不可控 | 默认**不勾**；用户自行决定 |
| R5 | 转档依赖本机 Keystore 仍可用 | 单条解不开时跳过密码、保留其余字段，并上报 `scrubbedFields` |
| R6 | `BackupExportUIProvider.kt` import 了 `ui/settings/backupScopeLabelRes`（core→ui） | 本仓库有大量先例（`PillUtil` 被近 20 个 core 模块 import），**不构成架构违规**；若将来要解耦，可把标签映射下沉到 core |
| R7 | **真机验证未做** | 见 §8 |

---

## 7. 决策台账

| # | 决策 | 理由 | 状态 |
|---|---|---|---|
| 1 | 备份范围**多选**，设置页与模块共用一份清单 | 需求 | 定稿 |
| 2 | 导入支持**覆盖 + 合并**两种 | 需求 | 定稿 |
| 3 | 用**注册表**而非硬编码清单 | 三处（设置页/模块/分发）派生自一处 ⇒ 新 scope 一处注册 | 定稿 |
| 4 | `export`/`import` 在**同一接口**里 | 结构上不可能「只注册导出没写导入」 | 定稿 |
| 5 | 空数据集返回 `count=0` 而非 `null` | `null` 只表示「本次不适用」；混同 = 静默失效 | 定稿 |
| 6 | `sensitive` 范围无口令时 `export` 返回 `null` | 安全闸：没口令就不写密钥 | 定稿 |
| 7 | 密钥用**用户口令**加密而非设备 Keystore | **只有口令方案能跨设备解密** | 定稿 |
| 8 | 逐字段加密而非整段 | 信封可读、可审计、损坏面小 | 定稿 |
| 9 | 加 `verifierHash` | 只靠 GCM tag 无法区分「口令错」与「verifier 被篡改」 | 定稿 |
| 10 | WebDAV 密码用**自建 Keystore 封装** | `androidx.security` 已 deprecated；零新依赖 | 定稿 |
| 11 | 文件夹恢复**保 id** | 否则 `workflow.folderId` 全断 | 定稿 |
| 12 | 追加 `replaceAllWorkflows` 而非复用 `saveAllWorkflows` | 后者是合并语义；且必须**单次原子写** | 定稿 |
| 13 | 导出模块写**固定目录**并输出路径 | 不弹 SAF ⇒ 无人值守可跑 | 定稿 |
| 14 | 导出模块参数 id 取 **`backup_password`** | 让现有清洗规则自动覆盖（`passphrase` 不被清洗 ⇒ 明文泄漏） | 定稿 |
| 15 | WebDAV 模块 `riskLevel = HIGH`（统一，不按 operation 分档） | `ActionModule.aiMetadata` 是静态 `val`（`ActionModule.kt:33`），**签名里没有 `step`** ⇒ 结构上做不到。**否决**扩展上游接口（动上游、波及所有模块与 AI 工具注册表） | 定稿 |
| 16 | WebDAV 模块配置选择**存配置名** | 存 id 需 UIProvider 自绘下拉（上游 UI 改动）。失配做成**显式失败**（`validate`/`execute`/`getSummary` 三处），**不塞幽灵条目进 options** | 定稿 |
| 17 | `AliasGcmEngine` 改名而非合并 | 两接口方法面不同；合并要引一层 alias→key 间接 | 定稿 |
| 18 | T4 **不收敛** T3 的 `WebDavProbe`（两份重定向实现暂时并存） | 跨任务改他人文件会把合并变成三方冲突，收益不抵风险 | 定稿（集成期未收敛，见 §8） |
| 19 | `chat` 默认不勾 | 体积 + 隐私（与 `secrets` 的「敏感」是**两个独立理由**） | 定稿 |
| 20 | settings 排除设备绑定键与 `is_first_run`/`disclaimer_accepted` | 跨设备复制语义错误 | 定稿 |
| 21 | `secrets.importOrder = 100`（非设计稿的 30） | 密钥必须**最后**导入 | 定稿 |
| 22 | 导入侧也展示 `scrubbedFields`（选模式前 + 导入后） | 用户**在导入那一刻**才体会数据缺失；导出侧已展示、导入侧遗漏是不对称 | 定稿（验收驳回后返工） |

---

## 8. 实现状态与交接

### 8.1 已完成

- ✅ 八个 scope 全量实现（含逐键白名单与理由）
- ✅ 口令加密（PBKDF2 210k + AES-GCM + verifier/verifierHash 四支判定）
- ✅ 覆盖/合并两种导入模式 + 二次确认
- ✅ 导出备份模块（写 `StorageManager.backupsDir` = `/sdcard/vFlow/backups`，输出路径）
- ✅ WebDAV 配置 CRUD + 测试连接 + 转档进 `secrets`
- ✅ WebDAV 工作流模块（list/upload/download/mkdir/delete 五操作）
- ✅ 两个设置页二级页 + 入口接线（含一处既有缺陷修复）
- ✅ legacy 备份兼容（`LegacyBackupAdapter`，走 `WorkflowJsonImportParser` 认 4 种形状）
- ✅ 门禁：test 2260 例（1 例既有失败）/ release 打包 / 签名验证 / 资源存活

### 8.2 ⚠️ 未做 / 未验证（**不得当成已做**）

| 项 | 说明 |
|---|---|
| **真机验证 0 项** | `adb devices` 为空。以下全部**只到「单测 + release 打包」这一层**：<br>· **导入后触发器恢复调度**（`reloadTriggers` 的端到端 —— 只在**不重启 App** 的前提下触发才证明得了这条链路）<br>· WebDAV **换机恢复**（转档的实际效果）<br>· `AndroidPrefs` 真实按类型读写 `vFlowPrefs`<br>· 四个新 scope 在设置页勾选界面的实际呈现<br>· `tiles` 的 MERGE 在真机磁贴上的表现<br>· `allowInsecureTls` 的自签名豁免（缺 `okhttp-tls` 依赖，无自动化手段）<br>· 各 WebDAV 服务端实测（Nextcloud / 坚果云）<br>· PBKDF2 210k 的派生耗时（真机量一次） |
| ~~**两份互操作层未收敛**~~ | ✅ **已于 2026-10-03 收敛**（见 §3.7.1）。<br>~~`WebDavProbe.kt`（T3 的测试连接）与 `WebDavClient.kt`（T4 的模块客户端）各自有一份重定向循环、各写一份 `REDIRECT_CODES` 与 `trustAllTrustManager`（后两者逐字相同）。~~ |
| **两个 Android 实现类无直接单测** | `AndroidBackupEnvironment` 的两个嵌套私有类（`AndroidPrefs` / `AndroidWebDavBackup`）是 `android.*` 生产实现，纯 JVM 起不来。行为契约由 `InMemoryPrefs`/`FakeWebDavBackup` 的**契约测试**间接证明；「生产实现与假实现语义一致」**只有 `writeAll` 的 `upsert` 调用点**由源码扫描覆盖 |

### 8.3 `FORK.md` 登记项（集成时已登记）

见 `FORK.md` 的「全局备份/恢复 + WebDAV」章节，逐条列出：
8 个 scope 的新增文件、两个上游追加（`WorkflowManager` +21 / `FolderManager` +13）、
四个共享追加点（`ModuleRegistry` / `SettingsScreen` / `SettingsRoute` / `AndroidManifest`）、
三语字符串追加、两个模块注册、`AliasGcmEngine` 的命名分歧、`SecretLayerPurityTest` 的单文件白名单。

---

## 9. 后续（P2+ 候选）

1. **真机回归**（最高优先）—— §8.2 的清单
2. ~~收敛两份 WebDAV 重定向实现~~ ✅ **已完成（2026-10-03，§3.7.1）**
3. **自动/定时备份**（决策 3 明确不做；若要做，走现有的触发器体系）
4. **备份文件加密的迭代次数自适应**（当前固定 210k）
5. **`settings` scope 的粒度细化**（当前是「全有或全无」，未来可按子组勾选）
