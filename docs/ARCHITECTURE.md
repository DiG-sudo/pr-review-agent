# PR Review Agent 当前架构

本文按当前 `src/main` 源码描述各模块的 before / execute / after / exception 边界。它是实现说明，不包含尚未落地的早期设计。

## 1. 应用启动与配置

对应源码：

- `PrReviewAgentApplication`
- `ReviewWorkerConfig`
- `ReadToolExecutorConfig`
- `ReviewToolConfig`
- `application.yml`

```text
Application:
before:Spring Boot 读取模型、MySQL、Redis、执行预算、分组和调度配置
    before:ReviewToolConfig 将 Spring AI ToolCallback 绑定为 READ、WRITE、TERMINAL
    after:ToolRegistry 按工具名建立不可变映射，重复工具名直接拒绝启动
    before:ReviewWorkerConfig 创建固定大小的 PR 工作线程池和有界等待队列
    before:ReadToolExecutorConfig 创建 4 线程、容量 8 的 READ 工具执行器
execute:启动 Web、调度器、Mapper、Harness、Runtime 和工具 Bean
after:定时 Outbox Publisher 与 Stream Consumer 开始轮询
exception:非法线程数、队列容量、规划配置或重复工具名使应用启动失败
```

## 2. GitHub Webhook 入口

对应源码：

- `GitHubWebhookController`
- `GitHubWebhookParser`
- `GitHubWebhookService`

```text
Webhook:
before:确认 GITHUB_WEBHOOK_SECRET 已配置；未配置返回 503
    before:使用原始请求体和 HMAC-SHA256 校验 X-Hub-Signature-256
    before:只接受 X-GitHub-Event=pull_request
    before:只处理 opened、reopened、synchronize
    before:校验 repository、PR number、base SHA、head SHA 格式
execute:构造 threadId=github:{owner/repo}#{number} 的 ReviewRequest
    before:开启 MySQL 事务
    execute:插入 review_run，初始状态 PENDING，并生成 publication_key
    execute:插入同 run_id 的 outbox_event，初始状态 PENDING
    after:事务提交后返回 HTTP 202 和 created
after:相同 thread_id + head_sha 命中唯一键时返回 HTTP 202 和 duplicate
exception:签名错误返回 401；载荷错误返回 400；数据库或事务错误返回 503
```

关键约束：Webhook 请求线程不准备 Workspace、不调用模型、不访问 Redis。HTTP 202 只表示任务和 Outbox 已经可靠写入 MySQL。

## 3. Outbox 到 Redis Stream

对应源码：

- `ReviewOutboxPublisher`
- `OutboxEventEntity`
- `OutboxEventMapper`

```text
OutboxPublisher:
before:按 ID 升序读取最多 50 条 status=PENDING 的 outbox_event
execute:逐条 XADD 到 Redis Stream pr-review:run:v1，消息只包含 run_id
    after:Redis 返回 entry ID 后，使用 status=PENDING 条件更新 Outbox 为 SENT
    after:记录 eventId、taskId、streamEntry
exception:Redis/DataAccessException 时保留当前 Outbox 为 PENDING，停止本批次，等待下次调度
```

如果 XADD 成功而 MySQL 更新失败，同一 Outbox 可能再次产生 Stream 消息；下游依赖父任务状态和 CAS 处理重复投递。

## 4. Redis Stream 消费与任务认领

对应源码：

- `ReviewStreamConsumer`
- `ReviewWorkerConfig`

```text
StreamConsumer:
before:创建 consumer group pr-review-workers；BUSYGROUP 视为已经存在
    before:读取工作线程池剩余队列容量；容量为 0 时不拉取消息
execute:使用 ReadOffset.lastConsumed() 读取新消息
    execute:每条消息提交到 reviewWorkerExecutor
        before:解析 run_id 并查询 review_run
        before:PUBLICATION_READY 当前保留在 PEL，等待尚未实现的恢复入口
        before:任务不存在或状态不是 PENDING 时 ACK 当前重复消息并结束
        execute:CAS 将 review_run 从 PENDING 改为 RUNNING
        after:CAS 失败时 ACK 当前竞争失败消息
        execute:调用 ReviewReActRuntime.run(taskId)
        after:返回 PUBLISHED 或 FAILED 等非运行态结果后 ACK
exception:RuntimeException 被记录，当前 Stream entry 不 ACK，保留在 PEL
```

