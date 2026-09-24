package com.musicrec.repository;

import com.musicrec.entity.ChatHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatHistoryRepository extends JpaRepository<ChatHistory, Long> {

    List<ChatHistory> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ChatHistory> findByUser_IdOrderByCreatedAtDesc(Long userId);
}
