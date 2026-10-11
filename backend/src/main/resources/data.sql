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
('Can''t Stop the Feeling!', 'Justin Timberlake', 'Trolls', 'Pop', 'English', 113, 0.80, 0.97, 'JOY', '["EXCITEMENT","LOVE"]', '["dance","happy","fun","family","positive"]', '1WkMMavIMc4JZ8cfMbaWUH', 'https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/68/19/43/68194388-efa7-3afe-8a15-a4c3eebef1f6/886445915211.jpg/600x600bb.jpg', 236000, 90);

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
('Bawara Mann', 'Jubin Nautiyal & Neeti Mohan', 'Jolly LLB 2', 'Bollywood', 'Hindi', 60, 0.21, 0.77, 'RELAXATION', '["LOVE","CALM","JOY"]', '["soft","romantic","peaceful","soothing","melodic"]', 245000, 76);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Enna Sona', 'Arijit Singh', 'Ok Jaanu', 'Bollywood', 'Hindi', 72, 0.30, 0.65, 'LOVE', '["CALM","JOY","NOSTALGIA"]', '["romantic","guitar","soft","melodic","couple"]', 247000, 82);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Agar Tum Saath Ho', 'Alka Yagnik', 'Tamasha', 'Bollywood', 'Hindi', 58, 0.25, 0.17, 'SADNESS', '["LOVE","MELANCHOLY"]', '["separation","emotional","duo","slow","heart-wrenching"]', 326000, 89);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Badtameez Dil', 'Benny Dayal', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi', 130, 0.87, 0.88, 'EXCITEMENT', '["JOY","LOVE"]', '["dance","party","energetic","fun","bollywood"]', 213000, 86);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Naina Da Kya Kasoor', 'Amit Trivedi', 'Andhadhun', 'Bollywood', 'Hindi', 60, 0.18, 0.28, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["retro","old-school","piano","bittersweet","slow"]', 216000, 77);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Ik Vaari Aa', 'Arijit Singh', 'Raabta', 'Bollywood', 'Hindi', 65, 0.22, 0.30, 'MELANCHOLY', '["SADNESS","LOVE","NOSTALGIA"]', '["longing","acoustic","soulful","indie","melancholic"]', 251000, 81);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Gulabi Aankhen', 'Mohammed Rafi', 'The Train', 'Classic Bollywood', 'Hindi', 100, 0.42, 0.80, 'JOY', '["LOVE","NOSTALGIA"]', '["classic","retro","evergreen","romantic","fun"]', 242000, 75);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Jab Koi Baat Bigad Jaye', 'Kumar Sanu & Sadhana Sargam', 'Jurm', 'Ghazal', 'Hindi', 60, 0.15, 0.42, 'STRESS', '["SADNESS","MELANCHOLY","CALM"]', '["ghazal","soothing","healing","soft","classic"]', 310000, 72);

