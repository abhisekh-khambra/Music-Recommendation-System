package com.musicrec.service;

import com.musicrec.dto.EmotionSlotDTO;
import com.musicrec.dto.TrackDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Top-10 must be split across the Patient's emotions by their share of the responses
 * (meeting requirement: "according to the percentage of emotional variability").
 */
class EmotionQuotaAllocatorTest {

    private long nextId = 1;

    private TrackDTO track(String primary, String... secondary) {
        TrackDTO t = new TrackDTO();
        t.setId(nextId++);
        t.setPrimaryEmotion(primary);
        t.setSecondaryEmotions(List.of(secondary));
        t.setLanguage("Hindi");
        return t;
    }

    private List<TrackDTO> tracks(int n, String primary) {
        List<TrackDTO> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(track(primary));
        return out;
    }

    private Map<String, Double> dist(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        return m;
    }

    @Test
    void slotsFollowTheShareOfResponses() {
        Map<String, Integer> slots = EmotionQuotaAllocator.slotTargets(
                Map.of("SADNESS", 0.5, "NOSTALGIA", 0.3, "CALM", 0.2), 10);
        assertThat(slots).containsExactly(Map.entry("SADNESS", 5), Map.entry("NOSTALGIA", 3), Map.entry("CALM", 2));
    }

    @Test
    void slotsAlwaysSumToTopNUsingLargestRemainder() {
        // 33.3 / 33.3 / 33.3 → 3.33 each: floors give 9, the leftover slot goes to one of them.
        Map<String, Integer> slots = EmotionQuotaAllocator.slotTargets(
                dist("SADNESS", 1 / 3.0, "STRESS", 1 / 3.0, "CALM", 1 / 3.0), 10);
        assertThat(slots.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10);
        assertThat(slots.values()).allMatch(n -> n == 3 || n == 4);
    }

    @Test
    void playlistIsSplitByShareWithDominantEmotionFirst() {
        // Preference order deliberately puts the minority emotion first, as the LLM might.
        List<TrackDTO> candidates = new ArrayList<>();
        candidates.addAll(tracks(6, "JOY"));
        candidates.addAll(tracks(8, "SADNESS"));

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("SADNESS", 70.0, "JOY", 30.0), 10);

