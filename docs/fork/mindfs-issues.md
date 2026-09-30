# mindfs 问题清单

> **用途**：给 mindfs 维护者排查。本文件不是 vFlow 的代码文档，可随时删除。
> **记录时间**：2026-09-29（2026-09-30 大幅补充：新增 ⑧–⑫，并把原 ⓪①④⑤ 归并）
> **记录人**：vFlow 项目的一次实际编排（父会话 = Claude Code 会话）

## 环境

| 项 | 值 |
|---|---|
| mindfs 版本 | `dev`（`mindfs --version`） |
| 服务 | Windows 11；⚠️ **端口会随重启变化** —— 经历 `http://127.0.0.1:7331` → `https://127.0.0.1:7766` |
| 受管根目录 | `vflow`（`D:\develop\myProjects\vflow`） |
| 任务组 | `group_edd3000b872666ee`（最终仍卡死，已弃用） |
| 模板 | 蓝图 `tmpl_573df242e81d47ff`（需求 → 方案 → 审核 → 实现 → 验收） |
| 父会话 | `1790658258-79147cc05ba3` |
| 数据文件 | `.mindfs/tasks/task-kanban.db`、`.mindfs/sessions/session-list.db` |

> 📌 **本轮的最终结论：整条编排链无法跑通。**
> 6 个任务里只有 task1 真正交付（靠父会话手动合并），task2 卡在实现阶段起不来，
> task3–6 从未启动。**根因是 ⑧（交付事件的 `stage_run_id` 为空 ⇒ 系统看不见交付）
> 与 ⑨（僵尸 run 阻塞依赖链）的叠加**，而这两条都由同一条「状态不闭环」引出。
> 用户已放弃该任务组，改用新会话重新拆分。

**模板「蓝图」的阶段配置**（供下文对照）：

| position | name | role | agent | model | auto_advance | session_reuse_policy |
|---|---|---|---|---|---|---|
| 0 | 需求 | user | — | — | false | task_main |
| 1 | 方案 | agent | claude | **fable** | true | task_main |
| 2 | 审核 | user | — | — | false | task_main |
| 3 | 实现 | agent | claude | **opus** | true | task_main |
| 4 | 验收 | agent | claude | fable | false | always_new |

---

## ⓪ ⭐⭐ 最高优先级 **根因**：agent 运行时会话被跨任务共用，导致任务间串台

> **2026-09-29 15:20 补充。这一条是 ①④⑤ 的**共同根因** ——
> 下面是证据链，建议先读这条再读那三条。**

### 表现

**vflow 的 task1 事件流里，出现了一份完全不相干的另一个项目的任务报告**，且记录为 task1 自己的交付。

`task_events` 表（`task_id = task_a72afed68a5e42ad`，即 vflow 的 task1）：

```
2026-09-29T07:19:33Z | from-task | ## 【重发/修正】阶段「方案」交付：docs/ 文档分类说明的实现方案
                               | ### 1. 目标
                               | 对 docs/ 下 Markdown 做主题归类，产出分类说明…
                               | （涉及 blueprint-template-requirements.md / windows-redeploy.md / 竞品报告死链）

2026-09-29T07:26:39Z | from-task | ## 阶段状态：已对齐补充要点，任务完成（提交 b07d05f6）
                               | ### 1) invoke = oneway void invoke(String requestJson) —— 已是该形状
                               | （讲 IHookCallback.aidl:121 / CapabilityRisk / 错误码，是本任务真正的内容）
```

**第一条不是 vflow 的任务。** 经查，它是另一个受管目录 `mindfs`（`D:\claudebot\mindfs`）下任务组的 task6：

```
mindfs 项目 task6 = task_c5c4dbd1f090f73b（状态 fail）
  "分析 docs/ 目录下各 markdown 文档的主题，输出一份分类说明。不要修改任何文件。"
  所属任务组 group_ad875c3fea06c1bc「E2E: 蓝图模板实测」（状态 blocked）
```

