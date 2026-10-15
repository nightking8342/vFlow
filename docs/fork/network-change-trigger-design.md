# 网络变化触发器设计 —— 连通性 / 网络身份变化监听

> 版本：v1.0
> 状态：**设计定稿，未实现**（本文件只写需求与设计，不含实现状态）
> 目录归属：**fork 独有**（冲突归我方），上游无此文件
> 关联（计划新增）：
> `core/workflow/module/triggers/NetworkChangeTriggerModule.kt`（模块声明）、
> `triggers/NetworkChangeStateResolver.kt`（纯函数状态机）、
> `triggers/NetworkChangeTriggerData.kt`（事件载荷）、
> `triggers/handlers/NetworkChangeTriggerHandler.kt`（事件源）
> 关联既有能力：`vflow.trigger.wifi`、`vflow.trigger.sim_data_switch`、`vflow.trigger.broadcast`
> 关联文档：`surveys/trigger-system-overview.md`（触发器体系现状地图）

---

## 0. 一句话方案

新增 **1 个** app 层触发器 `vflow.trigger.network_change`：

- **事件源**：`ConnectivityManager.registerDefaultNetworkCallback` —— **零权限**
  （`ACCESS_NETWORK_STATE` 是 normal 权限，已在 `app/src/main/AndroidManifest.xml:41`，
  装包即授予、不弹窗）。
- **形态**：`trigger_type` 枚举 + `getDynamicInputs()` 按类型隐藏无关参数
  （照抄 `WifiTriggerModule` 的既有形态，**不发明新范式**）。
- **关键决策**：把「从 A 切到 B」做成**一等事件**，而不是让用户拼
  「断开 A」+「连接 B」两条规则（§5.2）。
- **不走 Core、不走 Xposed**：该信号 app 层完全拿得到（§4）。

---

## 1. 需求澄清：三种含义

「手机网络发生变化」在中文语境下至少三种含义，判定键与权限完全不同：

| 含义 | 例子 | 判定键 | 权限 | 本期 |
|---|---|---|---|---|
| **连通性变化** | 断网 / 恢复、Wi-Fi ↔ 蜂窝、以太网 / VPN 起落 | 默认网络的有无 + `TRANSPORT_*` | 无 | ✅ |
| **网络身份变化** | Wi-Fi A → B；运营商 A → B | SSID / BSSID；PLMN / subId | 定位；`READ_PHONE_STATE` | ✅ Wi-Fi 域（电话域见 §9） |
| **网络能力变化** | 有网但没互联网、切到计费网络、需门户登录 | `NET_CAPABILITY_*` | 无 | ✅（作为筛选条件） |
| 网络质量变化 | 带宽、延迟、信号强度 | `LinkDownstreamBandwidthKbps` / `SignalStrength` | 无 / `READ_PHONE_STATE` | ❌ 不做 |

⚠️ **「Wi-Fi A → B」与「运营商 A → B」都属于「网络身份变化」**，而身份变化的共同点是：
**接入方式（transport）不变**。这一条直接决定了实现方式（§3.2）。

---

## 2. 现状盘点

### 2.1 仓库已有的三条路子

| 现成能力 | 实际覆盖 | 不覆盖 | 代价 |
|---|---|---|---|
| `vflow.trigger.wifi`（上游） | Wi-Fi 连接/断开（`NetworkRequest` + `TRANSPORT_WIFI`，`WifiTriggerHandler.kt:86`）、Wi-Fi 开关（`WIFI_STATE_CHANGED_ACTION`） | 蜂窝、默认网络切换、断网/恢复 | 声明 `LOCATION`（为读 SSID）⇒ 新设备未授权时工作流被**静默停用** |
| `vflow.trigger.sim_data_switch`（fork） | 默认上网卡（DDS）在卡1/卡2 之间切换 | 数据开关、连通性、同卡换运营商 | `READ_PHONE_STATE` |
| `vflow.trigger.broadcast`（fork，逃生舱） | 理论上可填 `android.net.conn.CONNECTIVITY_CHANGE` | — | 该广播**已废弃**、粒度粗（不说是哪张网、什么变了），投递行为随版本收窄（Android 8 起不投给 manifest receiver）；动态 receiver 能否收到**必须真机验**（§13.2） |

⚠️ `WifiTriggerModule` 的「网络连接」文案**有误导性**：它注册的 `NetworkRequest`
只 `addTransportType(TRANSPORT_WIFI)`，**移动数据的开关它一无所知**。

### 2.2 覆盖矩阵

