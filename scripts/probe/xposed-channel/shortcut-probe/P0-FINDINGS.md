# 快捷方式探针 · 结论（P0）

> 对应方案：`.mindfs/tasks/plan-4.md` §5 / §6 S1–S6
> 探针工程：`scripts/probe/xposed-channel/shortcut-probe/`
> 一键脚本：`scripts/xposed-shortcut-probe-verify.sh`
> 状态：**部分执行** —— 见 §0

---

## 0. 执行状态：**探针未跑，但真机证据已取得一部分**

| 环节 | 状态 | 证据 |
|---|---|---|
| 探针工程能打包 | ✅ **完成** | `bash scripts/probe/xposed-channel/build.sh shortcutprobe` → **exit 0**，产出 `out/shortcutprobe.apk`（33 KB），四道断言全过 |
| 探针 APK 能装到真机 | ✅ **完成** | `adb install -r -t` → `Success`；`pm list packages` 可见；`META-INF/xposed/` 三个谱文件在包内 |
| **探针真的注入 system_server** | ❌ **未执行** | 需在 LSPosed 里**手工**启用模块 + 勾「系统框架」作用域 + **重启设备**。本机 `adb shell su` 不可用（`inaccessible or not found`），**无法用命令行完成**；重启也会打断用户，未擅自做 |
| **五项【推断】的真机结论** | ❌ **未取得** | 依赖上一条 |
| **与 dumpsys 的 dat 对照** | ✅ **已取得**（不依赖探针） | 见 §2 —— 用 `dumpsys shortcut` 真机输出做的独立核对 |

⚠️ **2026-10-01 更新：真机结论的执行载体已变更**（用户裁决 + 父会话同意）。

- **原因**：本探针 APK 需「LSPosed 启用 + 勾系统框架 + **重启设备**」，代价太重（用户原话：「我不喜欢这种探针方式，它的代价太重，需要重启」）。
- **新载体**：hook 层**本来就跑在 system_server 里**（整个 ③ 通道的前提）⇒
  它反射读 `ShortcutService` 的能力与本独立探针**完全等价**，不必单独注入模块。
  父会话已在 `DiagnosticCapabilityHandler` 加了 `MODE_PROBE_SHORTCUT`，**重装 APK 即热重载生效**。
- **⇒ 真机结论由父会话走热重载路径取，不在本任务；本任务不再等设备。**
- **本探针工程保留** —— 作为**跨机型复测的脚手架**（同 `scripts/fold-trigger-verify.sh` 之于折叠屏）。
- 五项判定表（§1）**仍然有效并被复用**：父会话的实现照本探针的五项设计写的
  （含「候选字段名 + 遍历父类、不写死」）。

⇒ 本文件既记录「哪些拿到了」，也把「怎么把它跑完」写清楚（§4）。
**不把「打包成功」说成「已验证」。**

---

## 1. 五项【推断】的判定表（状态：**未取得**）

跑完 §4 的步骤后，用 `scripts/xposed-shortcut-probe-verify.sh logs` 自动判定并回填本表。

| # | 要验的 | 探针打印点 | ✅ 通过判据 | 结果 |
|---|---|---|---|---|
| 1 | `LocalServices.getService(ShortcutService)` 能否取到 | `[1]` | 非 null 且类名是 `com.android.server.pm.ShortcutService` | **未取得** |
| 2 | ★ hook 层调用时 `injectBinderCallingUid()` 返回什么 | `[5]` | 返回 **1000** | **未取得** |
| 3 | 内部字段名是否随版本漂移 | `[1.1]`/`[2.1]`/`[2.2]` | 能看到用户映射候选（探针按**类型**找，不写死名） | **未取得** |
| 4 | `getIntents()` 稳定 / dat 完整 / extras 的 javaClass | `[6.2]`/`[6.3]` | dat 完整；`extra_scene_account` 是 `java.lang.String` | **未取得**（但见 §2 的替代证据） |
| 5 | `ShortcutPackage` 是否已按当前用户过滤 | `[7]`/`[8]` | userKeys 匹配 `myUserId` | **未取得** |

---

## 2. ⭐ 本轮从**真机**独立取得的证据（不依赖 LSPosed）

设备：小米 MIX Fold 3 / Android 17（`192.168.1.32:42261`）。
⚠️ **本节的计数是时变快照** —— 设备上的快捷方式会随用户操作增减。
每处都标了采集时刻；**引用时必须带时刻，不要当常量**（§2.2 就是这条的实例）。

### 2.1 ⚠️⚠️ 文档里的米家包名是**错的**