-- ── Hindustani Classical / Raga-based Instrumental Tracks ──────
-- Raga ↔ emotion associations follow traditional rasa theory / raga-chikitsa
-- (see RagaRasaCatalog.java) — a heuristic retrieval signal, not a medical claim.
-- title/artist pairs below are verified real recordings (checked against the
-- iTunes Search API the frontend uses for previews — see verify_previews.py)
-- so the "Play preview" button actually resolves instead of dead-ending.

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, raga, duration_ms, popularity) VALUES
('Raga Bhairavi', 'Ali Akbar Khan', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 50, 0.15, 0.30, 'SADNESS', '["MELANCHOLY","CALM"]', '["raga","bhairavi","devotional","compassionate","healing"]', 'Bhairavi', 480000, 60),
('Yaman (Gat in Matta Tal)', 'Ravi Shankar & Allah Rakha', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 55, 0.25, 0.65, 'LOVE', '["CALM","RELAXATION"]', '["raga","yaman","romantic","evening","serene"]', 'Yaman', 420000, 58),
('Darbari Kanada', 'Buddhadev DasGupta', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 48, 0.12, 0.20, 'MELANCHOLY', '["SADNESS"]', '["raga","darbari","introspective","gravitas","night"]', 'Darbari Kanada', 540000, 55),
('Raga - Bageshree', 'Ustad Amir Khan', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 52, 0.20, 0.55, 'NOSTALGIA', '["LOVE","MELANCHOLY"]', '["raga","bageshree","longing","tender","night"]', 'Bageshree', 450000, 57),
('Malkauns', 'Pandit Hariprasad Chaurasia', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 46, 0.30, 0.18, 'ANXIETY', '["MELANCHOLY","STRESS"]', '["raga","malkauns","introspective","intense","midnight"]', 'Malkauns', 500000, 50),
('Ahir Bhairav', 'Buddhadev DasGupta', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 54, 0.18, 0.60, 'CALM', '["RELAXATION","STRESS"]', '["raga","ahir-bhairav","devotional","morning","calming"]', 'Ahir Bhairav', 460000, 62),
('Raga Bhoopali (Alap (Santoor & Flute))', 'Pandit Shivkumar Sharma & Pt. Hariprasad Chaurasia', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 50, 0.15, 0.68, 'CALM', '["RELAXATION"]', '["raga","bhoopali","peaceful","meditative","soothing"]', 'Bhoopali', 410000, 65),
('Hindol', 'Vidyadhar Vyas', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 96, 0.55, 0.85, 'JOY', '["EXCITEMENT"]', '["raga","hindol","basant","festive","spring","joyful"]', 'Hindol', 400000, 54),
('Raga Bhairav: Alap', 'Veena Sahasrabuddhe', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 52, 0.22, 0.45, 'STRESS', '["CALM","RELAXATION"]', '["raga","bhairav","grounding","solemn","morning"]', 'Bhairav', 470000, 52),
('Raga Todi (Alap)', 'Ulhas Kashalkar', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 48, 0.14, 0.22, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["raga","todi","introspective","delicate","morning"]', 'Todi', 430000, 48),
('Kafi', 'Pandit Hariprasad Chaurasia', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 78, 0.35, 0.70, 'LOVE', '["JOY","NOSTALGIA"]', '["raga","kafi","romantic","folk","thumri"]', 'Kafi', 390000, 56),
('Desh Raga', 'V S Narasimhan', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 62, 0.28, 0.58, 'NOSTALGIA', '["LOVE","RELAXATION"]', '["raga","desh","monsoon","nostalgic","romantic"]', 'Desh', 445000, 53),
('Bihag', 'Buddhadev DasGupta', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 68, 0.30, 0.72, 'LOVE', '["JOY"]', '["raga","bihag","romantic","night","confident"]', 'Bihag', 415000, 55),
('Marwa Raga', 'Nikhil Banerjee', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 50, 0.16, 0.22, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["raga","marwa","solitude","sunset","wistful"]', 'Marwa', 470000, 46),
('Puriya Dhanashri', 'Pandit Hariprasad Chaurasia', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 46, 0.14, 0.45, 'CALM', '["MELANCHOLY"]', '["raga","puriya-dhanashri","devotional","twilight","contemplative"]', 'Puriya Dhanashri', 495000, 47),
('Raga Mishra Shivranjani - Raga Mishra Shivranjani - Deepchandi', 'Pandit Shivkumar Sharma', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 58, 0.22, 0.68, 'LOVE', '["MELANCHOLY","NOSTALGIA"]', '["raga","shivranjani","tender","romantic","bittersweet"]', 'Shivranjani', 435000, 51),
('Bhimpalasi', 'Shruti Sadolikar', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 56, 0.20, 0.62, 'NOSTALGIA', '["LOVE"]', '["raga","bhimpalasi","devotional","longing","afternoon"]', 'Bhimpalasi', 425000, 49),
('Miyan Ki Malhar', 'Vidyadhar Vyas', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 64, 0.32, 0.58, 'RELAXATION', '["NOSTALGIA","JOY"]', '["raga","malhar","monsoon","yearning","rain"]', 'Miyan ki Malhar', 460000, 50),
('Alap and Jor - Raga Shree (Live)', 'Pandit Nikhil Banerjee', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 48, 0.18, 0.48, 'CALM', '["MELANCHOLY"]', '["raga","shree","solemn","devotional","evening"]', 'Shree', 480000, 45),
('Chandrakauns', 'Vidyadhar Vyas', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 47, 0.24, 0.20, 'MELANCHOLY', '["ANXIETY"]', '["raga","chandrakauns","introspective","midnight","soft"]', 'Chandrakauns', 505000, 44),
('Raga Hansadhwani', 'Pravin Godkhindi', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 100, 0.60, 0.88, 'JOY', '["EXCITEMENT"]', '["raga","hansadhwani","bright","festive","celebration"]', 'Hansadhwani', 385000, 57),
('Kirwani Raga', 'Rashid Khan', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 54, 0.20, 0.28, 'SADNESS', '["MELANCHOLY","CALM"]', '["raga","kirwani","hopeful","evening","bittersweet"]', 'Kirwani', 440000, 52),
('Adana', 'Buddhadev DasGupta', 'Indian Classical Collection', 'Indian Classical', 'Instrumental', 88, 0.75, 0.30, 'ANGER', '["FRUSTRATION","EXCITEMENT"]', '["raga","adana","valorous","intense","driving"]', 'Adana', 410000, 46),
-- Hindi VOCAL film songs with a commonly-cited (composer-interview / music-journalism) raga
-- basis — these, unlike the purely-instrumental rows above, are eligible for the Hindi-only
-- conversation/Raga pipeline's final recommendations. Raga attribution for film songs varies
-- between sources and is not a verified musicological fact — same caveat as RagaRasaCatalog.
('Albela Sajan', 'Kavita Krishnamurthy, Sonu Nigam', 'Hum Dil De Chuke Sanam', 'Bollywood Classical', 'Hindi', 92, 0.55, 0.62, 'JOY', '["EXCITEMENT","LOVE"]', '["raga","bhairav","wedding","festive","classical-bollywood"]', 'Bhairav', 330000, 74),
('Tadap Tadap', 'K.S. Chithra, Kumar Sanu', 'Hum Dil De Chuke Sanam', 'Bollywood Classical', 'Hindi', 58, 0.26, 0.16, 'SADNESS', '["MELANCHOLY","LOVE"]', '["raga","malkauns","heartbreak","intense","classical-bollywood"]', 'Malkauns', 372000, 80),
('Silsila Ye Chaahat Ka', 'Shreya Ghoshal, Udit Narayan', 'Devdas', 'Bollywood Classical', 'Hindi', 68, 0.34, 0.60, 'LOVE', '["CALM","NOSTALGIA"]', '["raga","yaman","romantic","classical-bollywood","evening"]', 'Yaman', 345000, 78),
('Man Mohana', 'Bela Shende', 'Jodhaa Akbar', 'Bollywood Classical', 'Hindi', 50, 0.20, 0.32, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["raga","darbari-kanada","devotional","classical-bollywood","gravitas"]', 'Darbari Kanada', 398000, 71);

-- ── Additional Hindi Tracks — catalogue expansion for Hindi-only coverage ──
-- Real, verifiably Hindi-language film/independent songs (no audio files, metadata
-- only, same pattern as the existing Hindi block above) — added so more emotion/raga
-- combinations in the Hindi-only conversation pipeline can reach a full top-10.

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('London Thumakda', 'Sonu Kakkar, Neha Kakkar, Vishal Dadlani', 'Queen', 'Bollywood', 'Hindi', 130, 0.85, 0.90, 'JOY', '["EXCITEMENT","LOVE"]', '["wedding","dance","upbeat","celebration","fun"]', 222000, 85);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Gallan Goodiyaan', 'Various Artists', 'Dil Dhadakne Do', 'Bollywood', 'Hindi', 128, 0.80, 0.86, 'JOY', '["EXCITEMENT"]', '["party","family","dance","energetic","celebration"]', 254000, 82);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Senorita', 'Farhan Akhtar, Arjun Rampal, Hrithik Roshan', 'Zindagi Na Milegi Dobara', 'Bollywood', 'Hindi', 120, 0.78, 0.84, 'EXCITEMENT', '["JOY"]', '["friendship","road-trip","upbeat","fun","energetic"]', 248000, 83);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Nagada Sang Dhol', 'Shreya Ghoshal, Osman Mir', 'Goliyon Ki Raasleela Ram-Leela', 'Bollywood', 'Hindi', 140, 0.90, 0.80, 'EXCITEMENT', '["JOY"]', '["festive","dance","garba","energetic","celebration"]', 275000, 80);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Malhari', 'Vishal Dadlani', 'Bajirao Mastani', 'Bollywood', 'Hindi', 150, 0.92, 0.75, 'EXCITEMENT', '["JOY"]', '["victory","dance","energetic","celebration","powerful"]', 221000, 85);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Sultan (Title Track)', 'Sukhwinder Singh', 'Sultan', 'Bollywood', 'Hindi', 132, 0.88, 0.70, 'EXCITEMENT', '["JOY"]', '["motivational","powerful","energetic","victory","sports"]', 213000, 79);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Hamari Adhuri Kahani', 'Arijit Singh', 'Hamari Adhuri Kahani', 'Bollywood', 'Hindi', 60, 0.28, 0.18, 'SADNESS', '["LOVE","MELANCHOLY"]', '["heartbreak","emotional","soulful","ballad","longing"]', 283000, 84);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tujhe Kitna Chaahne Lage', 'Arijit Singh', 'Kabir Singh', 'Bollywood', 'Hindi', 64, 0.30, 0.22, 'SADNESS', '["LOVE","MELANCHOLY"]', '["romantic","heartbreak","soulful","ballad","intense"]', 263000, 88);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Mere Dholna', 'Shreya Ghoshal, Kailash Kher', 'Bhool Bhulaiyaa', 'Bollywood', 'Hindi', 70, 0.32, 0.38, 'MELANCHOLY', '["SADNESS","NOSTALGIA"]', '["devotional","folk","soulful","classic","haunting"]', 295000, 76);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Hamdard', 'Arijit Singh', 'Ek Villain', 'Bollywood', 'Hindi', 62, 0.27, 0.25, 'MELANCHOLY', '["SADNESS","LOVE"]', '["romantic","emotional","soulful","ballad","tender"]', 280000, 85);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Lag Jaa Gale', 'Lata Mangeshkar', 'Woh Kaun Thi', 'Classic Bollywood', 'Hindi', 58, 0.20, 0.40, 'MELANCHOLY', '["LOVE","NOSTALGIA"]', '["classic","romantic","timeless","60s","tender"]', 230000, 82);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Bhaag D.K. Bose', 'Ram Sampath', 'Delhi Belly', 'Bollywood Rock', 'Hindi', 150, 0.90, 0.35, 'FRUSTRATION', '["STRESS","ANGER"]', '["energetic","rock","cathartic","intense","satirical"]', 240000, 68);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Emotional Atyachar', 'Hard Kaur, Rajan Batra', 'Dev D', 'Bollywood Rock', 'Hindi', 145, 0.85, 0.30, 'FRUSTRATION', '["ANGER","STRESS"]', '["breakup","rock","energetic","cathartic","intense"]', 256000, 66);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Zinda', 'Siddharth Mahadevan', 'Bhaag Milkha Bhaag', 'Bollywood Rock', 'Hindi', 135, 0.88, 0.55, 'ANGER', '["EXCITEMENT","FRUSTRATION"]', '["motivational","intense","powerful","driving","sports"]', 265000, 77);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tum Se Hi', 'Mohit Chauhan', 'Jab We Met', 'Bollywood', 'Hindi', 76, 0.30, 0.68, 'RELAXATION', '["LOVE","CALM"]', '["romantic","soft","soothing","gentle","melodic"]', 258000, 87);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Luka Chuppi', 'A.R. Rahman, Lata Mangeshkar', 'Rang De Basanti', 'Bollywood', 'Hindi', 66, 0.22, 0.30, 'RELAXATION', '["MELANCHOLY","CALM"]', '["devotional","soft","emotional","soothing","tender"]', 330000, 81);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tu Jo Mila', 'K.K.', 'Bajrangi Bhaijaan', 'Bollywood', 'Hindi', 84, 0.42, 0.70, 'RELAXATION', '["JOY","LOVE"]', '["uplifting","soft-rock","feel-good","melodic","warm"]', 270000, 78);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tera Yaar Hoon Main', 'Arijit Singh', 'Sonu Ke Titu Ki Sweety', 'Bollywood', 'Hindi', 80, 0.36, 0.64, 'LOVE', '["NOSTALGIA","JOY"]', '["friendship","soulful","warm","acoustic","melodic"]', 262000, 86);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Kesariya', 'Arijit Singh', 'Brahmastra', 'Bollywood', 'Hindi', 96, 0.50, 0.78, 'LOVE', '["JOY","EXCITEMENT"]', '["romantic","uplifting","melodic","modern","warm"]', 268000, 93);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Yeh Dosti', 'Kishore Kumar, Manna Dey', 'Sholay', 'Classic Bollywood', 'Hindi', 112, 0.60, 0.82, 'NOSTALGIA', '["JOY","LOVE"]', '["friendship","classic","70s","cheerful","iconic"]', 290000, 89);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tujhe Dekha To', 'Lata Mangeshkar, Kumar Sanu', 'Dilwale Dulhania Le Jayenge', 'Classic Bollywood', 'Hindi', 90, 0.44, 0.74, 'NOSTALGIA', '["LOVE","JOY"]', '["romantic","classic","90s","iconic","melodic"]', 275000, 90);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Kahin Deep Jale', 'Lata Mangeshkar', 'Bees Saal Baad', 'Classic Bollywood', 'Hindi', 54, 0.16, 0.45, 'NOSTALGIA', '["CALM","MELANCHOLY"]', '["classic","60s","devotional","soft","timeless"]', 260000, 73);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Ghar More Pardesiya', 'Shreya Ghoshal, Jyoti Nooran, Sukriti Kakar', 'Kalank', 'Bollywood Classical', 'Hindi', 72, 0.34, 0.42, 'MELANCHOLY', '["NOSTALGIA","SADNESS"]', '["thumri","classical-bollywood","soulful","wedding","bittersweet"]', 315000, 84);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('O Re Piya', 'Rahat Fateh Ali Khan', 'Aaja Nachle', 'Bollywood', 'Hindi', 68, 0.30, 0.58, 'LOVE', '["CALM","NOSTALGIA"]', '["sufi","romantic","soulful","soft","classical-bollywood"]', 295000, 80);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tum Hi Dekho Na', 'Shaan', 'Saawariya', 'Bollywood', 'Hindi', 60, 0.24, 0.50, 'CALM', '["LOVE","MELANCHOLY"]', '["romantic","soft","piano","gentle","intimate"]', 300000, 72);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Tu Chal', 'Rekha Bhardwaj, Sukhwinder Singh', 'Highway', 'Bollywood Folk', 'Hindi', 82, 0.40, 0.60, 'RELAXATION', '["JOY","NOSTALGIA"]', '["road-trip","folk","free-spirited","warm","acoustic"]', 255000, 70);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Phir Na Milo Kabhi', 'Mohit Chauhan', 'Rab Ne Bana Di Jodi', 'Bollywood', 'Hindi', 64, 0.26, 0.28, 'SADNESS', '["MELANCHOLY","LOVE"]', '["heartbreak","soulful","ballad","emotional","soft"]', 270000, 75);

INSERT INTO tracks (title, artist, album, genre, language, tempo_bpm, energy_level, valence, primary_emotion, secondary_emotions, mood_tags, duration_ms, popularity) VALUES
('Kabira (Encore)', 'Tochi Raina, Rekha Bhardwaj', 'Yeh Jawaani Hai Deewani', 'Bollywood', 'Hindi', 70, 0.30, 0.55, 'NOSTALGIA', '["LOVE","CALM"]', '["sufi","soulful","wistful","acoustic","reflective"]', 245000, 78);