当前只消费新消息，没有 `XAUTOCLAIM` 或其他 PEL 自动恢复逻辑。

## 5. Workspace 准备与清理

对应源码：`GitHubWorkspacePreparer`

```text
Workspace:
before:校验 ReviewRequest 的 threadId、repository、PR number、base/head SHA
execute:调用 GitHub PR API
    before:验证 API 返回的 base repository、base SHA、head SHA 与任务一致
    before:验证 base ref 可用于安全 RefSpec
execute:在 PR_REVIEW_WORKSPACE_ROOT 下创建独立临时目录
    execute:JGit 初始化 source 仓库
    execute:只 fetch base branch 和 refs/pull/{number}/head，不拉取 tags
    before:再次验证本地解析出的 base/head commit SHA
    execute:计算 merge-base，以 merge-base tree 到 head tree 生成 diff.patch
    execute:checkout 固定 head SHA
    execute:JGit Patch 解析为 FileDiff(path, changeType, patch)
after:返回持有 workspace、source、diff.patch、fileDiffs 的 ReviewWorkspace
    after:Harness try-with-resources 结束时递归删除临时目录
exception:API、Git、Diff 或文件异常时立即清理已创建目录，再抛出准备失败
```

Workspace 每次恢复都会重新从固定 revision 获取，不持久化源码副本。

## 6. Agent 分组与计划落库

对应源码：

- `ReviewPlanGenerator`
- `ReviewInitialMessagesBuilder`
- `ReviewAgentStore.createPlan`

```text
AgentPlan:
before:查询当前 run_id 的 review_agent
    after:已有记录表示恢复，直接按 agent_index 返回，不再次调用分组模型
execute:文件数不超过 grouping-threshold 时生成单 Agent 计划
execute:文件数超过阈值时调用一次 ChatModel 生成 JSON 分组
    before:规划输入按 max-planning-diff-chars 在文件间分配 patch 字符预算
    after:校验分组数为 2..maxAgents
    after:校验每个文件恰好出现一次、没有未知路径、没有空组
execute:为每组构造独立 system/user 初始消息
    before:user 消息列出 repository、PR、base/head SHA 和本组文件
    before:初始 Diff 总量受 max-initial-diff-chars 限制，遗漏 patch 提示使用 get_diff
execute:在一个事务中插入 1–4 条 review_agent
    after:保存 file_paths、initial_messages、空 Finding 状态、model_calls=0 和固定预算
exception:分组模型空响应、非法 JSON、遗漏/重复文件或数据库失败向外抛出；首个审查模型调用不会发生
```

分组模型调用当前不经过 `aroundReasoning`，不计入各 Agent 的审查预算。

## 7. 父任务生命周期：aroundRun

对应源码：`ReviewHarness.aroundRun`

```text
Run:
before:按 review_run.status 分流
    PUBLICATION_READY:直接进入远端查询/发布
    PUBLISHED、FAILED:直接返回父任务终态
    PENDING:拒绝执行，因为消费者尚未认领
    RUNNING:继续执行
execute:构造固定 revision 的 ReviewRequest，并准备 Workspace
    execute:加载或创建 Agent 计划
    execute:按 agent_index 串行遍历
        before:success=true 的 Agent 跳过
        before:success=false 与父任务 RUNNING 同时出现时拒绝继续
        before:每个未完成 Agent 启动前重新确认父任务仍为 RUNNING
        execute:ReviewRunRestorer 构造该 Agent 的 ReviewExecution
        execute:调用 Runtime.runLoop(execution)
        after:AgentRunResult.success=true 时进入下一 Agent
        after:确定性失败时同事务保存 agent.success=false 和 review_run=FAILED
    after:全部 Agent success=true 后汇总 Findings
    after:同一事务保存父任务 review_state、publication_payload 和 PUBLICATION_READY
after:关闭并删除 Workspace
execute:查询或发布 GitHub Review
after:保存 external_review_id 和 PUBLISHED，返回 ReviewRunResult(PUBLISHED)
exception:可恢复的 Workspace、模型、工具、数据库或发布异常向外抛；不伪装成 AgentRunResult
```

