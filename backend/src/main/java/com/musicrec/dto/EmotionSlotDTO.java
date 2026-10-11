package com.musicrec.dto;

/**
 * One emotion's share of the Top-N playlist (EmotionQuotaAllocator): the patient's share of
 * responses showing that emotion, how many of the N slots that share earns, how many of those
 * slots could actually be filled with a song matching that emotion, and the raga chosen for that
 * share (RagaSelectionService.ragasPerEmotion; null when no raga for it has Hindi tracks).
 * filledSlots below targetSlots means the catalogue had too few eligible songs for that emotion —
 * the gap is filled with the next-best general matches and reported, never hidden.
 */
public record EmotionSlotDTO(String emotion, double sharePercent, int targetSlots, int filledSlots, String raga) {

    public EmotionSlotDTO(String emotion, double sharePercent, int targetSlots, int filledSlots) {
        this(emotion, sharePercent, targetSlots, filledSlots, null);
    }
}
