# PR Review Agent

一个使用 Java 21、Spring Boot 和 Spring AI 实现的只读 GitHub Pull Request 审查 Agent。项目当前的核心不是代码生成，而是把真实模型调用放进一个可恢复、可持久化、可约束的 Harness 中，完成从 GitHub Webhook 到单次 Review 发布的完整链路。

本文只描述当前源码已经实现的行为。已知问题和后续方案见 [docs/ISSUES.md](docs/ISSUES.md)，逐模块调用链见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 项目边界

当前实现包含：

- GitHub `pull_request` Webhook 验签与事件过滤；
- MySQL `review_run + outbox_event` 同事务接收；
- Redis Stream 异步投递和有界工作线程池；
- GitHub PR base/head SHA 固定、临时工作区检出和逐文件 Diff 解析；
- 小 PR 单 Agent、大 PR 由一次模型调用分成最多四个 Agent；
- 每个 Agent 独立初始上下文、Finding 状态、模型调用预算和工具轮历史；
- ReAct 模型/工具循环；
- 连续 READ 工具并行、WRITE/TERMINAL 工具串行；
- 工具意图先落库、工具结果和 Finding 快照同事务提交；
- OPEN 工具轮恢复时转为 ABANDONED，COMPLETED 工具轮重建模型历史；
- 多 Agent 串行执行与 Findings 汇总；
- publication key 查询远端 Review，避免已发布成功时再次 POST；
- 发布成功后保存 GitHub Review ID，并将任务置为 `PUBLISHED`；
- 工作区在正常结束和异常退出时通过 `AutoCloseable` 清理。

当前不包含代码修改、提交、推送、RAG、MCP、通用工作流平台或分布式 Worker。自动认领 Redis PEL、严格的 Finding Diff 校验和最终评论质检仍未实现，详见问题文档。

## 主链路

```text
GitHub pull_request webhook
    ↓ HMAC-SHA256 验签、事件和字段校验
review_run(PENDING) + outbox_event(PENDING)
    ↓ 同一 MySQL 事务提交后返回 HTTP 202
ReviewOutboxPublisher
    ↓ XADD pr-review:run:v1，Outbox 改为 SENT
ReviewStreamConsumer
    ↓ review_run PENDING → RUNNING（CAS）
ReviewReActRuntime.run(taskId)
    ↓
ReviewHarness.aroundRun
    ├─ 准备固定 base/head 的临时工作区
    ├─ 创建或恢复 1–4 个 review_agent
    ├─ 串行执行每个未完成 Agent
    │    └─ ReAct：模型调用 → 工具轮 → 历史追加
    ├─ 汇总全部 Agent Findings
    ├─ review_run RUNNING → PUBLICATION_READY
    ├─ 查询远端 publication key
    ├─ 必要时发布一条 GitHub COMMENT Review
    └─ review_run PUBLICATION_READY → PUBLISHED
    ↓
Redis Stream ACK
```

## Runtime 与 Harness 边界

`ReviewReActRuntime` 只负责通用循环：

```text
请求一次模型响应
    ↓
没有工具调用：追加普通文本和继续提示，进入下一轮
    ↓
存在工具调用：交给 Harness 执行并持久化完整工具轮
    ↓
publish_review 单独成功：当前 Agent 完成
```

`ReviewHarness` 负责 PR 审查生命周期：

- 父任务状态分流；
- Workspace 准备与清理；
- Agent 计划创建、串行编排与恢复；
- 模型预算预占和上下文构造；
- 工具轮持久化；
- Agent 失败与父任务失败收口；
- Findings 汇总；
- 远端查询、发布和本地终态保存。

## 多 Agent 设计

`ReviewPlanGenerator` 根据变更文件数量决定执行方式：

