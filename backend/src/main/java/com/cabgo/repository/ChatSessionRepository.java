package com.cabgo.repository;

import com.cabgo.enums.ConversationState;
import com.cabgo.model.ChatSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends MongoRepository<ChatSession, String> {
    Optional<ChatSession> findByWhatsappPhone(String whatsappPhone);
    List<ChatSession> findByState(ConversationState state);
    long countByState(ConversationState state);
    Optional<ChatSession> findByPendingCashfreeOrderId(String pendingCashfreeOrderId);
}
