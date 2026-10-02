# Xposed JavaScript 模块 · 真机验证用例

本目录是 `scripts/xposed-js-verify.sh` 的配套材料，对应任务 **T4**（`.mindfs/tasks/plan-8.md`）。

## 文件

| 文件 | 用途 |
|---|---|
| `cases/*.js` | 各用例的脚本正文 |
| `t4_driver.py` | 建工作流 → 触发 → 采集两侧日志与产物 |
| `consistency_check.py` | 与 `vflow.system.js` 的行为一致性核对 |
| `core_exec.py` | 经 vFlow Core 的 root 通道执行 shell（脚本里唯一需要 root 的地方） |
| `out/` | 采集与判定结果（`out/cases/` 是逐用例证据，`out/verdicts.txt` 是判定汇总） |

## ⚠️⚠️ 用例脚本必须用【最后一行表达式】，不能写顶层 `return`

这是本次验证**最花时间的发现**，也是最容易让人误判「功能坏了」的地方。

### 事实

Rhino **1.9.0** 的语法解析器**不接受顶层 `return`**：

```js
return {a: 1};   // ✗ EvaluatorException: 返回的值无效（msg.bad.return）
({a: 1})         // ✓ 取最后一行表达式的值
```

- 它是**解析期**错误（`Parser` 抛 `msg.bad.return`），**不是**运行期错误。
  ⇒ 脚本**一行都不会执行**。用 `console.log` 在 `return` 之前打点验证过：
  **连那个 log 都不出现**（对比：表达式形式下 log 正常出现）。
- 报错位置会指向 `return` 所在行，信息是 `脚本错误（第 N 行第 M 列）：返回的值无效`。

### 这是 Rhino 的**通用**限制，不是本模块特有

本机用 `rhino-1.9.0.jar` 直接跑（见下方复现脚本）实测：

| 脚本 | 结果 |
|---|---|
| `return {a: 1+1};` | ✗ `EvaluatorException: 返回的值无效` |
| `({a: 1+1})` | ✓ 返回对象 |
| `var r = {a: 1+1}; r;` | ✓ 返回对象 |
| `(function(){ return {a:1+1}; })()` | ✓ 返回对象 |

换 `setLanguageVersion`（`VERSION_1_8` / `VERSION_ES6` / `VERSION_DEFAULT`）**都不解决**。

### 既有 App 侧模块早就用的是表达式形式

`vflow.system.js`（`JsModule.kt:50`）的**默认脚本**是：

```js
vflow.device.toast({ message: 'Hello from JavaScript!' })

// Return a dictionary as outputs.
var r = {};
r.result = 1 + 1;
r;              // ← 靠最后一行表达式返回，不是 return
```

注释虽然写着 "Return a dictionary"，但正文用的是 **`r;`**。

### ⇒ 与本次验证相关的一处**实质缺陷**（属 T3，本任务不改代码）

`vflow.xposed.js`（`XposedJsModule.kt:122`）的**默认示例脚本**写的是：

```js
// 用 return 返回一个字典，它会成为下游可见的 outputs。
return { sum: 1 + 1 };
```

真机实测**必然报** `脚本错误（第 N 行第 M 列）：返回的值无效`。
用户新建模块 → 直接点运行 → 立即失败，且报错信息完全指不到真正原因。

T2 的单测用的是 `({result: 2})`（表达式形式），所以**单测全绿而真机必挂** ——
这是典型的「测试用的方言 ≠ 交付给用户的方言」。**已在交付报告里作为缺陷上报**。

### 复现（零设备）

```bash
JAR=<gradle cache>/org.mozilla/rhino/1.9.0/rhino-1.9.0.jar
cat > T.java <<'EOF'
import org.mozilla.javascript.*;
public class T {
  public static void main(String[] a) {
    Context cx = Context.enter();
    try {
      cx.setOptimizationLevel(-1);
      Object r = cx.evaluateString(new ImporterTopLevel(cx),
          "return {a: 1+1};", "s", 1, null);
      System.out.println("OK " + Context.toString(r));
    } catch (Throwable t) {
      System.out.println(t.getClass().getSimpleName() + ": " + t.getMessage());
    } finally { Context.exit(); }
  }
}
EOF
javac -cp "$JAR" T.java && java -cp "$JAR;." T
# → EvaluatorException: 返回的值无效
```

