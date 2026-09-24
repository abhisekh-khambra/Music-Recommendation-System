-- ============================================================
-- Seed data — auto-loaded by DataInitializer on first startup
-- ============================================================

-- Anonymous user
INSERT INTO users (username, email)
VALUES ('anonymous', 'anon@musicrec.app')
ON DUPLICATE KEY UPDATE email = email;

-- ── English Tracks ───────────────────────────────────────────

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, spotify_id, album_art_url, duration_ms, popularity) VALUES
('Happy', 'Pharrell Williams', 'G I R L', 'Pop', 'English', 160, 0.96, 0.97, 'JOY', '["EXCITEMENT","LOVE"]', '["upbeat","feel-good","dance","summer","energetic"]', '60nZcImufyMA1MKQY3dcCH', 'https://i.scdn.co/image/ab67616d0000b273e8107e6d9214baa81bb79bba', 233000, 92);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, spotify_id, album_art_url, duration_ms, popularity) VALUES
('Can''t Stop the Feeling!', 'Justin Timberlake', 'Trolls', 'Pop', 'English', 113, 0.80, 0.97, 'JOY', '["EXCITEMENT","LOVE"]', '["dance","happy","fun","family","positive"]', '1WkMMavIMc4JZ8cfMbaWUH', 'https://i.scdn.co/image/ab67616d0000b2735ef5c9baf29cbf9ef6882e3e', 236000, 90);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, album_art_url, duration_ms, popularity) VALUES
('Walking on Sunshine', 'Katrina and The Waves', 'Walking on Sunshine', 'Rock', 'English', 109, 0.88, 0.95, 'JOY', '["EXCITEMENT","NOSTALGIA"]', '["classic","upbeat","sunny","cheerful","80s"]', NULL, 238000, 78);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Good as Hell', 'Lizzo', 'Cuz I Love You', 'Pop', 'English', 96, 0.61, 0.94, 'JOY', '["LOVE","EXCITEMENT"]', '["empowerment","self-love","feel-good","confidence","uplifting"]', 157000, 85);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, spotify_id, album_art_url, duration_ms, popularity) VALUES
('Someone Like You', 'Adele', '21', 'Pop', 'English', 68, 0.30, 0.13, 'SADNESS', '["MELANCHOLY","LOVE","NOSTALGIA"]', '["heartbreak","ballad","emotional","longing","breakup"]', '1zwMYTA5nlNjZxYrvBB2pV', 'https://i.scdn.co/image/ab67616d0000b2732118bf9b198b05a95ded6300', 285000, 95);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('The Night We Met', 'Lord Huron', 'Strange Trails', 'Indie', 'English', 75, 0.39, 0.12, 'SADNESS', '["MELANCHOLY","NOSTALGIA","LOVE"]', '["haunt","slow","indie","emotional","deep"]', 184000, 80);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Skinny Love', 'Bon Iver', 'For Emma Forever Ago', 'Indie Folk', 'English', 100, 0.43, 0.19, 'SADNESS', '["MELANCHOLY","STRESS"]', '["raw","emotional","acoustic","indie","breakup"]', 201000, 75);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Fix You', 'Coldplay', 'X&Y', 'Alternative Rock', 'English', 138, 0.41, 0.24, 'SADNESS', '["MELANCHOLY","LOVE","CALM"]', '["hope","emotional","slow-build","comfort","healing"]', 295000, 88);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Weightless', 'Marconi Union', 'Weightless', 'Ambient', 'English', 60, 0.08, 0.55, 'STRESS', '["RELAXATION","CALM","ANXIETY"]', '["anti-anxiety","meditation","binaural","calm","therapeutic"]', 479000, 72);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Breathe (2 AM)', 'Anna Nalick', 'Wreck of the Day', 'Folk Rock', 'English', 75, 0.37, 0.35, 'STRESS', '["SADNESS","MELANCHOLY","CALM"]', '["soothing","reflective","breathing","slow","healing"]', 234000, 65);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Claire de Lune', 'Claude Debussy', 'Suite bergamasque', 'Classical', 'Instrumental', 60, 0.04, 0.70, 'RELAXATION', '["CALM","NOSTALGIA"]', '["classical","piano","serene","peaceful","focus"]', 299000, 70);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Sunset Lover', 'Petit Biscuit', 'Presence', 'Electronic', 'English', 96, 0.45, 0.78, 'RELAXATION', '["CALM","JOY"]', '["chill","lofi","evening","study","ambient"]', 227000, 77);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Ocean', 'Goldfish', 'Life Is a Movie', 'Jazz-Fusion', 'English', 80, 0.35, 0.72, 'RELAXATION', '["CALM","JOY"]', '["lofi","smooth","chill","evening","calm"]', 210000, 60);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Break Stuff', 'Limp Bizkit', 'Significant Other', 'Nu-Metal', 'English', 115, 0.97, 0.21, 'ANGER', '["FRUSTRATION","STRESS"]', '["heavy","aggressive","cathartic","intense","loud"]', 171000, 74);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Killing in the Name', 'Rage Against the Machine', 'Rage Against the Machine', 'Rock', 'English', 105, 0.97, 0.17, 'ANGER', '["FRUSTRATION","EXCITEMENT"]', '["protest","heavy","intense","cathartic","empowerment"]', 312000, 80);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('In the End', 'Linkin Park', 'Hybrid Theory', 'Alternative Metal', 'English', 104, 0.92, 0.27, 'ANGER', '["SADNESS","FRUSTRATION","MELANCHOLY"]', '["cathartic","heavy","emotional","intense","classic"]', 217000, 92);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Blinding Lights', 'The Weeknd', 'After Hours', 'Synth-pop', 'English', 171, 0.80, 0.69, 'EXCITEMENT', '["JOY","LOVE","NOSTALGIA"]', '["energetic","night-drive","synthwave","upbeat","pop"]', 200000, 98);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Levitating', 'Dua Lipa', 'Future Nostalgia', 'Pop', 'English', 103, 0.82, 0.82, 'EXCITEMENT', '["JOY","LOVE"]', '["dance","pop","fun","energetic","disco"]', 203000, 95);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Perfect', 'Ed Sheeran', 'Divide', 'Pop', 'English', 95, 0.46, 0.76, 'LOVE', '["JOY","NOSTALGIA","CALM"]', '["romantic","wedding","tender","ballad","sweet"]', 263000, 94);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('All of Me', 'John Legend', 'Love in the Future', 'R&B', 'English', 63, 0.34, 0.58, 'LOVE', '["JOY","SADNESS","CALM"]', '["romantic","piano","soulful","wedding","tender"]', 269000, 90);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Hotel California', 'Eagles', 'Hotel California', 'Classic Rock', 'English', 147, 0.60, 0.44, 'NOSTALGIA', '["MELANCHOLY","RELAXATION"]', '["classic","70s","retro","storytelling","guitar"]', 391000, 88);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Yesterday', 'The Beatles', 'Help!', 'Classic Rock', 'English', 97, 0.19, 0.37, 'NOSTALGIA', '["SADNESS","MELANCHOLY","LOVE"]', '["classic","acoustic","timeless","60s","poignant"]', 125000, 86);