| | 包名 | 出处 |
|---|---|---|
| survey `:620` | `com.xiaomi.mihome` | ❌ **错** |
| 设计文档 `:440` | `com.xiaomi.mihome` | ❌ **错**（抄自 survey） |
| survey `:287` | `com.xiaomi.smarthome` | ✅ 对 |
| **真机 `pm list packages`** | **`com.xiaomi.smarthome`** | ✅ 实测 |

两份文档**内部就不一致**：`:620` 是讲 `verifyCaller` 时的随手举例（写错了），
`:287` 在同一份文档里用的是正确包名。设计文档抄了错的那个。

⇒ **已修正**：探针 `TARGET_PKG` 用 `com.xiaomi.smarthome`（并在源码注释里记了这件事）。

### 2.2 ⚠️ 一个**前提问题**，以及它在两次采集之间**变了**

⚠️⚠️ **本节是时变状态的快照，不是常量。** 采集时刻已标注，**引用时必须带时刻**。

| 采集时刻 | 米家（`com.xiaomi.smarthome`）快捷方式条数 |
|---|---|
| 2026-10-01 **01:17**（首轮） | **0** 条 —— App 装了，但没有快捷方式 |
| 2026-10-01 **01:34**（复采，评审指出后） | **1** 条 —— 就是设计文档描述的那条样本 |

那条样本（`timestamp=1790667311717` ⇒ 建于 **2026-09-29 15:35**，即**首轮采集之前就存在**）：
```
shortLabel=关闭灯与投影仪
intents=[Intent { act=com.xiaomi.smarthome.scene.smarthomelauncher
                  cmp=com.xiaomi.smarthome/.scene.activity.SmartHomeLauncherActivity
                  }/PersistableBundle[{extra_scene_account=1462285899,
                                      extra_str_scene_id=scene2.0_1981036855894749184_287001185508_1462285899}]]
```

⚠️ **它与方案 §4.2 的示例逐字段吻合**（label「关闭灯与投影仪」、键 `extra_scene_account=1462285899`）
—— 也就是说**方案里那个例子确实是真机上的真样本**，不是编的。

⇒ **首轮「0 条」为什么是 0，我没查清**（那条的时间戳早于首轮采集，按理不该是 0）。
可能是我首轮的 `grep` 写法的问题，也可能是当时的 `dumpsys` 快照不完整。
**不编原因** —— 记录事实：两次采集结果不同，且第一次的「0」未能复现。

#### 但它暴露的那个**设计问题是真的**，所以应对保留

无论米家当时有没有条目，「**目标样本不存在时探针该怎么办**」都是必须处理的分支：
探针第 4 项（dat 完整性）点名用米家做样本，若它不在结果里，
`[6]` 会走到「未找到」——**而「本来就没建」与「漏包（包可见性）」表现一模一样**，
下一步却完全不同。

⇒ **两处应对保留**（它们不依赖「当时有没有」这个时变事实）：
1. 探针 `[6.0]` 把**两种可能都打出来**，并提示「先用 dumpsys 区分」；
2. 回退路径 **`[6.5]`**：目标包没有时改报**任意几条带 dat 的样本**，
   脚本侧拿同样标识去 `dumpsys` 配对 —— **不依赖米家**，因此不受本设备状态影响。

### 2.3 ✅ dat 残缺率**复现**了 survey 的记载

用 `dumpsys shortcut`（**2026-10-01 01:17 采集**，407 条）按 **survey §3.1 的互斥四类**口径统计
（⚠️ 复采时块数为 407→409、含 dat 由 182→180，均为设备状态漂移，结论不变）：

| 类别 | 本机实测 | survey 记载 |
|---|---|---|
| **C. 无 `cmp` 且 dat 残缺 ⇒ 致命** | **71 条 = 17.4%** | 74 条 = **18.1%** |
| **D. 无 `cmp` 无 dat ⇒ 先天无解** | **23 条 = 5.7%** | 23 条 = **5.6%** |
| 含 `cmp` 合计 | 313 条 = 76.9% | 307 条 = 75.2% |
| 总数 | 407 | 408 |

⇒ **C/D 两类几乎逐字复现**（17.4% vs 18.1%、5.7% vs 5.6%），
这**独立佐证了本 capability 的存在理由**：那 17.4% 正是 ③ 能救、
而 dumpsys 路径永远救不了的那批。

⚠️ **口径警告**：本表**不支持**与 survey 的 A/B 两行直接比较 ——
survey 的 A/B 是按「extras 里有没有数字」切的（263 / 44），
本表是按「dat 是否残缺」切的。**维度不同，不可混用**。

### 2.4 其它可复用的量

- 本机 `dumpsys shortcut` 输出 = **442 KB / 8207 行 / 407 条 ≈ 1.09 KB/条**
  （与 survey 的 436 KB / 408 条 ≈ 1.07 KB/条 **一致**）
