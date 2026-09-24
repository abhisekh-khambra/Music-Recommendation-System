-- =============================================================
-- Emotion-Based Music Recommendation System
-- MySQL Schema + Sample Data
-- =============================================================

CREATE DATABASE IF NOT EXISTS music_rec_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE music_rec_db;

-- -------------------------------------------------------------
-- Table: users
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(100) NOT NULL UNIQUE,
    email       VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -------------------------------------------------------------
-- Table: tracks  (emotion tags stored as JSON arrays)
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tracks (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    title             VARCHAR(255)   NOT NULL,
    artist            VARCHAR(255)   NOT NULL,
    album             VARCHAR(255),
    genre             VARCHAR(100),
    language          VARCHAR(50)    NOT NULL DEFAULT 'English',
    tempo_bpm         INT,
    energy_level      DECIMAL(3,2)   COMMENT '0.0 (calm) to 1.0 (intense)',
    valence           DECIMAL(3,2)   COMMENT '0.0 (negative) to 1.0 (positive)',
    primary_emotion   VARCHAR(50)    NOT NULL,
    secondary_emotions JSON          COMMENT 'Array of secondary emotion strings',
    mood_tags         JSON          COMMENT 'Array of descriptive mood keywords',
    spotify_id        VARCHAR(100),
    preview_url       VARCHAR(500),
    album_art_url     VARCHAR(500),
    duration_ms       INT,
    popularity        INT            DEFAULT 50,
    created_at        TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -------------------------------------------------------------
-- Table: chat_history
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_history (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT,
    session_id            VARCHAR(100)                   NOT NULL,
    user_message          TEXT                           NOT NULL,
    detected_emotion      VARCHAR(50),
    emotion_intensity     DECIMAL(3,2),
    assistant_response    TEXT,
    recommended_track_ids JSON                           COMMENT 'Array of recommended track ids',
    input_type            ENUM('text','voice')           DEFAULT 'text',
    language              VARCHAR(50)                    DEFAULT 'English',
    created_at            TIMESTAMP                      DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chat_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- -------------------------------------------------------------
-- Indexes
-- -------------------------------------------------------------
CREATE INDEX idx_tracks_primary_emotion ON tracks(primary_emotion);
CREATE INDEX idx_tracks_genre           ON tracks(genre);
CREATE INDEX idx_tracks_language        ON tracks(language);
CREATE INDEX idx_tracks_valence         ON tracks(valence);
CREATE INDEX idx_tracks_energy          ON tracks(energy_level);
CREATE INDEX idx_chat_session           ON chat_history(session_id);
CREATE INDEX idx_chat_user              ON chat_history(user_id);
CREATE INDEX idx_chat_emotion           ON chat_history(detected_emotion);

-- =============================================================
-- Sample Data — default anonymous user
-- =============================================================
INSERT INTO users (username, email) VALUES ('anonymous', 'anon@musicrec.app');

-- =============================================================
-- Sample Tracks — English (30 tracks, diverse emotions)
-- =============================================================
INSERT INTO tracks
  (title, artist, album, genre, language, tempo_bpm, energy_level, valence,
   primary_emotion, secondary_emotions, mood_tags, spotify_id, album_art_url, duration_ms, popularity)
VALUES

-- ── JOY / HAPPINESS ──────────────────────────────────────────
('Happy', 'Pharrell Williams', 'G I R L', 'Pop', 'English',
 160, 0.96, 0.97, 'JOY',
 '["EXCITEMENT","LOVE"]',
 '["upbeat","feel-good","dance","summer","energetic"]',
 '60nZcImufyMA1MKQY3dcCH', 'https://i.scdn.co/image/ab67616d0000b273e8107e6d9214baa81bb79bba',
 233000, 92),

('Can''t Stop the Feeling!', 'Justin Timberlake', 'Trolls', 'Pop', 'English',
 113, 0.80, 0.97, 'JOY',
 '["EXCITEMENT","LOVE"]',
 '["dance","happy","fun","family","positive"]',
 '1WkMMavIMc4JZ8cfMbaWUH', 'https://i.scdn.co/image/ab67616d0000b2735ef5c9baf29cbf9ef6882e3e',
 236000, 90),

('Walking on Sunshine', 'Katrina and The Waves', 'Walking on Sunshine', 'Rock', 'English',
 109, 0.88, 0.95, 'JOY',
 '["EXCITEMENT","NOSTALGIA"]',
 '["classic","upbeat","sunny","cheerful","80s"]',
 '05wIrZSwuaVWhcv5FfqeH0', NULL,
 238000, 78),

('Good as Hell', 'Lizzo', 'Cuz I Love You', 'Pop', 'English',
 96, 0.61, 0.94, 'JOY',
 '["LOVE","EXCITEMENT"]',
 '["empowerment","self-love","feel-good","confidence","uplifting"]',
 '3Wonoez5r9QCvBOiL5RvBi', NULL,
 157000, 85),

-- ── SADNESS / MELANCHOLY ─────────────────────────────────────
('Someone Like You', 'Adele', '21', 'Pop', 'English',
 68, 0.30, 0.13, 'SADNESS',
 '["MELANCHOLY","LOVE","NOSTALGIA"]',
 '["heartbreak","ballad","emotional","longing","breakup"]',
 '1zwMYTA5nlNjZxYrvBB2pV', 'https://i.scdn.co/image/ab67616d0000b2732118bf9b198b05a95ded6300',
 285000, 95),

('The Night We Met', 'Lord Huron', 'Strange Trails', 'Indie', 'English',
 75, 0.39, 0.12, 'SADNESS',
 '["MELANCHOLY","NOSTALGIA","LOVE"]',
 '["haunt","slow","indie","emotional","deep"]',
 '3hRV0jL3vUpRrcy398teAU', NULL,
 184000, 80),

('Skinny Love', 'Bon Iver', 'For Emma Forever Ago', 'Indie Folk', 'English',
 100, 0.43, 0.19, 'SADNESS',
 '["MELANCHOLY","STRESS"]',
 '["raw","emotional","acoustic","indie","breakup"]',
 '4RVwu0g32PAqgUiJoXsdF8', NULL,
 201000, 75),

('Fix You', 'Coldplay', 'X&Y', 'Alternative Rock', 'English',
 138, 0.41, 0.24, 'SADNESS',
 '["MELANCHOLY","LOVE","CALM"]',
 '["hope","emotional","slow-build","comfort","healing"]',
 '7LVHVU3tWfcxj5aiPFEW4Q', NULL,
 295000, 88),

-- ── STRESS / ANXIETY ─────────────────────────────────────────
('Weightless', 'Marconi Union', 'Weightless', 'Ambient', 'English',
 60, 0.08, 0.55, 'STRESS',
 '["RELAXATION","CALM","ANXIETY"]',
 '["anti-anxiety","meditation","binaural","calm","therapeutic"]',
 '2Qp9PTkHXRGM9B9oT3eo28', NULL,
 479000, 72),

('Breathe (2 AM)', 'Anna Nalick', 'Wreck of the Day', 'Folk Rock', 'English',
 75, 0.37, 0.35, 'STRESS',
 '["SADNESS","MELANCHOLY","CALM"]',
 '["soothing","reflective","breathing","slow","healing"]',
 '0d4vMxSLMXY8IqakDW4dWj', NULL,
 234000, 65),

-- ── RELAXATION / CALM ────────────────────────────────────────
('Claire de Lune', 'Claude Debussy', 'Suite bergamasque', 'Classical', 'Instrumental',
 60, 0.04, 0.70, 'RELAXATION',
 '["CALM","NOSTALGIA"]',
 '["classical","piano","serene","peaceful","focus"]',
 '5HKaCHCHkODMjBNl7WfFpj', NULL,
 299000, 70),

('Sunset Lover', 'Petit Biscuit', 'Presence', 'Electronic', 'English',
 96, 0.45, 0.78, 'RELAXATION',
 '["CALM","JOY"]',
 '["chill","lofi","evening","study","ambient"]',
 '3EMNTsX8lgK3Gf97ANGnUQ', NULL,
 227000, 77),

('Ocean', 'Goldfish', 'Life Is a Movie', 'Jazz-Fusion', 'English',
 80, 0.35, 0.72, 'RELAXATION',
 '["CALM","JOY"]',
 '["lofi","smooth","chill","evening","calm"]',
 NULL, NULL, 210000, 60),

-- ── ANGER / FRUSTRATION ──────────────────────────────────────
('Break Stuff', 'Limp Bizkit', 'Significant Other', 'Nu-Metal', 'English',
 115, 0.97, 0.21, 'ANGER',
 '["FRUSTRATION","STRESS"]',
 '["heavy","aggressive","cathartic","intense","loud"]',
 '4yBV6UGGU9h7xHqfEcXBJd', NULL,
 171000, 74),

('Killing in the Name', 'Rage Against the Machine', 'Rage Against the Machine', 'Rock', 'English',
 105, 0.97, 0.17, 'ANGER',
 '["FRUSTRATION","EXCITEMENT"]',
 '["protest","heavy","intense","cathartic","empowerment"]',
 '59WN2psjkt1tyaxjspN8fp', NULL,
 312000, 80),

('In the End', 'Linkin Park', 'Hybrid Theory', 'Alternative Metal', 'English',
 104, 0.92, 0.27, 'ANGER',
 '["SADNESS","FRUSTRATION","MELANCHOLY"]',
 '["cathartic","heavy","emotional","intense","classic"]',
 '60a0Rd6pjrkxjPbaKzXjfq', NULL,
 217000, 92),

-- ── EXCITEMENT ───────────────────────────────────────────────
('Blinding Lights', 'The Weeknd', 'After Hours', 'Synth-pop', 'English',
 171, 0.80, 0.69, 'EXCITEMENT',
 '["JOY","LOVE","NOSTALGIA"]',
 '["energetic","night-drive","synthwave","upbeat","pop"]',
 '0VjIjW4GlUZAMYd2vXMi3b', NULL,
 200000, 98),

('Levitating', 'Dua Lipa', 'Future Nostalgia', 'Pop', 'English',
 103, 0.82, 0.82, 'EXCITEMENT',
 '["JOY","LOVE"]',
 '["dance","pop","fun","energetic","disco"]',
 '463CkQjx2Zfoiqh0dFCyQ', NULL,
 203000, 95),

-- ── LOVE / ROMANCE ───────────────────────────────────────────
('Perfect', 'Ed Sheeran', '÷ (Divide)', 'Pop', 'English',
 95, 0.46, 0.76, 'LOVE',
 '["JOY","NOSTALGIA","CALM"]',
 '["romantic","wedding","tender","ballad","sweet"]',
 '0tgVpDi06FyKpA1z0VMD4v', NULL,
 263000, 94),

('All of Me', 'John Legend', 'Love in the Future', 'R&B', 'English',
 63, 0.34, 0.58, 'LOVE',
 '["JOY","SADNESS","CALM"]',
 '["romantic","piano","soulful","wedding","tender"]',
 '3U4isOIWM3VvDubwSI3y7a', NULL,
 269000, 90),

-- ── NOSTALGIA ────────────────────────────────────────────────
('Hotel California', 'Eagles', 'Hotel California', 'Classic Rock', 'English',
 147, 0.60, 0.44, 'NOSTALGIA',
 '["MELANCHOLY","RELAXATION"]',
 '["classic","70s","retro","storytelling","guitar"]',
 '40riOy7x9W7GXjyGp4pjAv', NULL,
 391000, 88),

('Yesterday', 'The Beatles', 'Help!', 'Classic Rock', 'English',
 97, 0.19, 0.37, 'NOSTALGIA',
 '["SADNESS","MELANCHOLY","LOVE"]',
 '["classic","acoustic","timeless","60s","poignant"]',
 '3BQHpFgAp4l80e1XslIjNI', NULL,
 125000, 86),

-- =============================================================
-- Sample Tracks — Hindi / Bollywood (20 tracks)
-- =============================================================
('Tum Hi Ho', 'Arijit Singh', 'Aashiqui 2', 'Bollywood', 'Hindi',
 55, 0.29, 0.20, 'SADNESS',
 '["LOVE","MELANCHOLY","NOSTALGIA"]',
 '["romantic","sad","heartbreak","soulful","bollywood"]',
 NULL, NULL, 261000, 92),

('Kabira', 'Rekha Bhardwaj, Tochi Raina', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi',
 72, 0.33, 0.62, 'NOSTALGIA',
 '["SADNESS","LOVE","MELANCHOLY"]',
 '["sufi","soulful","longing","friendship","bittersweet"]',
 NULL, NULL, 250000, 88),

('Iktara', 'Kavita Seth', 'Wake Up Sid', 'Indie Bollywood', 'Hindi',
 78, 0.22, 0.58, 'RELAXATION',
 '["CALM","NOSTALGIA","MELANCHOLY"]',
 '["folk","peaceful","indie","sufi","soothing"]',
 NULL, NULL, 228000, 82),

('Zinda Hoon Main', 'Arijit Singh', 'Jab Tak Hai Jaan', 'Bollywood', 'Hindi',
 128, 0.73, 0.72, 'JOY',
 '["EXCITEMENT","LOVE"]',
 '["motivational","feel-good","bollywood","upbeat","life"]',
 NULL, NULL, 245000, 78),

('Dil Dhadakne Do', 'Priyanka Chopra, Farhan Akhtar', 'Dil Dhadakne Do', 'Bollywood', 'Hindi',
 130, 0.82, 0.85, 'EXCITEMENT',
 '["JOY","LOVE"]',
 '["party","dance","bollywood","energy","fun"]',
 NULL, NULL, 261000, 80),

('Ae Dil Hai Mushkil', 'Arijit Singh', 'Ae Dil Hai Mushkil', 'Bollywood', 'Hindi',
 62, 0.28, 0.18, 'SADNESS',
 '["LOVE","MELANCHOLY","STRESS"]',
 '["heartbreak","longing","emotional","bollywood","unrequited"]',
 NULL, NULL, 291000, 90),

('Raabta', 'Arijit Singh', 'Agent Sai Srinivasa Athreya', 'Bollywood', 'Hindi',
 70, 0.32, 0.55, 'LOVE',
 '["NOSTALGIA","CALM","JOY"]',
 '["romantic","soulful","soft","bollywood","connection"]',
 NULL, NULL, 243000, 84),

('Channa Mereya', 'Arijit Singh', 'Ae Dil Hai Mushkil', 'Bollywood', 'Hindi',
 67, 0.27, 0.15, 'SADNESS',
 '["LOVE","MELANCHOLY","NOSTALGIA"]',
 '["heartbreak","wedding","emotional","goodbye","soulful"]',
 NULL, NULL, 295000, 91),

('Manwa Laage', 'Arijit Singh, Shreya Ghoshal', 'Happy New Year', 'Bollywood', 'Hindi',
 78, 0.38, 0.72, 'LOVE',
 '["JOY","RELAXATION","NOSTALGIA"]',
 '["romantic","sufi","melodic","soft","couple"]',
 NULL, NULL, 267000, 83),

('Phir Le Aya Dil', 'Rekha Bhardwaj', 'Barfi!', 'Bollywood', 'Hindi',
 56, 0.18, 0.35, 'MELANCHOLY',
 '["SADNESS","NOSTALGIA","LOVE"]',
 '["sufi","longing","classic","soulful","bittersweet"]',
 NULL, NULL, 254000, 79),

('Jab Koi Baat Bigad Jaye', 'Attaullah Khan', 'Love', 'Ghazal', 'Hindi',
 60, 0.15, 0.42, 'STRESS',
 '["SADNESS","MELANCHOLY","CALM"]',
 '["ghazal","soothing","healing","soft","classic"]',
 NULL, NULL, 310000, 72),

('Kun Faya Kun', 'A.R. Rahman, Javed Ali, Mohit Chauhan', 'Rockstar', 'Sufi', 'Hindi',
 55, 0.24, 0.78, 'RELAXATION',
 '["CALM","NOSTALGIA","LOVE"]',
 '["sufi","spiritual","peaceful","divine","soul-stirring"]',
 NULL, NULL, 437000, 90),

('Bawara Mann', 'Swanand Kirkire, Shilpa Rao', 'Jolly LLB 2', 'Bollywood', 'Hindi',
 60, 0.21, 0.77, 'RELAXATION',
 '["LOVE","CALM","JOY"]',
 '["soft","romantic","peaceful","soothing","melodic"]',
 NULL, NULL, 245000, 76),

('Enna Sona', 'Arijit Singh', 'Ok Jaanu', 'Bollywood', 'Hindi',
 72, 0.30, 0.65, 'LOVE',
 '["CALM","JOY","NOSTALGIA"]',
 '["romantic","guitar","soft","melodic","couple"]',
 NULL, NULL, 247000, 82),

('Agar Tum Saath Ho', 'Alka Yagnik, Arijit Singh', 'Tamasha', 'Bollywood', 'Hindi',
 58, 0.25, 0.17, 'SADNESS',
 '["LOVE","MELANCHOLY"]',
 '["separation","emotional","duo","slow","heart-wrenching"]',
 NULL, NULL, 326000, 89),

('Badtameez Dil', 'Benny Dayal, Shefali Alvares', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi',
 130, 0.87, 0.88, 'EXCITEMENT',
 '["JOY","LOVE"]',
 '["dance","party","energetic","fun","bollywood"]',
 NULL, NULL, 213000, 86),

('Tumse Milke', 'Shaan, Hema Sardesai', 'Main Hoon Na', 'Bollywood', 'Hindi',
 88, 0.40, 0.75, 'JOY',
 '["LOVE","NOSTALGIA"]',
 '["romantic","happy","classic","college","melodic"]',
 NULL, NULL, 277000, 70),

('Naina Da Kya Kasoor', 'Armaan Malik', 'Andhadhun', 'Bollywood', 'Hindi',
 60, 0.18, 0.28, 'MELANCHOLY',
 '["SADNESS","NOSTALGIA"]',
 '["retro","old-school","piano","bittersweet","slow"]',
 NULL, NULL, 216000, 77),

('Ik Vaari Aa', 'Arijit Singh', 'Raabta', 'Bollywood', 'Hindi',
 65, 0.22, 0.30, 'MELANCHOLY',
 '["SADNESS","LOVE","NOSTALGIA"]',
 '["longing","acoustic","soulful","indie","melancholic"]',
 NULL, NULL, 251000, 81),

('Gulabi Aankhen', 'Mohammed Rafi', 'The Train', 'Classic Bollywood', 'Hindi',
 100, 0.42, 0.80, 'JOY',
 '["LOVE","NOSTALGIA"]',
 '["classic","retro","evergreen","romantic","fun"]',
 NULL, NULL, 242000, 75);