⇒ **两个不同项目、不同任务组的任务，用了同一条 agent 运行时会话。**

### 证据链

**1. agent 运行时会话是共享的。** `session_agent_bindings`：

```sql
select session_key,agent,agent_session_id from session_agent_bindings
 where session_key='1790661809-865d8c9e6d8b';
-- ('1790661809-865d8c9e6d8b', 'claude', 'cbbb860a-1736-4023-b83c-6fe923bd55a9')
```

**2. 而这条 agent 会话产出了两个不同任务的内容。** vflow 的会话文件里，含「文档分类」字样命中的是**另一个文件**：

```
命中: 1788367491-69058815ffbb.jsonl   ← 不是 task1 的 1790661809-865d8c9e6d8b
```

**3. 时间线（关键）**：

```
14:03:29  方案阶段启动，开 pool session cbbb860a（日志 agent_ctx_seq=2）
14:18:54  方案产出、会话冻结
14:42:37  实现阶段启动，复用同一条 cbbb860a（日志 agent_ctx_seq 递增）
15:19:33  ⚠️ 这条会话产出了「docs/ 文档分类」报告 → 记进 vflow 的 task1
15:26:39  ⚠️ 又产出了正确的 Xposed 报告 → 同样记进 vflow 的 task1
```

### 这一条解释了另外三条的全部症状

| 症状 | 出现位置 | 同一根因的表现 |
|---|---|---|
| 阶段会话不可见 | 本文件 ⑤ | 会话被跨任务共用，不落在本项目 key 下 |
| `{previous_input}` 空插值 | 本文件 ④ | 补发消息进了共享会话，被另一个任务的 context 盖住 |
| `agent=codex` 错配校验 | 本文件 ① | mindfs 项目自己用 codex（该库 4 条 codex 绑定全在 mindfs 项目） |
| 任务报告串台 | 本节 | 同一会话被两个任务交替使用 |

⚠️ **因此 ①④⑤ 可能不是三个独立缺陷，而是这一个根因的三个表现。**
建议优先定位「agent pool session 的分配与复用键」，再回头看那三条是否随之消失。

### 严重度

**数据正确性风险**，不只是可观察性：

- 一个任务的交付内容可能**被另一个任务的输出污染**；
- 若两个任务的产出都合法，**可能互相覆盖**；
- 若实现动手类任务这样串台，**可能在错误的仓库里改代码**。
  （本次侥幸未发生：vflow 的改动全部落在正确的 worktree `feature/xposed-capability-contract`，9 个文件 849 行、两个提交 `bc269e80`/`b07d05f6` 均属实。）

### 建议排查方向

1. pool session 的键是怎么算的？为何两个不同 root（`vflow` / `mindfs`）的任务会拿到同一条 `agent_session_id`？
2. `session_reuse_policy: task_main` 复用时，是否校验了「被复用的会话确实属于本任务」？
3. 任务消息（`from-task`）的归属是**按会话**还是**按任务**判定的？（本次按会话记到了错误的 task_id 上）
4. 是否应在复用前按 `task_id` / `root_id` 做隔离，避免跨 root 复用？

---

## ① ⭐ 最高优先级：`/resume` 与阶段推进都触发「agent 错配」校验，冻结整个任务组

> ⚠️ **可能与 ⓪ 同源**（mindfs 项目用 codex，校验疑似串到了那条会话的 agent）。
> 若 ⓪ 修复后本条的 `agent=codex` 来源仍未解释，再单独查。

### 表现

任务组 `status=blocked`，`block_reason = model "opus" is not supported by agent "codex"`。

此时**所有**任务的 `run-now` 都返回：

```
POST /api/tasks/{id}/run-now
→ {"error":"task cannot start: publication, group state or dependencies are not ready"}
```

用户视角：**点了按钮，什么都没发生**（无错误提示、无进度）。

### 日志证据