        assertThat(a.tracks()).hasSize(10);
        assertThat(a.tracks()).extracting(TrackDTO::getPrimaryEmotion)
                .containsExactly("SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS",
                        "JOY", "JOY", "JOY");
        assertThat(a.tracks()).extracting(TrackDTO::getMatchedEmotion)
                .containsExactly("SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS", "SADNESS",
                        "JOY", "JOY", "JOY");
        assertThat(a.slots()).containsExactly(
                new EmotionSlotDTO("SADNESS", 70.0, 7, 7),
                new EmotionSlotDTO("JOY", 30.0, 3, 3));
    }

    @Test
    void primaryMatchesAreNotUsedUpAsAnotherEmotionsSecondaryMatch() {
        // This NOSTALGIA track also lists SADNESS as secondary; SADNESS is listed first, but the
        // track must go to NOSTALGIA, the only share it can fill as a primary match.
        TrackDTO nostalgic = track("NOSTALGIA", "SADNESS");
        List<TrackDTO> candidates = new ArrayList<>(List.of(nostalgic));
        candidates.addAll(tracks(1, "SADNESS"));

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("SADNESS", 50.0, "NOSTALGIA", 50.0), 2);

        assertThat(nostalgic.getMatchedEmotion()).isEqualTo("NOSTALGIA");
        assertThat(a.slots()).allMatch(s -> s.filledSlots() == s.targetSlots());
    }

    @Test
    void secondaryMatchFillsAShareWhenNoPrimaryMatchExists() {
        TrackDTO calmish = track("RELAXATION", "CALM");
        List<TrackDTO> candidates = new ArrayList<>(tracks(1, "SADNESS"));
        candidates.add(calmish);

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("SADNESS", 50.0, "CALM", 50.0), 2);

        assertThat(calmish.getMatchedEmotion()).isEqualTo("CALM");
        assertThat(a.slots()).allMatch(s -> s.filledSlots() == s.targetSlots());
    }

    @Test
    void shortfallIsReportedAndFilledWithBestRemainingCandidates() {
        // STRESS should get 5 slots, but only 2 STRESS songs exist.
        List<TrackDTO> candidates = new ArrayList<>();
        candidates.addAll(tracks(2, "STRESS"));
        candidates.addAll(tracks(10, "SADNESS"));
        candidates.addAll(tracks(3, "LOVE"));

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("STRESS", 50.0, "SADNESS", 50.0), 10);

        assertThat(a.tracks()).hasSize(10);
        assertThat(a.slots()).containsExactly(
                new EmotionSlotDTO("STRESS", 50.0, 5, 2),
                new EmotionSlotDTO("SADNESS", 50.0, 5, 5));
        // 3 general-fit fillers, unlabelled, placed after the emotion shares.
        assertThat(a.tracks().subList(7, 10)).allMatch(t -> t.getMatchedEmotion() == null);
        assertThat(a.tracks().stream().map(TrackDTO::getId).distinct().count()).isEqualTo(10);
    }

    private TrackDTO inRaga(TrackDTO t, String raga) {
        t.setRaga(raga);
        return t;
    }

    @Test
    void withinAShareSongsInThatSharesRagaComeFirst() {
        // Preference order puts the untagged SADNESS songs first; the raga match must still win.
        List<TrackDTO> candidates = new ArrayList<>(tracks(3, "SADNESS"));
        TrackDTO darbari = inRaga(track("SADNESS"), "Darbari Kanada");
        candidates.add(darbari);

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("SADNESS", 100.0), Map.of("SADNESS", "Darbari Kanada"), 2);

        assertThat(a.tracks().get(0)).isSameAs(darbari);
        assertThat(a.slots()).containsExactly(new EmotionSlotDTO("SADNESS", 100.0, 2, 2, "Darbari Kanada"));
    }

    @Test
    void aSongInTheSharesRagaCanFillTheShareWhenNoEmotionMatchExists() {
        // No NOSTALGIA-tagged songs at all — a Yaman song (Yaman is traditionally linked to NOSTALGIA)
        // fills the NOSTALGIA share instead of an unrelated filler.
        TrackDTO yamanLove = inRaga(track("LOVE"), "Yaman");
        List<TrackDTO> candidates = new ArrayList<>(tracks(3, "JOY"));
        candidates.add(yamanLove);

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("NOSTALGIA", 100.0), Map.of("NOSTALGIA", "Yaman"), 1);

        assertThat(a.tracks()).containsExactly(yamanLove);
        assertThat(yamanLove.getMatchedEmotion()).isEqualTo("NOSTALGIA");
    }

    @Test
    void secondaryEmotionMatchBeatsARagaOnlyMatch() {
        TrackDTO secondary = track("LOVE", "NOSTALGIA");
        TrackDTO ragaOnly = inRaga(track("JOY"), "Yaman");
        List<TrackDTO> candidates = new ArrayList<>(List.of(ragaOnly, secondary));

        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                candidates, dist("NOSTALGIA", 100.0), Map.of("NOSTALGIA", "Yaman"), 1);

        assertThat(a.tracks()).containsExactly(secondary);
    }

    @Test
    void sharesWithoutARagaReportNullRaga() {
        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(
                tracks(2, "JOY"), dist("JOY", 100.0), Map.of(), 2);
        assertThat(a.slots()).containsExactly(new EmotionSlotDTO("JOY", 100.0, 2, 2, null));
    }

    @Test
    void noDistributionKeepsPreferenceOrder() {
        List<TrackDTO> candidates = tracks(12, "JOY");
        EmotionQuotaAllocator.Allocation a = EmotionQuotaAllocator.allocate(candidates, Map.of(), 10);
        assertThat(a.tracks()).containsExactlyElementsOf(candidates.subList(0, 10));
        assertThat(a.slots()).isEmpty();
    }
}
