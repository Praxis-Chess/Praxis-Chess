package com.praxis.prax.conversation.repository;

import com.praxis.prax.conversation.domain.Conversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Most recently used first — how anyone looks for a conversation they had. */
    List<Conversation> findByUsernameOrderByLastMessageAtDesc(String username, Pageable pageable);

    long countByUsername(String username);
}