父任务只有一套状态机；子 Agent 使用 nullable `success` 表示未完成、成功或确定性失败。

## 8. Agent 初始化与恢复

对应源码：`ReviewRunRestorer`

```text
Restore:
before:确认 review_agent 属于当前 run，且 success 仍为 null
execute:按 round_number 读取该 Agent 的全部 tool_round
    execute:反序列化持久化初始 System/User 消息
    execute:反序列化 Agent Finding 快照和分配文件
    execute:仅恢复 status=COMPLETED 的工具轮
        before:确认调用数量等于响应数量
        before:逐项确认 callId 和 tool name 严格配对
        after:重建 AssistantMessage + ToolResponseMessage 历史
    execute:忽略 ABANDONED；暂不把 OPEN 加入成功历史
execute:事务内将仍为 OPEN 的轮次 CAS 更新为 ABANDONED
execute:按 file_paths 从当前 Workspace 重建 scopedDiff
execute:恢复 ReviewState，并构造指向同一状态对象的 ReviewToolContext
after:nextToolRoundNumber=最大已有轮号+1；预算沿用持久化 model_calls/max_model_calls
exception:未知轮状态、调用响应不匹配、JSON 无法解析、分组文件不在当前 Diff 或预算缺失时拒绝恢复
```

模型调用但尚未形成完整工具轮的普通文本不持久化；恢复只信任已提交的完整工具轮和 Agent Finding 快照。

## 9. ReAct 循环

对应源码：`ReviewReActRuntime.runLoop`

```text
ReAct:
before:接收一个已经初始化或恢复的 ReviewExecution
execute:循环调用 aroundReasoning
    after:模型无工具调用时，将 AssistantMessage 加入内存历史
    after:追加“继续审查；完成后单独调用 publish_review”UserMessage
    after:进入下一轮
execute:模型返回工具调用时，交给 aroundToolRound
    after:将原始 AssistantMessage 和按原始顺序生成的 ToolResponseMessage 加入历史
    after:round.completed=false 时进入下一轮
    after:round.completed=true 时返回 AgentRunResult.succeeded
exception:AgentRunFailedException 转换为 AgentRunResult.failed
exception:其他 RuntimeException 继续向 Harness/Consumer 抛出，保留恢复机会
```

Runtime 不理解 PR、Workspace、数据库或 GitHub 发布；这些职责都在 Harness 和 Reviewer 模块。

## 10. 推理与模型调用边界

对应源码：

- `ReviewHarness.aroundReasoning`
- `ReviewContextBuilder`
- `ReviewHarness.aroundModelCall`

```text
Reasoning:
before:查询父任务并确认仍为 RUNNING
    before:使用 review_agent 当前计数和上限 CAS 预占一次模型调用
    before:预算耗尽抛出 AgentRunFailedException
execute:构造模型消息
    before:复制固定初始消息
    before:追加当前执行内完整历史
    before:追加权威 Finding JSON 快照
    before:追加当前预算和收口提示
execute:构造 ToolCallingChatOptions
    before:暴露 ToolRegistry 全部 callbacks
    before:关闭 Spring AI internalToolExecution
execute:调用 ChatModel
after:确认 ChatResponse、Result、Output 均非空
after:将已预占调用次数写回内存 ReviewExecution
exception:模型调用异常向外抛；数据库中已预占次数不会回滚或重置
```

当前上下文使用完整历史。`ToolHistorySelector` 仍在代码中，但 `ReviewContextBuilder` 没有调用它。

## 11. 工具注册与单工具执行

对应源码：

- `ReviewToolConfig`
- `ToolRegistry`
- `ToolExecutor`

```text
SingleTool:
before:按工具名从不可变 ToolRegistry 查询 ToolBinding
    after:未知工具直接生成 success=false 的 ToolOutcome
execute:使用模型原始 JSON arguments 和当前 ToolContext 调用 Spring AI ToolCallback
after:普通返回值转换为 success=true 的 ToolOutcome；null 转为空字符串
exception:ToolCallback 抛出的 Exception 被包装为 success=false 的 ToolOutcome
```

