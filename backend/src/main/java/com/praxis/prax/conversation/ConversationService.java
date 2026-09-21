package com.praxis.prax.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.praxis.config.AppProperties;
import com.praxis.prax.conversation.domain.Conversation;
import com.praxis.prax.conversation.domain.ConversationMessage;
import com.praxis.prax.conversation.domain.ConversationMessage.Role;
import com.praxis.prax.conversation.repository.ConversationMessageRepository;
import com.praxis.prax.conversation.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversations, scoped and persisted.
 *
 * Replaces the single global in-memory history list. Two things that list got
 * wrong and this fixes:
 *
 *   1. SCOPE — every browser tab shared one history, so asking a question in
 *      one silently changed the answer given in another.
 *   2. LIFETIME — a restart lost the thread mid-conversation.
 *
 * The prompt window is still bounded (MAX_CONTEXT_MESSAGES). Persistence is
 * about what the PLAYER can read back; the model still only sees the recent tail,
 * because the context is 4096 tokens and always will be.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /**
     * How much of a thread the MODEL sees. Six exchanges.
     *
     * Separate from how much is stored, and deliberately small: everything here
     * competes with the system prompt, the tool schemas and the answer inside a
     * 4096-token window.
     */
    static final int MAX_CONTEXT_MESSAGES = 12;

    /** How many threads the picker lists. */
    static final int MAX_LISTED = 30;

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public ConversationService(ConversationRepository conversations,
                               ConversationMessageRepository messages,
                               AppProperties props, ObjectMapper mapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.props = props;
        this.mapper = mapper;
    }

    private String user() {
        return props.chessCom().username();
    }

    @Transactional
    public Conversation start(String firstQuestion) {
        Conversation c = Conversation.builder()
                .username(user())
                .title(Conversation.titleFrom(firstQuestion))
                .lastMessageAt(OffsetDateTime.now())
                .build();
        return conversations.save(c);
    }

    public Optional<Conversation> find(UUID id) {
        return conversations.findById(id);
    }

    public List<Conversation> list() {
        return conversations.findByUsernameOrderByLastMessageAtDesc(
                user(), PageRequest.of(0, MAX_LISTED));
    }

    public List<ConversationMessage> messagesOf(UUID conversationId) {
        return messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * The tail of the thread, in Ollama's message shape.
     *
     * Only `content` is replayed — never the evidence, sources or artifacts. The
     * model must re-derive every figure from tools on each turn; letting it read
     * back a previous answer's numbers is exactly how uncited statistics reached
     * the player before the grounding layer existed.
     */
    public List<Map<String, Object>> contextFor(UUID conversationId) {
        if (conversationId == null) return List.of();
        List<ConversationMessage> all = messagesOf(conversationId);
        int from = Math.max(0, all.size() - MAX_CONTEXT_MESSAGES);

        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationMessage m : all.subList(from, all.size())) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            out.add(Map.of(
                    "role", m.getRole() == Role.USER ? "user" : "assistant",
                    "content", m.getContent()));
        }
        return out;
    }

    /**
     * Records one complete exchange.
     *
     * Written AFTER the final verdict, never per attempt — an escalated question
     * runs twice, and the suppressed first answer must not survive to be read
     * back as context.
     */
    @Transactional
    public void record(UUID conversationId, String question, String answer, Object payload) {
        OffsetDateTime now = OffsetDateTime.now();

        messages.save(ConversationMessage.builder()
                .conversationId(conversationId).role(Role.USER)
                .content(question).createdAt(now).build());

        messages.save(ConversationMessage.builder()
                .conversationId(conversationId).role(Role.ASSISTANT)
                .content(answer).payloadJson(toJson(payload))
                // A microsecond later, so ordering by timestamp cannot put the
                // answer before the question it answers.
                .createdAt(now.plusNanos(1000)).build());

        conversations.findById(conversationId).ifPresent(c -> {
            c.setLastMessageAt(now);
            if (c.getTitle() == null || c.getTitle().isBlank()) {
                c.setTitle(Conversation.titleFrom(question));
            }
            conversations.save(c);
        });
    }

    @Transactional
    public void delete(UUID conversationId) {
        messages.deleteByConversationId(conversationId);
        conversations.deleteById(conversationId);
    }

    /** The stored answer payload, for replaying a thread into the UI. */
    public Map<String, Object> payloadOf(ConversationMessage m) {
        if (m.getPayloadJson() == null || m.getPayloadJson().isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(m.getPayloadJson(), LinkedHashMap.class);
            return parsed;
        } catch (Exception e) {
            // A payload written by an older version must not break the history view.
            log.debug("[prax] could not read stored payload: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            log.debug("[prax] could not store answer payload: {}", e.getMessage());
            return null;
        }
    }
}
