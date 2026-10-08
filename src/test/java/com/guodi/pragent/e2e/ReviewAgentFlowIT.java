package com.guodi.pragent.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.*;
import com.guodi.pragent.entry.queue.*;
import com.guodi.pragent.entry.webhook.*;
import com.guodi.pragent.harness.*;
import com.guodi.pragent.persistence.outbox.*;
import com.guodi.pragent.persistence.reviewagent.*;
import com.guodi.pragent.persistence.reviewrun.*;
import com.guodi.pragent.persistence.toolround.*;
import com.guodi.pragent.preparation.*;
import com.guodi.pragent.reviewer.*;
import com.guodi.pragent.reviewer.tool.*;
import com.guodi.pragent.runtime.*;
import com.guodi.pragent.runtime.tool.*;

/** Real core + real MySQL/Redis; only model, workspace acquisition and GitHub are replaced. */
@SpringBootTest(classes = ReviewAgentFlowIT.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/pr_review_agent_e2e_test",
        "spring.datasource.username=root", "spring.datasource.password=${PR_REVIEW_DB_ROOT_PASSWORD:root_dev}",
        "spring.data.redis.database=14", "spring.ai.model.chat=none",
        "GITHUB_WEBHOOK_SECRET=e2e-secret", "pr-review.execution.max-model-calls=20",
        "pr-review.planning.grouping-threshold=4", "pr-review.planning.max-agents=4"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewAgentFlowIT {
    static final String SOURCE = "services/src/main/java/org/keycloak/authentication/authenticators/browser/AbstractUsernameFormAuthenticator.java";
    static final String STREAM = "pr-review:run:v1", GROUP = "pr-review-workers";
    @Autowired ReviewReActRuntime runtime;
    @MockitoSpyBean ReviewRunMapper runs;
    @Autowired org.mybatis.spring.SqlSessionTemplate sql;
    @Autowired ReviewHarness harness;
    @MockitoSpyBean(name = "reviewReadExecutor") ExecutorService readExecutor;
    @Autowired ToolRoundMapper rounds;
    @MockitoSpyBean ReviewAgentMapper agents;
    @MockitoSpyBean OutboxEventMapper outbox;
    @Autowired GitHubWebhookService webhookService;
    @Autowired GitHubWorkspacePreparer preparer;
    @Autowired GitHubReviewLookup lookup;
    @Autowired GitHubReviewPublisher publisher;
    @Autowired ScriptedChatModel model;
    @MockitoSpyBean ReviewAgentStore agentStore;
    @MockitoSpyBean ReviewRunRestorer restorer;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @MockitoSpyBean ToolRoundStore store;
    @MockitoSpyBean ReviewReadTools readTools;
    @Autowired ObjectMapper json;
    @Autowired Environment env;
    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired ReviewOutboxPublisher dispatcher;
    @Autowired ReviewStreamConsumer consumer;
    @Autowired ThreadPoolExecutor reviewWorkerExecutor;
    final List<Long> taskIds = new CopyOnWriteArrayList<>();
    final List<Path> workspaces = new CopyOnWriteArrayList<>();
    final Map<String, Path> taskWorkspaces = new ConcurrentHashMap<>();
    final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> productionLogs =
            new ch.qos.logback.core.read.ListAppender<>() {
                @Override protected synchronized void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                    event.prepareForDeferredProcessing(); super.append(event);
                }
            };
    final ThreadLocal<String> modelCorrelation = ThreadLocal.withInitial(()->"taskId=unknown agentId=none");
    long started;
    String scenario;

    @BeforeAll
    static void startLog() throws Exception {
        Files.createDirectories(Path.of("target/e2e-logs"));
        Files.writeString(Path.of("target/e2e-logs/intermediate.log"), "event=test-suite.started\n");
    }

    @BeforeEach
    void prepare(TestInfo info) throws Exception {
        productionLogs.list.clear();
        productionLogs.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.guodi.pragent")).addAppender(productionLogs);
        scenario = info.getTestMethod().orElseThrow().getName();
        started = System.nanoTime();
        model.reset();
        reset(preparer, lookup, publisher);
        when(lookup.findPublished(anyString(), anyInt(), anyString(), anyString())).thenAnswer(call -> {
            ReviewRunEntity task=runs.selectOne(Wrappers.<ReviewRunEntity>lambdaQuery().eq(ReviewRunEntity::getPublicationKey,call.getArgument(3)));
            event("publication.lookup taskId="+(task==null?"none":task.getId())+" publicationKey=" + call.getArgument(3) + " found=false");
            return OptionalLong.empty();
        });
        when(publisher.publish(anyString(), anyInt(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            ReviewRunEntity task = runs.selectOne(Wrappers.<ReviewRunEntity>lambdaQuery()
                    .eq(ReviewRunEntity::getPublicationKey, call.getArgument(3)));
            assertThat(task.getStatus()).isEqualTo("PUBLICATION_READY");
            assertThat(json.readTree(task.getPublicationPayloadJson()).get("body").asText()).isEqualTo(call.getArgument(4));
            Path ownWorkspace = taskWorkspaces.get(task.getHeadSha());
            if (ownWorkspace != null) assertThat(Files.exists(ownWorkspace)).isFalse();
            String body = call.getArgument(4);
            event("publication.submitted taskId=" + task.getId() + " externalReviewId=4242 bodyChars=" + body.length()
                    + " bodySha256=" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))));
            return 4242L;
        });
        when(preparer.prepareWorkspace(any())).thenAnswer(call -> {
            var workspace = fixtureWorkspace();
            taskWorkspaces.put(((ReviewRequest) call.getArgument(0)).headSha(), workspace.workspaceDirectory());
            return workspace;
        });
        doAnswer(call -> {
            Long roundId = (Long) call.callRealMethod();
            ToolRoundEntity saved = rounds.selectById(roundId);
            assertThat(saved.getStatus()).isEqualTo("OPEN");
            assertThat(saved.getToolResponseJson()).isNull();
            var original = (AssistantMessage) call.getArgument(2);
            var intent = json.readValue(saved.getAssistantMessageJson(), StoredAssistantMessage.class);
            assertThat(intent.text()).isEqualTo(original.getText());
            assertThat(intent.toolCalls()).containsExactlyElementsOf(original.getToolCalls().stream()
                    .map(t -> new StoredAssistantMessage.StoredToolCall(t.id(), t.type(), t.name(), t.arguments())).toList());
            ReviewAgentEntity agent = agents.selectById((Long) call.getArgument(0));
            assertThat(runs.selectById(agent.getRunId()).getStatus()).isEqualTo("RUNNING");
            event("toolRound.opened taskId=%s agentId=%s round=%s roundId=%s"
                    .formatted(agent.getRunId(), agent.getId(), call.getArgument(1), roundId));
            return roundId;
        }).when(store).beginRound(anyLong(), anyInt(), any(AssistantMessage.class));
        doAnswer(call -> {
            boolean completed = (Boolean) call.callRealMethod();
            ToolRoundEntity saved = rounds.selectById((Long) call.getArgument(1));
            assertThat(saved.getStatus()).isEqualTo("COMPLETED");
            ReviewAgentEntity agent = agents.selectById((Long) call.getArgument(0));
            assertThat(runs.selectById(agent.getRunId()).getStatus()).isEqualTo("RUNNING");
            assertThat(agent.getSuccess()).isEqualTo(completed ? Boolean.TRUE : null);
            var state = json.readValue(agent.getReviewStateJson(), StoredReviewState.class);
            assertThat(state.findings()).containsExactlyElementsOf(((ReviewState) call.getArgument(3)).findingsSnapshot());
            var response = json.readValue(saved.getToolResponseJson(), StoredToolResponseMessage.class);
            for (var item : response.responses()) {
                event("toolCall.completed taskId=%s agentId=%s round=%d toolCallId=%s toolName=%s success=%s".formatted(
                        agent.getRunId(), agent.getId(), saved.getRoundNumber(), item.callId(), item.name(), item.success()));
            }
            return completed;
        }).when(store).completeRound(anyLong(), anyLong(), anyList(), any(ReviewState.class));
        doAnswer(c -> {
            Long runId=c.getArgument(0);
            try {
                @SuppressWarnings("unchecked") List<ReviewAgentEntity> created=(List<ReviewAgentEntity>)c.callRealMethod();
                event("plan.persisted taskId="+runId+" agentCount="+created.size());
                return created;
            } catch(RuntimeException error) { event("plan.failed taskId="+runId+" errorType="+error.getClass().getSimpleName());throw error; }
        }).when(agentStore).createPlan(anyLong(),anyList(),anyInt());
        doAnswer(c -> {
            ReviewAgentEntity agent=c.getArgument(1);
            ReviewExecution execution=(ReviewExecution)c.callRealMethod();
            event("agent.execution taskId="+agent.getRunId()+" agentId="+agent.getId()+" agentIndex="+agent.getAgentIndex()
                +" mode="+(execution.getModelCalls()==0?"initialize":"restore")+" modelCalls="+execution.getModelCalls()+" maxModelCalls="+execution.getMaxModelCalls()
                +" nextRound="+execution.getNextToolRoundNumber()+" findingCount="+execution.getReviewState().findingsSnapshot().size());
            return execution;
        }).when(restorer).loadExecution(any(),any(),any());
        model.afterCall = (response,error) -> {
            try { event("model.finished "+modelCorrelation.get()+" errorType="+(error==null?"none":error.getClass().getSimpleName())+" validResponse="+(response!=null)); }
            catch(Exception e) { throw new IllegalStateException(e); }
        };
        model.beforeCall = prompt -> {
            try {
                for (Long id : taskIds) {
                    ReviewRunEntity task = runs.selectById(id);
                    if (task == null || !prompt.getInstructions().get(1).getText().contains(task.getHeadSha())) continue;
                    var active=agentsFor(id).stream().filter(a->readInitialUser(a).equals(prompt.getInstructions().get(1).getText())).findFirst().orElse(null);
                    modelCorrelation.set("taskId="+id+" agentId="+(active==null?"none":active.getId())+" agentBudget="+(active==null?0:active.getModelCalls()));
                    String event = "modelCall=%d taskId=%d status=%s persistedBudget=%d storedRounds=%d promptMessages=%d\n"
                            .formatted(model.prompts.size(), id, task.getStatus(), agentsFor(id).stream().mapToInt(ReviewAgentEntity::getModelCalls).sum(),
                                    history(id).size(), prompt.getInstructions().size());
                    event(event.strip());
                }
                if(ScriptedChatModel.isPlanning(prompt) && !taskIds.isEmpty()) modelCorrelation.set("taskId="+taskIds.getLast()+" agentId=none");
                String promptText=prompt.getInstructions().stream().map(Message::getText).reduce("",(a,b)->a+b);
                String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(promptText.getBytes(StandardCharsets.UTF_8)));
                event("model.started "+modelCorrelation.get()+" promptChars="+promptText.length()+" promptSha256="+digest+" kind="+(ScriptedChatModel.isPlanning(prompt)?"plan":"review")+" promptMessages="+prompt.getInstructions().size());
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        // Dedicated DB 14 keys only; never FLUSHDB and never touch production DB 0.
        redis.delete(STREAM);
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "groupReady", false);
    }

    private synchronized void event(String message) throws Exception {
        Files.writeString(Path.of("target/e2e-logs/intermediate.log"),
                java.time.Instant.now() + " source=test-observer scenario=" + scenario
                        + " elapsedMs=" + Duration.ofNanos(System.nanoTime() - started).toMillis() + " " + message + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private GitHubWorkspacePreparer.ReviewWorkspace fixtureWorkspace() throws Exception {
        Path fixture = Path.of(env.getRequiredProperty("pr-review.evaluation.benchmark-workspace"))
                .resolve("ai-code-review-evaluation_keycloak-greptile/pr-1");
        assertThat(Files.isRegularFile(fixture.resolve("metadata.json"))).isTrue();
        Path temp = Files.createTempDirectory("review-e2e-");
        workspaces.add(temp);
        event("workspace.prepared workspace=" + temp.getFileName());
        Files.createDirectories(temp.resolve("source").resolve(SOURCE).getParent());
        Files.copy(fixture.resolve("diff.patch"), temp.resolve("diff.patch"));
        Files.copy(fixture.resolve("source").resolve(SOURCE), temp.resolve("source").resolve(SOURCE));
        var constructor = GitHubWorkspacePreparer.ReviewWorkspace.class.getDeclaredConstructor(
                Path.class, Path.class, Path.class, List.class);
        constructor.setAccessible(true);
        var parser = GitHubWorkspacePreparer.class.getDeclaredMethod("parseFileDiffs", Path.class);
        parser.setAccessible(true);
        return constructor.newInstance(temp, temp.resolve("source"), temp.resolve("diff.patch"),
                ((List<GitHubWorkspacePreparer.FileDiff>) parser.invoke(null, temp.resolve("diff.patch"))).stream().filter(f -> f.path().equals(SOURCE)).toList());
    }

    @AfterAll void attestOfflineModelBoundary() throws Exception {
        int realBeans=applicationContext.getBeansOfType(ChatModel.class).values().stream().mapToInt(bean -> bean instanceof ScriptedChatModel ? 0 : 1).sum();
        Files.writeString(Path.of("target/e2e-logs/offline-model.json"),json.writeValueAsString(Map.of(
                "realLlmRequests",0,"realChatModelBeans",realBeans,
                "plannerCalls",ScriptedChatModel.totalPlannerCalls.get(),"reviewCalls",ScriptedChatModel.totalReviewCalls.get(),
                "evidence","Only scripted ChatModel; OpenAI auto-configurations excluded; no model transport exists")));
        assertThat(realBeans).isZero();
    }

    @AfterEach
    void recordAndClean(TestInfo info) throws Exception {
        try { recordWithCleanup(() -> recordSnapshot(info)); }
        finally {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.guodi.pragent")).detachAppender(productionLogs);
            productionLogs.stop();
        }
    }

    private void recordSnapshot(TestInfo info) throws Exception {
        // Save inspectable state before cleaning only rows created by this test.
        StringBuilder report = new StringBuilder("scenario=" + info.getDisplayName() + "\n");
        for (Long id : taskIds) {
            ReviewRunEntity task = runs.selectById(id);
            List<ToolRoundEntity> history = history(id);
            report.append("taskId=%s status=%s rounds=%d externalReviewId=%s\n".formatted(id, task.getStatus(), history.size(), task.getExternalReviewId()));
            for (var agent : agentsFor(id)) report.append("agentId=%s index=%s success=%s modelCalls=%s maxModelCalls=%s\n".formatted(agent.getId(), agent.getAgentIndex(), agent.getSuccess(), agent.getModelCalls(), agent.getMaxModelCalls()));
            if (task.getReviewStateJson() != null) report.append("findingCount=%d\n".formatted(
                    json.readValue(task.getReviewStateJson(), StoredReviewState.class).findings().size()));
            if (task.getPublicationPayloadJson() != null) {
                String body = json.readTree(task.getPublicationPayloadJson()).get("body").asText();
                report.append("bodyChars=%d bodySha256=%s\n".formatted(body.length(), HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)))));
            }
            for (ToolRoundEntity round : history) {
                report.append("round=%d id=%d status=%s\n".formatted(round.getRoundNumber(), round.getId(), round.getStatus()));
                JsonNode calls = json.readTree(round.getAssistantMessageJson()).get("toolCalls");
                JsonNode responses = round.getToolResponseJson() == null ? null : json.readTree(round.getToolResponseJson()).get("responses");
                for (int index = 0; index < calls.size(); index++) {
                    JsonNode item = calls.get(index);
                    report.append("  toolCallId=%s toolName=%s success=%s resultChars=%s\n".formatted(
                            item.get("id").asText(), item.get("name").asText(),
                            responses == null ? "uncommitted" : responses.get(index).get("success"),
                            responses == null ? "-" : responses.get(index).get("content").asText().length()));
                }
            }
        }
        report.append("pendingMessages=%d\n".formatted(Boolean.TRUE.equals(redis.hasKey(STREAM)) ? pendingCount() : 0));
        for(Long id:taskIds) { var task=runs.selectById(id);event("task.snapshot taskId="+id+" status="+task.getStatus()+" publicationRequests="+mockingDetails(publisher).getInvocations().size()); }
        event("stream.snapshot pending="+(Boolean.TRUE.equals(redis.hasKey(STREAM))?pendingCount():0)+" taskCount="+taskIds.size());
        report.append("modelRequests=%d publicationRequests=%d durationMs=%d\n".formatted(model.prompts.size(),
                mockingDetails(publisher).getInvocations().size(), Duration.ofNanos(System.nanoTime() - started).toMillis()));
        for (Path path : workspaces) {
            event("workspace.closed workspace=" + path.getFileName() + " deleted=" + !Files.exists(path)+" activeWorkers="+reviewWorkerExecutor.getActiveCount());
        }
        Path logs = Path.of("target/e2e-logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve(info.getTestMethod().orElseThrow().getName() + ".log"), report);
        System.out.print(report);
        Files.writeString(logs.resolve(scenario + "-production.log"), String.join("\n", productionLogs.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList()) + "\n");
    }

    @FunctionalInterface interface SnapshotWriter { void write() throws Exception; }
    private void recordWithCleanup(SnapshotWriter writer) throws Exception {
        Throwable failure = null;
        try { writer.write(); }
        catch (Exception | AssertionError error) { failure = error; throw error; }
        finally { try { cleanOwnedData(); } catch (Exception | AssertionError error) {
            if (failure != null) failure.addSuppressed(error); else throw error;
        } }
    }
    private void cleanOwnedData() throws Exception {
        await(() -> reviewWorkerExecutor.getActiveCount() == 0 && reviewWorkerExecutor.getQueue().isEmpty());
        if (readExecutor instanceof ThreadPoolExecutor pool) await(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
        for (Long id : taskIds) {
            List<Long> agentIds = agentsFor(id).stream().map(ReviewAgentEntity::getId).toList();
            if (!agentIds.isEmpty()) {
                rounds.delete(Wrappers.<ToolRoundEntity>lambdaQuery().in(ToolRoundEntity::getAgentId, agentIds));
            }
            agents.delete(Wrappers.<ReviewAgentEntity>lambdaQuery().eq(ReviewAgentEntity::getRunId, id));
            outbox.delete(Wrappers.<OutboxEventEntity>lambdaQuery().eq(OutboxEventEntity::getRunId, id));
            runs.deleteById(id);
        }
        redis.delete(STREAM);
        for (Path path : workspaces) org.springframework.util.FileSystemUtils.deleteRecursively(path);
        taskIds.clear(); workspaces.clear(); taskWorkspaces.clear();
    }

    private ReviewRunEntity task(String status) {
        ReviewRunEntity task = new ReviewRunEntity();
        task.setRepository("e2e/repo");
        task.setPullRequestNumber(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
        task.setThreadId("github:e2e/repo#" + task.getPullRequestNumber());
        task.setHeadSha(UUID.randomUUID().toString().replace("-", "") + "00000000");
        task.setBaseSha("b".repeat(40));
        task.setPublicationKey(UUID.randomUUID().toString());
        task.setStatus(status);
        runs.insert(task);
        taskIds.add(task.getId());
        return task;
    }

    private List<ToolRoundEntity> history(Long id) {
        List<Long> agentIds = agentsFor(id).stream().map(ReviewAgentEntity::getId).toList();
        if (agentIds.isEmpty()) return List.of();
        return rounds.selectList(Wrappers.<ToolRoundEntity>lambdaQuery().in(ToolRoundEntity::getAgentId, agentIds)
                .orderByAsc(ToolRoundEntity::getAgentId, ToolRoundEntity::getRoundNumber));
    }
    private List<ReviewAgentEntity> agentsFor(Long runId) {
        return agents.selectList(Wrappers.<ReviewAgentEntity>lambdaQuery()
                .eq(ReviewAgentEntity::getRunId, runId)
                .orderByAsc(ReviewAgentEntity::getAgentIndex));
    }
    private ReviewAgentEntity onlyAgent(Long runId) {
        assertThat(agentsFor(runId)).hasSize(1);
        return agentsFor(runId).getFirst();
    }
    private StoredReviewState agentState(Long runId) {
        try {
            return json.readValue(onlyAgent(runId).getReviewStateJson(), StoredReviewState.class);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
    private static AssistantMessage.ToolCall tool(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }
    private static ChatResponse response(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build())));
    }
    private static List<ToolResponseMessage.ToolResponse> lastResults(Prompt prompt) {
        return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).reduce((a, b) -> b).orElseThrow().getResponses();
    }
    private String args(Map<String, Object> value) throws Exception { return json.writeValueAsString(value); }

    private void mainScript() throws Exception {
        // A barrier proves all three READ callbacks are in flight together, without timing thresholds.
        CountDownLatch entered = new CountDownLatch(3);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        org.mockito.stubbing.Answer<Object> concurrentRead = call -> {
            threads.add(Thread.currentThread().getName());
            entered.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("three READ tools execute concurrently").isTrue();
            return call.callRealMethod();
        };
        doAnswer(concurrentRead).when(readTools).getDiff(any(), any(), any());
        doAnswer(concurrentRead).when(readTools).readFile(anyString(), any(), any(), any());
        doAnswer(concurrentRead).when(readTools).searchCode(anyString(), any(), any(), any());
        String fileArgs = args(Map.of("path", SOURCE));
        model.steps.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("Inspecting PR.")))));
        model.steps.add(prompt -> {
            for (Long id : taskIds) {
                assertThat(history(id)).isEmpty();
                var initialized = runs.selectById(id);
                var initializedAgent = onlyAgent(id);
                assertThat(initializedAgent.getInitialMessagesJson()).isNotBlank();
                assertThat(initializedAgent.getReviewStateJson()).isNotBlank();
                assertThat(onlyAgent(initialized.getId()).getModelCalls()).isEqualTo(2);
                assertThat(onlyAgent(initialized.getId()).getMaxModelCalls()).isEqualTo(20);
            }
            assertThat(prompt.getInstructions()).anyMatch(message -> message.getText() != null && message.getText().contains("请继续审查"));
            return response(tool("diff", "get_diff", "{}"), tool("read", "read_file", fileArgs),
                    tool("search", "search_code", "{\"query\":\"USER_SET_BEFORE_USERNAME_PASSWORD_AUTH\"}"));
        });
        String finding = args(Map.of("severity", "high", "category", "test", "file", SOURCE, "startLine", 58,
                "description", "Scripted pipeline assertion; not a benchmark quality judgment."));
        model.steps.add(prompt -> {
            assertThat(entered.getCount()).isZero();
            assertThat(threads).hasSize(3);
            assertThat(lastResults(prompt)).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("diff", "read", "search");
            assertThat(lastResults(prompt)).allMatch(result -> !result.responseData().startsWith("Tool execution failed"));
            return response(tool("add", "add_finding", finding));
        });
        model.steps.add(prompt -> {
            String result = lastResults(prompt).getFirst().responseData();
            String id = result.substring(result.indexOf("id=") + 3).split(" ")[0];
            return response(tool("update", "update_finding", "{\"id\":\"" + id + "\",\"description\":\"Updated scripted finding\"}"),
                    tool("list", "list_findings", "{}"));
        });
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getLast().responseData()).contains("Updated scripted finding");
            return response(tool("unknown", "unknown_tool", "{}"), tool("valid", "get_diff", "{}"));
        });
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getFirst().responseData()).contains("Unknown tool");
            return response(tool("read-again", "get_diff", "{}"), tool("mixed-1", "publish_review", "{}"), tool("mixed-2", "publish_review", "{}"));
        });
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt)).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("read-again", "mixed-1", "mixed-2");
            assertThat(lastResults(prompt).subList(1, 3)).allMatch(result -> result.responseData().contains("must be requested alone"));
            return response(tool("final", "publish_review", "{}"));
        });
    }

    private void assertPublished(Long id, int calls, int count) throws Exception {
        ReviewRunEntity task = runs.selectById(id);
        assertThat(task.getStatus()).isEqualTo("PUBLISHED");
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(calls);
        assertThat(onlyAgent(task.getId()).getMaxModelCalls()).isEqualTo(20);
        assertThat(task.getExternalReviewId()).isEqualTo("4242");
        List<ToolRoundEntity> history = history(id);
        assertThat(history).hasSize(count);
        for (int index = 0; index < history.size(); index++) {
            ToolRoundEntity round = history.get(index);
            assertThat(round.getRoundNumber()).isEqualTo(index + 1);
            assertThat(round.getStatus()).isEqualTo("COMPLETED");
            var intent = json.readValue(round.getAssistantMessageJson(), StoredAssistantMessage.class);
            var result = json.readValue(round.getToolResponseJson(), StoredToolResponseMessage.class);
            assertThat(result.responses()).hasSize(intent.toolCalls().size());
            for (int n = 0; n < intent.toolCalls().size(); n++) {
                assertThat(result.responses().get(n).callId()).isEqualTo(intent.toolCalls().get(n).id());
                assertThat(result.responses().get(n).name()).isEqualTo(intent.toolCalls().get(n).name());
            }
        }
        var finalResponse = json.readValue(history.getLast().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(finalResponse.responses()).hasSize(1);
        assertThat(finalResponse.responses().getFirst().content())
                .isEqualTo(json.readTree(task.getPublicationPayloadJson()).get("body").asText());
        assertThat(workspaces).isNotEmpty().allMatch(path -> !Files.exists(path));
        verify(publisher, times(1)).publish(eq(task.getRepository()), eq(task.getPullRequestNumber()), eq(task.getHeadSha()),
                eq(task.getPublicationKey()), eq(json.readTree(task.getPublicationPayloadJson()).get("body").asText()));
        assertThat(model.steps).isEmpty();
    }

    @Test void mainFlow() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        mainScript();
        assertThat(runtime.run(task.getId()).status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertPublished(task.getId(), 7, 6);
        var originals = model.responses.stream().map(r -> r.getResult().getOutput())
                .filter(a -> !a.getToolCalls().isEmpty()).toList();
        for (int i = 0; i < originals.size(); i++) {
            var intent = json.readValue(history(task.getId()).get(i).getAssistantMessageJson(), StoredAssistantMessage.class);
            assertThat(intent.toolCalls()).containsExactlyElementsOf(originals.get(i).getToolCalls().stream()
                    .map(t -> new StoredAssistantMessage.StoredToolCall(t.id(), t.type(), t.name(), t.arguments())).toList());
        }
        var state = json.readValue(runs.selectById(task.getId()).getReviewStateJson(), StoredReviewState.class);
        assertThat(state.findings()).hasSize(1);
        Finding finding = state.findings().getFirst();
        assertThat(finding.description()).isEqualTo("Updated scripted finding");
        String expected = "Findings (1 total):\n- [high] " + SOURCE + ":58 (open) id=" + finding.id()
                + "\n  Updated scripted finding";
        assertThat(json.readTree(runs.selectById(task.getId()).getPublicationPayloadJson()).get("body").asText().equals(expected))
                .as("Persisted multiline body equals domain plain text exactly (body omitted from log)").isTrue();
        verify(publisher).publish(anyString(), anyInt(), anyString(), anyString(), eq(expected));
        var mixed = json.readValue(history(task.getId()).get(4).getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(mixed.responses()).extracting(StoredToolResponseMessage.StoredToolResult::success).containsExactly(true, false, false);
    }

    @Test void ordinaryTextContinuesBeforeStandalonePublication() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> new ChatResponse(List.of(new Generation(new AssistantMessage("Inspecting PR.")))));
        model.steps.add(p -> {
            assertThat(history(task.getId())).isEmpty();
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(2);
            assertThat(p.getInstructions()).anyMatch(m -> "Inspecting PR.".equals(m.getText()));
            assertThat(p.getInstructions()).anyMatch(m -> m instanceof UserMessage && m.getText().contains("请继续审查"));
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId()); assertPublished(task.getId(), 2, 1);
    }

    @Test void mixedPublicationKeepsCompleteIntentAndCanContinue() throws Exception {
        var task = task("RUNNING"); mainScript();
        runtime.run(task.getId()); assertPublished(task.getId(), 7, 6);
        var mixed = history(task.getId()).get(4);
        var intent = json.readValue(mixed.getAssistantMessageJson(), StoredAssistantMessage.class);
        assertThat(intent.toolCalls()).extracting(StoredAssistantMessage.StoredToolCall::id)
                .containsExactly("read-again", "mixed-1", "mixed-2");
        var results = json.readValue(mixed.getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(results.responses()).extracting(StoredToolResponseMessage.StoredToolResult::success).containsExactly(true, false, false);
        assertThat(onlyAgent(task.getId()).getSuccess()).isTrue();
    }

    @Test void writeThenListSeesNewAndUpdatedFindingInSameRound() throws Exception {
        var task = task("RUNNING");
        List<String> ids = new ArrayList<>();
        model.steps.add(p -> response(tool("add", "add_finding", argsUnchecked(SOURCE, 58, "Original finding")),
                tool("list-added", "list_findings", "{}")));
        model.steps.add(p -> {
            assertThat(lastResults(p)).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("add", "list-added");
            assertThat(lastResults(p).getLast().responseData()).contains("Original finding");
            try {
                var finding = agentState(task.getId()).findings().getFirst();
                ids.add(finding.id());
                return response(tool("update", "update_finding", "{\"id\":\"" + finding.id() + "\",\"description\":\"Revised finding\"}"),
                        tool("list-updated", "list_findings", "{}"));
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        model.steps.add(p -> {
            assertThat(lastResults(p).getLast().responseData()).contains("Revised finding").doesNotContain("Original finding");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId()); assertPublished(task.getId(), 3, 3);
        var snapshot = json.readValue(runs.selectById(task.getId()).getReviewStateJson(), StoredReviewState.class);
        assertThat(snapshot.findings()).hasSize(1);
        assertThat(snapshot.findings().getFirst().description()).isEqualTo("Revised finding");
        assertThat(snapshot.findings().getFirst().id()).isEqualTo(ids.getFirst());
    }

    @Test void findingOnActualAddedDiffLineIsAccepted() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> {
            try {
                boolean file = false; int line = -1;
                for (String text : Files.readAllLines(taskWorkspaces.get(task.getHeadSha()).resolve("diff.patch"))) {
                    if (text.startsWith("+++ b/")) file = text.substring(6).equals(SOURCE);
                    else if (file && text.startsWith("@@")) {
                        var matcher = java.util.regex.Pattern.compile("\\+(\\d+)").matcher(text);
                        assertThat(matcher.find()).isTrue(); line = Integer.parseInt(matcher.group(1));
                    } else if (file && line > 0 && text.startsWith("+")) {
                        return response(tool("valid", "add_finding", argsUnchecked(SOURCE, line, "Issue on added diff line")));
                    } else if (file && line > 0 && !text.startsWith("-") && !text.startsWith("\\")) line++;
                }
                throw new AssertionError("Selected fixture must contain an added line in required source file");
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        model.steps.add(p -> {
            assertThat(lastResults(p).getFirst().responseData()).contains("id=");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId()); assertPublished(task.getId(), 2, 2);
        assertThat(json.readValue(runs.selectById(task.getId()).getReviewStateJson(), StoredReviewState.class).findings()).hasSize(1);
    }

    private String argsUncheckedPath(String path) {
        try { return args(Map.of("path", path)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void noFindings() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("empty", "publish_review", "{}")));
        runtime.run(task.getId());
        assertPublished(task.getId(), 1, 1);
        assertThat(json.readTree(runs.selectById(task.getId()).getPublicationPayloadJson()).get("body").asText().equals("No issues found."))
                .as("No-findings body equals plain text exactly (body omitted from log)").isTrue();
    }

    @Test void invalidArgumentsAndReadsReturnFailuresThenContinue() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        String missing = args(Map.of("path", "missing.java"));
        String outside = args(Map.of("path", "../diff.patch"));
        model.steps.add(prompt -> response(tool("bad-json", "read_file", "{"), tool("missing-param", "read_file", "{}"),
                tool("missing-file", "read_file", missing), tool("escape", "read_file", outside),
                tool("bad-page", "get_diff", "{\"startLine\":2147483647}")));
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt)).hasSize(5).allMatch(result -> result.responseData().contains("Tool execution failed"));
            return response(tool("single-mixed", "publish_review", "{}"), tool("ok", "get_diff", "{}"));
        });
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getFirst().responseData()).contains("must be requested alone");
            return response(tool("done", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        assertPublished(task.getId(), 3, 3);
        var failures = json.readValue(history(task.getId()).getFirst().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(failures.responses()).allMatch(result -> !result.success());
    }

    @Test void modelFailurePreservesBudgetAndCanRestore() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("read", "get_diff", "{}")));
        model.steps.add(prompt -> { throw new IllegalStateException("scripted outage"); });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("scripted outage");
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(2);
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
        ToolRoundEntity interrupted = new ToolRoundEntity();
        interrupted.setAgentId(onlyAgent(task.getId()).getId());
        interrupted.setRoundNumber(2);
        interrupted.setStatus("OPEN");
        interrupted.setAssistantMessageJson("{\"text\":\"\",\"toolCalls\":[{\"id\":\"not-replayed\",\"type\":\"function\",\"name\":\"add_finding\",\"arguments\":\"{}\"}]}");
        rounds.insert(interrupted);
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getFirst().id()).isEqualTo("read");
            return response(tool("done", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        ReviewRunEntity saved = runs.selectById(task.getId());
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
        assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(3);
        assertThat(onlyAgent(saved.getId()).getMaxModelCalls()).isEqualTo(20);
        assertThat(saved.getExternalReviewId()).isEqualTo("4242");
        assertThat(history(task.getId())).extracting(ToolRoundEntity::getStatus)
                .containsExactly("COMPLETED", "ABANDONED", "COMPLETED");
        assertThat(history(task.getId())).extracting(ToolRoundEntity::getRoundNumber).containsExactly(1, 2, 3);
        assertThat(json.readValue(saved.getReviewStateJson(), StoredReviewState.class).findings()).isEmpty();
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void intentStoreFailurePreventsToolExecution() {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("never-run", "get_diff", "{}")));
        doThrow(new IllegalStateException("intent write failed")).when(store).beginRound(anyLong(), anyInt(), any());
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("intent write failed");
        assertThat(history(task.getId())).isEmpty();
        verifyNoInteractions(readTools, publisher);
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
    }

    @Test void completionFailureRollsBackRoundAndFindingSnapshot() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        String finding = args(Map.of("severity", "high", "category", "test", "file", SOURCE,
                "startLine", 58, "description", "Rollback assertion"));
        model.steps.add(prompt -> response(tool("add", "add_finding", finding)));
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("commit boundary failure");
        }).when(store).completeRound(anyLong(), anyLong(), anyList(), any(ReviewState.class));
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("commit boundary failure");
        assertThat(history(task.getId())).hasSize(1);
        assertThat(history(task.getId()).getFirst().getStatus()).isEqualTo("OPEN");
        assertThat(history(task.getId()).getFirst().getToolResponseJson()).isNull();
        assertThat(agentState(task.getId()).findings()).isEmpty();
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
        verifyNoInteractions(publisher);
    }

    @Test void invalidModelResponseKeepsRecoverableState() {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> null);
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("模型没有返回有效响应");
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
        assertThat(history(task.getId())).isEmpty();
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
        verifyNoInteractions(publisher);
    }

    @Test void budgetExhaustionPersistsFailure() {
        ReviewRunEntity task = task("RUNNING");
        for (int n = 0; n < 20; n++) {
            model.steps.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("Continue.")))));
        }
        assertThat(runtime.run(task.getId()).status()).isEqualTo(ReviewStatus.FAILED);
        ReviewRunEntity saved = runs.selectById(task.getId());
        assertThat(saved.getStatus()).isEqualTo("FAILED");
        assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(20);
        assertThat(saved.getFinalResultJson()).contains("budget exhausted");
        assertThat(history(task.getId())).isEmpty();
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
        verifyNoInteractions(publisher);
    }

    @Test void publicationFailureRetriesLookupWithoutAnotherPost() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("done", "publish_review", "{}")));
        doThrow(new IllegalStateException("remote response lost")).when(publisher)
                .publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("remote response lost");
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY");
        when(lookup.findPublished(anyString(), anyInt(), anyString(), anyString())).thenReturn(OptionalLong.of(4242));
        runtime.run(task.getId());
        assertPublished(task.getId(), 1, 1);
        verify(preparer, times(1)).prepareWorkspace(any());
    }

    @Test void webhookToAckAndDuplicateIntake() throws Exception {
        mainScript();
        String sha = UUID.randomUUID().toString().replace("-", "") + "00000000";
        int pr = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        String payload = json.writeValueAsString(Map.of("action", "opened", "number", pr,
                "repository", Map.of("full_name", "e2e/repo"),
                "pull_request", Map.of("head", Map.of("sha", sha), "base", Map.of("sha", "b".repeat(40)))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("e2e-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Event", "pull_request");
        headers.set("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        var request = new HttpEntity<>(payload, headers);
        var accepted = http.postForEntity("/github/webhook", request, String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isEqualTo("created");
        ReviewRunEntity task = runs.selectOne(Wrappers.<ReviewRunEntity>lambdaQuery().eq(ReviewRunEntity::getHeadSha, sha));
        taskIds.add(task.getId());
        assertThat(task.getStatus()).isEqualTo("PENDING");
        dispatcher.publishPendingEvents();
        consumer.consumeNewMessages();
        await(() -> "PUBLISHED".equals(runs.selectById(task.getId()).getStatus())
                && reviewWorkerExecutor.getActiveCount() == 0
                && redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages() == 0);
        assertPublished(task.getId(), 7, 6);
        assertThat(outbox.selectOne(Wrappers.<OutboxEventEntity>lambdaQuery().eq(OutboxEventEntity::getRunId, task.getId())).getStatus()).isEqualTo("SENT");
        var duplicate = http.postForEntity("/github/webhook", request, String.class);
        assertThat(duplicate.getBody()).isEqualTo("duplicate");
        assertThat(runs.selectCount(Wrappers.<ReviewRunEntity>lambdaQuery().eq(ReviewRunEntity::getHeadSha, sha))).isEqualTo(1);
        assertThat(outbox.selectCount(Wrappers.<OutboxEventEntity>lambdaQuery().eq(OutboxEventEntity::getRunId, task.getId()))).isEqualTo(1);
        headers.set("X-Hub-Signature-256", "sha256=" + "0".repeat(64));
        assertThat(http.postForEntity("/github/webhook", new HttpEntity<>(payload, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }


    private org.springframework.data.redis.connection.stream.RecordId enqueue(ReviewRunEntity task) {
        consumer.consumeNewMessages(); // creates consumer group even when Stream is empty
        var id = redis.opsForStream().add(STREAM, Map.of("run_id", task.getId().toString()));
        consumer.consumeNewMessages();
        return id;
    }

    private long pendingCount() {
        return redis.opsForStream().pending(STREAM, GROUP).getTotalPendingMessages();
    }

    @Test void consumerModelFailureRemainsPendingWithoutAutomaticRecovery() throws Exception {
        ReviewRunEntity task = task("PENDING");
        model.steps.add(prompt -> { throw new IllegalStateException("consumer model outage"); });
        enqueue(task);
        await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertRecoverable(task.getId(), 1, "RUNNING");
        consumer.consumeNewMessages();
        assertThat(pendingCount()).isEqualTo(1);
        assertThat(model.prompts).hasSize(1);
        verifyNoInteractions(publisher);
    }

    @Test void consumerPublicationFailureRemainsPendingWithoutAutomaticRecovery() throws Exception {
        ReviewRunEntity task = task("PENDING");
        model.steps.add(prompt -> response(tool("publish", "publish_review", "{}")));
        doThrow(new java.io.UncheckedIOException(new java.net.SocketTimeoutException("publication response lost")))
                .when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        enqueue(task);
        await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertRecoverable(task.getId(), 1, "PUBLICATION_READY");
        consumer.consumeNewMessages();
        assertThat(pendingCount()).isEqualTo(1);
        assertThat(model.prompts).hasSize(1);
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void consumerBudgetFailureIsTerminalAndAcknowledged() throws Exception {
        ReviewRunEntity task = task("PENDING");
        for (int n = 0; n < 20; n++) model.steps.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("Continue")))));
        enqueue(task);
        await(() -> "FAILED".equals(runs.selectById(task.getId()).getStatus())
                && reviewWorkerExecutor.getActiveCount() == 0 && pendingCount() == 0);
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(20);
        verifyNoInteractions(publisher);
    }

    @Test void publicationLocalWriteFailureRecoversWithoutAnotherPost() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("publish", "publish_review", "{}")));
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            ReviewRunEntity update = call.getArgument(0);
            if ("PUBLISHED".equals(update.getStatus()) && fail.getAndSet(false)) {
                throw new IllegalStateException("local publication commit failed");
            }
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("et", update);
            parameters.put("ew", call.getArgument(1));
            return sql.update(ReviewRunMapper.class.getName() + ".update", parameters);
        }).when(runs).update(any(ReviewRunEntity.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("local publication commit failed");
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY");
        doReturn(OptionalLong.of(4242)).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        runtime.run(task.getId());
        assertPublished(task.getId(), 1, 1);
    }

    @Test void concurrentPublicationRunsSubmitOnlyOnce() throws Exception {
        ReviewRunEntity task = task("PUBLICATION_READY");
        task.setPublicationPayloadJson("{\"body\":\"No issues found.\"}");
        runs.updateById(task);
        CountDownLatch inPublisher = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        doAnswer(call -> {
            inPublisher.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return 4242L;
        }).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<ReviewRunResult> first = pool.submit(() -> runtime.run(task.getId()));
            assertThat(inPublisher.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ReviewRunResult> second = pool.submit(() -> {
                secondStarted.countDown();
                return runtime.run(task.getId());
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            @SuppressWarnings("unchecked")
            var locks = (Map<String, java.util.concurrent.locks.ReentrantLock>)
                    org.springframework.test.util.ReflectionTestUtils.getField(harness, "threadLocks");
            await(() -> locks.get(task.getThreadId()).hasQueuedThreads());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(ReviewStatus.PUBLISHED);
            assertThat(second.get(5, TimeUnit.SECONDS).status()).isEqualTo(ReviewStatus.PUBLISHED);
        } finally { release.countDown(); }
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(lookup, times(1)).findPublished(anyString(), anyInt(), anyString(), anyString());
        verifyNoInteractions(preparer);
        assertThat(model.prompts).isEmpty();
        assertThat(runs.selectById(task.getId()).getExternalReviewId()).isEqualTo("4242");
    }

    @Test void interruptedParallelReadsFinishBeforeWorkspaceCloses() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), finished = new CountDownLatch(2);
        doAnswer(call -> {
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(workspaces).allMatch(Files::exists);
                return call.callRealMethod();
            } finally { finished.countDown(); }
        }).when(readTools).getDiff(any(), any(), any());
        model.steps.add(prompt -> response(tool("read-1", "get_diff", "{}"), tool("read-2", "get_diff", "{}")));
        var result = new CompletableFuture<Throwable>();
        Thread owner = new Thread(() -> {
            try { runtime.run(task.getId()); result.complete(null); }
            catch (Throwable error) { result.complete(error); }
        }, "interrupted-review-owner");
        owner.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            owner.interrupt();
            await(() -> Arrays.stream(owner.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("drainReads")) || result.isDone());
            assertThat(result.isDone()).as("owner must drain active READs").isFalse();
            assertThat(workspaces).allMatch(Files::exists);
            release.countDown();
            assertThat(result.get(5, TimeUnit.SECONDS)).hasMessage("tool round interrupted");
            owner.join(5000);
            assertThat(owner.isAlive()).isFalse();
            assertThat(finished.getCount()).isZero();
            assertThat(workspaces).allMatch(path -> !Files.exists(path));
            assertThat(history(task.getId()).getFirst().getStatus()).isEqualTo("OPEN");
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            verifyNoInteractions(publisher);
        } finally {
            release.countDown();
            owner.join(5000);
        }
    }

    @Test void duplicateToolCallIdsAreRejectedBeforeIntentWrite() {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> response(tool("same-id", "get_diff", "{}"), tool("same-id", "get_diff", "{}")));
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessageContaining("unique within a round");
        assertThat(history(task.getId())).isEmpty();
        verifyNoInteractions(readTools, publisher);
    }

    @Test void emptyAndLargeResultsKeepPairingAndOnlyVisibleCopyIsTruncated() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        doReturn("", "x".repeat(20000)).when(readTools).getDiff(any(), any(), any());
        model.steps.add(prompt -> response(tool("empty", "get_diff", "{}")));
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getFirst().responseData()).isEmpty();
            return response(tool("large", "get_diff", "{}"));
        });
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt).getFirst().responseData()).startsWith("x".repeat(12000) + "\n[tool result truncated:");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        assertPublished(task.getId(), 3, 3);
        var stored = json.readValue(history(task.getId()).get(1).getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(stored.responses().getFirst().content()).hasSize(20000);
    }


    @Test void rejectedReadSubmissionKeepsOtherResultsAndLaterRounds() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (submissions.incrementAndGet() == 2) throw new RejectedExecutionException("scripted overload");
            return call.callRealMethod();
        }).when(readExecutor).submit(any(Callable.class));
        model.steps.add(prompt -> response(tool("read-1", "get_diff", "{}"), tool("read-2", "get_diff", "{}"), tool("read-3", "get_diff", "{}")));
        model.steps.add(prompt -> {
            assertThat(lastResults(prompt)).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("read-1", "read-2", "read-3");
            assertThat(lastResults(prompt).get(1).responseData()).contains("executor rejected");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        assertPublished(task.getId(), 2, 2);
        var stored = json.readValue(history(task.getId()).getFirst().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(stored.responses()).extracting(StoredToolResponseMessage.StoredToolResult::success).containsExactly(true, false, true);
    }

    @Test void emptyModelGenerationsAreRejected() {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> new ChatResponse(List.of()));
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("模型没有返回有效响应");
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
    }

    @Test void externallyStoppedTaskDoesNotExecuteReturnedToolCalls() {
        ReviewRunEntity task = task("RUNNING");
        model.steps.add(prompt -> {
            ReviewRunEntity update = new ReviewRunEntity();
            update.setId(task.getId());
            update.setStatus("FAILED");
            runs.updateById(update);
            return response(tool("never-run", "get_diff", "{}"));
        });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessageContaining("not RUNNING");
        assertThat(history(task.getId())).isEmpty();
        verifyNoInteractions(readTools, publisher);
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
    }

    @Test void repeatedFailedCallsCreateDistinctMonotonicRounds() throws Exception {
        ReviewRunEntity task = task("RUNNING");
        for (int n = 0; n < 2; n++) model.steps.add(prompt -> response(tool("retry-id", "read_file", "{\"path\":\"missing.java\"}")));
        model.steps.add(prompt -> response(tool("publish", "publish_review", "{}")));
        runtime.run(task.getId());
        assertPublished(task.getId(), 3, 3);
        assertThat(history(task.getId()).get(0).getId()).isNotEqualTo(history(task.getId()).get(1).getId());
        for (int n = 0; n < 2; n++) {
            var stored = json.readValue(history(task.getId()).get(n).getToolResponseJson(), StoredToolResponseMessage.class);
            assertThat(stored.responses().getFirst().success()).isFalse();
            assertThat(stored.responses().getFirst().callId()).isEqualTo("retry-id");
        }
    }


    @Test void modelGenerationWithoutOutputIsRejected() {
        ReviewRunEntity task = task("RUNNING");
        ChatResponse broken = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(broken.getResult()).thenReturn(generation);
        model.steps.add(prompt -> broken);
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("模型没有返回有效响应");
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
        assertThat(workspaces).allMatch(path -> !Files.exists(path));
    }

    @Test void correctedReadAndSearchEscape() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> response(tool("missing", "read_file", "{\"path\":\"missing.java\"}"),
                tool("escape-search", "search_code", "{\"query\":\"x\",\"path\":\"../diff.patch\"}")));
        model.steps.add(p -> {
            assertThat(lastResults(p)).allMatch(r -> r.responseData().contains("Tool execution failed"));
            return response(tool("corrected", "read_file", "{\"path\":\"" + SOURCE + "\"}"));
        });
        model.steps.add(p -> {
            assertThat(lastResults(p).getFirst().responseData()).contains("package org.keycloak");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        assertPublished(task.getId(), 3, 3);
        assertThat(workspaces).allMatch(p -> !Files.exists(p.resolve("metadata.json")));
    }

    @Test void restoreNonemptyFindingsCompletedAndOpenRoundsWithoutReplay() throws Exception {
        var task = task("RUNNING");
        String finding = args(Map.of("severity", "high", "category", "test", "file", SOURCE,
                "startLine", 58, "description", "Persistent finding"));
        model.steps.add(p -> response(tool("add-before", "add_finding", finding)));
        model.steps.add(p -> response(tool("read-before", "get_diff", "{}")));
        model.steps.add(p -> { throw new IllegalStateException("stop after completed rounds"); });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("stop after completed rounds");
        var before = onlyAgent(task.getId());
        String initial = before.getInitialMessagesJson(), snapshot = before.getReviewStateJson();
        store.beginRound(before.getId(), 3, response(tool("never-replay", "add_finding", finding)).getResult().getOutput());
        clearInvocations(readTools);
        model.steps.add(p -> {
            var calls = p.getInstructions().stream().filter(AssistantMessage.class::isInstance)
                    .map(AssistantMessage.class::cast).flatMap(a -> a.getToolCalls().stream()).toList();
            assertThat(calls).extracting(AssistantMessage.ToolCall::id).containsExactly("add-before", "read-before");
            assertThat(lastResults(p).getFirst().id()).isEqualTo("read-before");
            return response(tool("list-restored", "list_findings", "{}"));
        });
        model.steps.add(p -> {
            assertThat(lastResults(p).getFirst().responseData()).contains("Persistent finding");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId());
        var saved = runs.selectById(task.getId());
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
        assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(5);
        assertThat(onlyAgent(saved.getId()).getMaxModelCalls()).isEqualTo(20);
        var savedAgent = onlyAgent(task.getId());
        assertThat(savedAgent.getInitialMessagesJson()).isEqualTo(initial);
        assertThat(savedAgent.getReviewStateJson()).isEqualTo(snapshot);
        assertThat(history(task.getId())).extracting(ToolRoundEntity::getStatus)
                .containsExactly("COMPLETED", "COMPLETED", "ABANDONED", "COMPLETED", "COMPLETED");
        assertThat(history(task.getId())).extracting(ToolRoundEntity::getRoundNumber).containsExactly(1,2,3,4,5);
        verify(readTools, never()).getDiff(any(), any(), any());
        verify(readTools, times(1)).listFindings(any());
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), contains("Persistent finding"));
    }

    @Test void externalStateStopsBeforeNextModel() throws Exception {
        for (String status : List.of("PENDING", "FAILED", "PUBLISHED", "PUBLICATION_READY")) {
            var task = task("RUNNING");
            int before = model.prompts.size();
            model.steps.add(p -> {
                ReviewRunEntity update = new ReviewRunEntity(); update.setId(task.getId()); update.setStatus(status); runs.updateById(update);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("continue"))));
            });
            assertThatThrownBy(() -> runtime.run(task.getId())).hasMessageContaining("任务状态不允许推理");
            assertThat(model.prompts).hasSize(before + 1);
            assertThat(history(task.getId())).isEmpty();
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
        }
        verifyNoInteractions(readTools, publisher);
    }

    private void assertRecoverable(Long id, int budget, String state) throws Exception {
        await(() -> reviewWorkerExecutor.getActiveCount() == 0 && reviewWorkerExecutor.getQueue().isEmpty());
        assertThat(pendingCount()).isEqualTo(1);
        var saved = runs.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(state);
        assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(budget);
        assertThat(onlyAgent(saved.getId()).getMaxModelCalls()).isEqualTo(20);
        assertThat(workspaces).allMatch(p -> !Files.exists(p));
    }

    @Test void consumerExistingPublicationSkipsPostAndAcks() throws Exception {
        var task = task("PENDING");
        doReturn(OptionalLong.of(4242)).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        enqueue(task);
        await(() -> pendingCount() == 0 && reviewWorkerExecutor.getActiveCount() == 0);
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");
        assertThat(runs.selectById(task.getId()).getExternalReviewId()).isEqualTo("4242");
        verifyNoInteractions(publisher);
    }

    @Test void webhookOutboxFailureRollsBackTask() {
        var request = new ReviewRequest("rollback-" + UUID.randomUUID(), "e2e/repo", 1, "a".repeat(40), "b".repeat(40), null);
        doAnswer(call -> { throw new IllegalStateException("outbox insert failed"); }).when(outbox).insert(any(OutboxEventEntity.class));
        assertThatThrownBy(() -> webhookService.accept(request)).hasMessage("outbox insert failed");
        assertThat(runs.selectCount(Wrappers.<ReviewRunEntity>lambdaQuery().eq(ReviewRunEntity::getThreadId, request.threadId()))).isZero();
    }

    @Test void scheduledOutboxAndConsumerReachAckWithoutManualDispatch() throws Exception {
        var task = task("PENDING");
        model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        var event = new OutboxEventEntity(); event.setRunId(task.getId()); event.setEventType("RUN_ACCEPTED"); event.setStatus("PENDING"); outbox.insert(event);
        // Register the actual @Scheduled methods with Spring's production processor, only for this test.
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.initialize();
        var processor = new org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor();
        processor.setScheduler(scheduler);
        processor.setEmbeddedValueResolver(value -> "50");
        try {
            processor.postProcessAfterInitialization(dispatcher, "testOutbox");
            processor.postProcessAfterInitialization(consumer, "testConsumer");
            processor.afterSingletonsInstantiated();
            await(() -> "PUBLISHED".equals(runs.selectById(task.getId()).getStatus()) && pendingCount() == 0 && reviewWorkerExecutor.getActiveCount() == 0);
            assertPublished(task.getId(), 1, 1);
            assertThat(outbox.selectById(event.getId()).getStatus()).isEqualTo("SENT");
        } finally { processor.destroy(); scheduler.shutdown(); }
    }

    @Test void realLocalGitWorkspaceRunsAndRejectsBothShaMismatchesBeforeModel() throws Exception {
        Path root = Files.createTempDirectory("review-local-git-");
        workspaces.add(root);
        Path source = root.resolve("repository");
        Files.createDirectories(source);
        String base, head;
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.writeString(source.resolve("sample.txt"), "before\n");
            git.add().addFilepattern(".").call();
            base = git.commit().setMessage("base").setAuthor("Test", "test@example.com").setCommitter("Test", "test@example.com").call().name();
            Files.writeString(source.resolve("sample.txt"), "after\n");
            git.add().addFilepattern(".").call();
            head = git.commit().setMessage("head").setAuthor("Test", "test@example.com").setCommitter("Test", "test@example.com").call().name();
            git.branchCreate().setName("base").setStartPoint(base).call();
        }
        Path remote = root.resolve("remotes/e2e/repo.git"); Files.createDirectories(remote.getParent());
        try (var git = org.eclipse.jgit.api.Git.cloneRepository().setURI(source.toUri().toString()).setDirectory(remote.toFile()).setBare(true).call()) {
            for (int n = 1; n <= 3; n++) { var ref = git.getRepository().updateRef("refs/pull/" + n + "/head"); ref.setNewObjectId(org.eclipse.jgit.lib.ObjectId.fromString(head)); ref.update(); }
        }
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/e2e/repo/pulls/", exchange -> {
            byte[] body = ("{\"base\":{\"sha\":\"" + base + "\",\"ref\":\"base\",\"repo\":{\"full_name\":\"e2e/repo\"}},\"head\":{\"sha\":\"" + head + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var ctor = GitHubWorkspacePreparer.class.getDeclaredConstructor(org.springframework.web.client.RestClient.class, String.class, Path.class, String.class);
            ctor.setAccessible(true);
            var real = ctor.newInstance(org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), "", root.resolve("work"), root.resolve("remotes").toUri().toString());
            doAnswer(call -> real.prepareWorkspace(call.getArgument(0))).when(preparer).prepareWorkspace(any());
            // Mismatches fail before even creating a workspace or reserving a model call.
            for (boolean staleBase : List.of(true, false)) {
                var task = task("RUNNING"); task.setPullRequestNumber(staleBase ? 1 : 2); task.setThreadId("github:e2e/repo#" + task.getPullRequestNumber());
                task.setBaseSha(staleBase ? "0".repeat(40) : base); task.setHeadSha(staleBase ? head : "0".repeat(40)); runs.updateById(task);
                assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("cannot prepare PR workspace");
                assertThat(agentsFor(task.getId())).isEmpty();
                assertThat(agentsFor(task.getId())).isEmpty();
                assertThat(model.prompts).isEmpty();
            }
            var task = task("RUNNING"); task.setPullRequestNumber(3); task.setThreadId("github:e2e/repo#3"); task.setBaseSha(base); task.setHeadSha(head); runs.updateById(task);
            model.steps.add(p -> response(tool("diff", "get_diff", "{}"), tool("source", "read_file", "{\"path\":\"sample.txt\"}")));
            model.steps.add(p -> {
                assertThat(lastResults(p).get(0).responseData()).contains("-before", "+after");
                assertThat(lastResults(p).get(1).responseData()).contains("after");
                return response(tool("publish", "publish_review", "{}"));
            });
            doReturn(4242L).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
            runtime.run(task.getId());
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");
            try (var paths = Files.list(root.resolve("work"))) { assertThat(paths.toList()).isEmpty(); }
        } finally { server.stop(0); }
    }

    @Test void lifecycleLogsHaveRequiredFieldsAndExcludeSensitiveContents() throws Exception {
        var task = task("PENDING");
        String sentinel = "PRIVATE_CONTENT_SENTINEL_48f71";
        model.steps.add(p -> new ChatResponse(List.of(new Generation(new AssistantMessage(sentinel)))));
        model.steps.add(p -> response(tool("add", "add_finding", "{\"severity\":\"high\",\"category\":\"test\",\"file\":\"" + SOURCE + "\",\"startLine\":58,\"description\":\"" + sentinel + "\"}")));
        model.steps.add(p -> { throw new IllegalStateException(sentinel); });
        enqueue(task);
        await(() -> model.prompts.size() == 3 && reviewWorkerExecutor.getActiveCount() == 0);
        assertRecoverable(task.getId(), 3, "RUNNING");
        model.steps.add(p -> response(tool("read", "get_diff", "{}")));
        doThrow(new IllegalStateException(sentinel)).when(store).beginRound(anyLong(), anyInt(), any());
        assertThatThrownBy(() -> runtime.run(task.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(model.prompts).hasSize(4);
        assertRecoverable(task.getId(), 4, "RUNNING");
        doAnswer(call -> call.callRealMethod()).when(store).beginRound(anyLong(), anyInt(), any());
        model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        runtime.run(task.getId()); // explicit Runtime restore, no PEL claim or ACK
        assertThat(pendingCount()).isEqualTo(1);
        enqueue(runs.selectById(task.getId())); // only the new terminal notification is ACKed
        await(() -> pendingCount() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        var messages = productionLogs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("event=review.")).toList();
        String all = String.join("\n", messages);
        assertThat(all).doesNotContain(sentinel, "e2e-secret", "package org.keycloak", "Findings (1 total)", "\"severity\"");
        Map<String, List<String>> fields = Map.ofEntries(
                Map.entry("run.received", List.of("taskId", "threadId", "headSha", "status")),
                Map.entry("run.initialized", List.of("taskId", "modelCalls", "maxModelCalls", "nextToolRoundNumber", "findingCount")),
                Map.entry("run.restored", List.of("taskId", "modelCalls", "maxModelCalls", "nextToolRoundNumber", "findingCount")),
                Map.entry("workspace.prepared", List.of("taskId", "workspace", "durationMs")),
                Map.entry("workspace.closed", List.of("taskId", "workspace", "durationMs")),
                Map.entry("model.started", List.of("taskId", "modelCall", "messageCount", "toolCallCount", "durationMs", "errorType")),
                Map.entry("model.completed", List.of("taskId", "modelCall", "messageCount", "toolCallCount", "durationMs", "errorType")),
                Map.entry("model.failed", List.of("taskId", "modelCall", "messageCount", "toolCallCount", "durationMs", "errorType")),
                Map.entry("tool_round.opened", List.of("taskId", "roundNumber", "roundId", "toolCount", "durationMs")),
                Map.entry("tool_round.completed", List.of("taskId", "roundNumber", "roundId", "toolCount", "durationMs")),
                Map.entry("tool_round.failed", List.of("taskId", "roundNumber", "roundId", "toolCount", "durationMs")),
                Map.entry("tool_call.completed", List.of("taskId", "roundNumber", "toolCallId", "toolName", "success", "durationMs")),
                Map.entry("publication.lookup", List.of("taskId", "publicationKey", "found")),
                Map.entry("publication.submitted", List.of("taskId", "publicationKey", "externalReviewId")),
                Map.entry("run.completed", List.of("taskId", "status", "modelCalls", "toolRoundCount", "durationMs")),
                Map.entry("stream.acked", List.of("taskId", "entryId", "status")),
                Map.entry("stream.pending", List.of("taskId", "entryId", "errorType")));
        fields.forEach((event, keys) -> {
            var events = messages.stream().filter(m -> m.startsWith("event=review." + event + " ")).toList();
            assertThat(events).as(event).isNotEmpty();
            events.forEach(message -> keys.forEach(key -> assertThat(message).as(event).contains(key + "=")));
        });
        assertThat(messages.stream().filter(m -> m.contains("tool_call.completed"))).allMatch(m -> m.contains("taskId=" + task.getId()) && !m.contains("roundNumber=null"));
        assertThat(productionLogs.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test void snapshotFailureStillCleansOwnedRowsAndWorkspace() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        runtime.run(task.getId());
        assertThatThrownBy(() -> recordWithCleanup(() -> { throw new java.io.IOException("snapshot write failed"); }))
                .hasMessage("snapshot write failed");
        assertThat(runs.selectById(task.getId())).isNull();
        assertThat(history(task.getId())).isEmpty();
        assertThat(workspaces).allMatch(p -> !Files.exists(p));
        taskIds.clear(); workspaces.clear();
    }

    @Test void isolatedEnvironmentAndRealCorePreflight() throws Exception {
        try (var connection = sql.getSqlSessionFactory().getConfiguration().getEnvironment().getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("pr_review_agent_e2e_test");
            for (String table : List.of("review_run", "review_agent", "tool_round", "outbox_event")) {
                try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
                    Set<String> names = new HashSet<>(); while (columns.next()) names.add(columns.getString("COLUMN_NAME"));
                    assertThat(names).contains("id");
                    if (!table.equals("review_agent")) assertThat(names).contains("status");
                    if (table.equals("review_run")) assertThat(names).contains("review_state_json", "publication_payload_json").doesNotContain("model_calls", "initial_messages_json");
                    if (table.equals("review_agent")) assertThat(names).contains("initial_messages_json", "review_state_json", "model_calls", "max_model_calls", "success");
                    if (table.equals("tool_round")) assertThat(names).contains("round_number", "assistant_message_json", "tool_response_json");
                }
            }
        }
        assertThat(env.getProperty("spring.data.redis.database")).isEqualTo("14");
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        for (Object core : List.of(runtime, harness, consumer, dispatcher)) assertThat(mockingDetails(core).isMock()).isFalse();
        assertThat(mockingDetails(store).isSpy()).isTrue();
    }

    @Test void snapshotAssertionFailureStillCleansData() {
        var task = task("RUNNING");
        assertThatThrownBy(() -> recordWithCleanup(() -> { throw new AssertionError("snapshot assertion failed"); }))
                .isInstanceOf(AssertionError.class).hasMessage("snapshot assertion failed");
        assertThat(runs.selectById(task.getId())).isNull();
        taskIds.clear();
    }

    private ReviewRunEntity signedTask(int pr, String sha, String action, String eventType) throws Exception {
        String payload = json.writeValueAsString(Map.of("action", action, "number", pr,
                "repository", Map.of("full_name", "e2e/repo"),
                "pull_request", Map.of("head", Map.of("sha", sha), "base", Map.of("sha", "b".repeat(40)))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("e2e-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-GitHub-Event", eventType);
        headers.set("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        var result = http.postForEntity("/github/webhook", new HttpEntity<>(payload, headers), String.class);
        if (!eventType.equals("pull_request") || action.equals("closed")) {
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isEqualTo("ignored"); return null;
        }
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var task = runs.selectOne(Wrappers.<ReviewRunEntity>lambdaQuery().eq(ReviewRunEntity::getHeadSha, sha));
        if (!taskIds.contains(task.getId())) taskIds.add(task.getId());
        return task;
    }

    private void finishConsumer(ReviewRunEntity task) throws Exception {
        await(() -> "PUBLISHED".equals(runs.selectById(task.getId()).getStatus())
                && reviewWorkerExecutor.getActiveCount() == 0 && reviewWorkerExecutor.getQueue().isEmpty() && pendingCount() == 0);
    }

    @Test void webhookNoFindingsExactBodyAndAck() throws Exception {
        var task = signedTask(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), "c" + UUID.randomUUID().toString().replace("-", "") + "0000000", "opened", "pull_request");
        model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        dispatcher.publishPendingEvents(); consumer.consumeNewMessages(); finishConsumer(task);
        assertPublished(task.getId(), 1, 1);
        assertThat(json.readTree(runs.selectById(task.getId()).getPublicationPayloadJson()).get("body").asText().equals("No issues found."))
                .as("Webhook no-findings body equals plain text exactly (body omitted from log)").isTrue();
    }

    @Test void webhookMultilineBodyExactAndAck() throws Exception {
        mainScript();
        var task = signedTask(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE), "d" + UUID.randomUUID().toString().replace("-", "") + "0000000", "opened", "pull_request");
        dispatcher.publishPendingEvents(); consumer.consumeNewMessages(); finishConsumer(task);
        assertPublished(task.getId(), 7, 6);
        var finding = json.readValue(runs.selectById(task.getId()).getReviewStateJson(), StoredReviewState.class).findings().getFirst();
        String expected = "Findings (1 total):\n- [high] " + SOURCE + ":58 (open) id=" + finding.id() + "\n  Updated scripted finding";
        assertThat(json.readTree(runs.selectById(task.getId()).getPublicationPayloadJson()).get("body").asText().equals(expected))
                .as("Webhook multiline body equals domain plain text exactly (body omitted from log)").isTrue();
        verify(publisher).publish(anyString(), anyInt(), anyString(), anyString(), eq(expected));
    }

    @Test void ignoredWebhookDoesNotCreateTask() throws Exception {
        long before = runs.selectCount(null), events = outbox.selectCount(null);
        signedTask(99, "e".repeat(40), "closed", "pull_request");
        signedTask(99, "e".repeat(40), "opened", "push");
        assertThat(runs.selectCount(null)).isEqualTo(before);
        assertThat(outbox.selectCount(null)).isEqualTo(events);
    }

    @Test void duplicateStreamEntriesOnlyOneRuntimeExecution() throws Exception {
        var task = task("PENDING");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        model.steps.add(p -> {
            entered.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return response(tool("publish", "publish_review", "{}"));
        });
        consumer.consumeNewMessages();
        var first = redis.opsForStream().add(STREAM, Map.of("run_id", task.getId().toString()));
        var duplicate = redis.opsForStream().add(STREAM, Map.of("run_id", task.getId().toString()));
        try {
            consumer.consumeNewMessages(); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            await(() -> pendingCount() == 1);
            assertThat(model.prompts).hasSize(1);
            var pending = redis.opsForStream().pending(STREAM, GROUP, org.springframework.data.domain.Range.unbounded(), 10);
            assertThat(pending).hasSize(1);
            var winner = pending.iterator().next().getId();
            assertThat(winner).isIn(first, duplicate);
            event("stream.duplicate taskId=" + task.getId() + " first=" + first + " duplicate=" + duplicate
                    + " winner=" + winner + " pending=1");
        } finally { release.countDown(); }
        finishConsumer(task); assertPublished(task.getId(), 1, 1);
    }

    @Test void reverseReadCompletionKeepsOriginalOrder() throws Exception {
        var task = task("RUNNING");
        CountDownLatch all = new CountDownLatch(3), third = new CountDownLatch(1), second = new CountDownLatch(1);
        List<String> completed = new CopyOnWriteArrayList<>(); Set<String> threads = ConcurrentHashMap.newKeySet();
        org.mockito.stubbing.Answer<Object> reverse = call -> {
            int start = switch (call.getMethod().getName()) { case "getDiff" -> 1; case "readFile" -> 2; default -> 3; };
            threads.add(Thread.currentThread().getName()); all.countDown();
            assertThat(all.await(10, TimeUnit.SECONDS)).isTrue();
            if (start == 1) assertThat(second.await(10, TimeUnit.SECONDS)).isTrue();
            if (start == 2) assertThat(third.await(10, TimeUnit.SECONDS)).isTrue();
            Object result = call.callRealMethod(); completed.add("read-" + start);
            if (start == 3) third.countDown(); if (start == 2) second.countDown();
            return result;
        };
        doAnswer(reverse).when(readTools).getDiff(any(), any(), any());
        doAnswer(reverse).when(readTools).readFile(anyString(), any(), any(), any());
        doAnswer(reverse).when(readTools).searchCode(anyString(), any(), any(), any());
        model.steps.add(p -> response(tool("read-1", "get_diff", "{}"),
                tool("read-2", "read_file", argsUncheckedPath(SOURCE)),
                tool("read-3", "search_code", "{\"query\":\"USER_SET_BEFORE_USERNAME_PASSWORD_AUTH\"}")));
        model.steps.add(p -> {
            assertThat(completed).containsExactly("read-3", "read-2", "read-1"); assertThat(threads).hasSize(3);
            assertThat(lastResults(p)).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("read-1", "read-2", "read-3");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId()); assertPublished(task.getId(), 2, 2);
        var stored = json.readValue(history(task.getId()).getFirst().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(stored.responses()).extracting(StoredToolResponseMessage.StoredToolResult::callId).containsExactly("read-1", "read-2", "read-3");
        assertThat(agentState(task.getId()).findings()).isEmpty();
    }

    @Test void initializationFailurePreventsModelAndCleansWorkspace() {
        var task = task("RUNNING");
        doThrow(new IllegalStateException("initialization failed")).when(agents).insert(any(ReviewAgentEntity.class));
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("initialization failed");
        assertThat(model.prompts).isEmpty(); assertThat(history(task.getId())).isEmpty();
        assertThat(agentsFor(task.getId())).isEmpty();
        assertThat(workspaces).allMatch(p -> !Files.exists(p));
    }

    @Test void consumerStoreFailuresLeaveOriginalPendingAndRestore() throws Exception {
        for (boolean completing : List.of(false, true)) {
            var task = task("PENDING");
            model.steps.add(p -> response(tool("read", "get_diff", "{}")));
            if (completing) doAnswer(c -> { c.callRealMethod(); throw new IllegalStateException("complete failed"); }).when(store).completeRound(anyLong(), anyLong(), anyList(), any());
            else doThrow(new IllegalStateException("begin failed")).when(store).beginRound(anyLong(), anyInt(), any());
            var entry = enqueue(task);
            await(() -> model.prompts.size() == (completing ? 3 : 1) && reviewWorkerExecutor.getActiveCount() == 0);
            assertThat(pendingCount()).isEqualTo(completing ? 2 : 1);
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
            if (completing) {
                assertThat(history(task.getId())).extracting(ToolRoundEntity::getStatus).containsExactly("OPEN");
                assertThat(history(task.getId()).getFirst().getToolResponseJson()).isNull();
            } else { assertThat(history(task.getId())).isEmpty(); verifyNoInteractions(readTools); }
            assertThat(workspaces).allMatch(p -> !Files.exists(p));
            doAnswer(c -> c.callRealMethod()).when(store).beginRound(anyLong(), anyInt(), any());
            doAnswer(c -> c.callRealMethod()).when(store).completeRound(anyLong(), anyLong(), anyList(), any());
            model.steps.add(p -> response(tool("publish", "publish_review", "{}"))); runtime.run(task.getId());
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(2);
            assertThat(pendingCount()).isEqualTo(completing ? 2 : 1);
            event("explicit.restore taskId=" + task.getId() + " originalEntry=" + entry + " ack=false");
        }
    }

    @Test void consumerInvalidResponsesRemainPending() throws Exception {
        int expected = 0;
        for (var supplier : List.<java.util.function.Supplier<ChatResponse>>of(() -> null, () -> new ChatResponse(List.of()), () -> new ChatResponse(List.of(new Generation(null))))) {
            var task = task("PENDING"); model.steps.add(p -> supplier.get()); enqueue(task); expected++;
            final int count = expected;
            await(() -> model.prompts.size() == count && reviewWorkerExecutor.getActiveCount() == 0);
            assertThat(pendingCount()).isEqualTo(expected);
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(1);
            assertThat(history(task.getId())).isEmpty();
        }
        assertThat(workspaces).allMatch(p -> !Files.exists(p)); verifyNoInteractions(publisher);
    }

    @Test void consumerLookupFailureLeavesReadyWithoutPost() throws Exception {
        var task = task("PENDING"); model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        doThrow(new java.io.IOException("lookup outage")).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        enqueue(task); await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertThat(pendingCount()).isEqualTo(1); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY");
        assertThat(runs.selectById(task.getId()).getExternalReviewId()).isNull(); verifyNoInteractions(publisher);
        doReturn(OptionalLong.of(4242)).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        runtime.run(task.getId()); assertThat(model.prompts).hasSize(1); assertThat(pendingCount()).isEqualTo(1);
    }

    @Test void consumerUncertainPublicationReconcilesWithoutPostOrAck() throws Exception {
        var task = task("PENDING"); model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        Set<String> remote = ConcurrentHashMap.newKeySet();
        doAnswer(c -> remote.contains(c.getArgument(3)) ? OptionalLong.of(4242) : OptionalLong.empty()).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        doAnswer(c -> { remote.add(c.getArgument(3)); throw new IllegalStateException("response lost"); }).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        enqueue(task); await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertThat(pendingCount()).isEqualTo(1); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY");
        runtime.run(task.getId()); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");
        assertThat(pendingCount()).isEqualTo(1); assertThat(model.prompts).hasSize(1);
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(preparer, times(1)).prepareWorkspace(any());
    }

    @Test void repeatedModelFailuresCannotResetBudget() throws Exception {
        var task = task("RUNNING");
        for (int i = 1; i <= 3; i++) {
            model.steps.add(p -> { throw new IllegalStateException("outage"); });
            assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("outage");
            assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(i);
        }
        for (int i = 3; i < 20; i++) model.steps.add(p -> new ChatResponse(List.of(new Generation(new AssistantMessage("Continue")))));
        assertThat(runtime.run(task.getId()).status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(20);
        assertThat(model.prompts).hasSize(20); verifyNoInteractions(publisher);
    }

    @Test void findingOutsideDiffRejected() throws Exception { assertInvalidFinding("not-in-diff.java", 1); }
    @Test void findingUnchangedLineRejected() throws Exception { assertInvalidFinding(SOURCE, 1); }
    private void assertInvalidFinding(String file, int line) throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> response(tool("bad-finding", "add_finding", argsUnchecked(file, line, "Invalid diff location"))));
        model.steps.add(p -> { throw new IllegalStateException("stop for snapshot"); });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("stop for snapshot");
        var result = json.readValue(history(task.getId()).getFirst().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(result.responses().getFirst().success()).as("Finding outside changed lines must be rejected").isFalse();
        assertThat(agentState(task.getId()).findings()).isEmpty();
    }
    private String argsUnchecked(String file, int line, String description) {
        try { return args(Map.of("severity", "high", "category", "test", "file", file, "startLine", line, "description", description)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Test void duplicateFindingMustNotCreateTwoIssues() throws Exception {
        var task = task("RUNNING"); String arguments = argsUnchecked(SOURCE, 58, "Identical issue");
        model.steps.add(p -> response(tool("add-1", "add_finding", arguments), tool("add-2", "add_finding", arguments)));
        model.steps.add(p -> { throw new IllegalStateException("stop for snapshot"); });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("stop for snapshot");
        assertThat(agentState(task.getId()).findings().size())
                .as("Identical issue must have one Finding (contents omitted from log)").isEqualTo(1);
    }

    @Test void searchMustNotFollowNestedExternalSymlink() throws Exception { pathBoundary(true); }
    @Test void readAndSearchRejectExternalPaths() throws Exception { pathBoundary(false); }
    private void pathBoundary(boolean nestedSearch) throws Exception {
        var task = task("RUNNING"); Path outside = Files.createTempDirectory("review-outside-"); workspaces.add(outside);
        String sentinel = "OUTSIDE_SENTINEL_91a2"; Files.writeString(outside.resolve("secret.txt"), sentinel);
        model.steps.add(p -> {
            Path workspace = taskWorkspaces.get(task.getHeadSha());
            try { Files.createSymbolicLink(workspace.resolve("source/external.txt"), outside.resolve("secret.txt")); }
            catch (Exception e) { throw new IllegalStateException(e); }
            try { Files.createSymbolicLink(workspace.resolve("source/external-dir"), outside); }
            catch (Exception e) { throw new IllegalStateException(e); }
            if (nestedSearch) return response(tool("nested", "search_code", "{\"query\":\"OUTSIDE_SENTINEL\"}"));
            try { return response(tool("absolute-read", "read_file", args(Map.of("path", outside.resolve("secret.txt").toString()))),
                    tool("absolute-search", "search_code", args(Map.of("path", outside.toString(), "query", sentinel))),
                    tool("symlink-read", "read_file", "{\"path\":\"external.txt\"}"),
                    tool("directory-symlink-read", "read_file", "{\"path\":\"external-dir/secret.txt\"}"),
                    tool("directory-symlink-search", "search_code", "{\"path\":\"external-dir\",\"query\":\"OUTSIDE_SENTINEL\"}"),
                    tool("symlink-search", "search_code", "{\"path\":\"external.txt\",\"query\":\"OUTSIDE_SENTINEL\"}")); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        model.steps.add(p -> { throw new IllegalStateException("stop for snapshot"); });
        assertThatThrownBy(() -> runtime.run(task.getId())).hasMessage("stop for snapshot");
        var results = json.readValue(history(task.getId()).getFirst().getToolResponseJson(), StoredToolResponseMessage.class).responses();
        assertThat(results.stream().anyMatch(r -> r.content().contains(sentinel))).as("No source-external content in tool results").isFalse();
        if (!nestedSearch) assertThat(results).allMatch(r -> !r.success());
    }

    @Test void samePrRevisionsKeepIndependentState() throws Exception { concurrentRevisions(true); }
    @Test void differentPrReviewsKeepIndependentState() throws Exception { concurrentRevisions(false); }
    private void concurrentRevisions(boolean samePr) throws Exception {
        int pr = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE - 1);
        String shaA = UUID.randomUUID().toString().replace("-", "") + "00000000";
        String shaB = UUID.randomUUID().toString().replace("-", "") + "11111111";
        var a = signedTask(pr, shaA, "opened", "pull_request");
        var b = signedTask(samePr ? pr : pr + 1, shaB, "synchronize", "pull_request");
        assertThat(a.getPublicationKey()).isNotEqualTo(b.getPublicationKey());
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        Map<String, java.util.concurrent.atomic.AtomicInteger> calls = new ConcurrentHashMap<>();
        model.routed = p -> {
            String text = p.getInstructions().get(1).getText();
            String sha = text.contains(shaA) ? shaA : shaB;
            int n = calls.computeIfAbsent(sha, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            if (n == 1) {
                entered.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                return response(tool("finding-" + sha, "add_finding", argsUnchecked(SOURCE, 58, "issue for " + sha)));
            }
            return response(tool("publish-" + sha, "publish_review", "{}"));
        };
        try {
            dispatcher.publishPendingEvents(); consumer.consumeNewMessages();
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("Both distinct runs reach model before release").isTrue();
            event("revision.overlap samePr=" + samePr + " taskA=" + a.getId() + " taskB=" + b.getId());
        } finally { release.countDown(); }
        finishConsumer(a); finishConsumer(b);
        for (var task : List.of(a, b)) {
            var saved = runs.selectById(task.getId());
            var findings = json.readValue(saved.getReviewStateJson(), StoredReviewState.class).findings();
            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().description()).isEqualTo("issue for " + task.getHeadSha());
            assertThat(history(task.getId())).extracting(ToolRoundEntity::getRoundNumber).containsExactly(1, 2);
            assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(2);
            verify(publisher, times(1)).publish(eq(task.getRepository()), eq(task.getPullRequestNumber()), eq(task.getHeadSha()), eq(task.getPublicationKey()), contains(task.getHeadSha()));
        }
        assertThat(taskWorkspaces.get(shaA)).isNotEqualTo(taskWorkspaces.get(shaB));
    }

    @Test void consumerExplicitPublicationFailureCanRetryWithoutModel() throws Exception {
        var task = task("PENDING"); model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        doThrow(new IllegalStateException("HTTP 403")).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        enqueue(task); await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY"); assertThat(pendingCount()).isEqualTo(1);
        doReturn(4242L).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        runtime.run(task.getId());
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");
        assertThat(model.prompts).hasSize(1); assertThat(pendingCount()).isEqualTo(1);
        verify(preparer, times(1)).prepareWorkspace(any());
        verify(publisher, times(2)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test void consumerLocalPublicationCommitFailureReconcilesWithoutPost() throws Exception {
        var task = task("PENDING"); model.steps.add(p -> response(tool("publish", "publish_review", "{}")));
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Set<String> remote = ConcurrentHashMap.newKeySet();
        doAnswer(c -> remote.contains(c.getArgument(3)) ? OptionalLong.of(4242) : OptionalLong.empty()).when(lookup).findPublished(anyString(), anyInt(), anyString(), anyString());
        doAnswer(c -> { remote.add(c.getArgument(3)); return 4242L; }).when(publisher).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        doAnswer(c -> {
            ReviewRunEntity update = c.getArgument(0);
            if ("PUBLISHED".equals(update.getStatus()) && fail.getAndSet(false)) throw new IllegalStateException("local commit outage");
            Map<String, Object> parameters = new HashMap<>(); parameters.put("et", update); parameters.put("ew", c.getArgument(1));
            return sql.update(ReviewRunMapper.class.getName() + ".update", parameters);
        }).when(runs).update(any(ReviewRunEntity.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        enqueue(task); await(() -> model.prompts.size() == 1 && reviewWorkerExecutor.getActiveCount() == 0);
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLICATION_READY");
        assertThat(runs.selectById(task.getId()).getExternalReviewId()).isNull(); assertThat(pendingCount()).isEqualTo(1);
        runtime.run(task.getId());
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED"); assertThat(pendingCount()).isEqualTo(1);
        verify(publisher, times(1)).publish(anyString(), anyInt(), anyString(), anyString(), anyString());
        assertThat(model.prompts).hasSize(1);
    }

    @Test void initializationIsCommittedBeforeModelAndToolsMatchRegistry() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> {
            var saved = runs.selectById(task.getId());
            var savedAgent = onlyAgent(task.getId());
            assertThat(savedAgent.getInitialMessagesJson()).isNotBlank();
            assertThat(savedAgent.getReviewStateJson()).isNotBlank();
            assertThat(onlyAgent(saved.getId()).getModelCalls()).isEqualTo(1); assertThat(onlyAgent(saved.getId()).getMaxModelCalls()).isEqualTo(20);
            assertThat(p.getInstructions().get(1).getText()).contains(task.getHeadSha(), task.getBaseSha(), task.getRepository());
            var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) p.getOptions();
            assertThat(options.getInternalToolExecutionEnabled()).isFalse();
            assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).containsExactlyInAnyOrder("get_diff", "read_file", "search_code", "list_findings", "add_finding", "update_finding", "publish_review");
            return response(tool("publish", "publish_review", "{}"));
        });
        runtime.run(task.getId());
    }

    @Test void unknownFindingUpdateFailsThenAgentContinues() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> response(tool("missing", "update_finding", "{\"id\":\"missing\",\"description\":\"update\"}")));
        model.steps.add(p -> { assertThat(lastResults(p).getFirst().responseData()).contains("finding not found"); return response(tool("publish", "publish_review", "{}")); });
        runtime.run(task.getId()); assertPublished(task.getId(), 2, 2);
    }

    @Test void goldenSentinelStaysOutsideAgentAndObserverLogs() throws Exception {
        Path evaluatorOnly = Files.createTempDirectory("review-evaluator-only-"); workspaces.add(evaluatorOnly);
        String sentinel = "EVALUATOR_ONLY_7f9b134d";
        Files.writeString(evaluatorOnly.resolve("answers.txt"), sentinel);
        var property = new org.springframework.core.env.MapPropertySource("test-evaluator-path", Map.of("pr-review.evaluation.golden-comments", evaluatorOnly.toString()));
        ((org.springframework.core.env.ConfigurableEnvironment) env).getPropertySources().addFirst(property);
        try {
            var task = task("PENDING"); model.steps.add(p -> response(tool("read", "get_diff", "{}")));
            model.steps.add(p -> response(tool("publish", "publish_review", "{}"))); enqueue(task); finishConsumer(task);
            boolean leaked = model.prompts.stream().flatMap(p -> p.getInstructions().stream()).anyMatch(m -> m.getText() != null && m.getText().contains(sentinel));
            assertThat(leaked).as("Evaluator sentinel absent from all model instructions").isFalse();
            var saved = runs.selectById(task.getId());
            String agentData = agentsFor(task.getId()).stream()
                    .map(agent -> agent.getInitialMessagesJson() + agent.getReviewStateJson())
                    .reduce("", String::concat);
            assertThat((agentData + saved.getReviewStateJson() + saved.getPublicationPayloadJson()).contains(sentinel)).isFalse();
            for (var round : history(task.getId())) assertThat((round.getAssistantMessageJson() + round.getToolResponseJson()).contains(sentinel)).isFalse();
            assertThat(Files.readString(Path.of("target/e2e-logs/intermediate.log")).contains(sentinel)).isFalse();
            assertThat(productionLogs.list.stream().anyMatch(e -> e.getFormattedMessage().contains(sentinel))).isFalse();
            verify(publisher).publish(anyString(), anyInt(), anyString(), anyString(), argThat(body -> !body.contains(sentinel)));
        } finally { ((org.springframework.core.env.ConfigurableEnvironment) env).getPropertySources().remove("test-evaluator-path"); }
    }

    @Test void observerEventsAreCorrelatedAndDoNotExposeContent(TestInfo info) throws Exception {
        var task = task("PENDING");
        model.steps.add(p -> response(tool("observed-read", "get_diff", "{}")));
        model.steps.add(p -> response(tool("observed-publish", "publish_review", "{}")));
        enqueue(task); finishConsumer(task); assertPublished(task.getId(), 2, 2);
        recordSnapshot(info);
        var lines = Files.readAllLines(Path.of("target/e2e-logs/intermediate.log")).stream()
                .filter(line -> line.contains("scenario=" + scenario + " ")).toList();
        assertThat(lines).isNotEmpty().allMatch(line -> line.contains("source=test-observer") && line.contains("elapsedMs="));
        String events = String.join("\n", lines);
        assertThat(events).contains("taskId=" + task.getId(), "persistedBudget=", "toolRound.opened", "round=1",
                "toolCallId=observed-read", "success=true", "publication.lookup", "publication.submitted", "bodySha256=", "workspace.closed");
        assertThat(events.contains("e2e-secret") || events.contains("package org.keycloak") || events.contains("No issues found.")).isFalse();
    }

    @Test void largeRealReadTruncatesOnlyModelVisibleCopy() throws Exception {
        var task = task("RUNNING");
        model.steps.add(p -> {
            try { Files.writeString(taskWorkspaces.get(task.getHeadSha()).resolve("source/large.txt"), "x".repeat(20000)); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return response(tool("large", "read_file", "{\"path\":\"large.txt\"}"));
        });
        model.steps.add(p -> {
            String visible = lastResults(p).getFirst().responseData();
            assertThat(visible).contains("tool result truncated");
            var stored = history(task.getId()).getFirst();
            try { assertThat(json.readValue(stored.getToolResponseJson(), StoredToolResponseMessage.class).responses().getFirst().content().length()).isGreaterThan(20000); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return response(tool("publish", "publish_review", "{}"));
        }); runtime.run(task.getId());
    }

    private GitHubWorkspacePreparer.ReviewWorkspace syntheticWorkspace(int count) throws Exception {
        Path root = Files.createTempDirectory("agent-groups-"); workspaces.add(root);
        Path source = Files.createDirectories(root.resolve("source"));
        StringBuilder patch = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String file = "File" + i + ".java";
            Files.writeString(source.resolve(file), "class File" + i + " {}\n");
            patch.append("diff --git a/" + file + " b/" + file + "\n--- a/" + file + "\n+++ b/" + file + "\n@@ -1 +1 @@\n-old\n+class File" + i + " {}\n");
        }
        Files.writeString(root.resolve("diff.patch"), patch);
        var parser = GitHubWorkspacePreparer.class.getDeclaredMethod("parseFileDiffs", Path.class); parser.setAccessible(true);
        var ctor = GitHubWorkspacePreparer.ReviewWorkspace.class.getDeclaredConstructor(Path.class, Path.class, Path.class, List.class); ctor.setAccessible(true);
        return ctor.newInstance(root, source, root.resolve("diff.patch"), parser.invoke(null, root.resolve("diff.patch")));
    }
    private void useSynthetic(int count) {
        doAnswer(c -> {
            var workspace = syntheticWorkspace(count);
            taskWorkspaces.put(((ReviewRequest)c.getArgument(0)).headSha(), workspace.workspaceDirectory());
            return workspace;
        }).when(preparer).prepareWorkspace(any());
    }
    private ChatResponse textResponse(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    private String planJson(int count, int groups) throws Exception {
        List<Map<String,Object>> list = new ArrayList<>();
        for (int g=0; g<groups; g++) {
            List<String> files = new ArrayList<>();
            for (int i=g; i<count; i+=groups) files.add("File"+i+".java");
            list.add(Map.of("files", files));
        }
        return json.writeValueAsString(Map.of("groups",list));
    }
    private int currentIndex(Prompt prompt, Long runId) {
        String initial = prompt.getInstructions().get(1).getText();
        return agentsFor(runId).stream().filter(a -> initial.equals(readInitialUser(a))).findFirst().orElseThrow().getAgentIndex();
    }
    private String readInitialUser(ReviewAgentEntity agent) {
        try { return json.readTree(agent.getInitialMessagesJson()).get(1).get("text").asText(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }

    @Test void planningThresholdUsesNoPlannerForOneAndFourFiles() throws Exception {
        for (int count : List.of(1,4)) {
            useSynthetic(count); var task=task("RUNNING");
            model.routed=p -> { assertThat(ScriptedChatModel.isPlanning(p)).isFalse(); return response(tool("finish", "publish_review", "{}")); };
            runtime.run(task.getId());
            assertThat(agentsFor(task.getId())).hasSize(1);
            assertThat(json.readTree(onlyAgent(task.getId()).getFilePathsJson())).hasSize(count);
        }
        assertThat(model.plannerCalls.get()).isZero(); assertThat(model.reviewCalls.get()).isEqualTo(2);
    }
    @Test void largePlansCoverEveryFileOnceWithAtMostFourAgents() throws Exception {
        int expectedCalls=0;
        for (int count : List.of(5,9,20)) {
            int groups=count==5?2:count==9?3:4;
            useSynthetic(count); var task=task("RUNNING"); String plan=planJson(count,groups);
            model.routed=p -> ScriptedChatModel.isPlanning(p)?textResponse(plan):response(tool("finish", "publish_review", "{}"));
            runtime.run(task.getId()); expectedCalls+=groups;
            var planRows=agentsFor(task.getId()); assertThat(planRows).hasSize(groups);
            List<String> files=new ArrayList<>();
            for(var a:planRows) files.addAll(json.readerForListOf(String.class).<List<String>>readValue(a.getFilePathsJson()));
            assertThat(files).hasSize(count).doesNotHaveDuplicates();
            for(int i=0;i<count;i++) assertThat(files).contains("File"+i+".java");
        }
        assertThat(model.plannerCalls.get()).isEqualTo(3); assertThat(model.reviewCalls.get()).isEqualTo(expectedCalls);
    }
    @Test void invalidPlansLeaveNoAgentsAndDoNotStartReviewer() throws Exception {
        useSynthetic(5);
        for (String invalid : List.of("not-json", "null", "{\"groups\":[]}", "{\"groups\":[{\"files\":[]},{\"files\":[\"File0.java\"]}]}",
                "{\"groups\":[{\"files\":[\"File0.java\"]},{\"files\":[\"File0.java\"]}]}",
                "{\"groups\":[{\"files\":[\"File0.java\"]},{\"files\":[\"unknown.java\"]}]}",
                "{\"groups\":[{\"files\":[\"File0.java\"]},{\"files\":[\"File1.java\"]}]}",planJson(5,1),planJson(5,5))) {
            var task=task("RUNNING"); model.routed=p -> textResponse(invalid);
            assertThatThrownBy(()->runtime.run(task.getId())).isInstanceOf(IllegalStateException.class);
            assertThat(agentsFor(task.getId())).isEmpty(); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        }
        assertThat(model.reviewCalls.get()).isZero(); verifyNoInteractions(publisher);
    }
    @Test void planInsertionFailureRollsBackEveryAgent() throws Exception {
        useSynthetic(5); var task=task("RUNNING"); String plan=planJson(5,2);
        model.routed=p -> textResponse(plan);
        java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(c -> { var r=sql.insert("com.guodi.pragent.persistence.reviewagent.ReviewAgentMapper.insert", c.getArgument(0)); if(writes.incrementAndGet()==2) throw new IllegalStateException("plan SQL failure"); return r; }).when(agents).insert(any(ReviewAgentEntity.class));
        assertThatThrownBy(()->runtime.run(task.getId())).hasMessage("plan SQL failure");
        assertThat(agentsFor(task.getId())).isEmpty(); assertThat(model.reviewCalls.get()).isZero();
    }
    @Test void multiAgentSerialScopesBudgetsAndPublicationAreIndependent() throws Exception {
        useSynthetic(5); var task=task("RUNNING"); String plan=planJson(5,2);
        List<Integer> order=new ArrayList<>();
        Map<Integer,Integer> steps=new HashMap<>();
        model.routed=p -> {
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int index=currentIndex(p,task.getId()); order.add(index);
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(runs.selectById(task.getId()).getPublicationPayloadJson()).isNull();
            verifyNoInteractions(publisher,lookup);
            var a=agentsFor(task.getId()).get(index);
            assertThat(a.getMaxModelCalls()).isEqualTo(20);
            int step=steps.merge(index,1,Integer::sum);
            if(step==1) {
                try {
                    String initial=readInitialUser(a);
                    for(int f=0;f<5;f++) if(!json.readTree(a.getFilePathsJson()).toString().contains("File"+f+".java")) assertThat(initial).doesNotContain("File"+f+".java");
                } catch(Exception e) { throw new IllegalStateException(e); }
                return response(tool("scope", "get_diff", "{}"));
            }
            String diff=lastResults(p).getFirst().responseData();
            try {
                for(int i=0;i<5;i++) {
                    boolean own=json.readTree(a.getFilePathsJson()).toString().contains("File"+i+".java");
                    if(own) assertThat(diff).contains("File"+i+".java"); else assertThat(diff).doesNotContain("File"+i+".java");
                }
            } catch(Exception e) { throw new IllegalStateException(e); }
            if(index==0 && step==2) return textResponse("Continue");
            return response(tool("finish", "publish_review", "{}"));
        };
        runtime.run(task.getId());
        assertThat(order).containsExactly(0,0,0,1,1);
        var all=agentsFor(task.getId()); assertThat(all).extracting(ReviewAgentEntity::getModelCalls).containsExactly(3,2);
        assertThat(all).extracting(ReviewAgentEntity::getSuccess).containsOnly(true);
        for(var a:all) assertThat(rounds.selectList(Wrappers.<ToolRoundEntity>lambdaQuery().eq(ToolRoundEntity::getAgentId,a.getId()).orderByAsc(ToolRoundEntity::getRoundNumber))).extracting(ToolRoundEntity::getRoundNumber).containsExactly(1,2);
        assertThat(model.plannerCalls.get()).isEqualTo(1);
        verify(publisher,times(1)).publish(anyString(),anyInt(),anyString(),anyString(),eq("No issues found."));
        runtime.run(task.getId()); assertThat(model.reviewCalls.get()).isEqualTo(5);
        verify(publisher,times(1)).publish(anyString(),anyInt(),anyString(),anyString(),anyString());
    }
    @Test void multiAgentFailureStopsRemainingAgentsAndParent() throws Exception {
        useSynthetic(5); var task=task("RUNNING"); String plan=planJson(5,3);
        model.routed=p -> {
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int i=currentIndex(p,task.getId());
            if(i==0) return response(tool("finish","publish_review","{}"));
            assertThat(i).isEqualTo(1); return textResponse("Continue");
        };
        assertThat(runtime.run(task.getId()).status()).isEqualTo(ReviewStatus.FAILED);
        var all=agentsFor(task.getId()); assertThat(all).extracting(ReviewAgentEntity::getSuccess).containsExactly(true,false,null);
        assertThat(all).extracting(ReviewAgentEntity::getModelCalls).containsExactly(1,20,0); verifyNoInteractions(publisher);
    }
    @Test void multiAgentPartialRestoreSkipsSuccessAndAbandonsOpenRound() throws Exception {
        useSynthetic(5); var task=task("RUNNING"); String plan=planJson(5,3);
        java.util.concurrent.atomic.AtomicBoolean failing=new java.util.concurrent.atomic.AtomicBoolean(true);
        Map<Integer,Integer> steps=new HashMap<>();
        model.routed=p -> {
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int i=currentIndex(p,task.getId()); int step=steps.merge(i,1,Integer::sum);
            if(i==0) return response(tool("finish","publish_review","{}"));
            if(i==1 && step==1) return response(tool("add","add_finding","{\"severity\":\"high\",\"category\":\"bug\",\"file\":\"File1.java\",\"startLine\":1,\"description\":\"Saved finding\"}"));
            if(i==1 && failing.get()) throw new IllegalStateException("temporary model outage");
            if(i==1) {
                var calls=p.getInstructions().stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast).flatMap(a->a.getToolCalls().stream()).toList();
                assertThat(calls).extracting(AssistantMessage.ToolCall::id).containsExactly("add");
                assertThat(p.getInstructions().toString()).contains("Saved finding");
            }
            return response(tool("finish","publish_review","{}"));
        };
        assertThatThrownBy(()->runtime.run(task.getId())).hasMessage("temporary model outage");
        var before=agentsFor(task.getId()); assertThat(before).extracting(ReviewAgentEntity::getSuccess).containsExactly(true,null,null);
        assertThat(before).extracting(ReviewAgentEntity::getModelCalls).containsExactly(1,2,0);
        store.beginRound(before.get(1).getId(),2,response(tool("never","get_diff","{}")).getResult().getOutput());
        failing.set(false); runtime.run(task.getId());
        var after=agentsFor(task.getId()); assertThat(after).extracting(ReviewAgentEntity::getModelCalls).containsExactly(1,3,1);
        assertThat(after).extracting(ReviewAgentEntity::getInitialMessagesJson).containsExactlyElementsOf(before.stream().map(ReviewAgentEntity::getInitialMessagesJson).toList());
        assertThat(history(task.getId()).stream().filter(r->r.getAgentId().equals(before.get(1).getId()))).extracting(ToolRoundEntity::getStatus).containsExactly("COMPLETED","ABANDONED","COMPLETED");
        assertThat(model.plannerCalls.get()).isEqualTo(1); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");
    }
    @Test void repositorySourceOutsideAssignedDiffRemainsReadable() throws Exception {
        useSynthetic(5); var task=task("RUNNING"); String plan=planJson(5,2); Map<Integer,Integer> steps=new HashMap<>();
        model.routed=p->{
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int i=currentIndex(p,task.getId()); if(steps.merge(i,1,Integer::sum)==1) return response(tool("related","read_file","{\"path\":\"File"+(i==0?1:0)+".java\"}"),tool("related-search","search_code","{\"query\":\"class File"+(i==0?1:0)+"\"}"));
            assertThat(lastResults(p)).hasSize(2).allMatch(r->r.responseData().contains("class File")); return response(tool("finish","publish_review","{}"));
        }; runtime.run(task.getId());
    }
    @Test void consumerAutomaticallyRecoversPendingAfterModelOutage() throws Exception {
        var task=task("PENDING"); model.steps.add(p->{throw new IllegalStateException("recoverable outage");});
        enqueue(task); await(()->reviewWorkerExecutor.getActiveCount()==0 && model.reviewCalls.get()==1);
        assertThat(pendingCount()).isEqualTo(1); assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        model.steps.add(p->response(tool("finish","publish_review","{}")));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<deadline && !"PUBLISHED".equals(runs.selectById(task.getId()).getStatus())) { consumer.consumeNewMessages(); Thread.sleep(25); }
        assertThat(runs.selectById(task.getId()).getStatus()).as("Consumer must reclaim pending and resume without manual Runtime invocation").isEqualTo("PUBLISHED");
        assertThat(pendingCount()).isZero();
    }
    @Test void webhookMultiAgentFindingsAggregateExactlyAndAck() throws Exception {
        useSynthetic(5);
        var task=signedTask(ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE),UUID.randomUUID().toString().replace("-","")+"00000000","opened","pull_request");
        String plan=planJson(5,2); Map<Integer,Integer> steps=new HashMap<>();
        model.routed=p->{
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int i=currentIndex(p,task.getId());
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(runs.selectById(task.getId()).getPublicationPayloadJson()).isNull(); verifyNoInteractions(publisher,lookup);
            if(steps.merge(i,1,Integer::sum)==1) return response(tool("add","add_finding",argsUnchecked("File"+i+".java",1,"First line \"quoted\"\nSecond line")));
            return response(tool("finish","publish_review","{}"));
        };
        dispatcher.publishPendingEvents();consumer.consumeNewMessages();finishConsumer(task);
        var all=agentsFor(task.getId()); assertThat(all).extracting(ReviewAgentEntity::getSuccess).containsExactly(true,true);
        var aggregate=json.readValue(runs.selectById(task.getId()).getReviewStateJson(),StoredReviewState.class);
        assertThat(aggregate.findings()).hasSize(2);
        StringBuilder expected=new StringBuilder("Findings (2 total):");
        for(var f:aggregate.findings()) expected.append("\n- [high] "+f.file()+":1 (open) id="+f.id()+"\n  First line \"quoted\"\nSecond line");
        String body=json.readTree(runs.selectById(task.getId()).getPublicationPayloadJson()).get("body").asText();
        assertThat(body).isEqualTo(expected.toString());
        verify(publisher,times(1)).publish(anyString(),anyInt(),anyString(),anyString(),eq(body));
        runtime.run(task.getId());assertThat(model.plannerCalls.get()).isEqualTo(1);assertThat(model.reviewCalls.get()).isEqualTo(4);
        assertThat(pendingCount()).isZero();
    }
    @Test void crossAgentIdenticalFindingMustAggregateOnce() throws Exception {
        useSynthetic(5);var task=task("RUNNING");String plan=planJson(5,2);Map<Integer,Integer> steps=new HashMap<>();
        model.routed=p->{
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            int i=currentIndex(p,task.getId());
            if(steps.merge(i,1,Integer::sum)==1) return response(tool("duplicate","add_finding","{\"severity\":\"high\",\"category\":\"bug\",\"file\":\"File0.java\",\"startLine\":1,\"description\":\"Same root cause\"}"));
            return response(tool("finish","publish_review","{}"));
        };runtime.run(task.getId());
        assertThat(json.readValue(runs.selectById(task.getId()).getReviewStateJson(),StoredReviewState.class).findings()).as("Identical findings from separate Agents must not publish twice").hasSize(1);
    }

    private StoredToolResponseMessage storedResponses(ToolRoundEntity round) {
        try { return json.readValue(round.getToolResponseJson(),StoredToolResponseMessage.class); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    @Test void mixedRoundPersistsFindingRejectsPublishThenCompletesNextRound() throws Exception {
        var task=task("RUNNING");
        model.steps.add(p->response(tool("write","add_finding",argsUnchecked(SOURCE,58,"Saved before publication")),tool("mixed","publish_review","{}")));
        model.steps.add(p->{
            var responses=lastResults(p);
            assertThat(responses).extracting(ToolResponseMessage.ToolResponse::id).containsExactly("write","mixed");
            assertThat(responses.getFirst().responseData()).contains("Finding added:");
            assertThat(responses.getLast().responseData()).contains("must be requested alone");
            var persisted=history(task.getId());assertThat(persisted).hasSize(1);
            var results=storedResponses(persisted.getFirst());
            assertThat(results.responses()).extracting(StoredToolResponseMessage.StoredToolResult::success).containsExactly(true,false);
            assertThat(agentState(task.getId()).findings()).hasSize(1);
            assertThat(onlyAgent(task.getId()).getSuccess()).isNull();
            assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
            assertThat(runs.selectById(task.getId()).getPublicationPayloadJson()).isNull();
            verifyNoInteractions(lookup,publisher);
            return response(tool("final","publish_review","{}"));
        });
        runtime.run(task.getId());assertPublished(task.getId(),2,2);
        assertThat(agentState(task.getId()).findings()).hasSize(1);
        verify(publisher,times(1)).publish(anyString(),anyInt(),anyString(),anyString(),contains("Saved before publication"));
    }
    @Test void lostConsumerPendingEntryMustBeReclaimed() throws Exception {
        var task=task("PENDING");consumer.consumeNewMessages();
        var id=redis.opsForStream().add(STREAM,Map.of("run_id",task.getId().toString()));
        var records=redis.<String,String>opsForStream().read(org.springframework.data.redis.connection.stream.Consumer.from(GROUP,"old-offline-consumer"),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(1),
                org.springframework.data.redis.connection.stream.StreamOffset.create(STREAM,org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()));
        assertThat(records).hasSize(1);assertThat(records.getFirst().getId()).isEqualTo(id);assertThat(pendingCount()).isEqualTo(1);
        model.steps.add(p->response(tool("finish","publish_review","{}")));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<deadline && !"PUBLISHED".equals(runs.selectById(task.getId()).getStatus())) { consumer.consumeNewMessages();Thread.sleep(25); }
        assertThat(runs.selectById(task.getId()).getStatus()).as("Entry owned by an offline consumer must be reclaimed").isEqualTo("PUBLISHED");assertThat(pendingCount()).isZero();
    }
    @Test void invalidPlannerResponseShapesNeverStartReview() throws Exception {
        useSynthetic(5);
        for(var supplier:List.<java.util.function.Supplier<ChatResponse>>of(()->null,()->new ChatResponse(List.of()),()->new ChatResponse(List.of(new Generation(null))))) {
            var task=task("RUNNING");model.routed=p->supplier.get();
            assertThatThrownBy(()->runtime.run(task.getId())).isInstanceOf(IllegalStateException.class);
            assertThat(agentsFor(task.getId())).isEmpty();assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        }
        assertThat(model.reviewCalls.get()).isZero();verifyNoInteractions(publisher);
    }

    @Test void differentPrMultiAgentRunsKeepSerialIsolation() throws Exception {
        useSynthetic(5);
        int pr=ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE-1);
        var a=signedTask(pr,UUID.randomUUID().toString().replace("-","")+"00000000","opened","pull_request");
        var b=signedTask(pr+1,UUID.randomUUID().toString().replace("-","")+"11111111","opened","pull_request");
        String plan=planJson(5,2);CountDownLatch overlapping=new CountDownLatch(2);
        Map<String,java.util.concurrent.atomic.AtomicInteger> steps=new ConcurrentHashMap<>();
        Map<Long,List<Integer>> order=new ConcurrentHashMap<>();
        Map<Long,java.util.concurrent.atomic.AtomicInteger> active=new ConcurrentHashMap<>();
        Map<Long,java.util.concurrent.atomic.AtomicInteger> maximum=new ConcurrentHashMap<>();
        for(var t:List.of(a,b)) { order.put(t.getId(),new CopyOnWriteArrayList<>());active.put(t.getId(),new java.util.concurrent.atomic.AtomicInteger());maximum.put(t.getId(),new java.util.concurrent.atomic.AtomicInteger()); }
        model.routed=p->{
            if(ScriptedChatModel.isPlanning(p)) return textResponse(plan);
            var t=p.getInstructions().get(1).getText().contains(a.getHeadSha())?a:b;
            int index=currentIndex(p,t.getId());String key=t.getId()+":"+index;
            int n=steps.computeIfAbsent(key,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            order.get(t.getId()).add(index);
            int running=active.get(t.getId()).incrementAndGet();maximum.get(t.getId()).accumulateAndGet(running,Math::max);
            try {
                if(index==0 && n==1) { overlapping.countDown();assertThat(overlapping.await(5,TimeUnit.SECONDS)).as("Two PRs overlap in real workers").isTrue(); }
                if(n==1) return response(tool("add","add_finding",argsUnchecked("File"+index+".java",1,"Task marker "+t.getHeadSha())));
                return response(tool("finish","publish_review","{}"));
            } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException(error);}
            finally {active.get(t.getId()).decrementAndGet();}
        };
        dispatcher.publishPendingEvents();consumer.consumeNewMessages();finishConsumer(a);finishConsumer(b);
        for(var t:List.of(a,b)) {
            assertThat(order.get(t.getId())).containsExactly(0,0,1,1);assertThat(maximum.get(t.getId()).get()).isEqualTo(1);
            assertThat(agentsFor(t.getId())).extracting(ReviewAgentEntity::getModelCalls).containsExactly(2,2);
            String body=json.readTree(runs.selectById(t.getId()).getPublicationPayloadJson()).get("body").asText();
            assertThat(body).contains(t.getHeadSha()).doesNotContain(t==a?b.getHeadSha():a.getHeadSha());
        }
        assertThat(model.plannerCalls.get()).isEqualTo(2);assertThat(model.reviewCalls.get()).isEqualTo(8);assertThat(pendingCount()).isZero();
        verify(publisher,times(2)).publish(anyString(),anyInt(),anyString(),anyString(),anyString());
    }
    @Test void multiAgentConsumerOutagePreservesPlanBudgetAndPendingMessage() throws Exception {
        useSynthetic(5);var task=task("PENDING");String plan=planJson(5,3);java.util.concurrent.atomic.AtomicBoolean broken=new java.util.concurrent.atomic.AtomicBoolean(true);
        model.routed=p->{
            if(ScriptedChatModel.isPlanning(p))return textResponse(plan);
            int i=currentIndex(p,task.getId());
            if(i==1 && broken.get())throw new IllegalStateException("temporary group outage");
            return response(tool("finish","publish_review","{}"));
        };
        enqueue(task);await(()->reviewWorkerExecutor.getActiveCount()==0 && model.reviewCalls.get()==2);
        assertThat(pendingCount()).isEqualTo(1);assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(agentsFor(task.getId())).extracting(ReviewAgentEntity::getSuccess).containsExactly(true,null,null);
        assertThat(agentsFor(task.getId())).extracting(ReviewAgentEntity::getModelCalls).containsExactly(1,1,0);verifyNoInteractions(publisher);
        broken.set(false);runtime.run(task.getId()); // Explicit recovery, deliberately does not ACK the original PEL entry.
        assertThat(model.plannerCalls.get()).isEqualTo(1);assertThat(agentsFor(task.getId())).extracting(ReviewAgentEntity::getModelCalls).containsExactly(1,2,1);
        assertThat(runs.selectById(task.getId()).getStatus()).isEqualTo("PUBLISHED");assertThat(pendingCount()).isEqualTo(1);
    }

    @Test void failureObserverRecordsBudgetCorrelationAndPendingWithoutContent() throws Exception {
        var task=task("PENDING");String sentinel="PRIVATE_OBSERVER_SENTINEL_76d2";
        model.steps.add(p->textResponse(sentinel));
        model.steps.add(p->{throw new IllegalStateException("temporary provider outage");});
        enqueue(task);await(()->reviewWorkerExecutor.getActiveCount()==0 && model.reviewCalls.get()==2);
        assertThat(pendingCount()).isEqualTo(1);assertThat(onlyAgent(task.getId()).getModelCalls()).isEqualTo(2);
        event("stream.pending taskId="+task.getId()+" agentId="+onlyAgent(task.getId()).getId()+" pending="+pendingCount());
        var lines=Files.readAllLines(Path.of("target/e2e-logs/intermediate.log")).stream().filter(l->l.contains("scenario="+scenario+" ")).toList();
        String events=String.join("\n",lines);
        assertThat(events).contains("taskId="+task.getId(),"agentId="+onlyAgent(task.getId()).getId(),"agentBudget=2","errorType=IllegalStateException","stream.pending","pending=1","promptSha256=");
        assertThat(events.contains(sentinel)||events.contains("e2e-secret")||events.contains("package org.keycloak")).isFalse();
    }

    @Test void offlineContextContainsOnlyScriptedModel() {
        assertThat(applicationContext.getBeansOfType(ChatModel.class)).hasSize(1).containsValue(model);
        assertThat(env.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(applicationContext.getBeanNamesForType(org.springframework.ai.openai.OpenAiChatModel.class)).isEmpty();
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).as("bounded wait for task completion and Redis ACK").isTrue();
    }

    @Configuration
    @EnableAutoConfiguration(excludeName = {"org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration","org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration","org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration","org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration","org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration","org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration"})
    @MapperScan("com.guodi.pragent.persistence")
    @Import({ReviewReActRuntime.class, ReviewHarness.class, ReviewRunRestorer.class, ReviewContextBuilder.class,
            ToolRoundCoordinator.class, ToolRoundStore.class, ToolRoundExecutor.class, ToolExecutor.class,
            ReviewAgentStore.class, ReviewPlanGenerator.class, ReviewToolConfig.class, ReviewReadTools.class, FindingWriteTools.class, ReviewTerminalTools.class,
            ReadToolExecutorConfig.class, GitHubWebhookController.class, GitHubWebhookService.class,
            ReviewOutboxPublisher.class, ReviewStreamConsumer.class})
    static class Config {
        @Bean @Primary ScriptedChatModel scriptedModel() { return new ScriptedChatModel(); }
        @Bean GitHubWorkspacePreparer workspacePreparer() { return mock(GitHubWorkspacePreparer.class); }
        @Bean GitHubReviewLookup reviewLookup() { return mock(GitHubReviewLookup.class); }
        @Bean GitHubReviewPublisher reviewPublisher() { return mock(GitHubReviewPublisher.class); }
        // Same bounded production executor, without enabling background schedulers in tests.
        @Bean(destroyMethod = "shutdown") ThreadPoolExecutor reviewWorkerExecutor() {
            return new ReviewWorkerConfig().reviewWorkerExecutor(2, 8);
        }
    }
}