| 变化 | 现状 |
|---|---|
| Wi-Fi 连接/断开 | ✅ 已有 |
| Wi-Fi 开关 | ✅ 已有 |
| 断网 / 恢复 | ❌ |
| Wi-Fi ↔ 蜂窝 / 以太网 / VPN 切换 | ❌ |
| 有网但没互联网（captive portal） | ❌ |
| 计费 / 非计费网络变化 | ❌ |
| Wi-Fi A → B（不同 SSID） | ⚠️ 半可用（见 §2.3、§3.2） |
| Wi-Fi 同 SSID 换 AP（漫游） | ❌ |
| 换默认上网卡 | ✅ 已有（`sim_data_switch`） |
| 同卡换运营商 / 漫游 | ❌（§9） |

### 2.3 ⚠️ 顺带发现的既有缺陷：`bssid` 输出恒为空串

`WifiTriggerModule.kt:119` 声明了 `bssid` 输出，`execute()` 在 `:151` 读
`context.getVariable("bssid")` —— 但全仓**没有任何地方写过这个变量**
（`grep -i bssid` 只有 3 处命中：输出声明、该读取、兜底 `VString("")`）。
Handler 传给 `executeTrigger` 的 `triggerData` 只有 SSID。

⇒ 该输出**恒为 `""`**，下游引用它不会报错、也不会拿到值。这同时是
「同 SSID 换 AP」目前无法判定的直接原因（BSSID 是唯一能区分同 SSID 不同 AP 的键）。

**处置**：本期**不改** `WifiTriggerModule`（上游文件，控制 diff 面积），
由本模块用 BSSID 覆盖该场景；缺陷本身记入 §16。

---

## 3. 分层模型与两条关键推论

### 3.1 五层

| 层 | 变的是什么 | 判定键 | 信号源 |
|---|---|---|---|
| L0 承载开关 | Wi-Fi / 移动数据 / 飞行模式开关 | 开关状态 | 广播 + `Settings.Global` |
| L1 连通性 | 有没有网 | 默认网络的有无 | `NetworkCallback` |
| L2 接入方式 | 用哪条腿 | `TRANSPORT_*` | 同上 + `NetworkCapabilities` |
| **L3 网络身份** | 连的是哪一个网络 | SSID / BSSID / subId / PLMN | **Wi-Fi 域 + 电话域（两套）** |
| L4 网络能力 | 这条网好不好用 | `NET_CAPABILITY_*` | `onCapabilitiesChanged` |

### 3.2 推论 1：L2 不变时，按 transport 判定的触发器抓不到

Wi-Fi A → B 与运营商 A → B **都在 L3**，而 L2 是不变的（A、B 都是 Wi-Fi / 都是蜂窝）。
⇒ 只按「网络类型变化」判定的触发器对这两类**是瞎的**。

它只能靠副作用间接感知（换 SSID 时系统通常会发 `onLost(A)` + `onAvailable(B)`），
但这**不保证**：

- 同 SSID 换 AP（漫游）可能复用同一个 `NetworkAgent` ⇒ 两个回调都不发；
- 「双 Wi-Fi 加速」下可能同时存在两个 `TRANSPORT_WIFI` 的 `Network`
  ⇒ 单值「当前网络」模型会误判。

⇒ L3 要可靠，**必须引入身份键**，不能靠 L1/L2 的边角料。

### 3.3 推论 2：IP **不是**身份键

IP 是网络环境的**结果**，两个方向都会错：

| 机制 | 后果 |
|---|---|
| DHCP 重租 + IPv6 隐私扩展（RFC 4941，Android 默认开） | 同一网络被判定为变了（**误报**） |
| 私有网段高度重复（`192.168.1.0/24` 被无数路由器复用） | 换了网络却判定为没变（**漏报**） |
| NAT / CGNAT 聚合 | 整个小区/公司共用一个公网 IP ⇒ 区分度远低于直觉 |

分界线：**L2 变了，IP 就够用；L2 不变（L3 场景），IP 必然失效。**
所以 IP 不能替代事件源，最多当辅助身份键 —— 而比它更好的零权限候选见 §3.4。

### 3.4 零权限的身份键候选（按区分度排序）

| 候选键 | 区分度 | 来源 |
|---|---|---|
| **`Network` 对象本身**（network handle） | 中高 | `NetworkCallback` 回调参数，**无需权限** |
| **IPv6 `/64` 前缀** | 中高 | `LinkProperties`（用前缀，不用完整地址，否则被隐私扩展坑掉） |
| `LinkProperties.getInterfaceName()` | 中 | `wlan0` / `rmnet_data0` / `tun0` |
| 完整 IP 地址 | 低 | — |
| DNS / 默认网关 | 低 | 私有网段网关同样高度重复 |

---

## 4. 通道选型：app 层