工具业务失败是模型可见结果，不会让整个 Agent 自动失败；调度中断和持久化失败不在此处吞掉。

## 12. 工具批次调度

对应源码：`ToolRoundExecutor`

```text
ToolBatch:
before:按模型原始调用数组建立执行批次
    before:连续 READ 合并为一个并行批次
    before:WRITE、TERMINAL、未知工具各自形成单调用批次
execute:批次之间串行执行
    execute:单调用批次在当前线程直接执行
    execute:多 READ 批次提交到 reviewReadExecutor
    after:线程池拒绝单个 READ 时生成调度失败 ToolOutcome
after:按批次索引依次 Future.get，保证返回结果顺序与原始调用顺序一致
exception:当前线程中断或 Future 以未包装异常失败时向 ToolRoundCoordinator 抛出
```

READ 并行只发生在同一模型响应中连续出现的 READ 调用之间。

## 13. 完整工具轮：aroundToolRound

对应源码：

- `ToolRoundCoordinator`
- `ToolRoundStore`

```text
ToolRound:
before:确认模型至少返回一个工具调用
    before:确认父任务仍为 RUNNING
    before:确认 review_agent 属于父任务且 success 仍为 null
    before:将模型原始文本、全部调用 ID、类型、名称和参数保存为 OPEN 工具轮
    before:识别同时包含 publish_review 和其他调用的混合批次
execute:混合批次只移除 publish_review，其他调用按串行/并行规则正常执行
    execute:每个工具由 ToolExecutor 包装为 ToolOutcome
after:按模型原始调用顺序补回被拒绝的 publish_review 失败结果
    after:构造全部调用的 StoredToolResponseMessage
    after:取得当前 ReviewState Finding 快照
    after:同一事务将工具轮 OPEN → COMPLETED，并更新 review_agent.review_state_json
    after:仅当唯一调用是成功的 publish_review 时，将 review_agent.success 设为 true
    after:内存 nextToolRoundNumber 加一
exception:线程中断恢复 interrupt 标记并向外抛
exception:执行器未包装异常、工具轮持久化失败或 Agent 状态竞争向外抛
exception:异常发生后 OPEN 轮次保留，恢复时改为 ABANDONED；不会重放该轮调用
```

`ToolRoundStore` 不感知多 Agent 编排；它只处理传入 `agentId` 的轮次、Finding 快照和完成标记。

## 14. Reviewer 工具

对应源码：

- `ReviewReadTools`
- `FindingWriteTools`
- `ReviewTerminalTools`
- `ReviewToolContext`

```text
ReadTools:
before:从 ToolContext 取得真实 Workspace 根目录、当前 ReviewState 和 scopedDiff
execute:get_diff 对 scopedDiff 按 1-based 行号分页，单页最多 500 行
execute:read_file 将路径 normalize + toRealPath，并验证真实路径仍在 source/ 下
execute:search_code 使用 Files.walk；只读取 NOFOLLOW_LINKS 普通文件
execute:list_findings 读取当前 Agent 的同步 Finding 快照
after:分页结果带范围和总行数；搜索最多返回配置请求内的 200 条匹配
exception:空参数、越界分页、路径逃逸、不存在路径和读取异常进入失败 ToolOutcome

FindingTools:
before:add_finding 校验非空字段和正行号；当前未校验文件属于本组或行号属于 Diff
execute:add_finding 生成 UUID，并保存 open Finding
execute:update_finding 按 ID 更新状态、严重度、描述、建议或备注
after:修改后的 ReviewState 在工具轮完成事务中持久化
exception:无效 Finding ID 或参数进入失败 ToolOutcome

TerminalTool:
before:提示模型只在每个分配文件完成一次 focused pass 后单独调用
execute:publish_review 从当前 ReviewState 构造正文
after:PlainTextResultConverter 保持纯文本，不添加 JSON 字符串引号
after:工具本身不调用 GitHub；单独成功只表示当前 Agent 完成
exception:与其他工具混合时由 ToolRoundCoordinator 拒绝 publish_review
```

