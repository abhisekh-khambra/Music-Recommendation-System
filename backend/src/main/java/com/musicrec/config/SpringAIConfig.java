package com.musicrec.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring AI bean configuration.
 *
 * Two named ChatClient beans are provided so that EmotionAnalysisService and
 * MusicRecommendationService each carry a dedicated system prompt — keeping the
 * LLM roles clearly separated without sharing state.
 */
@Configuration
public class SpringAIConfig {

    private static final String EMOTION_SYSTEM_PROMPT = """
            You are an expert multilingual emotion analyst specializing in human psychology.
            Your task is to analyze text written in Hindi or English and extract a precise emotional profile.
            You MUST respond with ONLY a valid JSON object — no markdown fences, no extra text.
            The JSON keys must exactly match the schema given in the user prompt.
            """;

    private static final String MUSIC_RERANK_SYSTEM_PROMPT = """
            You are an expert music therapist and recommendation specialist.
            You understand how music affects human emotions and mental states.
            Given a user's emotional profile and a list of candidate tracks, you select
            the most therapeutically and emotionally appropriate songs.
            You MUST respond with ONLY a valid JSON object — no markdown fences, no extra text.
            """;

    /** Used by EmotionAnalysisService */
    @Bean("emotionChatClient")
    @Primary
    public ChatClient emotionChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(EMOTION_SYSTEM_PROMPT)
                .build();
    }

    /** Used by MusicRecommendationService */
    @Bean("rerankerChatClient")
    public ChatClient rerankerChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(MUSIC_RERANK_SYSTEM_PROMPT)
                .build();
    }
}