判据一句话：**这个信号在 app 层拿得到吗？拿得到就别动 Core / Xposed。**

| 通道 | 选用条件 | 本仓库先例 | 本需求 |
|---|---|---|---|
| **app 层** | 公开 API 就能拿到的信号 | `FoldTrigger`、`SimDataSwitch`、`WifiTrigger` | ✅ **全部信号都能拿到** |
| `:core`（app_process，uid 2000/0） | **只能**在特权进程里拿到的信号 | `LogcatTrigger`（普通 app 读不到别人的日志） | ❌ 无一项需要 |
| Xposed（注入 system_server） | 只有 system_server 内部才有的数据 | `ActivityChangedTrigger`（Activity 的 Intent extras） | ❌ 零收益 |

补充三条：

1. **架构错配**：触发器体系整个在 app 进程（`TriggerService` → `TriggerHandlerRegistry`
   → Handler → `TriggerExecutionCoordinator`）。走 Core 要新建一条**事件回传通道**
   （只有 logcat 那套流式双工可参考），把「监听型事件源」硬塞进「请求-响应型」socket。
2. **权限收益为零**：`ACCESS_NETWORK_STATE` 本就是 normal 权限；L3 的身份键（SSID/BSSID）
   需要定位权限，而**权限判定按调用方 uid 走，挪到 Core 进程一样要**，还多一层回传故障面。
3. **Xposed 代价完全不成比例**：xposed 通道的理由是「app 层拿不到」（Activity 的 Intent
   extras 只有 system_server 里有）。本需求**没有一项**属于此类，而 xposed 要付：
   注入 system_server 的整机软重启风险、用户必须装 LSPosed、hook 点随 ROM 漂移。

**唯一可能回头考虑 Core 的条件**：**进程保活**。app 层 `NetworkCallback` 在
`TriggerService` 降级/进程被杀时会漏事件；`app_process`（uid 2000）进程不受 App Standby
约束，理论上更抗杀 —— 但 Core 进程本身也是 app 拉起来的，收益不确定。
⇒ **先按 app 层做，真机实测发现息屏/深度待机漏事件，再考虑把事件源挪到 Core**
（走 logcat 那条已验证的流式通道）。

---

## 5. 模块划分

### 5.1 分界线是「事件源」，不是「能力清单」

| 方案 | 评价 |
|---|---|
| 一个模块包含所有（含电话域） | ❌ **不做**。跨事件源 = 真耦合：一个 Handler 要同时持有 `NetworkCallback`（零权限）与 `TelephonyCallback`/广播（要 `READ_PHONE_STATE`），两套生命周期、两套基线、两套失败模式，任何一半出问题都会让另一半跟着不可用，且排障时分不清是哪一半 |
| 按 L1/L2/L4 拆成多个 | ❌ **不做**。① 用户心里的分类 ≠ 实现的分类；② 同一事件源内拆开会产生多份回调 + 多份独立基线，判定不一致（触发器梳理 §8.2 第 5 条记的正是这个坑）；③ 模块数量成本（已有 25+ 触发器） |
| **同一事件源 → 一个模块（枚举 + 动态参数）** | ✅ **本期方案** |

### 5.2 ⚠️ 为什么「切换 A→B」必须是一等事件

如果让用户拼「断开 A」+「连接 B」两条规则：

1. 一次切换会**触发两次**工作流；
2. 而重入策略默认是工作流级 `BLOCK_NEW` ⇒ A 那次还在跑时，B 那次会被**直接丢弃**
   并记 `REENTRY_BLOCKED_NEW_EXECUTION`；
3. 净表现是「只有断开那一次生效」，**没有任何报错**。

⇒ 本模块提供 `switched` 事件类型：在合并窗口内把 `onLost(old)` + `onAvailable(new)`
合成**一个**事件，并带 `from_network` / `to_network`。

### 5.3 与既有模块的关系

- **不替代** `vflow.trigger.wifi`：它已发布、编辑器里找得到、参数是 Wi-Fi 专用的结构化形态；
  本模块是它的**超集**，但用户已有的工作流不应受影响。**一行都不改它**（上游文件）。
- **不替代** `vflow.trigger.broadcast`：逃生舱保留（第三方 action 仍需它）。
- **与 `vflow.trigger.sim_data_switch` 划清边界**：它管 DDS 切换，本模块**不碰电话域**（§9）。

---

## 6. 平台行为断言与证据等级

> ⚠️ 按仓库纪律（`docs/fork/xposed-channel-design.md` 决策 20/21）：
> **每条平台行为结论必须标注证据类型**，且【推断】项**必须真机实测后才可升格为断言**，
> 不得直接落笔成设计依据。
> 本表是**实现前必须核对的清单**，不是结论。

