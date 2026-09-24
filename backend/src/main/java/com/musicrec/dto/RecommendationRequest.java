package com.musicrec.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RecommendationRequest {

    /** The user's text message (in Hindi or English) */
    @NotBlank(message = "Message must not be empty")
    @Size(max = 2000, message = "Message too long — max 2000 characters")
    private String message;

    /** Client-generated or server-provided session identifier */
    private String sessionId;

    /** Optional userId — if absent, defaults to anonymous */
    private Long userId;
}
