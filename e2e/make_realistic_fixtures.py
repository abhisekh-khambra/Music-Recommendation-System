"""
Generates the "realistic" audio fixtures for run_e2e.py --only realistic: conversations voiced by
Microsoft neural voices (Hindi and Indian English), including same-gender speaker pairs, a noisy
phone-quality recording, a long emotional arc and short backchannel turns. Ground truth (who speaks
each turn, and the emotion each patient line was written to express) goes to
fixtures/manifest_realistic.json.

    python e2e/make_realistic_fixtures.py --ffmpeg <path to ffmpeg.exe>

Requires the edge-tts package (pip install edge-tts==7.2.8) and network access: the scripted
lines below are sent to Microsoft's TTS service. No user data is sent.
"""
import argparse
import asyncio
import json
import subprocess
import wave
from pathlib import Path

import edge_tts

OUT = Path(__file__).resolve().parent / "fixtures"
TMP = OUT / "_realistic_turns"

HI_F, HI_M = "hi-IN-SwaraNeural", "hi-IN-MadhurNeural"
EN_F, EN_M = "en-IN-NeerjaNeural", "en-IN-PrabhatNeural"

C, P = "HEALTH_CONSULTANT", "PATIENT"

# Each turn: (role, voice, text, intended patient emotion or None). The emotion column documents
# what each patient line was written to express; the suite only uses it loosely (see run_e2e.py).
CONVERSATIONS = {
    "real_hindi_male_consultant_female_patient": [
        (C, HI_M, "नमस्ते, आइए बैठिए। पिछले हफ़्ते से आप कैसा महसूस कर रही हैं?", None),
        (P, HI_F, "सच कहूँ तो बहुत तनाव में हूँ। ऑफिस में काम इतना ज़्यादा है कि रात को नींद ही नहीं आती, दिल घबराता रहता है।", "STRESS"),
        (C, HI_M, "यह तो काफ़ी थका देने वाला लगता है। घर पर किसी से इस बारे में बात होती है?", None),
        (P, HI_F, "नहीं, मैं अकेली रहती हूँ। कभी कभी इतना अकेलापन लगता है कि बस रोना आ जाता है।", "SADNESS"),
        (C, HI_M, "आपको आख़िरी बार कब अच्छा महसूस हुआ था?", None),
        (P, HI_F, "पिछले महीने गाँव गई थी, माँ के हाथ का खाना खाया, बचपन की गलियों में घूमी। वो दिन बहुत याद आते हैं।", "NOSTALGIA"),
        (C, HI_M, "बहुत सुंदर याद है। आज हम कुछ आसान साँस लेने के अभ्यास करेंगे।", None),
        (P, HI_F, "ठीक है, आपसे बात करके थोड़ा हल्का महसूस हो रहा है।", "CALM"),
    ],
    "real_indian_english_male_consultant_female_patient": [
        (C, EN_M, "Good evening. Please have a seat. How was your week?", None),
        (P, EN_F, "Honestly, terrible. My manager shouted at me in front of the whole team and I was so angry I could not even speak.", "ANGER"),
        (C, EN_M, "That sounds humiliating. What did you do after the meeting?", None),
        (P, EN_F, "I went home and kept replaying it. Yaar, I am so frustrated, I work so hard and nobody notices.", "FRUSTRATION"),
        (C, EN_M, "Is there anything that helps you unwind when you feel like this?", None),
        (P, EN_F, "Music helps. Last night I listened to old songs on the balcony and finally felt relaxed for a while.", "RELAXATION"),
    ],
    "real_two_male_voices": [
        (C, EN_M, "Hello, please come in. How have you been sleeping lately?", None),
        (P, HI_M, "बिल्कुल अच्छी नींद नहीं आ रही। हर समय चिंता लगी रहती है कि नौकरी चली जाएगी।", "ANXIETY"),
        (C, EN_M, "That worry sounds constant. When did it start?", None),
        (P, HI_M, "दो महीने पहले कंपनी में छँटनी हुई थी। तब से डर लगता है और मन उदास रहता है।", "SADNESS"),
        (C, EN_M, "Thank you for telling me. Let us work on this together.", None),
        (P, HI_M, "जी, धन्यवाद। उम्मीद है धीरे धीरे सब ठीक होगा।", "CALM"),
    ],
    "real_two_female_voices": [
        (C, HI_F, "आइए, बैठिए। आज आप कैसी हैं?", None),
        (P, EN_F, "I am very anxious today. My daughter's exams are next week and I keep worrying about everything.", "ANXIETY"),
        (C, HI_F, "समझ सकती हूँ। आप अपने लिए क्या करती हैं?", None),
        (P, EN_F, "Nothing really. I miss the days when I used to paint and sing. I felt so happy back then.", "NOSTALGIA"),
        (C, HI_F, "शायद हम फिर से उन चीज़ों के लिए समय निकाल सकते हैं।", None),
        (P, EN_F, "Yes, I would love that. Just talking about it makes me smile.", "JOY"),
    ],
    "real_long_emotional_arc": [
        (C, EN_M, "Welcome back. Where would you like to start today?", None),
        (P, EN_F, "I have been so anxious. Every morning my heart races before I even get out of bed.", "ANXIETY"),
        (C, EN_M, "Does anything in particular trigger it?", None),
        (P, EN_F, "Work, mostly. And I am nervous all the time that I will make a mistake and lose my job.", "ANXIETY"),
        (C, EN_M, "How do things feel at home?", None),
        (P, EN_F, "My brother borrowed money and never returned it. I am really angry with him, we had a huge fight.", "ANGER"),
        (C, EN_M, "That must be painful, especially with family.", None),
        (P, EN_F, "It is. After the fight I just cried. I feel lonely and sad, like nobody is on my side.", "SADNESS"),
        (C, EN_M, "Was there a time you felt close to him?", None),
        (P, EN_F, "When we were kids we used to fly kites on the terrace every Sankranti. I miss those days so much.", "NOSTALGIA"),
        (C, EN_M, "Holding on to that memory might help you reconnect.", None),
        (P, EN_F, "Maybe. Right now I still feel heavy and sad inside.", "SADNESS"),
        (C, EN_M, "Let us take a slow breath together.", None),
        (P, EN_F, "Okay. That feels a little calmer, thank you.", "CALM"),
    ],
    "real_backchannel_turns": [
        (C, EN_M, "Tell me what happened this weekend.", None),
        (P, EN_F, "I had a panic attack at the mall. Everything was too loud and I could not breathe properly.", "ANXIETY"),
        (C, EN_M, "Hmm.", None),
        (P, EN_F, "My friend took me outside and after some time I felt better, but I was embarrassed and upset.", "SADNESS"),
        (C, EN_M, "I see.", None),
        (P, EN_F, "Now I am scared to go out again.", "ANXIETY"),
        (C, EN_M, "That fear makes sense. We will take it step by step.", None),
    ],
}

