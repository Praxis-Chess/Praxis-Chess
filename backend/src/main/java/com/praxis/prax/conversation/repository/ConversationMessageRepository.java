package com.praxis.prax.conversation.repository;

import com.praxis.prax.conversation.domain.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ConversationMessage m WHERE m.conversationId = :id")
    void deleteByConversationId(@Param("id") UUID id);
}