同一条错误出现 **3 次**，每次都由一个**明确动作**触发：

```
2026/09/29 14:18:54 [session/model] validate.error root=vflow session=1790658258-79147cc05ba3
                    agent=codex model="opus" err=model "opus" is not supported by agent "codex"
    ↑ 紧随其后：方案阶段产出完成、要推进到下一阶段

2026/09/29 14:42:37 [session/model] validate.error ... （同一条）
    ↑ 由 POST /api/task-groups/group_edd3000b872666ee/resume 触发

2026/09/29 15:16:26 [session/model] validate.error ... （同一条）
    ↑ 同样由 POST /api/task-groups/.../resume 触发
```

### 判据

**1. 校验用错了 agent。** 被校验的 session 是**父会话** `1790658258-79147cc05ba3`（任务组的 `session_key`）。查询 `session_agent_bindings`：

```sql
select * from session_agent_bindings where session_key='1790658258-79147cc05ba3';
-- ('1790658258-79147cc05ba3', 'claude', 'd8ed92a4-5fdd-4df5-afbd-cc4167de77cd', 10, ...)
```

**绑定的是 `claude`，不是 `codex`。** 全库 108 条绑定里只有 4 条 `codex`，且都不是这个会话：

```sql
select agent,count(*) from session_agent_bindings group by agent;
-- ('claude', 104), ('codex', 4)
```

⇒ **`agent=codex` 的来源不明**，疑似校验路径取了错误的字段（或某个默认值）。

**2. 同一毫秒后，真实执行者用 claude + opus 完全正常。**

```
[model] switch.detected session=1790661809-865d8c9e6d8b agent=claude from="fable" to="opus"
[model] switch.done     session=1790661809-865d8c9e6d8b agent=claude model="opus" pool_session=claude-1790661809-865d8c9e6d8b
```

⇒ 「claude + opus」是可行的，那条「codex 不支持 opus」是**错配的校验**，不是配置问题。

**3. 被校验的 `model: opus` 来自模板阶段 3「实现」**，模板定义本身正确（`agent: claude` + `model: opus`）。

### 严重度与影响

- 冻结粒度是**整个任务组**，不是单个任务 —— 一个阶段推进失败会连累所有任务。
- **每个 agent 阶段启动都可能重演**（本批 6 任务 × 蓝图 3 个 agent 阶段 = 18 次机会）。
- **`block_reason` 不会被自动清除**：resume 后 `status` 会回到 `active`，但 `block_reason` 字段仍保留原值；且在 `block_reason` 非空期间再次 resume，会**再触发同一条 validate.error**。我第一次 `-resume` 看似成功（`status=active`），第二次就失败了（仍是 `blocked`）—— 疑似状态无法自愈。
- **本清单里唯一会阻断交付的问题。**

### 建议排查方向

1. 该校验为什么用 `codex` 而不是 `session_agent_bindings` 里的 `claude`？校验的输入对象是「父会话」还是「任务/阶段的实际执行者」？
   - 逻辑上应该是后者 —— 因为 `model` 来自**阶段快照**，就应该用**该阶段的 agent** 去校验。
2. `resume` 为什么会重新跑一次模型校验？恢复操作不应重新校验（它不改变配置）。
3. `block_reason` 在 `status` 恢复后为什么不清空？这会让「已恢复」和「仍冻结」在数据上无法区分。

### 复现路径

在 Windows 上、父会话 agent 为 `claude`、使用蓝图模板建任务组并发布 → 在前端点「开始」→ 等方案阶段产出 → 前端点「审核通过」（或调用 `POST /api/task-groups/{id}/resume`）→ 观察 `task_groups.status` 变为 `blocked`，日志出现上述 validate.error。

---

## ② 前端 `approve-plan` 未带 `root_id`，且错误归因对不上

### 表现

点击「开始」后前端弹两个提示：`task is not published` 和 `file.write_failed`。用户会以为磁盘/权限有问题。