# The same Hindi conversation, degraded: background noise, telephone bandwidth, and a patient who is
# 6 dB quieter than the consultant (e.g. sitting farther from the phone).
NOISY_VARIANT = ("real_hindi_noisy_phone", "real_hindi_male_consultant_female_patient")

VOICE_CLIPS = {
    "real_voice_hindi_sad": (HI_F, "मुझे आजकल कुछ भी अच्छा नहीं लगता। मन बहुत उदास रहता है और बार बार रोना आता है।", {}),
    "real_voice_excited": (EN_F, "Oh my god, I got the scholarship! I am so excited, I cannot stop jumping around!",
                           {"rate": "+25%", "pitch": "+20Hz", "volume": "+20%"}),
    "real_voice_calm": (EN_F, "I am sitting by the window with a cup of tea. Everything feels slow and quiet today.",
                        {"rate": "-25%", "pitch": "-15Hz", "volume": "-30%"}),
}


def ffmpeg(ff, *args):
    subprocess.run([ff, "-loglevel", "error", "-y", *args], check=True)


async def tts(voice, text, dest, **prosody):
    await edge_tts.Communicate(text, voice, **prosody).save(str(dest))


def timeline(wavs, gaps):
    """Ground-truth [startMs, endMs] of each turn inside the joined recording."""
    spans, t = [], 0.0
    for w, gap in zip(wavs, gaps):
        with wave.open(str(w)) as f:
            dur = f.getnframes() / f.getframerate()
        spans.append([round(t * 1000), round((t + dur) * 1000)])
        t += dur + gap
    return spans


