# P0 探针实测结论（注入 system_server）

> **日期**：2026-09-26
> **设备**：小米 MIX Fold 3（Android 17 / API 37 / 澎湃 OS，**LSPosed 2.2.0**）
> **探针**：`hookprobe/`（Java + libxposed API 102，注入 system_server）
> **本文是 P0 探针的结果记录**。设计文档：`docs/fork/xposed-channel-design.md`

---

## 0. 结论汇总

| # | 验证项 | 结果 |
|---|---|---|
| **1** | hook 点 `ActivityRecord.activityResumedLocked` 是否存在 | ✅ **存在、已挂上、真的被调用** |
| ⭐ | **能否拿到 Activity 的 Intent** | ✅ **能**（核心能力验证通过，见 §1） |
| **18** | `onSystemServerStarting` 是否触发 | ✅ **触发** |
| **15** | system_server 能否持有 signature 权限 | ❌ **「不可信」**——对照组判定成立，该方法取不到有效结论（§5.2） |
| **14** | system_server 内 `bindService` | ✅✅ **成功** —— 拿到 `BinderProxy@1adc174`（§5.1）。<br/>⚠️ 曾一度全失败，真因是**探针跑得比设备解锁早**（目标 Service `directBootAware=false`）⇒ 加 `isUserUnlocked()` 判据后通过 |
| **17** | 热更新对 system_server | ⬜ 未测 |

**本轮最重要的成果：§1 —— Xposed 通道的核心价值（拿到 Intent）已实证。**

**⚠️ §7 记的是三次「模块不加载」的现象 —— 但真因【未查明】，其中的归因已降级为假设。**
**遇到不加载先怀疑自己的代码，不要怀疑框架。**

---

## 1. ⭐ 核心能力：能拿到 Activity + Intent

### 实测输出（`adb logcat | grep "E HookProbe"`）

```
★ activityResumedLocked | arg0=ActivityRecord$Token arg1=false
   反查路径 ①: ActivityRecord.forToken(IBinder) 命中
   ✅ 反查到 ActivityRecord
      pkg=com.miui.home    component={com.miui.home/...Launcher}
      intent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME]
                      flg=0x10000100 cmp=com.miui.home/.launcher.Launcher (has extras) }

      pkg=bin.mt.plus      component={bin.mt.plus/bin.mt.plus.Main}
      intent=Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
                      flg=0x10300000 cmp=bin.mt.plus/.MainLightIcon }

      pkg=bin.mt.plus      component={bin.mt.plus/l.֫ܿ᩶}
      intent=Intent { act=android.intent.action.VIEW dat=file:///... xflg=0x4 cmp=... }
```

**拿到了 `pkg` + `component` + 完整 `intent`（含 `dat` URI）** —— 这正是
设计文档 §4.1 里 `activity_changed` 触发器要的东西，**也是前三条通道拿不到的**
（`dumpsys` 的 intent 打印**不含 extras**，且 Activity 级事件根本拿不到）。

### 1.1 实现要点（写给正式实现）

| 项 | 值 |
|---|---|
| 类名 | **`com.android.server.wm.ActivityRecord`**（API 12+；9~11 是 `com.android.server.am.ActivityRecord`） |
| 方法 | `static void activityResumedLocked(`**`android.os.IBinder`**`, `**`boolean`**`)` |
| **关键** | 它是 **`static`** —— **没有 `this`**，不能用 `chain.getThisObject()` |
| **取值路径** | 第 0 个参数是 **`ActivityRecord$Token`**（`IBinder` 子类）<br/>用 **`ActivityRecord.forToken(IBinder)`** 反查实例 |
| 之后 | 从实例读 `packageName` / `mActivityComponent` / `intent` |

> ⚠️ **曾写错两处**（都是想当然）：
> 1. 类名写成 `android.app.ActivityRecord` ⇒ `ClassNotFoundException`
> 2. 用 `getThisObject()` 取值 ⇒ 恒为 null（因为是 static）
>
> **`forToken(IBinder)` 是唯一需要的反查路径**（另一条「遍历活动列表」的兜底路径用不上）。

### 1.2 对设计文档的影响

**§4.1 的数据来源描述此前不准确** —— 写的是「hook 拿到 `ActivityRecord.intent`」，
实际**要经 `IBinder` 参数 + `forToken` 反查**。**必须改。**

---

## 2. `onSystemServerStarting` 触发 ✅

```
════ onSystemServerStarting ════
  ✅ 5b 入口回调【触发】了
```

**5b 形态的入口可用。** 且日志确认模块加载正常：