### 证据

```
POST /api/task-groups/group_edd3000b872666ee/approve-plan                  → 409
POST /api/task-groups/group_edd3000b872666ee/approve-plan?root=vflow       → 200 ✅
```

直接 curl 不带 `?root=` 时，服务端返回：

```json
{"error":"root_id required"}
```

### 判据

1. 前端发的 `approve-plan` **缺 `root_id`**。
2. 服务端返回的是 **409**，而前端把它映射成了通用错误 `file.write_failed` —— 该错误码的 messageKey 是 `error.file.writeFailed`（在前端 bundle `index-*.js` 中可查到）。**错误归因完全对不上**，把「参数缺失」显示成了「文件写入失败」。

### 建议

- 前端 `approve-plan` 补 `root_id`（或服务端从 body 里读）；
- `file.write_failed` 不应作为「发布确认失败」的兜底错误码，应使用专门的错误码。

---

## ③ 首次确认只能在前端点，CLI 无法完成也无法提示

### 表现

`mindfs <root> -task-group <gid> -publish` 返回 `published: false`、`block_reason: "publish_approval"`，之后 CLI **没有任何命令**能完成确认，也没有命令能告知使用者「需要在浏览器里点一下」。

编排指南写的是「首次发布后，等待用户在前端确认」—— 流程正确，但使用者（尤其以 CLI 为主的场景）会卡在这里反复重发 `-publish`（我重发了 2 次，状态不变）。

### 建议

- `-publish` 的返回里明确写「等待前端确认」（而不只是给一个 `block_reason` 字符串）；
- 或提供 CLI 确认方式（若人工卡点是刻意的，至少让提示更直白）。

---

## ④ 阶段 `status=success` 但 `result` 为空，`{previous_input}` 静默插成空串

### 证据

`stage_runs` 表（任务 `task_a72afed68a5e42ad`）：

| stage_index | stage_name | status | input 长度 | rendered_prompt 长度 | **result 长度** |
|---|---|---|---|---|---|
| 0 | 需求 | waiting_user | 3083 | 0 | 0 |
| 1 | 方案 | **success** | 0 | 4146 | **0** |
| 2 | 审核 | waiting_user | 0 | 0 | 0 |
| 3 | 实现 | running | 0 | 7241 | 0 |

方案阶段跑 **14 分 25 秒**，实际产出了 **17256 字符**的方案（内容完整，含涉及文件、接口契约、实现步骤、验收标准，可在 `.mindfs/sessions/1790661809-865d8c9e6d8b.jsonl` 的 `seq=2` 条目中读到），但 `result` 字段为空。

于是实现阶段的 prompt 被渲染成：

```
你是实现者。按已批准的方案实现功能。

## 方案
                     ← 空
## 原始需求
目标：落地 Xposed 通道 ③...
```

**`{previous_input}` 空插值没有任何告警，也没有阻断** —— 实现者在**没有方案**的状态下继续跑了下去（已完成 849 行改动）。

### 责任划分（诚实说明）

**任务书措辞有一部分责任**：编排者在任务书里写了「若需求有歧义或缺少关键信息，直接在方案末尾列出待确认问题，不要猜测」。方案照做，提了 3 个待确认问题后停下等答复，**没有以 `completed: true` 交付**。

### 仍然是 mindfs 的问题，两个点

1. **状态不一致**：阶段标 `success` 却 `result` 为空 —— 两处自相矛盾。
2. **静默降级**：下游 prompt 的关键插值为空，不告警、不阻断，把「无方案的实现」跑了下去。这类失败形态最难发现（本仓库 vFlow 自己反复记录过同类「静默失效」教训）。

### 建议

- 阶段结束标 `success` 时，若 `result` 为空则至少打 warn；
- `{previous_input}` 插值为空时阻断或显式提示（模板里多个阶段都依赖它）。

---

## ⑤ 复用 pool session 导致「阶段会话不可见」

### 表现