def join(ff, wavs, dest, gaps):
    """Concatenates 16 kHz mono WAV turns with the given silence after each, to a 64 kbps MP3."""
    inputs, filt, labels = [], "", ""
    for i, (w, gap) in enumerate(zip(wavs, gaps)):
        inputs += ["-i", str(w)]
        filt += f"[{i}:a]aresample=16000,apad=pad_dur={gap}[a{i}];"
        labels += f"[a{i}]"
    filt += f"{labels}concat=n={len(wavs)}:v=0:a=1[out]"
    ffmpeg(ff, *inputs, "-filter_complex", filt, "-map", "[out]", "-ac", "1", "-ar", "16000", "-b:a", "64k", str(dest))


async def build(ff):
    OUT.mkdir(exist_ok=True)
    TMP.mkdir(exist_ok=True)
    manifest = {"conversations": {}, "voice": {}}
    gap_cycle = [0.5, 0.9, 0.6, 1.1, 0.4, 0.8, 0.7]  # irregular pauses, like real turn-taking

    turn_wavs = {}
    for name, turns in CONVERSATIONS.items():
        wavs = []
        for i, (role, voice, text, _) in enumerate(turns):
            mp3, wav = TMP / f"{name}-{i}.mp3", TMP / f"{name}-{i}.wav"
            await tts(voice, text, mp3)
            ffmpeg(ff, "-i", str(mp3), "-ac", "1", "-ar", "16000", str(wav))
            wavs.append(wav)
        turn_wavs[name] = wavs
        gaps = [gap_cycle[i % len(gap_cycle)] for i in range(len(turns))]
        join(ff, wavs, OUT / f"{name}.mp3", gaps)
        manifest["conversations"][name] = {
            "file": f"{name}.mp3",
            "roles": [t[0] for t in turns],
            "spansMs": timeline(wavs, gaps),
            "intendedEmotions": [t[3] for t in turns],
            "voices": [t[1] for t in turns],
            "intendedPatientEmotions": [t[3] for t in turns if t[0] == P],
            "sameGenderSpeakers": name in ("real_two_male_voices", "real_two_female_voices"),
        }

    noisy, source = NOISY_VARIANT
    turns = CONVERSATIONS[source]
    adjusted = []
    for i, (wav, (role, *_)) in enumerate(zip(turn_wavs[source], turns)):
        out = TMP / f"{noisy}-{i}.wav"
        ffmpeg(ff, "-i", str(wav), "-af", "volume=-6dB" if role == P else "volume=0dB", str(out))
        adjusted.append(out)
    clean = TMP / f"{noisy}-clean.mp3"
    join(ff, adjusted, clean, [gap_cycle[i % len(gap_cycle)] for i in range(len(turns))])
    # Mix in pink noise ~15 dB below speech, then band-limit to telephone range (300-3400 Hz).
    ffmpeg(ff, "-i", str(clean), "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.02:sample_rate=16000",
           "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=first:normalize=0,highpass=f=300,lowpass=f=3400[out]",
           "-map", "[out]", "-ac", "1", "-ar", "16000", "-b:a", "64k", str(OUT / f"{noisy}.mp3"))
    manifest["conversations"][noisy] = {**manifest["conversations"][source], "file": f"{noisy}.mp3",
                                        "degraded": "pink noise, 300-3400 Hz phone band, patient -6 dB"}

    for name, (voice, text, prosody) in VOICE_CLIPS.items():
        mp3 = OUT / f"{name}.mp3"
        await tts(voice, text, mp3, **prosody)
        manifest["voice"][name] = f"{name}.mp3"
    noisy_clip = OUT / "real_voice_hindi_sad_noisy.mp3"
    ffmpeg(ff, "-i", str(OUT / "real_voice_hindi_sad.mp3"), "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.02:sample_rate=24000",
           "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=first:normalize=0,highpass=f=300,lowpass=f=3400[out]",
           "-map", "[out]", str(noisy_clip))
    manifest["voice"]["real_voice_hindi_sad_noisy"] = noisy_clip.name

    for f in TMP.iterdir():
        f.unlink()
    TMP.rmdir()
    (OUT / "manifest_realistic.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    for f in sorted(OUT.glob("real_*.mp3")):
        print(f"{f.name:55s} {f.stat().st_size:>8d} bytes")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ffmpeg", default="ffmpeg")
    asyncio.run(build(ap.parse_args().ffmpeg))
