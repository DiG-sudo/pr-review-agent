# 当前问题、严重程度与解决方案

本文只记录能够由当前源码、测试或真实运行证据支持的问题。严重程度表示对这个项目作为“可恢复 PR Review Agent”的影响，不等同于被审查业务代码的 Finding severity。

证据类型：

- **运行确认**：真实 PR 或集成测试已观察到；
- **源码确认**：当前源码存在明确 TODO、缺少分支或缺少约束；
- **质量评估**：人工对照 Diff、调用链和离线评测结果确认；
- **设计建议**：尚未实现的解决方案，不描述为当前能力。

## 严重程度定义

| 等级 | 含义 |
| --- | --- |
| P0 | 会造成安全事故、数据破坏或系统整体不可用；当前没有已确认 P0 |
| P1 | 会导致任务长期无法恢复、重复外部副作用或审查结果明显不可信 |
| P2 | 会降低准确率、可观察性或长任务稳定性，但主链路仍能完成 |
| P3 | 局部一致性、维护成本或展示质量问题 |

## P1：高优先级

### P1-01 Redis PEL 和 `PUBLICATION_READY` 没有自动恢复入口

**证据：源码确认。**

`ReviewStreamConsumer.consumeNewMessages()` 只读取 `ReadOffset.lastConsumed()` 的新消息，源码保留了恢复 PEL 的 TODO。`processMessage()` 遇到 `PUBLICATION_READY` 时只记录日志并返回，不 ACK，也不重新调用发布流程。

**可达场景：**

```text
模型服务暂时失败
数据库暂时失败
进程在任务 RUNNING 时退出
GitHub 发布成功但本地 PUBLISHED 更新失败
任务已经保存为 PUBLICATION_READY，但发布响应异常
```

**后果：**

- 数据库中已有恢复所需的 Agent、预算和工具轮；
- Redis 消息也仍在 PEL；
- 但调度器不会自动重新认领；
- 任务可能永久停留在 `RUNNING` 或 `PUBLICATION_READY`，必须人工再次调用 Runtime。

**最小解决方案：**

1. 在现有 Consumer 调度中增加一个有界 PEL 恢复分支，不新增调度系统；
2. 使用 Redis `XAUTOCLAIM` 认领超过空闲阈值的 entry；
3. 重新读取 `review_run`：
   - `RUNNING`：调用 Runtime 恢复未完成 Agent；
   - `PUBLICATION_READY`：调用 Runtime，使 `aroundRun` 进入查询/发布分支；
   - `PUBLISHED/FAILED`：直接 ACK；
4. Runtime 异常继续保留 PEL；
5. 只在可靠终态后 ACK。

**最后一次质检 LLM能否解决：不能。**这是调度和持久化恢复问题。

---

### P1-02 Agent 完成不等于每个分配文件都真正完成审查

**证据：运行确认 + 质量评估。**

真实烟雾运行中，系统成功完成四个 Agent 和一次远端发布，但人工离线核对仍发现预期风险未被报告；其中一个包含相关异步调用模式的文件属于已执行 Agent，而该 Agent 最终为零 Finding。

当前完成条件只有：

```text
模型单独成功调用 publish_review
```

系统提示词要求“逐文件 focused pass”，但 `publish_review` 没有参数，Harness 无法判断模型是否逐个处理了分配文件。

**后果：**

- 系统能证明 Agent 结束，不能证明文件覆盖；
- 最终质检只看到已有 Findings 时，无法发现完全漏掉的问题；
- 模型可能在某个复杂调用链上消耗预算，忽略同组其他文件。

**低成本解决方案：**

给 `publish_review` 增加必填 `reviewed_files`：

```text
before:读取当前 Agent 的 assigned file paths
execute:比较 reviewed_files 与 assigned files 的集合
after:完全一致才允许当前 Agent success=true
异常:缺少文件时返回失败 ToolOutcome，明确列出未完成文件，模型继续下一轮
```

不增加表和状态机；调用参数已经随工具意图持久化。这个约束只能证明模型明确确认了覆盖，不能证明语义上零漏检，但能阻止明显提前收口。

同时增加一条条件式审查规则：当函数由同步变为异步或返回类型变为 Promise 时，搜索全部调用方，并逐个判断“已 await、明确传递 Promise、错误使用”以及异常传播边界。

**最后一次质检 LLM能否解决：只能部分解决。**如果只审核 Findings，它无法补回未发现的问题；如果重新读取完整 Diff 和源码，它已经变成第二个完整 Reviewer。

---

### P1-03 Finding 没有强制绑定当前 Agent 文件和有效变更行

**证据：运行确认 + 源码确认。**

真实烟雾运行中，一个 Agent 被分配 `getCalendar.ts`、`app-store/index.ts`、`videoClient.ts`，却成功向另一个 Agent 负责的 `CalendarManager.ts` 写入 Finding。

`FindingWriteTools.addFinding()` 当前有明确 TODO：尚未验证 `file` 属于当前 PR Diff，也未验证 `startLine` 是该文件新版本中的有效变更行。`ReviewToolContext` 目前只携带 Workspace、ReviewState 和拼接后的 scopedDiff，没有结构化的 assigned files/changed lines。