```
框架 API 版本 = 102      ← 印证「设备上 102 可用」的判断
框架名/版本   = LSPosed / 2.2.0
进程名        = system
isSystemServer = true
```

---

## 3. `signature` 权限 = GRANTED（⚠️ 解读待确认）

```
#15 本 uid(1000) 对应的包: [com.qualcomm.qti.xrvd.service, ..., com.android.settings, ...]
#15 checkPermission(HOOK_CONTROL, pid=3155, uid=1000) = GRANTED ✅
```

> ### ⚠️ 这条不能直接当结论
>
> `checkPermission(perm, pid, uid)` 我传的是 **`pid=自己, uid=1000`** ——
> **查询方与被查询方是同一个 uid**。所以有两种解释：
> 1. **真放行**：signature 权限对 uid < `FIRST_APPLICATION_UID` 放行（与包可见性同源）
> 2. **自检放行**：自己查自己，Android 直接返回 GRANTED
>
> **区分方式**：用一个**不属于自己**的 signature 权限去查。
> **在区分前，设计文档里「system_server 能否持 vFlow 签名权限」仍算未决**（§8-15）。

---

## 4. ⚠️ 两个操作层面的坑（都不是代码问题，但严重拖慢调试）

### 4.1 日志：`adb logcat` 的 2 MiB 环形缓冲会被冲爆

**实测**：

| 时刻 | 现象 |
|---|---|
| 刚重启 3 分钟内 | ✅ `adb logcat` 能看到启动期日志 |
| 约 10 分钟后 | ❌ 启动日志**已被冲掉**（只剩事件日志） |
| 设备高负载时（`load average: 41`） | ⚠️ **被冲得更快** |

**量化**（`logcat -g`）：
```
main:   ring buffer is 2 MiB   ← 开机 15 秒内产生约 10400 条日志 ⇒ 必然冲爆
system: ring buffer is 2 MiB
```

**结论**：
- **要抓启动期日志，必须重启后立刻抓**——这是**唯一有效**的办法
- **持久化方案**：让用户从 LSPosed 管理器导出 verbose 日志（内容完整、不丢）

> ⚠️ **我曾试图「绕过」这个限制**（在探针里写 `Settings.Global`），
> **结果把模块彻底改坏**（§7 第 3 轮）。
> **教训：缓冲限制是环境事实，只能靠「重启后立刻抓」应对；
> 为绕过它而改代码，代价远大于多抓几次日志。**

### 4.2 ⚠️ 「写文件」这条路已放弃（**不是权限问题，是我的实现问题**）

**曾经的错误结论**：以为是 `/data/local/tmp` 对 uid 1000 不可写（`drwxrwx--x`）。

**实际**：**在 hook 路径 / 启动期路径上做文件写入，会导致模块完全不加载**。
这与路径权限无关，改到 `/sdcard/vFlow/` 同样不加载。详见 **§7**。

> **归因教训**：我当初看到「写失败」就去找**文件系统权限**的解释，
> 而真相是**我根本不该在那里写**。**这是同一类错误的又一次重演。**

---

## 5. ⭐ #14 / #15 的结论（**经 LSPosed verbose 日志取得**）

### 5.0 ⚠️ 先说方法：**verbose 日志是唯一可靠的读取途径**

**本次三条结论全部来自 LSPosed 管理器导出的 verbose 日志**，不是 `adb logcat`。
**这个方法是本文档 §4.1 早就写着的**，我却绕了三轮（改代码、调缓冲、抢时间）才用上。

| 途径 | 结果 |
|---|---|
| `adb logcat` | ❌ **实测 `main` 缓冲仅覆盖约 50 秒**（63439 条 / 52 秒 ≈ **1220 条/秒**，开机高峰期）⇒ 启动期输出**必然**已出缓冲 |
| LSPosed verbose 日志 | ✅ **持久化、完整、不丢** ← **正解** |

**导出方式**：LSPosed 管理器 → 日志 → 导出（落在 `.mindfs/upload/<日期>/verbose_*.log`）。

> **⚠️ 不要再试图用「改探针代码」或「调 logcat 缓冲」绕过**：
> `logcat -G 16M` 实测**有效但重启即失效**（回到 2 MiB，`persist.logd.size` 无权限写）。
> **verbose 日志是唯一正路。**

### 5.1 #14：`bindService` ✅✅ **成功 —— 主通道已实证**