| # | 断言 | 证据 | 对本设计的影响 |
|---|---|---|---|
| 1 | `registerDefaultNetworkCallback` / `registerNetworkCallback` 需要 `ACCESS_NETWORK_STATE` | 【文档】 | 决定「零权限」成立与否 —— 该权限已在 `AndroidManifest.xml:41` |
| 2 | `NetworkCapabilities` 提供 `TRANSPORT_*` 与 `NET_CAPABILITY_VALIDATED / NOT_METERED / CAPTIVE_PORTAL` | 【文档】 | 决定 L2 / L4 的判定键 |
| 3 | `NetworkCallback` 的回调有 `onAvailable` / `onLost` / `onCapabilitiesChanged` / `onLinkPropertiesChanged` / `onUnavailable` | 【文档】 | 决定事件源形态 |
| 4 | 换 SSID 会**产生新的 `Network` 对象**（新 handle） | 【推断·待实测】 | 决定 `switched` 能否只靠 handle 判定 |
| 5 | **同 SSID 换 AP（漫游）复用同一个 `Network`** ⇒ 无 `onLost`/`onAvailable` | 【推断·待实测】 | 若成立 ⇒ 必须引入 BSSID 才能覆盖该场景 |
| 6 | 一次连接会回调 `onCapabilitiesChanged` **多次** | 【推断·待实测】 | 决定去抖窗口的必要性与取值（§10.3） |
| 7 | 「双 Wi-Fi 加速」下**同时存在两个** `TRANSPORT_WIFI` 的 `Network` | 【推断·待实测】 | 若成立 ⇒ 状态必须是**集合**而非单值 |
| 8 | 移动数据开关在 **Wi-Fi 已连**时不产生默认网络事件 | 【推断·待实测】 | 若成立 ⇒ 「数据开关」需要额外的 `Settings.Global` 观察者（本期不做，§16） |
| 9 | `WifiInfo.getSSID()` 需 `ACCESS_FINE_LOCATION`（否则返回 `<unknown ssid>`） | 【文档】 | 决定 L3 的权限 |
| 10 | `WifiInfo.getSSID()` 是否**还要求位置服务开启** | 【推断·待实测】 | 决定降级提示文案 |
| 11 | `CONNECTIVITY_ACTION` 自 API 28 废弃、API 26 起不投给 manifest receiver | 【文档】 | 排除它作为主路径 |
| 12 | `ACTION_AIRPLANE_MODE_CHANGED` 是 protected broadcast，动态注册可收 | 【文档】+【推断】 | 飞行模式是否单列 |
| 13 | `Settings.Global.MOBILE_DATA` 读取无需权限 | 【推断·待实测】 | 决定数据开关能否零权限 |
| 14 | `NetworkInterface.getNetworkInterfaces()` 读取本地 IP 无需权限 | 【文档】+【实测】（`GetIpAddressModule` 已在用） | 零权限身份键 |
| 15 | 息屏 / 深度待机下 `NetworkCallback` 是否漏事件（含 MIUI/HyperOS 省电策略） | 【推断·待实测】 | 决定是否需要把事件源挪到 Core（§4 唯一条件） |

---

## 7. 模块设计

### 7.1 模块身份

```
id                   = "vflow.trigger.network_change"
metadata.categoryId  = "trigger"     // id 前缀与 categoryId 两条都要有
metadata.category    = "触发器"       // （触发器身份判定有三套独立路径，见触发器梳理 §6）
```

### 7.2 参数

| id | 类型 | 取值（**稳定常量**） | 说明 |
|---|---|---|---|
| `trigger_type` | ENUM | `connected` / `disconnected` / `switched` / `capability_changed` | 默认 `connected` |
| `network_target` | STRING | `ANY`（哨兵）/ 具体 SSID | 仅 `connected` / `disconnected` / `switched` 可见 |
| `transport_filter` | ENUM | `any` / `wifi` / `cellular` / `ethernet` / `vpn` | 所有类型可见 |
| `capability_filter` | ENUM | `any` / `validated` / `not_validated` / `metered` / `not_metered` | 仅 `capability_changed` 可见 |

⚠️ 枚举**必须存稳定常量值**，不存本地化文案（否则切语言后已保存的工作流会失配）。
⚠️ `legacyValueMap` 只在真的改过**已发布**取值时才加 —— 全新模块**不要预先加无依据的兼容逻辑**。

### 7.3 动态输入

`getDynamicInputs(step, allSteps)` 按 `trigger_type` 返回可见参数
（照抄 `WifiTriggerModule` 的写法），避免用户在 `connected` 下看到 `capability_filter`。