- 文件数不超过 `pr-review.planning.grouping-threshold`，创建一个 Agent；
- 文件数超过阈值，调用一次分组模型；
- 分组必须覆盖全部文件，每个文件只能出现一次；
- 分组数为 2 到 `pr-review.planning.max-agents`，源码限制最大为 4；
- Agent 按 `agent_index` 串行执行；
- 每个 Agent 拥有相同但互相独立的模型调用上限；
- 已成功 Agent 在恢复时跳过，未完成 Agent 从持久化历史继续；
- 任一 Agent 确定性失败时，父任务进入 `FAILED`，后续 Agent 不再执行；
- 只有全部 Agent 成功后才生成父任务发布正文。

分组读取范围通过 `ReviewToolContext.scopedDiff` 限制 `get_diff`；`read_file` 和 `search_code` 可以读取工作区源码作为调用链证据。当前 `add_finding` 尚未强制限制目标文件属于当前 Agent，这是已记录问题。

## 工具模型

| 类型 | 工具 | 当前行为 |
| --- | --- | --- |
| READ | `get_diff` | 分页读取当前 Agent 的 Diff |
| READ | `read_file` | 分页读取工作区 `source/` 内文件 |
| READ | `search_code` | 搜索普通文件；跳过符号链接和不可读文件 |
| READ | `list_findings` | 返回当前 Agent 的 Finding 快照 |
| WRITE | `add_finding` | 向内存 `ReviewState` 新增 Finding |
| WRITE | `update_finding` | 更新已有 Finding |
| TERMINAL | `publish_review` | 生成当前 Agent 的纯文本结果并结束该 Agent，不直接请求 GitHub |

连续 READ 调用由四线程有界执行器并行执行；WRITE、TERMINAL、未知工具均单独成批。无论实际完成顺序如何，结果都按模型原始调用顺序返回。

单个工具抛出的普通异常会被转换为失败 `ToolOutcome`，模型可以读取失败并修正参数。工具轮线程中断、Future 执行异常以及持久化异常向外抛出，不会伪装成业务失败结果。

## 状态与持久化

### `review_run`

代表一个固定 `repository + PR number + head SHA` 的父任务。

```text
PENDING → RUNNING → PUBLICATION_READY → PUBLISHED
                  ↘ FAILED
```

- `(thread_id, head_sha)` 唯一，重复 Webhook 不创建新任务；
- `review_state_json` 保存全部 Agent 汇总后的 Findings；
- `publication_payload_json` 保存最终正文；
- `publication_key` 写入远端 Review 隐藏标记；
- `external_review_id` 只在远端成功且本地保存成功后写入。

### `review_agent`

代表一个独立模型上下文：

- `file_paths_json`：分配文件；
- `initial_messages_json`：固定初始消息；
- `review_state_json`：该 Agent 的 Findings；
- `model_calls/max_model_calls`：已预占调用次数和固定上限；
- `success = NULL/TRUE/FALSE`：未完成、成功、确定性失败。

### `tool_round`

代表一次模型返回的完整工具调用批次：

```text
OPEN → COMPLETED
  ↘ 恢复时 ABANDONED
```

- `assistant_message_json` 保存模型原始工具调用 ID、名称和参数；
- `tool_response_json` 保存每个调用的完整成功状态和内容；
- `COMPLETED` 轮次恢复为严格配对的 Assistant/ToolResponse 历史；
- `OPEN` 轮次不重放，恢复时改为 `ABANDONED`。

### `outbox_event`

Webhook 事务内创建 `PENDING` 事件。调度器发送到 Redis Stream 后更新为 `SENT`。Redis 重复投递由父任务状态和 `PENDING → RUNNING` CAS 控制。

## Workspace 与安全边界

`GitHubWorkspacePreparer` 执行以下约束：

- GitHub API 返回的 repository、base SHA、head SHA 必须与已接收任务一致；
- fetch 后再次验证本地 base/head commit；
- 使用 merge-base 到 head 生成 Diff；
- checkout 固定 head SHA，而不是可移动分支名；
- 每次运行创建独立临时目录；
- 准备失败立即递归清理；
- Harness 使用 try-with-resources，在执行完成或异常退出后清理。

读取工具将用户路径解析为真实路径，并验证仍在 `source/` 下。`search_code` 使用 `NOFOLLOW_LINKS`，不会把外部符号链接目标作为普通文件读取。

