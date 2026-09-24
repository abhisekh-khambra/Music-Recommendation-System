package com.musicrec.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.musicrec.util.LongListConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "chat_history")
@Data
@NoArgsConstructor
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore                          // prevent LazyInitializationException during serialization
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "detected_emotion")
    private String detectedEmotion;

    @Column(name = "emotion_intensity", precision = 3, scale = 2)
    private BigDecimal emotionIntensity;

    @Column(name = "assistant_response", columnDefinition = "TEXT")
    private String assistantResponse;

    @Convert(converter = LongListConverter.class)
    @Column(name = "recommended_track_ids", columnDefinition = "JSON")
    private List<Long> recommendedTrackIds;

    @Column(name = "input_type")
    @Enumerated(EnumType.STRING)
    private InputType inputType;

    private String language;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum InputType {
        text, voice
    }
}