### 7.4 输出

| id | 类型 | 说明 |
|---|---|---|
| `event` | STRING | `connected` / `disconnected` / `switched` / `capability_changed` |
| `network_type` | STRING | `wifi` / `cellular` / `ethernet` / `vpn` / `other` / `none` |
| `previous_network_type` | STRING | 上一个网络类型；无则 `none` |
| `ssid` | STRING | 仅在有定位权限时非空；否则 `""`（**不猜**） |
| `from_network` / `to_network` | STRING | 仅 `switched` 有意义 |
| `interface_name` | STRING | `wlan0` / `rmnet_data0` / `tun0` |
| `is_metered` | BOOLEAN | `NOT_METERED` 取反 |
| `is_validated` | BOOLEAN | `NET_CAPABILITY_VALIDATED` |
| `has_internet` | BOOLEAN | `NET_CAPABILITY_INTERNET` |

⚠️ 下游引用方式是 `{{<triggerStepId>.<outputId>}}` ⇒ **改 output id 会静默废掉下游引用**，
一经发布不要改。

### 7.5 摘要

照 `WifiTriggerModule.getSummary` 的 `PillUtil` 形态；⚠️ 占位符**必须显式索引**
（混用裸 `%s` 与 `%2$d` 会抛 `IllegalFormatException`，本仓库已记过）。

---

## 8. 权限设计：按 step 动态声明

### 8.1 结论：一个模块**不会**逼所有用户授权

`PermissionManager.getMissingPermissions(context, workflow)`（`PermissionManager.kt:793`）
遍历 **`workflow.allSteps`**（`Workflow.kt:53-54` 定义为 `triggers + steps`）并调用
`getRequiredPermissions(step)` —— **传的是真实 step**。而决定「缺权限就静默停用工作流」
的那一处（`TriggerService.kt:311`）走的正是这条路径。

⇒ 触发器**可以**按参数分支声明权限。先例：`MobileDataModule` / `WifiModule` / `SimDataSwitchModule`。

### 8.2 本模块的权限表

| 配置 | 需要的权限 |
|---|---|
| `network_target = ANY`（不读 SSID） | **无** |
| `network_target = <具体 SSID>`，或 `switched` 且要填 `from`/`to` | `ACCESS_FINE_LOCATION` |

⇒ `getRequiredPermissions(step)` 按是否使用 SSID 返回 `emptyList()` 或
`listOf(PermissionManager.LOCATION)`。

⚠️ `getRequiredPermissions(null)`（编辑器 / 模块管理器 /
`PermissionManager.getAllRegisteredPermissions()`）**必须返回并集**（`listOf(LOCATION)`）
—— 这是 `ActionModule.kt:115` 的 KDoc 明确要求的，返回空会让权限提示不准。

⚠️ **不要因为「缺了 SSID 也能触发」就一律不声明**：本仓库已记过同类教训
（`sim_data_switch` 初版误判零权限 ⇒ 新装设备**静默永不触发**）。
判据是「**缺了它这个配置还能不能工作**」，不是「用户是否被多要了一次授权」。

---

## 9. 电话域（运营商变化）—— 本期不做

「运营商 A → B」有三种互不相同的成因：

| 成因 | 判定键 | 信号 | 现状 |
|---|---|---|---|
| 换默认上网卡（卡1 ↔ 卡2） | subId / DDS | `ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED` | ✅ 已有 `sim_data_switch` |
| **同卡换运营商**（漫游、掉网重选、跨网共享） | PLMN（`ServiceState.getOperatorNumeric()`） | `TelephonyCallback.ServiceStateListener`（API 31+）/ `PhoneStateListener.onServiceStateChanged`（deprecated，minSdk 29 可用） | ❌ **全仓零 telephony 监听代码**（grep `TelephonyCallback` / `PhoneStateListener` / `ServiceState` 无命中） |
| 物理换 SIM 卡 | 订阅列表 | `ACTION_SIM_STATE_CHANGED` | ❌ 无（`sim_data_switch` 只认 DDS 变更广播） |

**本期处置**：不做。理由：① 它属**另一个事件源**（§5.1），应独立模块；
② 长尾需求，90% 的真实场景是「换网 / 断网就跑」。

**将来若要做的推荐形态**：给既有 `vflow.trigger.sim_data_switch` 加 `trigger_type`
（`dds_switch` / `carrier_change`），默认值保持现有语义 ⇒ 零破坏
（老工作流无该参数 → 归一化到默认值）、模块数不涨、且它已是 fork 文件无上游冲突成本。

---

## 10. Handler 与状态机

### 10.1 基类：`ListeningTriggerHandler`