**后果：**

- 多 Agent 可能重复审查和评论同一文件；
- Agent 的独立上下文边界失效；
- Finding 可能挂在未修改行或 PR 外文件；
- GitHub 行级评论定位和离线评测可信度下降；
- 预算被分组外问题消耗，增加本组漏检概率。

**最小解决方案：**

1. `ReviewToolContext` 增加当前 Agent 的 `assignedPaths` 和从 FileDiff 解析出的新文件变更行集合；
2. `add_finding` 前做确定性校验：
   - `file ∈ assignedPaths`；
   - `startLine` 属于该文件新增/修改行；
3. 继续允许 `read_file/search_code` 读取组外文件作为证据；
4. 越界 Finding 返回失败 `ToolOutcome`，让模型修正目标文件，而不是让整个工具轮异常退出。

不需要新增数据库字段；分配文件已存在 `review_agent.file_paths_json`，Diff 在 Workspace 准备阶段已经结构化解析。

**最后一次质检 LLM能否解决：只能事后删除。**它不能追回已经浪费的预算，确定性边界也不应交给模型判断。

---

## P2：中优先级

### P2-01 评论事实核验和增量因果判断不足

**证据：运行确认 + 质量评估。**

真实运行产生了一条高严重度 Finding：认为 `getCalendar()` 没有立即 await 导致 Promise 泄漏。但完整调用链随后执行了 `await item.calendar` 并处理空值，评论没有证明错误消费路径；其直接加 `await` 的建议也没有处理所在同步回调。

另三条 Finding 识别了 `forEach(async ...)` 模式，却没有清楚区分：

- 修改前已经存在的 fire-and-forget 行为；
- 本次同步函数改为异步后新增的异常传播变化；
- 哪些业务后果是已证明，哪些只是可能。

**后果：**

- 误报降低 Review 信任度；
- 旧问题可能被错误归因给当前 PR；
- 修复建议可能不能直接应用；
- 严重度与证据不匹配。

**最小解决方案：**

先强化 `add_finding` 前的提示词契约：

```text
1. 写明 base 行为；
2. 写明 head 行为；
3. 写明本次变化新增的可达错误；
4. 跟踪返回值到实际消费者；
5. 没有增量影响证据时不提交 Finding。
```

随后可在全部 Agent 成功、父任务进入 `PUBLICATION_READY` 之前增加一次独立质检 LLM。输入应包含候选 Findings、对应 Diff 和最小证据片段，输出只允许保留、删除、合并、改写和调整严重度。质检成功后再生成最终聚合状态和正文。

**最后一次质检 LLM能否解决：可以处理大部分已有误报和描述问题，但无法替代缺失证据。**

---

### P2-02 同根因 Finding 会跨文件、跨 Agent 重复

**证据：运行确认 + 源码确认。**

真实运行对三个文件生成了文字几乎相同的异步 `forEach` Finding。`ReviewState.addFinding()` 和 `updateFinding()` 都保留了重复识别 TODO，父任务汇总也只是按 Agent 顺序拼接。

**后果：**Review 正文膨胀，同一根因被统计为多个独立问题，严重度和质量评估失真。

**最小解决方案：**

- 完全相同的 `file + line + normalized description` 在 `ReviewState` 中确定性拒绝；
- 跨文件语义重复交给最终质检 LLM合并为一个根因，并列出受影响位置；
- 不设计通用向量相似度、RAG 或新的去重服务。

**最后一次质检 LLM能否解决：可以解决语义合并；完全相同重复应由代码解决。**

---

### P2-03 当前完整历史没有上下文上限保护

**证据：源码确认。**

`ReviewContextBuilder` 当前把全部 `execution.history` 发送给模型。`ToolHistorySelector` 实现了完整轮选择和工具结果截断，但没有被当前上下文构造器使用。

**后果：**长 Diff、多轮读取或大工具结果可能超过模型上下文限制，导致后期模型调用失败；失败后虽然可以恢复，但相同完整上下文可能再次失败。

**解决方案：**当前真实运行能在预算内完成，暂不立即启用复杂压缩。出现可复现上下文超限后，优先复用现有 `ToolHistorySelector`：保留固定初始消息、当前 Findings 和最近完整工具轮，不能拆散 Assistant/ToolResponse 配对。进一步摘要仅在该方案仍不足时增加。

**最后一次质检 LLM能否解决：不能。**这是主循环上下文治理问题。

---

### P2-04 模型调用缺少显式超时、重试和持久化调用记录

**证据：源码确认。**

`ReviewHarness.aroundModelCall()` 当前只调用 `next.apply(prompt)` 并校验响应非空，源码保留了超时、重试和调用记录 TODO。模型调用次数会在请求前可靠预占，但没有独立的请求/响应元数据表或 Trace 实现。

**后果：**

- 模型请求挂起时占用 Worker；
- 短暂网络错误完全依赖外部恢复；
- 只能从工具轮和数据库预算推断执行进度；
- 无法精确区分超时、提供方错误和非法响应。