- 含 `dat=` 的条目 = 182 条，其中**被省略成 `...` 的 = 176 条**（96.7%）
  —— ⚠️ 这个比例高得吓人，但它统计的是「所有含 dat 的条目」，
  其中大部分同时有 `cmp`（属可用类），**不是 §2.3 的 C 类**。

---

## 3. 探针代码的**去留**（需求点名要写清楚）

**结论：探针不留在正式路径上。**

| 项 | 去留 | 理由 |
|---|---|---|
| `scripts/probe/xposed-channel/shortcut-probe/` 全部 | **保留在仓库**（`scripts/probe/` 下） | 与既有 `scripts/probe/xposed-channel/hookprobe/` 同处 —— 那是本仓库对探针的既有约定：**探针源码入库、编译产物不入库**（`.gitignore`），供将来复测与追溯 |
| `out/shortcutprobe.apk` | **不入库** | 编译产物，已在 `.gitignore` 覆盖范围内 |
| `build.sh` 的 `build_shortcutprobe()` | **保留** | 纯追加，不改既有函数；删掉反而让探针无法复现 |
| 探针代码是否进入 `app/`？ | **否** | 探针是**独立的 javac + aapt2 工程**，不在 `:app` 模块、不在 `:core`，**零行进入正式路径** —— 这是「探针不留在正式路径上」的**落实方式**（不是「写在别处」，而是「根本不在那个构建里」） |

⚠️ 第二步的正式实现**不从这里拷贝任何文件** —— 它会按 `plan-4.md` §6 S8 新写
`xposed/capabilities/QueryShortcutIntentsHandler.kt`（那要遵守 `WireLayerPurityTest` 的引用面约束，
而探针工程不受那套约束，两者的规矩不同）。

---

## 4. 怎么把它跑完（可执行步骤）

```bash
# 1) 打包（已完成，可跳过）
bash scripts/probe/xposed-channel/build.sh shortcutprobe

# 2) 采基线 + 安装（脚本会打印手工步骤）
bash scripts/xposed-shortcut-probe-verify.sh full

# 3) 【手工，设备上操作】—— 脚本做不了（无 root，且必须重启）
#    a. 打开 LSPosed 管理器 → 模块 → 启用「vFlow ShortcutProbe」
#    b. **作用域勾选「系统框架」**（对应 scope.list 里的 system）
#    c. **重启设备**（system_server 必须重启才会注入；只重启 App 无效）

# 4) 重启后等约 90 秒（探针延后 60 秒跑），然后：
bash scripts/xposed-shortcut-probe-verify.sh logs
```

**日志判据**（脚本会自动逐条判，这里列出供人工核对）：

```
E ShortcutProbe  ════ ShortcutProbe 开始 ════
E ShortcutProbe    [1] ✓ 拿到：com.android.server.pm.ShortcutService
E ShortcutProbe    [2]     值类型：com.android.server.pm.ShortcutUser
E ShortcutProbe    [3] 全量遍历：ShortcutInfo 总数 = 407      ← 与 dumpsys 的 407 对比
E ShortcutProbe    [3.3] ★ 真实 wire 估算：wire ≈ ... = raw×2
E ShortcutProbe    [5] ✓ 返回 = 1000                          ← ★ 唯一必须真机验的项
E ShortcutProbe    [6.0] ✗ 未找到 ...（本设备米家无快捷方式）
E ShortcutProbe    [6.5] ✓ 已报 5 条样本（脚本侧去 dumpsys 找同名项比对）
```

⚠️ **读日志的两个坑**（`P0-FINDINGS.md`(hookprobe) §4 的既有教训）：
1. `adb logcat` 的 `main` 缓冲会被开机洪流冲爆 ⇒ **重启后立刻抓**，或用 LSPosed 导出的 verbose 日志；
2. LSPosed 的框架日志**只持久化 Error 级** —— 探针已全部用 `ilog.ERROR`，故两边都能拿到。

---

## 5. 下一步

1. **跑完 §4** ⇒ 回填 §1 的五项判定表 ⇒ 那份结论决定第二步（`plan-4.md` §6 S8–S14）怎么写。
2. 若第 1 项（拿到 `ShortcutService`）失败 ⇒ 走 `plan-4.md` §5 的失败退路
   （改从 `ServiceManager` 取 `IShortcutService` 的本地实现；若都不行则整个方案要重评估）。
3. 若第 2 项返回**非 1000** ⇒ 需在 hook 层给 system_server 的 PackageManager 放行包可见性
   （**只需**在那一处放行，不需逐 App 处理）。
