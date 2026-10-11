package com.musicrec.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String CONVERSATION_SYSTEM_PROMPT = """
            You are an expert conversation-transcript analyst. You segment a transcribed
            two-person spoken conversation into individual speaker turns, in original order,
            and label each turn's role. You never diagnose, and you never add clinical or
            medical claims — you only segment and label who is speaking.
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

    /** Used by ConversationAnalysisService for speaker turn segmentation */
    @Bean("conversationChatClient")
    public ChatClient conversationChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(CONVERSATION_SYSTEM_PROMPT)
                .build();
    }

    /**
     * Low-level audio API client, used only by SpeechToTextService to request
     * verbose_json transcription (real per-segment timestamps from Whisper) —
     * a capability the higher-level OpenAiAudioTranscriptionModel bean doesn't
     * expose. Reuses the same Groq base-url/api-key as the auto-configured
     * transcription model; no new credentials or dependency.
     */
    @Bean
    public OpenAiAudioApi openAiAudioApi(
            @Value("${spring.ai.openai.audio.transcription.base-url}") String baseUrl,
            @Value("${spring.ai.openai.audio.transcription.api-key}") String apiKey) {
        return OpenAiAudioApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }
}