- 单一 `NetworkCallback` + 全量条件下发 ⇒ **不需要** `BroadcastTriggerHandler` 那种
  「每触发器一个 receiver」的形态（那是为了让平台 `IntentFilter` 当唯一判定点；
  本模块的判定逻辑是自己写的纯函数，不存在语义复刻风险）。
- ⚠️ **不要**因为「将来可能要按触发器分别注册」就继承 `BaseTriggerHandler`：
  `ListeningTriggerHandler` 的引用计数（首个 `addTrigger` 启动、末个 `removeTrigger` 停止）
  正是本场景要的。

### 10.2 状态机（纯函数，可跑纯 JVM 单测）

`NetworkChangeStateResolver`：**无 Android 依赖**，输入是「网络快照」序列，输出是「事件」。

```
NetworkSnapshot(
    networks: Set<NetworkKey>,     // 见 §6 #7：必须是集合，不能是单值
    activeKey: NetworkKey?,        // 默认网络
    capabilities: CapabilitiesSnapshot,
)
```

理由与 `FoldStateResolver` 相同：本层全是「**改错了不报错、只静默变差**」的地方
（漏一次去抖 ⇒ 重复触发；基线没建 ⇒ 启动时误触发一次），必须由测试钉住。

### 10.3 去抖：基线 + 等值比较 + 合并窗口

| 机制 | 作用 | 依据 |
|---|---|---|
| **基线**：`startListening` 时先读一次当前状态 | 防止「刚注册就误触发一次」 | `DoNotDisturbTriggerHandler` 的既有范式 |
| **等值比较**：与上次快照相同则直接返回 | 压掉 `onCapabilitiesChanged` 的重复回调（§6 #6） | 同上 |
| **合并窗口**（建议 500 ms）：把窗口内的 `onLost(old)` + `onAvailable(new)` 合成 `switched` | 兑现 §5.2 的一等事件语义 | 本模块特有 |

⚠️ 窗口内的**首个**事件决定窗口起点，超时后必须**冲刷**（否则最后一个事件会永久挂住
—— 这是定时合并的经典静默失效）。

### 10.4 生命周期

- `stopListening` 里必须：`unregisterNetworkCallback` + **清空全部基线/窗口状态**
  （Handler 是 `TriggerService` 的持久成员，跨 add/remove 存活 ⇒ 不清会拿到陈旧状态）。
- ⚠️ `BaseTriggerHandler.stop()` 会 `triggerScope.cancel()` ⇒ 子类 `stop` 里不能再用它起协程；
  需异步清理时先 `triggerScope.launch { ... }` 再 `super.stop()`。
- 回调来自 ConnectivityThread 的 binder 线程 ⇒ 不在回调里跑重活，`triggerScope.launch` 再处理。

---

## 11. ⚠️ 静默失效点清单

本仓库的触发器子系统**最大的坑就是静默失效**（触发器梳理 §8.2 第 1 条）。
逐条列出并给出防线：

| # | 失效点 | 表现 | 防线 |
|---|---|---|---|
| 1 | 只注册 `ModuleRegistry`，漏 `TriggerHandlerRegistry` | 能选能配、后台永不触发 | 两处一起改；纯函数层 + 接线测试 |
| 2 | 只注册 `TriggerHandlerRegistry`，漏 `ModuleRegistry` | 用户根本选不到 | 同上 |
| 3 | `requiredPermissions` 声明少了 | `TriggerService` 在注册前**静默把工作流置为未启用** | §8.2 的权限表 + 与 `sim_data_switch` 的教训对齐 |
| 4 | `getRequiredPermissions(null)` 不返回并集 | 编辑器权限提示不准 | §8.2 的 ⚠️ |
| 5 | 枚举存了本地化文案 | 切语言后已保存的工作流失配 | 只存稳定常量；归一化一律走 `normalizeEnumValue()` |
| 6 | 一次切换触发两次 | 第二次被 `BLOCK_NEW` 吞掉，且无报错 | `switched` 一等事件（§5.2） |
| 7 | `TriggerService` 降级（用户关掉后台服务通知） | **所有**监听类触发器静默失效 | 已知既有缺陷，排障时先查（`fold-trigger-design.md` §8.1） |
| 8 | 未做去抖 | 一次连接触发多次 | §10.3 |
| 9 | `stopListening` 不清基线 | 下次启动拿到陈旧状态 | §10.4 |
| 10 | 合并窗口超时不冲刷 | 最后一个事件永久挂住 | §10.3 的 ⚠️ |
| 11 | 填了具体 SSID 但没有定位权限 | **永不匹配**，且无任何日志 | 注册时打 WARN（含「缺权限，SSID 条件无法生效」文案） |
| 12 | 读不到 SSID 时「猜一个」 | 触发错的工作流，比失灵更难发现 | **查不到就不触发**（与 `sim_data_switch` 决策 6 同一判据） |
| 13 | 改了 `output id` | 下游 `{{triggerId.outputId}}` 静默失效 | 输出按 id 键控，一经发布不改 |
| 14 | 改了 trigger 步骤的 step id | `triggerId` 含 stepId ⇒ 解绑失败、旧触发器残留 | 编辑器侧既有风险，不在本模块引入 |
| 15 | 多触发器共用一条 steps 链 | A 触发时 `{{触发器B.id.xxx}}` 为空（`seedTriggerOutputs` 只填命中那个） | 既有语义，文档如实说明 |

