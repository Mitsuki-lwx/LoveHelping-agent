package cn.lwx.lwxaiagent.harness.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
public class GuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    private final InputGuardrail inputGuardrail;
    private final OutputGuardrail outputGuardrail;

    public GuardrailAdvisor(InputGuardrail inputGuardrail, OutputGuardrail outputGuardrail) {
        this.inputGuardrail = inputGuardrail;
        this.outputGuardrail = outputGuardrail;
    }

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = getUserText(request);
        GuardrailResult inputCheck = inputGuardrail.check(userText);
        if (inputCheck.blocked()) {
            if (inputCheck.vague()) {
                log.info("Input vague → asking clarification: {}", truncate(userText));
            } else {
                log.warn("Input blocked ({}): {}", inputCheck.reason(), truncate(userText));
            }
            return fallbackResponse(inputCheck.fallback());
        }

        ChatClientResponse response = chain.nextCall(request);

        String outputText = getOutputText(response);
        GuardrailResult outputCheck = outputGuardrail.check(outputText, userText);
        if (outputCheck.blocked()) {
            log.warn("Output guardrail: {}", outputCheck.reason());
        }

        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = getUserText(request);
        GuardrailResult inputCheck = inputGuardrail.check(userText);
        if (inputCheck.blocked()) {
            if (inputCheck.vague()) {
                log.info("Input vague (stream) → asking clarification: {}", truncate(userText));
            } else {
                log.warn("Input blocked (stream) ({}): {}", inputCheck.reason(), truncate(userText));
            }
            return Flux.just(fallbackResponse(inputCheck.fallback()));
        }

        Flux<ChatClientResponse> responses = chain.nextStream(request);

        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(responses, aggregated -> {
                    String outputText = getOutputText(aggregated);
                    GuardrailResult outputCheck = outputGuardrail.check(outputText, userText);
                    if (outputCheck.blocked()) {
                        log.warn("Output guardrail (stream): {}", outputCheck.reason());
                    }
                });
    }

    private String getUserText(ChatClientRequest request) {
        try {
            return request.prompt().getUserMessage().getText();
        } catch (Exception e) {
            return "";
        }
    }

    private String getOutputText(ChatClientResponse response) {
        try {
            return response.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            return "";
        }
    }

    private String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    private ChatClientResponse fallbackResponse(String text) {
        ChatResponse chatResponse = new ChatResponse(
                java.util.List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(chatResponse, java.util.Map.of());
    }
}