## 上下文与预算

每次模型调用前：

1. 验证父任务仍为 `RUNNING`；
2. 使用数据库 CAS 预占当前 Agent 的一次调用；
3. 组合固定初始消息、完整已提交历史、当前 Finding 快照和预算提示；
4. 向模型暴露 ToolRegistry 中全部工具，并关闭 Spring AI 自动工具执行；
5. 校验模型响应非空。

当前保留全部历史，没有启用 `ToolHistorySelector` 的轮次裁剪和工具结果截断。这样恢复语义直接，但长任务可能触及模型上下文上限。

## 发布幂等

`publish_review` 只结束单个 Agent。全部 Agent 完成后，Harness 汇总 Findings 并保存 `PUBLICATION_READY` 和最终正文。

远端发布前先分页查询 PR Reviews，匹配：

- publication key 隐藏标记；
- head SHA；
- 已存在 `submitted_at`；
- 有效 Review ID。

查到结果时只补写本地 `external_review_id`；没有查到时才 POST `event=COMMENT`。这一机制可以处理“远端成功但响应丢失”的重复发布风险。当前 Consumer 尚未实现 `PUBLICATION_READY` 和 PEL 的自动恢复调度。

## 技术栈

| 组件 | 版本或用途 |
| --- | --- |
| Java | 21 |
| Spring Boot | 3.5.13 |
| Spring AI | 1.1.8，OpenAI-compatible ChatModel |
| MySQL | 父任务、Agent、工具轮、Outbox |
| Redis Streams | 本地异步唤醒队列 |
| MyBatis-Plus | 数据访问和 CAS 更新 |
| JGit | 固定 revision 获取、checkout 和 Diff |
| GitHub REST API | PR 元数据、Review 查询和发布 |

## 关键配置

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `PR_REVIEW_SERVER_PORT` | `18080` | 本地 HTTP 端口 |
| `AI_API_KEY` | 无 | OpenAI-compatible API Key |
| `AI_BASE_URL` | 无 | 模型服务 Base URL |
| `AI_MODEL` | 无 | 模型名称 |
| `GITHUB_WEBHOOK_SECRET` | 空 | Webhook HMAC Secret；为空时入口返回 503 |
| `GITHUB_TOKEN` | 空 | 私有仓库读取和 Review 发布凭据 |
| `PR_REVIEW_GROUPING_THRESHOLD` | `4` | 超过该文件数时调用分组模型 |
| `PR_REVIEW_MAX_AGENTS` | `4` | 最大 Agent 数，源码要求 2–4 |
| `PR_REVIEW_MAX_MODEL_CALLS` | `20` | 每个 Agent 独立模型调用预算 |
| `PR_REVIEW_WORKERS` | `8` | PR 工作线程数 |
| `PR_REVIEW_QUEUE_CAPACITY` | `64` | Worker 等待队列容量 |
| `PR_REVIEW_WORKSPACE_ROOT` | JVM 临时目录 | 临时 Workspace 根目录 |

完整配置以 `src/main/resources/application.yml` 为准。

## 验证方式

项目测试包含：

- Runtime 普通文本、工具循环、预算耗尽和异常响应单元测试；
- 工具批次并行/串行和结果顺序测试；
- 工具轮事务、恢复与 Findings 快照测试；
- Webhook 验签、重复事件和 Outbox 事务测试；
- Workspace SHA、Diff、路径和清理测试；
- GitHub 查询与发布替身测试；
- 使用 `ScriptedChatModel`、真实 MySQL/Redis 的完整链路集成测试；
- 单次真实模型调用的独立测试入口。

最近一次真实 PR 烟雾运行的证据保存在 `target/live-smoke/20261008-160145-rerun/`。该运行完成了四个 Agent、一次汇总 Review 和最终 ACK，同时暴露了评论质量、跨组 Finding 和预算提示问题，这些问题已记录在 [docs/ISSUES.md](docs/ISSUES.md)。
