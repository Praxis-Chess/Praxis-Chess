package com.praxis.prax.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.ai.*;
import com.praxis.ai.ollama.OllamaProvider;
import com.praxis.ai.openai.OpenAiCompatibleProvider;
import com.praxis.config.AiProperties;
import com.praxis.config.AppProperties;
import com.praxis.prax.routing.QuestionRouter;
import com.praxis.prax.tools.ToolRegistry;
import com.praxis.prax.tools.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The agent speaks only the provider-neutral types, through the router. One full
 * tool turn against a fake provider: what the provider is asked, and that the
 * agent's own callId pairs the tool call with its result.
 */
@DisplayName("Prax agent over an LlmProvider")
class PraxAgentProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AppProperties LOCAL = new AppProperties(
            new AppProperties.Ollama("http://localhost:11434", "qwen2.5:7b", null, null, "qwen3:4b-instruct", null),
            null, null, null, null, null);

    @Test
    @DisplayName("a tool call and its result share the agent's callId, and the answer is grounded in it")
    void oneToolTurn() {
        List<ChatRequest> seen = new ArrayList<>();
        OllamaProvider fake = new OllamaProvider(LOCAL, MAPPER) {
            private int turn;

            @Override
            public ChatResult chat(ChatRequest req) {
                seen.add(req);
                return turn++ == 0
                        ? new ChatResult("", List.of(new ToolCall("r1", "find_games", Map.of("limit", 3))))
                        : new ChatResult("{\"answer\":\"You played 3 games.\",\"evidence\":["
                                + "{\"label\":\"Games\",\"value\":\"3\",\"callId\":\"tc_1\"}],\"followUp\":null}", List.of());
            }
        };
        var none = new AiProperties(null, null);
        var router = new ModelRouter(LOCAL, none, fake, new OpenAiCompatibleProvider(none, MAPPER));

        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.schemasFor(any())).thenReturn(List.of(new ToolSpec("find_games", "Search games.", Map.of())));
        when(tools.execute(eq("find_games"), any())).thenReturn(ToolResult.playerData("find_games", List.of(1, 2, 3), 3));
        when(tools.followUp(any(), any())).thenReturn(Optional.empty());

        var out = new PraxAgent(router, tools, MAPPER)
                .run("how many games did I play?", List.of(), QuestionRouter.Lane.PLAYER);

        // First turn: tools offered, on Prax's local model, with tool-turn headroom.
        assertThat(seen.get(0).model()).isEqualTo("qwen3:4b-instruct");
        assertThat(seen.get(0).tools()).extracting(ToolSpec::name).containsExactly("find_games");
        assertThat(seen.get(0).budget()).isEqualTo(OutputBudget.TOOL_TURN);
        assertThat(seen.get(1).budget()).isEqualTo(OutputBudget.TOOL_TURN);   // tools still offered
        assertThat(seen.get(0).jsonAnswer()).isFalse();

        // Second turn: the call, then its result, paired by the agent's callId.
        List<ChatMessage> msgs = seen.get(1).messages();
        ChatMessage call = msgs.get(msgs.size() - 2);
        ChatMessage result = msgs.get(msgs.size() - 1);
        assertThat(call.role()).isEqualTo(ChatMessage.Role.ASSISTANT);
        assertThat(call.toolCalls()).containsExactly(new ToolCall("tc_1", "find_games", Map.of("limit", 3)));
        assertThat(result.role()).isEqualTo(ChatMessage.Role.TOOL);
        assertThat(result.toolCallId()).isEqualTo("tc_1");
        assertThat(result.content()).contains("\"callId\":\"tc_1\"");

        assertThat(out.trustedCalls()).isEqualTo(1);
        assertThat(out.answer()).isEqualTo("You played 3 games.");
        assertThat(out.evidence()).hasSize(1);
    }
}