前端看不到实现阶段的会话。

### 证据

`stage_runs` 里两个 agent 阶段的 `session_key` **完全相同**：

| stage_index | stage_name | session_key |
|---|---|---|
| 1 | 方案 | `1790661809-865d8c9e6d8b` |
| 3 | 实现 | `1790661809-865d8c9e6d8b` | 

任务的 `main_session_key` 也是同一个 key。

而 `.mindfs/sessions/1790661809-865d8c9e6d8b.jsonl` **从 14:18:54 起再没被写过**（文件 mtime 停在方案阶段结束那一刻），里面只有 2 条记录（`seq=1` 方案 prompt、`seq=2` 方案产出）。

与此同时实现阶段**确实在活跃工作**：

```
15:14:48 [agent/claude] session=claude-1790661809-865d8c9e6d8b ... cache_read_input_tokens=269696
```

且 worktree 里的文件持续在被修改（14:58、14:59 仍在新建 `CapabilityPresence.kt` / `CapabilityRegistry.kt`）。

### 判据

蓝图模板各阶段 `session_reuse_policy: task_main` ⇒ 实现阶段复用了方案阶段的 pool session。后果：

1. 实现阶段的产出**不落该 key 的会话文件**；
2. **UI 不把它显示为「实现阶段的会话」**（疑似 UI 按「本阶段新建的会话」去列，而这里没有新建）。

⇒ **人无法看到 agent 在干什么**，只能靠 `git status` 反推。

### 建议

- 明确「复用会话」在 UI 上的归属（至少在阶段详情里列出「本阶段复用/运行于哪个会话」）；
- 或让 `task_main` 策略下每个阶段有可区分的展示项。

---

## ⑥ 无关但持续刷屏：`root=System32` 只读库

### 表现

日志每 5 秒一条，从服务启动起一直刷（`C:\Windows\System32` 是 2026-07-03 注册的受管根目录，很可能是某次从该目录启动 mindfs 时把相对路径解析到了 cwd）：

```
[kanban] schedule.error root=System32 err=attempt to write a readonly database (8)
```

### 已处理

用 `mindfs -remove "C:\Windows\System32"` 摘除注册（**只摘注册、未动该目录本身**，`registry.json` 已备份到 `registry.json.bak-before-system32-remove-*`），刷屏已停。

### 仍建议查

一个写不进去的 root 让调度器**每 5 秒无限重试并打错误**，**没有退避、没有禁用、没有上限**。后果是日志被淹没 —— 排查问题①时我几乎错过了关键的那几行 validate.error。

---

## ⑦ 任务级无回退手段（不一定是 bug，供确认）

### 表现

一个被误推进的任务**无法退回上一阶段**：

```
POST /api/tasks/{id}/prev  → {"error":"unsupported task operation; use to-task, from-task or cancel"}
POST /api/tasks/{id}/jump  → 同上
POST /api/tasks/{id}/next  → {"error":"agent stage has not delivered"}
POST /api/tasks/{id}/pause → {"error":"individual orchestrated tasks do not support pause"}
```

`-prev` / `-next` 只对 `-task-group` 有效，而任务组级同样不支持：

```
POST /api/task-groups/{id}/prev → unsupported group action "prev"
```

### 场景

在 UI 上误点导致 task2（依赖 task1）被推进到「方案」阶段，此时 task1 尚未交付。想让 task2 退回待命，发现**没有任何命令可用**，只能 `cancel` 重建。

（注：调度器实际拦住了它 —— `run-now` 报依赖不就绪，所以 task2 并未真的启动。但**状态被改坏且无法修复**这件事本身是问题。）

### 建议

- 提供任务级回退到上一阶段的能力；或
- 在文档里明确「误推进只能 cancel 重建」。

---

## ⑧ ⭐⭐⭐ **最关键**：交付事件的 `stage_run_id` 为空 ⇒ 系统「看不见」交付 ⇒ 阶段永不完成