**最小解决方案：**先配置客户端级连接/响应超时并记录结构化日志：`runId、agentId、callNumber、duration、outcome、exceptionType`。暂不新增通用重试框架；只对明确可重试且未收到模型响应的错误做最多一次重试，并继续使用已预占预算。

**最后一次质检 LLM能否解决：不能。**

---

### P2-05 同一 PR 的不同 revision 没有 thread 级串行化

**证据：源码确认。**

唯一键是 `(thread_id, head_sha)`，所以同一 PR 的不同 head SHA 会创建不同 `review_run`。Consumer 的 CAS 只保护单个 run ID；工作线程池可以同时执行两个 revision。当前源码没有 thread lock 或“只运行最新 revision”的判断。

**后果：**旧 revision 可能在新 revision 之后完成并发布评论；两个任务也会重复下载 Workspace 和调用模型。

**最小解决方案：**当前是单进程应用，可在 Consumer/aroundRun 外层按 `thread_id` 使用本地锁串行执行，并在获得锁后重新读取任务与最新 revision。只有未来真正部署多实例时才升级为数据库锁或租约。

**最后一次质检 LLM能否解决：不能。**

## P3：低优先级

### P3-01 预算提示比数据库实际调用次数少一次

**证据：运行确认 + 源码确认。**

`aroundReasoning()` 先调用 `reserveModelCall()`，随后立即调用 `buildModelMessages(execution)`，最后才执行 `execution.setModelCalls(modelCalls)`。因此上下文构造器看到的是旧值：第一次可能显示 `0/20`，最后一次显示 `19/20`；数据库中的真实预算仍正确。

**解决方案：**预占成功后先 `execution.setModelCalls(modelCalls)`，再构造模型消息。若后续构造消息或模型调用异常，不回退数据库和内存计数。

**最后一次质检 LLM能否解决：不能，但影响仅限提示准确性。**

---

### P3-02 分组模型调用没有进入统一模型治理

**证据：源码确认。**

`ReviewPlanGenerator.generate()` 直接调用 `ChatModel.call()`，不经过 `aroundModelCall/aroundReasoning`，没有预算预占、统一响应日志或重试策略。分组结果只有成功后创建 `review_agent` 才会持久化。

**后果：**分组调用成本和失败原因不能与审查调用统一统计；调用成功后、计划落库前崩溃会在恢复时重新分组。

**解决方案：**暂不新增规划状态表。先为分组调用增加同样的超时和结构化日志；只有实际出现重复分组成本或恢复不一致时，再持久化原始计划 JSON。

**最后一次质检 LLM能否解决：不能。**

---

### P3-03 数据库迁移文件存在重复序号

**证据：源码确认。**

`src/main/resources/migrations/` 同时存在：

```text
003_add_run_publication_payload.sql
003_review_run_status.sql
```

当前应用直接执行幂等 `schema.sql`，没有自动迁移框架，因此不影响新库初始化；人工按文件名执行旧库迁移时容易遗漏顺序。

**解决方案：**在引入正式迁移工具前，只需将后一个迁移重编号并在项目总览中记录顺序。不要为了四张表单独引入复杂迁移平台。

## 已解决并由当前源码体现的问题

### 发布正文被 JSON 字符串包装

`ReviewTerminalTools.publish_review` 已使用 `PlainTextResultConverter`，工具结果保持纯文本；`No issues found.` 和多行 Finding 正文不会再被额外双引号包裹。

### 搜索工具读取外部符号链接

`search_code` 当前只接受 `Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)`；`read_file` 通过 `toRealPath()` 后验证真实路径仍以 Workspace `source/` 开头。

### 模型没有明确停止条件

当前 system prompt 和每轮预算提示都明确要求：逐个文件完成一次 focused pass、不要开放式探索、零 Finding 是成功结果、完成后单独调用 `publish_review`。真实烟雾运行中四个 Agent 均能在各自 20 次预算内主动结束，但一次运行不能证明所有模型和 PR 都稳定收口。

### 多 Agent 独立持久化和恢复

当前每个 Agent 已有独立 `review_agent` 记录、预算、初始消息、Finding 快照和 `tool_round` 外键。恢复时不会重新分组，不会重放成功 Agent 或 OPEN 工具轮。

## 建议实施顺序

按收益和改动规模排序：

1. `add_finding` 增加 assigned file 和 changed line 的确定性校验；
2. `publish_review` 增加 `reviewed_files` 完整集合校验；
3. 修正预算提示的调用顺序；
4. 增加 PEL 与 `PUBLICATION_READY` 的统一恢复入口；
5. 增加最终一次评论质检 LLM，只处理已有 Findings 的事实核验、去重和严重度；
6. 出现真实上下文超限后再启用现有 `ToolHistorySelector`；
7. 根据部署形态决定是否增加 thread 级本地锁。

前三项不需要新增数据库表，也不改变父任务状态机；第四项复用现有 Runtime 恢复和 publication lookup；第五项必须明确不能替代逐文件覆盖和工具边界校验。
