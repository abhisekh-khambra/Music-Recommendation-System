package com.musicrec.dto;

import com.musicrec.entity.Track;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Lightweight projection of Track — used in API responses and LLM reranking payload */
@Data
@NoArgsConstructor
public class TrackDTO {

    private Long id;
    private String title;
    private String artist;
    private String album;
    private String genre;
    private String language;
    private Integer tempoBpm;
    private BigDecimal energyLevel;
    private BigDecimal valence;
    private String primaryEmotion;
    private List<String> secondaryEmotions;
    private List<String> moodTags;
    private String albumArtUrl;
    private String previewUrl;
    private Integer durationMs;
    private Integer popularity;

    /** Set by MusicRecommendationService after reranking */
    private String explanation;

    public static TrackDTO from(Track track) {
        TrackDTO dto = new TrackDTO();
        dto.setId(track.getId());
        dto.setTitle(track.getTitle());
        dto.setArtist(track.getArtist());
        dto.setAlbum(track.getAlbum());
        dto.setGenre(track.getGenre());
        dto.setLanguage(track.getLanguage());
        dto.setTempoBpm(track.getTempoBpm());
        dto.setEnergyLevel(track.getEnergyLevel());
        dto.setValence(track.getValence());
        dto.setPrimaryEmotion(track.getPrimaryEmotion());
        dto.setSecondaryEmotions(track.getSecondaryEmotions());
        dto.setMoodTags(track.getMoodTags());
        dto.setAlbumArtUrl(track.getAlbumArtUrl());
        dto.setPreviewUrl(track.getPreviewUrl());
        dto.setDurationMs(track.getDurationMs());
        dto.setPopularity(track.getPopularity());
        return dto;
    }
}