```
（最终版，21:35 日志）
21:35:20   等待用户解锁…（已等 20500ms）      ← 旧版在这时就已经跑了
21:35:59   #14/#15 系统服务已就绪【且已解锁】（等待 59500ms）
21:35:59   #14 resolveService(...) = com.vflow.hookprobe.FakeVFlowService  ✅
21:35:59   #14 bindService() 返回 true ⇒ 已提交 ✅
21:36:00   ★★★ #14 bindService 成功，拿到 binder=android.os.BinderProxy@1adc174  ✅✅
```

**三组组件级对照（解锁后，全部 ✅）**：

```
getServiceInfo(com.android.systemui)      = ✅ AutofillRendererService (exported=true)
resolveService(com.android.systemui)      = 命中 ✅
getServiceInfo(bin.mt.plus)               = ✅ ActivityRecordService (exported=true)
resolveService(bin.mt.plus)               = 命中 ✅
getServiceInfo(com.vflow.hookprobe.vflow) = ✅ FakeVFlowService (exported=true)
resolveService(com.vflow.hookprobe.vflow) = 命中 ✅
```

### ⭐ 真因：**探针跑得比设备解锁早**（一个与可见性无关的时序变量）

| 时刻 | 事件 |
|---|---|
| 21:18:33 | 探针查询 ⇒ 全部失败（**设备尚未解锁**） |
| 21:19:20 | 设备解锁（比探针晚 **47 秒**） |
| 21:35:59 | 加 `isUserUnlocked()` 判据后重跑 ⇒ **全部成功** |

**机制**：目标 Service 是 **`directBootAware=false`**。
设备未解锁时，这类组件**在 package 解析阶段就被排除**，
表现与「包不可见」**一模一样**：

| 现象 | 未解锁 | 解锁后 |
|---|---|---|
| `getServiceInfo` | ❌ `NameNotFoundException` | ✅ 命中 |
| `resolveService` | ❌ `null` | ✅ 命中 |
| `bindService` | ❌ `false` | ✅ `true` + `BinderProxy` |

> ### ⚠️ 这条**必须写进正式实现**
>
> **hook 层要等「用户已解锁」再开始通信**（判据：`UserManager.isUserUnlocked()`）。
> 未解锁时去解析 vFlow 的组件、或去 bind，会得到**一堆假的「连不上」**。
> **这不是探针的临时措施，是正式实现的必需环节。**

### 5.1.1 ❌❌ 归因反面教材：我在这里**连错两次，方向还相反**

| 我当时的结论 | 真相 |
|---|---|
| 「**实测推翻了文档核心论据**」（包可见性） | ❌ **过头结论** —— 一次实验、**零对照项**，次日被对照矩阵反转 |
| 「组件可能没装 / 装错版本」 | ❌ **又错** —— 组件一直在（设备 APK 的 manifest 里就有 `FakeVFlowService`） |
| 「是未解锁」 | ✅ **实测确证** |

**我做过的无效排查**（都应避免）：
- 用 `dumpsys | grep` 判断「组件不存在」—— **`Service Resolver Table` 不列无 `<intent-filter>` 的组件**，方法本身错
- 用 `query-services -p <包>` 判断 —— **`-p` 是「按 ACTION 查并限定包」**，语义不是「列出该包的 Service」；查具体组件要用 `-n <包>/<组件>`
- 怀疑「哈希不同的 APK 装错了」—— 拉下来比对后发现 manifest 里 **Service 一直在**

**教训**：
1. **拿到 `null`/`false`/异常时，先做【对照项】把范围框住，再谈结论**
2. **优先怀疑时序/状态类变量**（**解锁**、`stopped`、进程存活）——
   它们最容易被误判成「能力/权限」问题
3. **要推翻一条有源码支撑的论据时，门槛必须更高**
4. **别被异常名误导**：`getServiceInfo` 抛 `NameNotFoundException`
   **不代表组件不存在** —— **被过滤**（含未解锁）时也抛它

### 5.2 #15：signature 权限 ❌ **「不可信」—— 对照组判定成立**

```
#15 本 uid(1000) 对应的包数=46
#15 ① HOOK_CONTROL (vFlow 定义)      = GRANTED
#15 ② BIND_SERVICE (第三方定义·对照) = GRANTED
#15 ③ PUSH_WRITE_PROVIDER (对照2)    = GRANTED
#15 判读：
⚠️ 目标权限 GRANTED，但【对照也 GRANTED】
⇒ 是「自己查自己一律放行」，**结论不可信**
⇒ system_server 能否持 vFlow 签名权限【仍未定论】
```