-- ── Hindi / Bollywood Tracks ──────────────────────────────────

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tum Hi Ho', 'Arijit Singh', 'Aashiqui 2', 'Bollywood', 'Hindi', 55, 0.29, 0.20, 'SADNESS', '["LOVE","MELANCHOLY","NOSTALGIA"]', '["romantic","sad","heartbreak","soulful","bollywood"]', 261000, 92);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Kabira', 'Rekha Bhardwaj', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi', 72, 0.33, 0.62, 'NOSTALGIA', '["SADNESS","LOVE","MELANCHOLY"]', '["sufi","soulful","longing","friendship","bittersweet"]', 250000, 88);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Iktara', 'Kavita Seth', 'Wake Up Sid', 'Indie Bollywood', 'Hindi', 78, 0.22, 0.58, 'RELAXATION', '["CALM","NOSTALGIA","MELANCHOLY"]', '["folk","peaceful","indie","sufi","soothing"]', 228000, 82);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Zinda Hoon Main', 'Arijit Singh', 'Jab Tak Hai Jaan', 'Bollywood', 'Hindi', 128, 0.73, 0.72, 'JOY', '["EXCITEMENT","LOVE"]', '["motivational","feel-good","bollywood","upbeat","life"]', 245000, 78);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Dil Dhadakne Do', 'Priyanka Chopra', 'Dil Dhadakne Do', 'Bollywood', 'Hindi', 130, 0.82, 0.85, 'EXCITEMENT', '["JOY","LOVE"]', '["party","dance","bollywood","energy","fun"]', 261000, 80);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Ae Dil Hai Mushkil', 'Arijit Singh', 'Ae Dil Hai Mushkil', 'Bollywood', 'Hindi', 62, 0.28, 0.18, 'SADNESS', '["LOVE","MELANCHOLY","STRESS"]', '["heartbreak","longing","emotional","bollywood","unrequited"]', 291000, 90);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Raabta', 'Arijit Singh', 'Agent Sai Srinivasa', 'Bollywood', 'Hindi', 70, 0.32, 0.55, 'LOVE', '["NOSTALGIA","CALM","JOY"]', '["romantic","soulful","soft","bollywood","connection"]', 243000, 84);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Channa Mereya', 'Arijit Singh', 'Ae Dil Hai Mushkil', 'Bollywood', 'Hindi', 67, 0.27, 0.15, 'SADNESS', '["LOVE","MELANCHOLY","NOSTALGIA"]', '["heartbreak","wedding","emotional","goodbye","soulful"]', 295000, 91);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Manwa Laage', 'Arijit Singh', 'Happy New Year', 'Bollywood', 'Hindi', 78, 0.38, 0.72, 'LOVE', '["JOY","RELAXATION","NOSTALGIA"]', '["romantic","sufi","melodic","soft","couple"]', 267000, 83);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Phir Le Aya Dil', 'Rekha Bhardwaj', 'Barfi!', 'Bollywood', 'Hindi', 56, 0.18, 0.35, 'MELANCHOLY', '["SADNESS","NOSTALGIA","LOVE"]', '["sufi","longing","classic","soulful","bittersweet"]', 254000, 79);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Kun Faya Kun', 'A.R. Rahman', 'Rockstar', 'Sufi', 'Hindi', 55, 0.24, 0.78, 'RELAXATION', '["CALM","NOSTALGIA","LOVE"]', '["sufi","spiritual","peaceful","divine","soul-stirring"]', 437000, 90);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Bawara Mann', 'Shilpa Rao', 'Jolly LLB 2', 'Bollywood', 'Hindi', 60, 0.21, 0.77, 'RELAXATION', '["LOVE","CALM","JOY"]', '["soft","romantic","peaceful","soothing","melodic"]', 245000, 76);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Enna Sona', 'Arijit Singh', 'Ok Jaanu', 'Bollywood', 'Hindi', 72, 0.30, 0.65, 'LOVE', '["CALM","JOY","NOSTALGIA"]', '["romantic","guitar","soft","melodic","couple"]', 247000, 82);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Agar Tum Saath Ho', 'Alka Yagnik', 'Tamasha', 'Bollywood', 'Hindi', 58, 0.25, 0.17, 'SADNESS', '["LOVE","MELANCHOLY"]', '["separation","emotional","duo","slow","heart-wrenching"]', 326000, 89);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Badtameez Dil', 'Benny Dayal', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi', 130, 0.87, 0.88, 'EXCITEMENT', '["JOY","LOVE"]', '["dance","party","energetic","fun","bollywood"]', 213000, 86);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Naina Da Kya Kasoor', 'Armaan Malik', 'Andhadhun', 'Bollywood', 'Hindi', 60, 0.18, 0.28, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["retro","old-school","piano","bittersweet","slow"]', 216000, 77);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Ik Vaari Aa', 'Arijit Singh', 'Raabta', 'Bollywood', 'Hindi', 65, 0.22, 0.30, 'MELANCHOLY', '["SADNESS","LOVE","NOSTALGIA"]', '["longing","acoustic","soulful","indie","melancholic"]', 251000, 81);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Gulabi Aankhen', 'Mohammed Rafi', 'The Train', 'Classic Bollywood', 'Hindi', 100, 0.42, 0.80, 'JOY', '["LOVE","NOSTALGIA"]', '["classic","retro","evergreen","romantic","fun"]', 242000, 75);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Jab Koi Baat Bigad Jaye', 'Attaullah Khan', 'Love', 'Ghazal', 'Hindi', 60, 0.15, 0.42, 'STRESS', '["SADNESS","MELANCHOLY","CALM"]', '["ghazal","soothing","healing","soft","classic"]', 310000, 72);