---

## 12. 文件级改动计划

**原则：能加新文件就不改上游文件。**

### 12.1 新增（全部 fork 独有）

| 文件 | 内容 |
|---|---|
| `triggers/NetworkChangeTriggerModule.kt` | 模块声明（id / metadata / inputs / dynamic inputs / outputs / summary / validate / execute） |
| `triggers/NetworkChangeStateResolver.kt` | 纯函数状态机（无 Android 依赖，可纯 JVM 单测） |
| `triggers/NetworkChangeTriggerData.kt` | 事件载荷（Parcelable） |
| `triggers/handlers/NetworkChangeTriggerHandler.kt` | `NetworkCallback` 事件源 + 条件下发 |
| `test/.../triggers/NetworkChangeStateResolverTest.kt` | 纯函数单测 |
| `test/.../triggers/NetworkChangeTriggerModuleTest.kt` | 模块声明锁（枚举稳定值 / 动态输入 / 输出 schema） |
| `docs/fork/network-change-trigger-design.md` | 本文件 |
| `res/drawable/rounded_*_24.xml` | 图标（⚠️ **不要与既有触发器图标撞车** —— 有声明体检测试锁住） |

### 12.2 修改（上游文件，需登记 FORK.md）

| 文件 | 改动 | 冲突归属 |
|---|---|---|
| `core/workflow/module/ModuleRegistry.kt` | **追加一行**注册（按分类位置追加，**不重排已有注册**） | 手动合并（1 行） |
| `core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt` | **追加一行**注册 | 手动合并（1 行） |
| `res/values{,-en,-ja}/strings_module.xml` | 模块名/描述/参数名/枚举文案/摘要/错误（**三语都要补**） | 手动合并 |
| `FORK.md` | 登记本模块与本文件 | 我方 |

---

## 13. 测试策略

### 13.1 纯函数单测（必须）

| 用例组 | 锁什么 |
|---|---|
| 状态机转换表 | `connected` / `disconnected` / `switched` / `capability_changed` 各自在什么快照序列下产生 |
| 合并窗口 | 窗口内的 lost+available 合成**一个** `switched`；窗口外是两个独立事件 |
| **窗口超时冲刷** | 最后一个事件不会永久挂住（反向断言） |
| 基线 | 首个快照只建基线、**不产生事件** |
| 等值去抖 | 重复的 `onCapabilitiesChanged` 不产生第二个事件 |
| 集合语义 | 双 Wi-Fi 场景（两个 Wi-Fi `Network` 同时存在）不产生假的 `switched` |
| 枚举归一化 | 历史/非法取值落到默认值 |
| SSID 匹配 | 大小写、空白、空串、`ANY` 哨兵 |
| 权限缺失降级 | 无定位权限时 SSID 条件不匹配（**不猜**） |

### 13.2 真机验证清单（**实现前先验 §6 的【推断】项**）

按仓库纪律：**平台行为断言先实跑再落笔**（`backup-webdav-design.md` §1.5 的教训）。

1. 换 SSID：是否产生新 `Network` handle（§6 #4）
2. 同 SSID 换 AP（漫游）：是否有回调（§6 #5）
3. 一次连接 `onCapabilitiesChanged` 的实际回调次数（§6 #6）
4. 双 Wi-Fi 加速：是否两个 Wi-Fi `Network` 并存（§6 #7）
5. Wi-Fi 已连时开关移动数据：是否产生默认网络事件（§6 #8）
6. 无定位权限时 `WifiInfo.getSSID()` 的实际返回值（§6 #9/#10）
7. 息屏 30 分钟 / 深度待机下的漏事件情况（§6 #15）
8. 飞行模式开关的广播可收性（§6 #12）
9. 一次 Wi-Fi A→B 切换的实际事件序列（**验证 §5.2 的双触发问题真实存在**）
10. `vflow.trigger.broadcast` + `CONNECTIVITY_ACTION` 的动态可收性（§2.1 的 ⚠️）

