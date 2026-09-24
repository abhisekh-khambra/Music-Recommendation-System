package com.musicrec.repository;

import com.musicrec.entity.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackRepository extends JpaRepository<Track, Long> {

    /** Full catalogue sorted by popularity — used by the catalog API */
    List<Track> findAllByOrderByPopularityDesc();

    /** Filter catalogue by language only */
    List<Track> findByLanguageIgnoreCaseOrderByPopularityDesc(String language);

    /** Candidate retrieval Step-1: exact primary emotion match */
    List<Track> findByPrimaryEmotionIgnoreCaseOrderByPopularityDesc(String emotion);

    /** Candidate retrieval Step-2: language filter with primary emotion */
    List<Track> findByPrimaryEmotionIgnoreCaseAndLanguageIgnoreCaseOrderByPopularityDesc(
            String emotion, String language);

    /** Step-3: secondary emotion contained in JSON array (native query) */
    @Query(value = """
            SELECT * FROM tracks
            WHERE JSON_CONTAINS(secondary_emotions, JSON_QUOTE(:emotion))
            ORDER BY popularity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Track> findBySecondaryEmotionContains(@Param("emotion") String emotion,
                                               @Param("limit") int limit);

    /** Step-4: mood tag keyword search across mood_tags JSON array */
    @Query(value = """
            SELECT * FROM tracks
            WHERE JSON_CONTAINS(mood_tags, JSON_QUOTE(:tag))
            ORDER BY popularity DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Track> findByMoodTagContains(@Param("tag") String tag,
                                      @Param("limit") int limit);

    /** Fallback: valence range + energy range for emotion approximation */
    @Query("""
            SELECT t FROM Track t
            WHERE t.valence BETWEEN :minValence AND :maxValence
              AND t.energyLevel BETWEEN :minEnergy AND :maxEnergy
            ORDER BY t.popularity DESC
            """)
    List<Track> findByValenceAndEnergy(@Param("minValence") double minValence,
                                       @Param("maxValence") double maxValence,
                                       @Param("minEnergy") double minEnergy,
                                       @Param("maxEnergy") double maxEnergy);

    /** Fetch by IDs preserving order (used after reranking) */
    @Query("SELECT t FROM Track t WHERE t.id IN :ids ORDER BY t.popularity DESC")
    List<Track> findAllByIdIn(@Param("ids") List<Long> ids);
}