> **2026-09-30 补。这是整条链跑不通的**直接原因** ——
> ⑨（僵尸 run）、⑩（按钮报错）、④（`result` 空）都是它的表现。**

### 表现

**父会话/前端点「进入下一步」被永久拒绝**，两种措辞（取决于是否最后一个 agent 阶段）：

```
task1（验收 = final agent stage）→ {"error":"final agent stage requires a delivery message with completed: true"}
task2（实现 = 非 final）        → {"error":"agent stage has not delivered"}
```

而**交付消息是存在的** —— agent 明明调了 `-from-task` 且带了 `completed: true`。

### 判据：`stage_run_id` 是关联键，空值即不可见

服务端二进制里那条 SQL 是铁证：

```sql
UPDATE task_events SET stage_run_id=? WHERE id=? AND handled_at=''
```

按 `stage_run_id` 是否为空分组统计（**全库数据，规律 100% 成立**）：

| 事件类型 | `stage_run_id` | 处理状态 | 条数 |
|---|---|---|---|
| `from-task` | **有值** | ✅ 已处理 | **1** |
| `from-task` | **空** | ❌ **从未处理** | **6** |

⇒ **`stage_run_id` 非空的 `from-task` 才会被处理；空的永远不处理。**

### 实例

vflow task1 的全部 5 条 `from-task`：

| 时间 | `stage_run_id` | handled | completed | 内容 |
|---|---|---|---|---|
| 07:19 | **空** | ❌ | true | （串台报告） |
| 07:26 | **空** | ❌ | true | 实现阶段交付 |
| 12:18 | `group_turn_ffc2a9bcc72aee3a` | ✅ | — | 验收报告 |
| 13:26 | **空** | ❌ | true | 验收结论（result 2692 字符） |
| 01:40 | **空** | ❌ | true | 父会话补交 |

**只有唯一那条 `stage_run_id` 非空的处理了。** 4 条 `completed: true` 的交付全部被系统无视 ⇒ 系统认为「从未交付」⇒ 阶段永远 `running` ⇒ `/next` 永久拒绝。

### ⚠️ 已试过的补救（都无效，供维护者参考）

1. **父会话用 `-from-task` 补交**（带 `completed: true`）：事件落库，但**同样是 `stage_run_id=''`** ⇒ 依然不被处理。**说明这不是 agent 侧的问题，是写入路径本身不填该字段。**
2. **手动回填 `stage_run_id`**（把那条验收交付挂到它所属的 run `run_809fbb4b40c8673c`）：重启服务后 `/next` **仍报同样的错** ⇒ **追加证据表明 `stage_run_id` 只是必要条件，不是充分条件**，还有别的判据（未定位到）。
3. `/complete` API：被设计拒绝 —— `{"error":"orchestrated tasks deliver results through -from-task with completed: true"}`。

### 建议排查方向

1. **`-from-task` 写入事件时为什么不填 `stage_run_id`？** 它应该取「当前阶段的活跃 run」。取不到的场景是：run 已被取代 / 服务重启过 / 会话上下文丢失 —— 而**这些恰是常态**（见 ⑨）。
2. 处理交付时除了 `stage_run_id`，还要求什么？为什么回填后 `/next` 仍拒绝？
3. **为什么 `stage_run_id` 为空的事件不被兜底处理？** 至少应该按 `task_id + 时间窗` 回退匹配，或打一条 warn —— 现在是**完全静默**的。

---

## ⑨ ⭐⭐ 旧 run 不被终止 ⇒ 僵尸阶段永久 `running` ⇒ **阻塞整条依赖链**

### 表现

阶段被重复触发时，**旧 run 不被标终止**，永远停在 `running`：

```
task1 验收：run_2fceb14b success / run_809fbb4b success / run_230d874a running ← 僵尸（20 小时）
task2 方案：run_4bbd6768 success / run_c02180d0 running ← 僵尸 / run_de3d5bd2 success
```

