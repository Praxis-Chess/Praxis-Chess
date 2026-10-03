package com.praxis.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cut-off answer gets one more try with twice the room, on any provider. A
 * limit that is too tight shows in the log, not as a silently dropped answer.
 */
@DisplayName("Retry on a cut-off answer")
class RetryOnCutOffTest {

    /** Cut off on the first `cutOffs` calls; records every request. */
    private static final class FakeProvider implements LlmProvider {
        final List<Integer> scales = new ArrayList<>();
        private final int cutOffs;

        FakeProvider(int cutOffs) {
            this.cutOffs = cutOffs;
        }

        @Override public String id() { return "fake"; }
        @Override public String host() { return "test"; }
        @Override public int maxTokens(OutputBudget budget) { return 100; }
        @Override public Health health() { return Health.up(); }

        @Override
        public ChatResult chat(ChatRequest request) {
            scales.add(request.scale());
            return new ChatResult("{\"answer\":\"" + request.scale() + "\"}", List.of(), scales.size() <= cutOffs);
        }

        @Override
        public Completion complete(CompletionRequest request) {
            scales.add(request.scale());
            return new Completion("{\"scale\":" + request.scale() + "}", scales.size() <= cutOffs);
        }
    }

    private static CompletionRequest completion() {
        return CompletionRequest.standard("m", "p", OutputBudget.EXPLANATION);
    }

    private static ChatRequest chat() {
        return new ChatRequest("m", List.of(ChatMessage.user("hi")), List.of(), true,
                OutputBudget.CHAT_ANSWER, 1, 0.3, 1.18, 4096);
    }

    @Test
    @DisplayName("an answer that fits is returned as is, with no second call")
    void noCutOff() {
        var p = new FakeProvider(0);
        assertThat(p.completeWithRetry(completion())).isEqualTo("{\"scale\":1}");
        assertThat(p.scales).containsExactly(1);
    }

    @Test
    @DisplayName("a cut-off completion is asked again with twice the room")
    void retriesOnce() {
        var p = new FakeProvider(1);
        assertThat(p.completeWithRetry(completion())).isEqualTo("{\"scale\":2}");
        assertThat(p.scales).containsExactly(1, 2);
    }

    @Test
    @DisplayName("cut off twice: an error naming the budget, so the caller's fallback runs with a reason")
    void cutOffTwice() {
        var p = new FakeProvider(2);
        assertThatThrownBy(() -> p.completeWithRetry(completion()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explanation")
                .hasMessageContaining("200 tokens");
        assertThat(p.scales).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a cut-off chat turn is retried once; a second cut-off is returned for the agent to handle")
    void chatRetry() {
        var once = new FakeProvider(1);
        assertThat(once.chatWithRetry(chat()).truncated()).isFalse();
        assertThat(once.scales).containsExactly(1, 2);

        var twice = new FakeProvider(2);
        assertThat(twice.chatWithRetry(chat()).truncated()).isTrue();
        assertThat(twice.scales).containsExactly(1, 2);
    }
}
