package com.musicrec.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Raga tags must come from a cited source (README: "Raga tags must come from a verified source, so none
 * were invented"). Guards database/raga_tagged_hindi_songs.json and the SQL generated from it.
 */
class RagaTaggedSongsTest {

    private JsonNode songs() throws Exception {
        return new ObjectMapper().readTree(new File("../database/raga_tagged_hindi_songs.json")).get("songs");
    }

    @Test
    void everySongIsHindiInACataloguedRagaWithASourceAndCanonicalEmotions() throws Exception {
        Set<String> seen = new HashSet<>();
        for (JsonNode s : songs()) {
            String id = s.get("title").asText() + " / " + s.get("film").asText();
            assertThat(seen.add(id)).as("duplicate %s", id).isTrue();
            assertThat(RagaRasaCatalog.profileFor(s.get("raga").asText())).as("raga of %s", id).isPresent();
            assertThat(s.get("source").asText()).as("source of %s", id).startsWith("https://");
            assertThat(EmotionLabels.CANONICAL).as("emotion of %s", id).contains(s.get("primaryEmotion").asText());
            s.get("secondaryEmotions").forEach(e -> assertThat(EmotionLabels.CANONICAL).contains(e.asText()));
            double valence = s.get("valence").asDouble(), energy = s.get("energy").asDouble();
            assertThat(valence).isBetween(0.0, 1.0);
            assertThat(energy).isBetween(0.0, 1.0);
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void generatedSqlIsInSyncAndIdempotent() throws Exception {
        String sql = new ClassPathResource("data_raga_additions.sql").getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode s : songs()) {
            assertThat(sql).as("SQL for %s", s.get("title").asText())
                    .contains("'" + s.get("title").asText().replace("'", "''") + "'");
        }
        // Every insert is guarded, so running the file on every startup never duplicates rows.
        long inserts = sql.lines().filter(l -> l.startsWith("INSERT INTO tracks")).count();
        long guards = sql.lines().filter(l -> l.contains("WHERE NOT EXISTS")).count();
        assertThat(inserts).isEqualTo(songs().size()).isEqualTo(guards);
    }
}