`task.status` 取「当前阶段的 status」，**命中僵尸 run 就永远 `running`** ⇒ 任务永不完成。

### 级联后果（本轮最严重的实际损失）

```
task1 验收僵尸 running ⇒ task1 永不完成
   ⇒ task2 依赖 task1 ⇒ run-now 报 "dependencies are not ready"
   ⇒ task2 实现阶段 admitted=False、永不启动
   ⇒ task3–6 全部起不来
```

**整条 6 任务链死在第 1 个任务的一个僵尸 run 上。**

### 触发场景

每个 agent 阶段**只要被重新触发一次**（父会话发 `-to-task`、或重新交付）就会新建一个 run，而旧的**不会**被终止。本轮里每次介入都制造了一个僵尸。

### 建议

- 新建 run 时把**同一阶段的前一个 `running` run 标为 `superseded`/`cancelled`；
- 或按**超时**（如 1 小时无活动）自动终止 — run 有 `updated_at`，判据现成；
- `task.status` 不应盲取「当前阶段 status」，应忽略非活跃 run。

---

## ⑩ 前端 `file.write_failed` 掩盖真实错误（第二次出现）

⑧/⑨ 的报错在前端都显示成 `file.write_failed`（messageKey `error.file.writeFailed`）。

服务端明明返回的是**语义明确**的 `{"error":"final agent stage requires a delivery message with completed: true"}`，前端却把它归成「文件写入失败」——**指向完全错误的方向**（用户会去查磁盘/权限）。

（第一次出现是 ② 的 `approve-plan` 缺 `root_id`，同一处缺陷。）

**建议**：前端不要用 `file.write_failed` 兜底非文件类错误，应透出服务端的 `error` 文本。

---

## ⑪ Windows 上 `curl -d '中文'` 静默损坏数据（★ 静默、已实测定位）

### 表现

用 `curl -d '...中文...'` 发请求，**服务端返回 200、事件也正常落库**，但**内容已被损坏成 `U+FFFD`** —— 表面上一切正常。

### 判据（实测字节）

| 传法 | 发出字节 | 结果 |
|---|---|---|
| `curl -d 'x=中文测试'`（中文**在命令行参数里**） | `d6d0 cec4`（**GBK**） | ❌ 坏 |
| heredoc 写文件 + `--data-binary @file` | `e4b8ad e69687`（UTF-8） | ✅ 好 |

`中文` 的 UTF-8 应为 `e4b8ad e69687`，GBK 为 `d6d0 cec4` —— 前者发出的正是 GBK。

### 机制

**Git Bash / MSYS2 在把命令行参数交给原生 Windows 程序时做字符集转换**（locale `C.UTF-8` → 系统 ANSI 代码页 936）。heredoc / 重定向写文件**不经过该转换**（字节直通）。

⚠️ **注意**：经 Python `subprocess` 调用时**不会复现**（走 `CreateProcessW` 传 UTF-16，路径不同）—— 所以「用 Python 测没事」不能证明 bash 直写没事。

### 规避（对所有在此环境下操作 mindfs 的人）

```bash
# 永远不要把多字节字符直接写在命令行参数里
cat > body.json <<'EOF'
{"input":"中文内容"}
EOF
curl --data-binary @body.json -H 'Content-Type: application/json; charset=utf-8' ...
```

### 给维护者的建议

HTTP body 的编码**应由 `Content-Type: charset` 决定，不应跟随系统代码页**。请确认服务端在 `charset` 缺失时是否有「按系统代码页解码」的分支 —— **该分支在 Windows 上会静默损坏所有中文 payload**。

---

## ⑫ 阶段意见入口（审核阶段）**服务端有、前端没有** + 首次确认卡点等同

### 表现

用户「找不到在哪提审核意见」——**前端确实没做**。

前端 bundle 里 `taskGroup.*` 的全部 UI 文案：

```
context / dag / messages / noMessages / parentConversation / pause / resume / start / title
```

