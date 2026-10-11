# System architecture

Three input modes feed one emotion → Raga → Top-10 pipeline. Conversation mode (Health Consultant + Patient) is the main focus. Two offline evaluations measure it: one on labelled transcripts (emotion, Raga, song and overall levels) and one on labelled recordings (speaker separation and voice emotion).

```mermaid
flowchart TB
    subgraph IN["Input modes"]
        T["Text mode<br/>typed message"]
        V["Voice mode<br/>single speaker recording"]
        C["Conversation mode<br/>Health Consultant + Patient recording"]
    end

    subgraph SPEECH["Speech processing"]
        STT["Speech-to-text<br/>Whisper, timestamped segments"]
        SER["Speech-emotion service (local)<br/>wav2vec2 voice emotion + WavLM voiceprints"]
        AC["Acoustic analysis<br/>pitch + energy → vocal arousal"]
        DIA["Speaker separation<br/>voiceprints, else pitch clustering"]
        ROLE["Role labelling<br/>which voice is the Patient"]
    end

    subgraph EMO["Emotion analysis"]
        TE["Text emotion (LLM)<br/>12 canonical emotions"]
        FUS["Words + voice fusion<br/>emotionSource: TEXT / VOICE / FUSED"]
        VAR["Emotional variability<br/>dominant emotion + % distribution"]
    end

    subgraph REC["Recommendation"]
        RAGA["Raga selection<br/>overall + one per emotion share"]
        RET["Retrieve: emotion, Raga, share ragas, keyword,<br/>valence/energy, TF-IDF (Hindi only)"]
        RRF["Weighted reciprocal rank fusion"]
        EV["Evidence grounding + rerank"]
        SEL["LLM selection with<br/>evidence-only explanations"]
        SPLIT["Emotion-share split of the Top 10<br/>50% sad → 5 songs, each share prefers its raga"]
    end

    OUT["Top-10 Hindi songs<br/>+ Raga per share + per-song explanation"]
    PL["More playlists<br/>history · artist · raga"]

    subgraph EVAL["Evaluation (offline)"]
        DS["Labelled transcripts<br/>18 conversations"]
        AU["Labelled recordings<br/>turn times + emotions"]
        M["Emotion · Raga Top-1/Top-3 · Precision/Recall/NDCG@10<br/>song raga / emotion / genre accuracy · end-to-end"]
        MA["Speaker role accuracy (pitch vs voiceprint)<br/>words vs voice vs fused emotion"]
    end

    T --> TE
    V --> STT --> TE
    V --> SER
    V --> AC
    TE --> FUS
    SER --> FUS --> RET

    C --> STT
    C --> SER
    C --> AC
    STT --> DIA
    SER --> DIA
    AC --> DIA
    DIA --> ROLE --> TE
    FUS --> VAR --> RAGA
    VAR --> SPLIT
    RAGA --> RET

    RET --> RRF --> EV --> SEL
    SEL -- "text / voice" --> OUT
    SEL -- conversation --> SPLIT --> OUT
    OUT --> PL

    DS -. "speaker-labelled turns" .-> ROLE
    OUT -.-> M
    DS -.-> M
    AU -.-> SER
    AU -.-> MA
```

| Mode | Emotion comes from | Raga selection | Playlist |
|---|---|---|---|
| Text | the typed words (LLM) | one retrieval source | Top 10 by fused rank |
| Voice | the words and the voice (speech-emotion model), fused; vocal arousal adjusts intensity | one retrieval source | Top 10 by fused rank |
| Conversation | each Patient answer separately, words + voice fused per answer | central: one overall raga, plus one raga per emotion share | Top 10 split across the Patient's emotions by share, each share preferring its own raga |

**Emotion source.** Voice and conversation requests take `emotionSource`: `TEXT` (words only), `VOICE` (the voice decides) or `FUSED` (default). Fusion is a weighted average of the two signals' scores, with voice weight 0.3. On the synthetic test recordings, words alone scored 85%, the voice model alone 40% (4 classes) and fusion at 0.3 kept 85%. At weight 0.5 accuracy fell to 63%, so the voice only decides when the words are clearly ambiguous.

**Speaker separation.** Voiceprints (timbre) are tried first and pitch clustering second. Each only reports a confident split when the two voices are clearly different. Male/female pairs separate reliably. Same-gender synthetic voices are flagged uncertain, and the user can correct roles in the UI.

**Song library.** 121 tracks, including 25 Hindi film songs whose raga comes from a cited source (`database/raga_tagged_hindi_songs.json`). 17 of the 22 catalogue ragas now have Hindi songs. Todi, Marwa, Puriya Dhanashri, Shree and Hindol still have none.