### 13.3 探针

- 复用 `scripts/probe/apk/` 的最小接收 APK 套路（**不要**用 shell 进程 `app_process`
  做 receiver 探针 —— 它什么广播都收不到，会得出完全相反的结论）。
- 状态读数走 `DebugLogger` 诊断探针（同 `probeDndState` / `probeSimState`）：
  **免改上游 `AndroidManifest.xml`、免新增 Activity** 就能把真机取值拿到手。

---

## 14. 已知限制与风险

1. **同 SSID 换 AP（漫游）需要 BSSID** ⇒ 需要定位权限；无权限时该场景**不触发**
   （如实告知，不猜）。
2. **进程保活**：`TriggerService` 降级（用户关掉后台服务通知）时本触发器与所有监听类触发器
   一起静默失效 —— 既有缺陷，非本模块引入。
3. **移动数据开关在 Wi-Fi 已连时可能不触发**（§6 #8）：若实测确认，需要额外的
   `Settings.Global.MOBILE_DATA` 观察者 —— **本期不做**，记入 §16。
4. **「一次切换」与「两次独立事件」的语义选择**：`switched` 会吞掉中间的瞬时状态
   （例如 A 断开后隔 30 秒才连上 B，那不是「切换」）⇒ 合并窗口取值需要真机标定。
5. **AI 侧无影响**：触发器一律不设 `aiMetadata`（触发器梳理 §8.2 第 7 条），
   故本模块不会出现在 AI 工具清单里。

---

## 15. 决策台账

| # | 决策 | 理由 |
|---|---|---|
| 1 | 通道选 app 层 | 信号 app 层完全拿得到；判据见 §4 |
| 2 | 事件源用 `registerDefaultNetworkCallback` | 零权限 + 覆盖 L1/L2/L4（§6 #1） |
| 3 | 不引入 `CONNECTIVITY_ACTION` | 已废弃、粒度粗、投递行为随版本收窄（§6 #11） |
| 4 | 一个模块（不拆 L1/L2/L4） | 同一事件源内的判定共享同一份状态（§5.1） |
| 5 | 电话域不做、且**不**并进本模块 | 跨事件源 = 真耦合（§5.1、§9） |
| 6 | `switched` 做成一等事件 | 避免双触发 + `BLOCK_NEW` 静默吞掉（§5.2） |
| 7 | 权限按 step 动态声明 | `PermissionManager.getMissingPermissions` 传真实 step（§8.1） |
| 8 | 身份键优先零权限（handle / IPv6 前缀 / interfaceName） | SSID 要定位权限，只在用户显式填 SSID 时才要（§3.4、§8.2） |
| 9 | IP **不**作为身份键 | 两个方向都会错（§3.3） |
| 10 | Handler 继承 `ListeningTriggerHandler` | 单一回调 + 全量条件下发（§10.1） |
| 11 | 状态机抽成纯函数 | 本层全是「改错了不报错、只静默变差」（§10.2） |
| 12 | 读不到身份键时**不触发** | 猜一个会触发错的工作流（§11 #12） |
| 13 | 不改 `WifiTriggerModule`（上游文件） | 控制 diff 面积（§5.3、§2.3） |
| 14 | 状态用**集合**而非单值 | 双 Wi-Fi 加速（§6 #7） |
| 15 | 去抖 = 基线 + 等值 + 合并窗口 | 三种失效各有对策（§10.3） |

---

## 16. 未决项

| # | 事项 | 处置 |
|---|---|---|
| 1 | `WifiTriggerModule` 的 `bssid` 输出恒为空串（既有缺陷） | 本期不改上游文件；登记在此，另行评估 |
| 2 | 移动数据开关（Wi-Fi 已连时）是否纳入 | 等 §6 #8 实测结果；若要，加 `Settings.Global.MOBILE_DATA` 观察者 |
| 3 | 飞行模式是否单列一个事件类型 | 等 §6 #12 实测；`NetworkCallback` 通常已能连带感知 |
| 4 | 合并窗口的具体取值 | 真机标定（§14.4） |
| 5 | 是否需要「网络质量」维度（带宽/信号） | 本期明确不做 |
| 6 | 是否把事件源挪到 Core | 仅在 §6 #15 实测确认漏事件时考虑（§4） |

---

## 17. 与上游的关系

- 本文件、四个新增 Kotlin 文件、单测、图标：**fork 独有**，上游无对应物 ⇒ 冲突归我方。
- 唯一的上游改动是 `ModuleRegistry.kt` / `TriggerHandlerRegistry.kt` **各追加一行**
  + 三语文案 ⇒ 冲突时按「追加」处理（**不重排已有注册**）。
