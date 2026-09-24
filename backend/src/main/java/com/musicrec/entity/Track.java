package com.musicrec.entity;

import com.musicrec.util.StringListConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "tracks")
@Data
@NoArgsConstructor
public class Track {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String artist;

    private String album;
    private String genre;

    @Column(nullable = false)
    private String language;

    @Column(name = "tempo_bpm")
    private Integer tempoBpm;

    @Column(name = "energy_level", precision = 3, scale = 2)
    private BigDecimal energyLevel;

    /** 0.0 = very negative/sad, 1.0 = very positive/happy */
    @Column(precision = 3, scale = 2)
    private BigDecimal valence;

    @Column(name = "primary_emotion", nullable = false)
    private String primaryEmotion;

    /** Stored as JSON array in MySQL, auto-converted */
    @Convert(converter = StringListConverter.class)
    @Column(name = "secondary_emotions", columnDefinition = "JSON")
    private List<String> secondaryEmotions;

    @Convert(converter = StringListConverter.class)
    @Column(name = "mood_tags", columnDefinition = "JSON")
    private List<String> moodTags;

    @Column(name = "spotify_id")
    private String spotifyId;

    @Column(name = "preview_url", length = 500)
    private String previewUrl;

    @Column(name = "album_art_url", length = 500)
    private String albumArtUrl;

    @Column(name = "duration_ms")
    private Integer durationMs;

    private Integer popularity;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