**结论**：**`ctx.checkPermission(perm, 自己pid, 自己uid)` 这条路拿不到有效结论**
——查谁都是 GRANTED（连两个毫不相关的第三方 signature 权限也是）。

> **⚠️ 我当初加对照组的判断是对的。**
> 若只看 ①（上一版的做法），会得出「system_server 能持 vFlow 签名权限」这个**错误结论**，
> 进而让整个鉴权方案建立在错误前提上。

**要拿到真实结论，必须换方法**（本探针未做）：

| 候选方法 | 说明 |
|---|---|
| 从 vFlow 侧反查 | vFlow 用 `checkPermission(perm, hookPid, 1000)` —— **查询方≠被查询方** |
| 真实 binder 事务 | 让 hook 层**实际调用** vFlow 的 binder，在 vFlow 侧读 `Binder.getCallingUid()` |
| 看 `ActivityManager` 的 grant 记录 | 查 uid 1000 是否真在某个 permission 的 granted 列表里 |

### 5.3 启动链 —— 全部正常 ✅

```
onModuleLoaded          ✅   框架 API 版本 = 102 / LSPosed 2.2.0 / isSystemServer=true
onSystemServerStarting  ✅
注入成功                 ✅
```

**⇒ 「5b 形态能否加载」此前已答（§2），本次再次确认。**

### 5.4 仍未测

| # | 项 | 状态 |
|---|---|---|
| **17** | 热更新对 system_server 是否适用 | ⬜ 未测 |

---

## 5.5 ⚠️ 读取结论的正确姿势（**下次直接用这个，不要重复我的弯路**）

1. **探针里只 `say()`，不引入任何新机制**（§7）
2. **重启后** → **LSPosed 管理器导出 verbose 日志** → 读文件
3. **不要**用 `adb logcat` 抢时间（缓冲实测仅 ~50 秒）
4. **不要**调 `logcat -G`（重启即失效）
5. **不要**为了「方便读结果」改探针代码（§7 三次失败的根因）

---

## 6. 构建链路（`build.sh` 的 `hookprobe` 目标）

手动打包 libxposed 模块要走通的几步（**每步都踩过**）：

