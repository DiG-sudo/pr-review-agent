package com.guodi.pragent.runtime;

import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import org.springframework.stereotype.Component;

import com.guodi.pragent.harness.ReviewHarness;

/** 外部执行入口和核心 ReAct 循环；构造时装配 Harness 插入点。 */
@Component
public class ReviewReActRuntime {

    private final Function<Prompt, ChatResponse> modelHandler;
    private final Function<ReviewExecution, ReviewRunResult> reasonHandler;
    private final Function<Long, ReviewRunResult> runHandler;

    public ReviewReActRuntime(ReviewHarness reviewHarness, ChatModel chatModel) {
        this.modelHandler = wrapModelCall(reviewHarness, chatModel);
        this.reasonHandler = wrapReasonCall(reviewHarness, modelHandler);
        this.runHandler = wrapRunCall(reviewHarness, this::runLoop);

        
        this.toolHandler = wrapTool(reviewHarness, this::runLoop);
    }

    /** 消费者只传数据库任务 ID；任务准备和恢复由 Harness 负责。 */
    public ReviewRunResult run(Long taskId) {
        return runHandler.apply(taskId);
    }

    private Function<Prompt, ChatResponse> wrapModelCall(ReviewHarness reviewHarness, ChatModel chatModel) {
        return prompt -> reviewHarness.aroundModelCall(prompt, chatModel::call);
    }

    private Function<ReviewExecution, ReviewRunResult> wrapReasonCall(ReviewHarness reviewHarness, Function<Prompt, ChatResponse> next) {
        return execution -> reviewHarness.aroundReasoning(execution, next);
    }

    private Function<Long, ReviewRunResult> wrapRunCall(ReviewHarness reviewHarness, Function<ReviewExecution, ReviewRunResult> next) {
        return taskId -> reviewHarness.aroundRun(taskId, next);
    }
    private Function<Long, ReviewRunResult> wrapTool(ReviewHarness reviewHarness, Function<ReviewExecution, ReviewRunResult> next) {
        reviewHarness.aroundToolRound(null, null)
    }

    private ReviewRunResult runLoop(ReviewExecution execution) {
       
        while (true) {
            ReviewRunResult result = reasonHandler.apply(execution);
            if (result.status() != ReviewStatus.RUNNING) {
                return result;
            }
            AssistantMessage assistantMessage = result.response().getResult().getOutput();
            //由于ReviewStatus.RUNNING,response1.无工具调用,2.有工具调用不包含publish,3.有工具调用包含publish
            if(!assistantMessage.hasToolCalls()){
                //继续循环并且添加消息
                execution.getHistory().add(new Message("尚未发布评论,,,"));
                break;
            }
            
            //此时包含工具调用,交给aroundTool处理
           
        }
    }
       
    
}