## ⚠️ 并发用例必须用**不同的工作流**

`WorkflowExecutor` 有**重入保护**（`reentryBehavior = block_new`）：同一个工作流
在运行时再次触发会被丢弃，日志是「工作流 'X' 已在运行，忽略新的执行请求」。

⇒ 想把 hook 侧容量 2 的线程池压满，**每次并发必须新建一个工作流**
（`t4_driver.py` 的 `burst` 已按此实现）。否则请求到不了 capability 层，
池永远不会满，第 6 项会被误判成「不可中断不成立」。

## ⚠️ 断言嵌套结构不能靠读产物整串

模块 `outputs` 是 `VDictionary`，而它的 `asString()` 把**每个值都包了引号**：

```json
{"a": "{"b": "1, 2"}"}
```

⇒ 拿它去 grep 嵌套的 `[1, 2]` **永远 grep 不到**（中间隔着一层引号），
「正确实现」会被判成失败。

正确做法是**用魔法变量探针落盘**：把 `{{s1.outputs.a.b}}` 写进一个文件步骤，
取到 `1, 2` 就证明 `a` 是**可按键导航的字典**（不是 `[object Object]` 字符串）。
`t4_driver.py` 的 `probes` 就是干这个的。

## ⚠️ adb 可能对同一台设备开出多条 transport

USB 与无线调试各一条时，**不带 `-s`** 的 `adb` 命令会以
`error: more than one device/emulator` 失败 —— 而失败是**静默**的：
`logcat -d` 返回空、采集文件全空、判定全判「未验证」，
看起来像「设备没问题但功能不对」。脚本已固定第一条可用 serial（`T4_ADB_SERIAL`）。

## ⚠️⚠️ `set -o pipefail` + `echo "$bigvar" | grep -q` 会【假阴性】

**这是本任务真的踩过的一个坑**，且它的症状极具误导性。

```bash
set -uo pipefail
log="$(adb logcat -d)"          # 数 MiB
if echo "$log" | grep -q 'PATTERN'; then echo hit; else echo miss; fi
```

即使 `PATTERN` **确实存在**，也会走 `else` 分支。

**机理**：`grep -q` 一命中就立即退出 ⇒ 上游 `echo` 写不完就被 SIGPIPE 掉
（退出码 141）⇒ `pipefail` 让**整条管道**返回非零 ⇒ `if` 判否。
输入越大越必然（实测 3 MiB 稳定复现；小输入因写完了不触发，**所以测试时看不出来**）。

**修法**：先落盘，再让 `grep` 直接读文件（管道里就没有 `echo` 了）：

```bash
printf '%s\n' "$log" > "$file"
if grep -q 'PATTERN' "$file"; then ...
```

**症状长什么样**（真实经历）：`scope` 子命令报
「❌ 一条 VFlowHook 日志都没有 ⇒ 作用域可能未勾选，需人工重启设备」，
而实际日志**就在缓冲里**（手工 `grep -c` 数得到 17 行）。
若照它的提示去做，会让人去 LSPosed 里白折腾一圈。

**排查手法**（本次靠它定位）：把「通过」分支临时改成打印**实际抓到的行数**，
看到「行数很多、判定却是否」这个矛盾，才想到是管道退出码而不是匹配失败。

## ⚠️ 「本任务从未触碰 LSPosed」这条要能自证

`break` / `restore` 子命令改的是 **App 侧 `HookChannelService` 组件**的启用状态，
不是「在 LSPosed 里禁用 vFlow 模块」。两者都能让 hook 层的 `bindService` 失败、
都能构造 `channel_down`，但**前者可逆、可脚本化，后者是人工 GUI 操作**。

自证方式（零残留）：

```bash
# 脚本里【没有】任何 lsposed 相关命令（只想看有没有，自己确认）
grep -nE 'lsposed' scripts/xposed-js-verify.sh      # 命中全是注释/文案

# 组件状态应恢复为 enabled
python scripts/xposed-js-verify/core_exec.py \
  "dumpsys package com.chaomixian.vflow | grep -A3 enabledComponents"
```

⚠️ **Shell（uid 2000）改不动组件状态**（`SecurityException: Shell cannot change
component state`），必须借 Core 的 root 通道 —— 这是脚本里唯一需要 Core 的地方。