**没有「审核」「提交意见」「确认方案」之类的入口。**

### 服务端入口是存在的

`POST /api/tasks/{id}/input`，写入当前阶段的 `input` 字段（审核阶段该字段原本存上游方案全文，覆盖它即为审核意见 → 作为下一阶段输入）。模板 prompt 也印证：

> 请审阅上方方案。认可：直接推进；需调整：**在此写下具体要求（会作为实现阶段的输入）**。

### 连带证据

task1 当时确实走过这条路，但写的是**空字符串**（`stage_input_updated` payload `{"input":""}`）—— **入口被触发了，却没地方填内容**。这解释了 task1 方案里那 3 个疑问为什么始终没人回答。

### 与 ③ 的关系

③（首次确认只能在前端点、CLI 无法完成）是同一类问题的另一处：**关键的人工卡点在服务端有语义、在 UI 上无入口或缺提示**。

---

## 附：一个设计语义待确认（不是 bug）

**任务组处于 `blocked` 时，正在运行的 agent 阶段不受影响。**

实测：组在 15:16 是 `blocked`，而 task1 的实现阶段在 15:14 仍正常写代码、新建文件。

即 `blocked` 拦的是**阶段推进**，不是执行中的工作。不确定这是有意设计还是副作用 —— 若是前者，建议文档写明；若是后者，需注意「`blocked` 期间工作仍在进行，其结果可能被丢弃或与解冻后的计划不一致」。

---

## 优先级建议

| # | 问题 | 是否阻断 | 建议优先级 |
|---|---|---|---|
| **⑧** | **交付事件 `stage_run_id` 空 ⇒ 交付不可见 ⇒ 阶段永不完成** | **是（整链死）** | **P0** |
| **⑨** | **旧 run 不终止 ⇒ 僵尸阶段 ⇒ 阻塞依赖链** | **是（整链死）** | **P0** |
| ① | 错配校验冻结任务组 | 是 | P0 |
| **⑪** | **Windows 上 `curl -d` 中文静默损坏** | 是（静默数据损坏） | **P1** |
| ⑩ | `file.write_failed` 掩盖真实错误 | 否（但误导排查方向） | P1 |
| ④ | `result` 为空 + 静默空插值 | 部分 | P1 |
| ② | `approve-plan` 缺 `root_id` | 是（首次确认卡住） | P1 |
| **⑫** | 审核意见前端无入口 | 是（人工卡点无法完成） | P1 |
| ⑤ | 阶段会话不可见 | 否 | P2 |
| ⑥ | System32 只读库刷屏 | 否（淹没日志） | P2 |
| ③ | CLI 无法完成首次确认 | 否 | P3 |
| ⑦ | 任务级无回退 | 否 | P3 |

---

## 一句话总结（给维护者）

**这条链的死因不是某个功能缺失，而是「状态不闭环」：**

```
agent 交付 → 事件写了，但 stage_run_id 为空（⑧）
                ⇒ 系统看不见交付
                ⇒ 阶段永远 running（⑨）
                ⇒ 任务永不完成 → 依赖它的任务全被挡住（⑨ 级联）
                ⇒ UI 按钮报一个被 file.write_failed 掩盖的错（⑩）
```

**最小可验证的修复顺序建议**：
1. **⑧**：`-from-task` 写入时填 `stage_run_id`（取当前活跃 run）；并加兜底 —— `stage_run_id` 为空时按 `task_id + 时间窗` 回退匹配，至少打 warn。
2. **⑨**：新建 run 时终止同阶段前一个 `running` run；或按 `updated_at` 超时自动终止。
3. **⑪**：确认服务端是否在 `Content-Type` 无 `charset` 时按系统代码页解码（Windows 上会静默损坏中文）。
4. **⑩ / ⑫**：前端错误透传、审核入口补齐。

前两条修完，**编排链才有可能跑通**；后两条决定它是否可用。
