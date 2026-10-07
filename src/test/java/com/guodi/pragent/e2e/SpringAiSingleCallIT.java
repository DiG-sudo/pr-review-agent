package com.guodi.pragent.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/** Explicit real API smoke: run only with -Dtest=SpringAiSingleCallIT. */
class SpringAiSingleCallIT {
    @Test
    @Timeout(90)
    void oneConversationUsingApplicationConfiguration() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> env.getPropertySources().addLast(source));
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(60000);
        var requests = new AtomicInteger();
        var client = RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            if (requests.incrementAndGet() != 1) throw new IllegalStateException("Only one HTTP request allowed");
            System.out.println("Spring AI request: " + request.getMethod() + " "
                    + request.getURI().getHost() + request.getURI().getPath());
            return execution.execute(request, body);
        });
        var api = OpenAiApi.builder()
                .baseUrl(env.getRequiredProperty("spring.ai.openai.base-url"))
                .apiKey(env.getRequiredProperty("spring.ai.openai.api-key"))
                .completionsPath(env.getProperty("spring.ai.openai.chat.completions-path", "/v1/chat/completions"))
                .restClientBuilder(client).build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(env.getRequiredProperty("spring.ai.openai.chat.options.model"))
                        .maxTokens(128).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
        long start = System.nanoTime();
        try {
            String reply = model.call("仅回复 OK。");
            System.out.printf("Spring AI reply: %s; durationMs=%d%n", reply, (System.nanoTime() - start) / 1_000_000);
            assertThat(reply).isNotBlank();
        } finally {
            System.out.println("Spring AI HTTP requests: " + requests.get());
            assertThat(requests.get()).isEqualTo(1);
        }
    }
}
