package com.praxis.prax.conversation.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One message in a conversation.
 *
 * The assistant's row carries the whole answer payload — evidence, sources,
 * artifacts, steps — as JSON, so reopening a conversation shows what the player
 * actually saw rather than a bare paragraph.
 *
 * Stored as JSON text rather than as child tables because it is an opaque
 * snapshot, only ever read back whole, and nothing queries inside it. Child
 * tables here would be structure for its own sake, and would drift from the
 * artifact vocabulary every time it grows.
 *
 * NOTE what is deliberately NOT stored: the model's ungrounded first attempt on
 * an escalated question. Only the final, shipped answer is written — recording a
 * suppressed fabrication would let it be read back as context on the next turn.
 */
@Entity
@Table(name = "prax_message", indexes = {
        @Index(name = "idx_prax_message_conversation", columnList = "conversation_id, created_at")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ConversationMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** The full Answer payload for an assistant message. Null for a user message. */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