| 步骤 | 坑 |
|---|---|
| 取依赖 | **`api` + `service` 两个 aar 都要**。`service` 提供 `XposedProvider` **实现类**，**必须打进 dex**；只声明 provider 不打包实现 ⇒ **模块静默不加载** |
| javac | classpath 上多个 jar **必须同一种路径风格**（Windows `D:\` vs Unix `/d/` 混用 ⇒ javac 全都找不到） |
| 模块元数据 | `META-INF/xposed/{module.prop,java_init.list,scope.list}` **aapt2 不管**，要 `jar uf` 手动注入 |
| `module.prop` | **`minApiVersion` 写 101**（写 102 会被拒载）；`targetApiVersion` 才是 102 |
| manifest | `XposedProvider` 的 `<provider>` 声明（内容取自 `service` aar 的 manifest，`${applicationId}` 换实际包名） |
| `scope.list` | system_server 用**特殊虚拟包名 `system`** |

### 6.1 ~~重装 APK 后 LSPosed 勾选会丢~~ ❌ **本条已作废**

> **原文的推测是错的，已由用户实践推翻**：用户明确说明
> **「每次重启都会手动取消勾选、再勾选一次」**——即勾选状态从未丢失。
> 因此「重装导致勾选重置」这个推测**不成立**，原文的结论「不要先怀疑代码」**也是错的**。
>
> **真实原因是代码问题**，见 §7。**这条留作反面教材**：
> 我当时把「模块不加载」归因到框架，而实际三次都是我自己改坏了。

---

## 7. ⚠️ 三次「模块不加载」—— **真因未查明**（勿当规则用）

> ## ❌❌ **本节此前写成「启动期不能新增 IPC / I/O」，该结论已被反证**
>
> **反证**：当前**已实测可用**的探针版本（拿到 `BinderProxy@1adc174` 的那一版），
> **启动期路径上就有四处跨进程调用**：
> ```
> getSystemContext()                   ← 反射
> ctx.getPackageManager()              ← 跨进程
> ctx.getSystemService(USER_SERVICE)   ← 跨进程
> um.isUserUnlocked()                  ← 跨进程   ← 还是我为解决解锁问题【新加的】
> ```
> **模块加载正常、hook 正常、`bindService` 正常。⇒「启动期不能有 IPC」不成立。**

### 7.1 实际观察到的事实（**这部分可靠**）

P0 期间探针**三次在改动后完全不加载**（连 `onModuleLoaded` 都不触发）：

| 轮次 | 改动内容 | 结果 |
|---|---|---|
| 1 | 往 `say()` 里加**写文件**（`/data/local/tmp`、`/sdcard/vFlow/`） | ❌ 不加载 |
| 2 | `probeCommunication` 改签名 + 新增**静态缓存** `probeResultCache` + 在 hook 回调里调 `flushProbeResultIfReady()` | ❌ 不加载 |
| 3 | 新增 `report()` —— 在 `probeCommunication` 里**写 `Settings.Global`** | ❌ 不加载 |

**「能跑的版本 ↔ 不能跑的版本」对照有效** —— 每次回退后立刻恢复，
设备 / LSPosed / 重启流程都没变。**⇒ 三次确实与我的改动有关。**

### 7.2 ⚠️ 但「启动期 IPC/I/O 是元凶」**只是假设，不是结论**

**我的推断链**：观察到三次不加载 → 找共性 → 都落在启动期 → **写成了规则**。

**错在第 3 步** —— 那是**假设**，需二分定位才能确认，**而我直接写成了结论。**

**而且我连二分定位都没做**：
- 第 2 轮**一次改了三处**，回退时**三处一起退** ⇒ **无法知道是哪一处**
- 三次都是**整体回退**，**从未逐处验证**

**⇒ 真因至今未知。** 候选（**均未验证**）：
- 轮次 2 里的**静态字段缓存**，或**hook 回调里新增的调用**
- 某个具体 API 在这台设备 / 这个 LSPosed 版本上的问题
- 与启动期无关的其它因素

### 7.3 保留唯一一条**有依据**的纪律

> **改 hook 路径时：一次只改一处，且始终保留一个「确认可用的版本」可回退。**

**依据**：这不是从平台行为推出的规则，而是**上表三次失败的直接教训**
——**一次性改多处会让失败无法归因**（我自己就因此连真因都没查出来）。
**它约束的是「改动方式」，不是「平台能做什么」。**

### 7.4 归因纪律（**非绝对表述**）

> 遇到「模块不加载」：**先自查代码改动**（回退 → **逐处二分**），
> **但不要预设「一定是我的代码」** —— 也可能是环境。
> **关键是：不要凭一次观察断定是谁的问题，要做对照实验。**
>
> ⚠️ 我在此处的实际教训是**「归因太快」而不是「方向错」**：
> 三次先怀疑框架、三次都错 —— 但更本质的是**三次都没做二分定位**，
> 所以**既没证明自己，也没排除环境**。

### 7.5 改动纪律（**只保留有依据的**）

| # | 纪律 | 依据 |
|---|---|---|
| 1 | **一次只改一处**，改完立刻验证，不夹带 | ⚠️ **有依据**：P0 三次失败中，第 2 轮一次改三处 ⇒ 回退时只能整体退 ⇒ **真因至今查不出** |
| 2 | **始终保留一个「确认可用的版本」**可随时回退 | 同上（P0 靠它恢复了三次） |
| 3 | 要回传结论**优先复用既有日志通道** | ⚠️ **弱依据**：这是「用成熟机制而非新机制」的一般工程考量，**不是平台限制**。<br/>（当初我试图借它绕过 logcat 缓冲，结果引入了 `Settings.Global` 写入 —— **那次失败的原因至今也没查清**） |

**❌ 已删除的条款**（都是我的推断，不是实测结论）：

- ~~「启动期路径上绝不新增 IPC / I/O」~~ —— **已被反证**（见本节开头）
- ~~「遇到不加载，先假设是我的代码，不得先归因框架」~~ ——
  **过度绝对化**。正确做法是「不要凭一次观察断定，要做对照实验」，
  **而不是预设方向**。我三次归因错，根因是**没做二分定位**，不是「方向选错了」。
- ~~「改动必须逐项过一遍『会不会落进启动期路径』」~~ ——
  基于已被反证的前提，**无依据**

### 7.6 一条仍然成立的操作事实

**`adb logcat` 的环形缓冲只有 2 MiB，开机洪流会把它冲爆**（见 §4.1）。
实测开机 15 秒内约 **10400 条**日志 ⇒ 启动期 `say()` 输出**读的时候已不在**。
**唯一幸存的证据**往往只是**系统自己打的 warning 栈帧**（例如
`W ContextImpl: ... HookProbeEntry.probeCommunication:439`）——它能证明「代码执行到了」，
但**看不到探针自己打的内容**。
**⇒ 抓启动期结论必须、且只能在重启后立刻抓。**