## 15. Findings 汇总与父任务 READY

对应源码：

- `ReviewHarness.aggregateAndMarkReady`
- `ReviewAgentStore.markPublicationReady`
- `ReviewState`

```text
Aggregate:
before:重新按 agent_index 读取全部 review_agent
    before:确认每个 Agent success=true
execute:反序列化每个 Agent 的 StoredReviewState
execute:按 Agent 顺序合并所有 Finding 到父级 ReviewState
execute:生成父级 Finding 快照和固定发布正文
after:同一事务保存 review_run.review_state_json、publication_payload_json
after:CAS 将 review_run RUNNING → PUBLICATION_READY
exception:任一 Agent 未完成、Finding JSON 无法解析、序列化失败或 CAS 失败向外抛
```

当前汇总不做跨 Agent 去重和事实复核。

## 16. GitHub 查询、发布与本地终态

对应源码：

- `ReviewHarness.publishAndComplete`
- `GitHubReviewLookup`
- `GitHubReviewPublisher`

```text
Publication:
before:使用 repository、PR number、head SHA、publication key 分页查询远端 Reviews
    before:只接受同时包含 publication marker、commit_id 匹配、submitted_at 非空、ID 有效的结果
execute:查到远端 ID 时跳过 POST
execute:未查到时读取 publication_payload_json.body
    execute:POST /pulls/{number}/reviews
    before:正文追加 publication-key HTML 注释
    before:event 固定为 COMMENT，commit_id 固定为任务 head SHA
    after:确认响应包含正 ID 和 submitted_at
after:CAS 将 review_run PUBLICATION_READY → PUBLISHED，并保存 external_review_id
after:返回 ReviewRunResult(PUBLISHED)
exception:查询任一页失败时不发布；POST 或响应校验失败时不保存 PUBLISHED
exception:远端成功但本地保存失败时保留 PUBLICATION_READY；后续入口先 Lookup 再决定是否 POST
```

## 17. 失败分类与恢复边界

```text
Deterministic Agent failure:
触发:模型调用预算耗尽
处理:Runtime 转为 AgentRunResult.failed
结果:同事务保存 agent.success=false、review_run=FAILED

Recoverable execution failure:
触发:模型服务异常、非法模型响应、数据库异常、工具调度中断、Workspace/发布异常
处理:异常穿过 Runtime 和 Harness 到 Consumer
结果:Stream entry 不 ACK；已提交预算、COMPLETED 工具轮和 Finding 快照保留

Recovery:
触发:再次调用 Runtime.run(taskId)
处理:重新准备固定 Workspace，复用既有 Agent 计划，跳过成功 Agent，恢复未完成 Agent
结果:OPEN 工具轮转 ABANDONED；COMPLETED 历史不重放；预算继续累计

Automatic dispatch gap:
当前 Consumer 只读取新消息，不自动认领 PEL，也不重新派发 PUBLICATION_READY
结果:恢复数据已经存在，但自动恢复入口尚未实现
```

## 18. 数据所有权

| 数据 | 唯一所有者 | 生命周期 |
| --- | --- | --- |
| PR revision 状态 | `review_run` | Webhook 创建，到 PUBLISHED/FAILED |
| Agent 分组和预算 | `review_agent` | 首次 aroundRun 固定，恢复复用 |
| 工具意图与结果 | `tool_round` | 每次工具批次 OPEN/COMPLETED/ABANDONED |
| 当前 Agent Findings | `review_agent.review_state_json` | 每次完成工具轮更新 |
| 汇总 Findings | `review_run.review_state_json` | 全部 Agent 完成后写入 |
| 最终发布正文 | `review_run.publication_payload_json` | PUBLICATION_READY 时写入 |
| 远端发布身份 | `publication_key + external_review_id` | 接收时生成 key，发布后保存 ID |
| 源码与 Diff | 临时 Workspace | 每次 aroundRun 创建并清理 |
| 模型可见历史 | `initial_messages + COMPLETED tool_round + 本次内存历史` | 恢复时重建 |
