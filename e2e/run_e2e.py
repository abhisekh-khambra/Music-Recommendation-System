"""
MoodTunes end-to-end scenario suite — runs every input mode against the LIVE backend API and
checks each response against expectations written before the run (ground truth for speaker
roles comes from fixtures/manifest.json, produced by make_fixtures.ps1).

    python e2e/run_e2e.py [--base http://localhost:8080/api/v1] [--backend-log path/to/backend.log]

The Groq free tier allows ~8,000 tokens/minute, so the suite paces itself and, when a
--backend-log is given, detects rate-limit fallbacks (HTTP 429) and retries that scenario
once the limit has reset, so a rate-limit is not mistaken for a pipeline defect.
Writes e2e/results/e2e_results.json and e2e/results/e2e_results.md.
"""
import argparse
import json
import re
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent
FIXTURES = ROOT / "fixtures"
RESULTS = ROOT / "results"
CATALOG_JAVA = ROOT.parent / "backend/src/main/java/com/musicrec/reference/RagaRasaCatalog.java"

CANONICAL = {"JOY", "SADNESS", "STRESS", "ANGER", "RELAXATION", "ANXIETY",
             "EXCITEMENT", "MELANCHOLY", "NOSTALGIA", "LOVE", "FRUSTRATION", "CALM"}

PACE_TEXT_S = 20         # pause after a text/voice call
PACE_CONVERSATION_S = 45  # pause after a conversation call (several LLM calls each)
RATE_LIMIT_WAIT_S = 65


def load_raga_catalog():
    """Raga -> emotions, parsed from RagaRasaCatalog.java so the check uses the real catalog."""
    src = CATALOG_JAVA.read_text(encoding="utf-8")
    pattern = re.compile(r'new RagaProfile\("([^"]+)",\s*"[^"]*",\s*List\.of\(([^)]*)\)', re.S)
    return {m.group(1): set(re.findall(r'"([A-Z]+)"', m.group(2))) for m in pattern.finditer(src)}


class Suite:
    def __init__(self, base, backend_log):
        self.base = base.rstrip("/")
        self.log = Path(backend_log) if backend_log else None
        self.manifest = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8-sig"))
        self.catalog = load_raga_catalog()
        self.results = []
        self.cache = {}

    # ── infrastructure ───────────────────────────────────────────

    def _rate_limit_hits(self):
        if not self.log or not self.log.exists():
            return 0
        return self.log.read_text(encoding="utf-8", errors="replace").count("rate_limit_exceeded")

    def call(self, method, path, pace, **kwargs):
        """HTTP call that retries once if the backend hit the Groq rate limit while serving it."""
        for attempt in (1, 2):
            before = self._rate_limit_hits()
            started = time.time()
            resp = requests.request(method, self.base + path, timeout=240, **kwargs)
            elapsed = time.time() - started
            limited = self._rate_limit_hits() > before
            time.sleep(pace)
            if not limited or attempt == 2:
                return resp, elapsed, limited, attempt
            print(f"    rate-limited during {path} — waiting {RATE_LIMIT_WAIT_S}s and retrying")
            time.sleep(RATE_LIMIT_WAIT_S)

    def upload(self, path, file_name, mime, pace, data=None):
        with open(FIXTURES / file_name, "rb") as fh:
            return self.call("POST", path, pace, files={"audio": (file_name, fh, mime)}, data=data or {})

    def record(self, sid, mode, scenario, expected, checks, elapsed=None, limited=False, attempts=1, observed=""):
        passed = all(ok for _, ok, _ in checks)
        self.results.append({
            "id": sid, "mode": mode, "scenario": scenario, "expected": expected,
            "status": "PASS" if passed else "FAIL",
            "checks": [{"check": c, "passed": ok, "detail": d} for c, ok, d in checks],
            "observed": observed, "seconds": round(elapsed, 1) if elapsed else None,
            "rateLimitedOnFinalAttempt": limited, "attempts": attempts,
        })
        print(f"[{'PASS' if passed else 'FAIL'}] {sid} {scenario}")
        for c, ok, d in checks:
            if not ok:
                print(f"       ✗ {c}: {d}")

    # ── shared assertions ────────────────────────────────────────

    @staticmethod
    def track_checks(tracks):
        ids = [t.get("id") for t in tracks]
        non_hindi = [f"{t.get('title')} ({t.get('language')})" for t in tracks if t.get("language") != "Hindi"]
        no_expl = [t.get("title") for t in tracks if not (t.get("explanation") or "").strip()]
        return [
            ("1-10 tracks returned", 1 <= len(tracks) <= 10, f"{len(tracks)} tracks"),
            ("all tracks Hindi", not non_hindi, f"non-Hindi: {non_hindi}" if non_hindi else "all Hindi"),
            ("every track has an explanation", not no_expl, f"missing: {no_expl}" if no_expl else "ok"),
            ("no duplicate tracks", len(ids) == len(set(ids)), f"{len(ids)} ids, {len(set(ids))} unique"),
        ]

    def raga_check(self, raga_sel, emotions):
        raga = (raga_sel or {}).get("raga")
        raga_emotions = self.catalog.get(raga, set())
        overlap = raga_emotions & set(emotions)
        return [
            ("raga is from the catalog", raga in self.catalog, f"raga={raga}"),
            ("raga's catalog emotions match a detected emotion", bool(overlap),
             f"{raga} covers {sorted(raga_emotions)}; detected {sorted(set(emotions))}"),
            ("raga selection gives a reason", bool((raga_sel or {}).get("reason")), "reason present" if (raga_sel or {}).get("reason") else "no reason"),
        ]


    @staticmethod
    def distribution_checks(d):
        """Dominant emotion and % distribution must be derived exactly from the returned per-response emotions."""
        var = d.get("emotionalVariability") or {}
        segs = var.get("segments") or []
        if not segs:
            return [("no segments -> no dominant emotion", var.get("dominantEmotion") is None, f"dominant={var.get('dominantEmotion')}")]
        counts, intensity = {}, {}
        for sg in segs:
            counts[sg["primaryEmotion"]] = counts.get(sg["primaryEmotion"], 0) + 1
            intensity[sg["primaryEmotion"]] = intensity.get(sg["primaryEmotion"], 0) + (sg.get("intensity") or 0)
        expected_dominant = sorted(counts, key=lambda e: (-counts[e], -intensity[e]))[0]
        dist = var.get("emotionDistribution") or {}
        expected_pct = {e: round(100 * c / len(segs), 1) for e, c in counts.items()}
        pct_ok = set(dist) == set(counts) and all(abs(dist[e] - expected_pct[e]) <= 0.15 for e in counts)
        reason = (d.get("ragaSelection") or {}).get("reason") or ""
        return [
            ("dominant emotion = most frequent response emotion", var.get("dominantEmotion") == expected_dominant,
             f"reported {var.get('dominantEmotion')}, computed {expected_dominant} from {counts}"),
            ("distribution % matches response counts", pct_ok, f"reported {dist}, computed {expected_pct}"),
            ("distribution sums to 100%", abs(sum(dist.values()) - 100) < 0.05, f"sum={sum(dist.values()):.1f}"),
            ("raga reason states coverage of the distribution", "% of the patient's responses" in reason, reason[-120:]),
        ]

    # ── TEXT mode ────────────────────────────────────────────────

    TEXT_CASES = [
        ("T01", "English — anxiety", "I have my final exam tomorrow, my heart is racing and I cannot stop worrying that I will fail.", {"ANXIETY", "STRESS"}),
        ("T02", "English — sadness/grief", "My grandmother passed away last week. I feel empty and I keep crying at night.", {"SADNESS", "MELANCHOLY"}),
        ("T03", "English — joy", "I just got my dream job offer! I am so happy I want to dance all day.", {"JOY", "EXCITEMENT"}),
        ("T04", "English — anger", "My colleague took credit for my work in front of the boss again. I am absolutely furious.", {"ANGER", "FRUSTRATION"}),
        ("T05", "English — nostalgia", "I keep looking at my old school photos and missing my childhood friends and those simpler days.", {"NOSTALGIA"}),
        ("T06", "English — calm", "Sitting by the lake at sunset with a cup of tea, everything feels peaceful and still.", {"CALM", "RELAXATION"}),
        ("T07", "Hinglish (Roman script) — stress", "Aaj bahut tension hai, office ka kaam khatam hi nahi ho raha aur boss baar baar pressure de raha hai.", {"STRESS", "ANXIETY", "FRUSTRATION"}),
        ("T08", "Hindi (Devanagari) — sadness", "मैं बहुत उदास हूँ, कोई मेरी बात नहीं समझता और मैं अकेला महसूस करता हूँ।", {"SADNESS", "MELANCHOLY"}),
    ]

    def run_text(self):
        for sid, scenario, msg, expected in self.TEXT_CASES:
            resp, elapsed, limited, attempts = self.call("POST", "/recommend/text", PACE_TEXT_S,
                                                         json={"message": msg, "sessionId": f"e2e-{sid}"})
            checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}")]
            observed = ""
            if resp.status_code == 200:
                d = resp.json()
                e = d.get("emotion") or {}
                primary = e.get("primary_emotion")
                fallback = "Fallback" in (e.get("context_summary") or "")
                checks += [
                    ("emotion is a canonical label", primary in CANONICAL, f"primary={primary}"),
                    (f"emotion in {sorted(expected)}", primary in expected, f"primary={primary}, secondary={e.get('secondary_emotions')}"),
                    ("LLM analysis used (not keyword fallback)", not fallback, "keyword fallback" if fallback else "LLM"),
                ] + self.track_checks(d.get("recommendations") or [])
                self.cache[sid] = {t["id"] for t in d.get("recommendations") or []}
                observed = f"{primary} (intensity {e.get('intensity')}), {len(d.get('recommendations') or [])} songs"
            self.record(sid, "Text", scenario, f"emotion ∈ {sorted(expected)}; ≤10 Hindi songs with reasons",
                        checks, elapsed, limited, attempts, observed)

        # Edge cases
        for sid, scenario, body in [("T09", "Empty message", {"message": ""}),
                                    ("T10", "Whitespace-only message", {"message": "    "})]:
            resp, elapsed, _, _ = self.call("POST", "/recommend/text", 1, json=body)
            self.record(sid, "Text", scenario, "HTTP 400, clear error",
                        [("rejected with HTTP 400", resp.status_code == 400, f"HTTP {resp.status_code}: {resp.text[:120]}")],
                        elapsed, observed=f"HTTP {resp.status_code}")

        resp, elapsed, limited, attempts = self.call("POST", "/recommend/text", PACE_TEXT_S,
                                                     json={"message": "asdf qwerty zxcv lorem ipsum 12345"})
        checks = [("HTTP 200 (no crash)", resp.status_code == 200, f"HTTP {resp.status_code}")]
        observed = f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            primary = (resp.json().get("emotion") or {}).get("primary_emotion")
            checks.append(("emotion is still a canonical label", primary in CANONICAL, f"primary={primary}"))
            checks += self.track_checks(resp.json().get("recommendations") or [])
            observed = f"{primary}, {len(resp.json().get('recommendations') or [])} songs"
        self.record("T11", "Text", "Gibberish input", "No crash; valid label; Hindi-only songs", checks, elapsed, limited, attempts, observed)

        a, b = self.cache.get("T03", set()), self.cache.get("T02", set())
        jacc = len(a & b) / len(a | b) if a | b else 1.0
        self.record("T12", "Text", "Playlist is dynamic: joy (T03) vs sadness (T02)", "Overlap (Jaccard) < 0.5",
                    [("different moods give different playlists", jacc < 0.5, f"Jaccard={jacc:.2f}, shared={len(a & b)}")],
                    observed=f"Jaccard {jacc:.2f} ({len(a & b)} shared songs)")

    # ── VOICE mode ───────────────────────────────────────────────

    def run_voice(self):
        resp, elapsed, limited, attempts = self.upload("/recommend/voice", "voice_nostalgic.wav", "audio/wav", PACE_TEXT_S, {"sessionId": "e2e-V01"})
        checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}")]
        observed = f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            d = resp.json()
            e = d.get("emotion") or {}
            prof = e.get("audio_emotion_profile") or {}
            checks += [
                ("transcript produced", bool((d.get("transcript") or "").strip()), (d.get("transcript") or "")[:60]),
                ("emotion = NOSTALGIA", e.get("primary_emotion") == "NOSTALGIA", f"primary={e.get('primary_emotion')}"),
                ("acoustic arousal measured (not UNKNOWN)", prof.get("arousalLevel") not in (None, "UNKNOWN"),
                 f"arousal={prof.get('arousalLevel')} score={prof.get('arousalScore')}"),
                ("audio limitations disclosed", bool(prof.get("limitations")), "present" if prof.get("limitations") else "missing"),
            ] + self.track_checks(d.get("recommendations") or [])
            observed = f"{e.get('primary_emotion')}; arousal {prof.get('arousalLevel')} ({prof.get('arousalScore')})"
        self.record("V01", "Voice", "Single-speaker nostalgic clip", "Transcript; NOSTALGIA; measured arousal; Hindi songs",
                    checks, elapsed, limited, attempts, observed)

        scores = {}
        for name in ("high_arousal", "low_arousal"):
            resp, _, _, _ = self.upload("/recommend/voice", f"voice_{name}.wav", "audio/wav", PACE_TEXT_S)
            prof = ((resp.json().get("emotion") or {}).get("audio_emotion_profile") or {}) if resp.status_code == 200 else {}
            scores[name] = (prof.get("arousalLevel"), prof.get("arousalScore"))
        hi, lo = scores["high_arousal"], scores["low_arousal"]
        self.record("V02", "Voice", "Excited (loud/fast/high) vs calm (soft/slow/low) delivery — same voice",
                    "Arousal score: excited > calm",
                    [("both measured", None not in (hi[1], lo[1]) and "UNKNOWN" not in (hi[0], lo[0]), f"excited={hi}, calm={lo}"),
                     ("excited scores higher than calm", (hi[1] or 0) > (lo[1] or 0), f"{hi[1]} vs {lo[1]}")],
                    observed=f"excited {hi[0]} {hi[1]} vs calm {lo[0]} {lo[1]}")

        resp, elapsed, limited, attempts = self.upload("/recommend/voice", "silence.wav", "audio/wav", PACE_TEXT_S)
        if resp.status_code == 200:
            d = resp.json()
            prof = (d.get("emotion") or {}).get("audio_emotion_profile") or {}
            transcript = (d.get("transcript") or "").strip()
            checks = [("no speech is not presented as a measured result",
                       prof.get("arousalLevel") in (None, "UNKNOWN"), f"arousal={prof.get('arousalLevel')}"),
                      ("no songs recommended from a hallucinated transcript", not transcript,
                       f"transcript='{transcript[:60]}', {len(d.get('recommendations') or [])} songs")]
            observed = f"HTTP 200, transcript '{transcript[:40]}', arousal {prof.get('arousalLevel')}"
        else:
            checks = [("rejected with 4xx and a clear message", 400 <= resp.status_code < 500, f"HTTP {resp.status_code}: {resp.text[:120]}")]
            observed = f"HTTP {resp.status_code}"
        self.record("V03", "Voice", "3 s of silence", "Rejected (4xx) or honest UNKNOWN — never a fabricated emotion",
                    checks, elapsed, limited, attempts, observed)

        for sid, scenario, f, mime in [("V04", "Text file disguised as .mp3", "not_audio.mp3", "audio/mpeg"),
                                       ("V05", "Zero-byte file", "empty.mp3", "audio/mpeg")]:
            resp, elapsed, _, _ = self.upload("/recommend/voice", f, mime, 2)
            self.record(sid, "Voice", scenario, "HTTP 4xx, clear error",
                        [("rejected with 4xx", 400 <= resp.status_code < 500, f"HTTP {resp.status_code}: {resp.text[:120]}")],
                        elapsed, observed=f"HTTP {resp.status_code}")

    # ── CONVERSATION mode ────────────────────────────────────────

    def conversation_checks(self, d, truth):
        turns = sorted(d.get("turns") or [], key=lambda t: t["order"])
        roles = [t["speakerRole"] for t in turns]
        correct = sum(1 for a, b in zip(roles, truth) if a == b)
        segs = (d.get("emotionalVariability") or {}).get("segments") or []
        seg_orders = sorted(s["order"] for s in segs)
        truth_patient = [i for i, r in enumerate(truth) if r == "PATIENT"]
        emotions = [s["primaryEmotion"] for s in sorted(segs, key=lambda s: s["order"])]
        transitions = sum(1 for a, b in zip(emotions, emotions[1:]) if a != b)
        var = d.get("emotionalVariability") or {}
        prof = d.get("audioEmotionProfile") or {}
        analysis = d.get("analysis") or {}
        detected = [analysis.get("primaryEmotionalState")] + list(analysis.get("secondaryEmotionalStates") or []) + emotions
        missing_ts = [t["order"] for t in turns if t.get("endMs", 0) <= t.get("startMs", 0)]
        checks = [
            ("turn count matches recording", len(turns) == len(truth), f"{len(turns)} turns vs {len(truth)} spoken"),
            ("speaker roles 100% correct", correct == len(truth) and len(turns) == len(truth), f"{correct}/{len(truth)} correct; got {roles}"),
            ("every turn has a valid timestamp", not missing_ts, f"bad timestamps on turns {missing_ts}" if missing_ts else "ok"),
            ("emotion analysed only on Patient turns", seg_orders == truth_patient, f"segments {seg_orders} vs patient turns {truth_patient}"),
            ("acoustic arousal measured on patient audio", prof.get("arousalLevel") not in (None, "UNKNOWN"),
             f"arousal={prof.get('arousalLevel')} pitch={prof.get('meanPitchHz')}"),
            ("variability distinct count matches segments", var.get("distinctEmotionCount") == len(set(emotions)),
             f"reported {var.get('distinctEmotionCount')}, actual {len(set(emotions))}"),
            ("variability transition count matches segments", var.get("transitionCount") == transitions,
             f"reported {var.get('transitionCount')}, actual {transitions}"),
            ("all labels canonical", all(x in CANONICAL for x in detected if x), f"{detected}"),
        ]
        checks += self.raga_check(d.get("ragaSelection"), detected)
        checks += self.distribution_checks(d)
        checks += self.track_checks(d.get("recommendations") or [])
        observed = (f"roles {correct}/{len(truth)}; uncertain={d.get('speakerAssignmentUncertain')}; "
                    f"patient emotions {emotions}; arousal {prof.get('arousalLevel')} @ {prof.get('meanPitchHz')} Hz; "
                    f"raga {(d.get('ragaSelection') or {}).get('raga')}")
        return checks, observed

    def run_conversation(self):
        convs = self.manifest["conversations"]

        for sid, key, scenario, extra in [
            ("C01", "conv_male_consultant_female_patient", "Male consultant + female patient, 7 turns",
             {"patient emotions include anxiety, nostalgia, sadness": lambda e: bool({"ANXIETY", "STRESS"} & e) and "NOSTALGIA" in e and bool({"SADNESS", "MELANCHOLY", "FRUSTRATION"} & e)}),
            ("C02", "conv_female_consultant_male_patient_patient_first", "Female consultant + male patient, patient speaks first",
             {"patient emotions include anger": lambda e: bool({"ANGER", "FRUSTRATION"} & e)}),
        ]:
            truth = convs[key]["roles"]
            resp, elapsed, limited, attempts = self.upload("/conversation/analyze", convs[key]["file"], "audio/mpeg",
                                                           PACE_CONVERSATION_S, {"sessionId": f"e2e-{sid}"})
            checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}: {resp.text[:120] if resp.status_code != 200 else ''}")]
            observed = f"HTTP {resp.status_code}"
            if resp.status_code == 200:
                d = resp.json()
                self.cache[sid] = d
                c, observed = self.conversation_checks(d, truth)
                checks += c
                checks.append(("separation reported as reliable", d.get("speakerAssignmentUncertain") is False,
                               f"uncertain={d.get('speakerAssignmentUncertain')}"))
                seg_emotions = {s["primaryEmotion"] for s in (d.get("emotionalVariability") or {}).get("segments") or []}
                for name, fn in extra.items():
                    checks.append((name, fn(seg_emotions), f"{sorted(seg_emotions)}"))
                if sid == "C02":
                    pitch = (d.get("audioEmotionProfile") or {}).get("meanPitchHz") or 0
                    checks.append(("patient audio is the male voice (pitch < 150 Hz)", 0 < pitch < 150, f"{pitch} Hz"))
            self.record(sid, "Conversation", scenario, "Roles 100% correct; patient-only emotion; measured arousal; raga; Hindi songs",
                        checks, elapsed, limited, attempts, observed)

        key = "conv_single_voice_both_roles"
        resp, elapsed, limited, attempts = self.upload("/conversation/analyze", convs[key]["file"], "audio/mpeg",
                                                       PACE_CONVERSATION_S, {"sessionId": "e2e-C03"})
        checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}")]
        observed = f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            d = resp.json()
            checks.append(("one voice is NOT accepted as a confident two-speaker split",
                           d.get("speakerAssignmentUncertain") is True,
                           f"uncertain={d.get('speakerAssignmentUncertain')}; method={(d.get('diarizationMethod') or '')[:90]}"))
            checks += self.track_checks(d.get("recommendations") or [])
            observed = f"uncertain={d.get('speakerAssignmentUncertain')}; roles {[t['speakerRole'][0] for t in sorted(d.get('turns') or [], key=lambda t: t['order'])]}"
        self.record("C03", "Conversation", "Same voice reads both roles", "Flagged uncertain (speakerAssignmentUncertain = true)",
                    checks, elapsed, limited, attempts, observed)

        key = "conv_single_turn"
        resp, elapsed, limited, attempts = self.upload("/conversation/analyze", convs[key]["file"], "audio/mpeg",
                                                       PACE_CONVERSATION_S, {"sessionId": "e2e-C04"})
        checks = [("HTTP 200 (no crash)", resp.status_code == 200, f"HTTP {resp.status_code}")]
        observed = f"HTTP {resp.status_code}"
        if resp.status_code == 200:
            d = resp.json()
            checks.append(("flagged uncertain (one turn cannot prove two speakers)", d.get("speakerAssignmentUncertain") is True,
                           f"uncertain={d.get('speakerAssignmentUncertain')}"))
            checks += self.track_checks(d.get("recommendations") or [])
            observed = f"uncertain={d.get('speakerAssignmentUncertain')}, {len(d.get('turns') or [])} turn(s)"
        self.record("C04", "Conversation", "Only one turn in the recording", "No crash; flagged uncertain", checks, elapsed, limited, attempts, observed)

        # Manual role correction, using the C01 turns with roles deliberately swapped by the "user"
        base = self.cache.get("C01")
        if base:
            turns = [{"order": t["order"], "text": t["text"], "startMs": t["startMs"], "endMs": t["endMs"],
                      "speakerRole": "PATIENT" if t["order"] in (0, 2) else "HEALTH_CONSULTANT"}
                     for t in base["turns"]]
            resp, elapsed, limited, attempts = self.call("POST", "/conversation/reassign-roles", PACE_CONVERSATION_S,
                                                         json={"sessionId": "e2e-C05", "turns": turns})
            checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}")]
            observed = f"HTTP {resp.status_code}"
            if resp.status_code == 200:
                d = resp.json()
                expected_text = " ".join(t["text"] for t in sorted(turns, key=lambda t: t["order"]) if t["speakerRole"] == "PATIENT")
                prof = d.get("audioEmotionProfile") or {}
                checks += [
                    ("patient text rebuilt from the corrected roles", (d.get("patientText") or "").strip() == expected_text.strip(),
                     (d.get("patientText") or "")[:80]),
                    ("audio honestly NOT re-analysed (UNKNOWN)", prof.get("arousalLevel") in (None, "UNKNOWN"), f"arousal={prof.get('arousalLevel')}"),
                    ("user is warned about the correction", any("manually corrected" in w for w in d.get("warnings") or []), f"{d.get('warnings')}"),
                ] + self.track_checks(d.get("recommendations") or [])
                observed = f"patient turns {[t['order'] for t in turns if t['speakerRole'] == 'PATIENT']}; emotion {(d.get('analysis') or {}).get('primaryEmotionalState')}"
            self.record("C05", "Conversation", "User corrects speaker roles (reassign-roles)", "Recomputed from corrected roles; audio not re-claimed",
                        checks, elapsed, limited, attempts, observed)
        else:
            self.record("C05", "Conversation", "User corrects speaker roles (reassign-roles)", "—",
                        [("prerequisite C01 succeeded", False, "C01 did not return turns")])

        resp, elapsed, _, _ = self.call("POST", "/conversation/reassign-roles", 1, json={"turns": []})
        self.record("C06", "Conversation", "reassign-roles with no turns", "HTTP 400",
                    [("rejected with HTTP 400", resp.status_code == 400, f"HTTP {resp.status_code}: {resp.text[:100]}")], elapsed,
                    observed=f"HTTP {resp.status_code}")

        resp, elapsed, _, _ = self.upload("/conversation/analyze", "not_audio.mp3", "audio/mpeg", 2)
        self.record("C07", "Conversation", "Text file disguised as .mp3", "HTTP 4xx, clear error",
                    [("rejected with 4xx", 400 <= resp.status_code < 500, f"HTTP {resp.status_code}: {resp.text[:120]}")], elapsed,
                    observed=f"HTTP {resp.status_code}")


    # ── REALISTIC neural-voice recordings (make_realistic_fixtures.py) ─────

    FAMILIES = {"ANXIETY": {"ANXIETY", "STRESS"}, "STRESS": {"STRESS", "ANXIETY", "FRUSTRATION"},
                "ANGER": {"ANGER", "FRUSTRATION"}, "FRUSTRATION": {"FRUSTRATION", "ANGER", "STRESS"},
                "SADNESS": {"SADNESS", "MELANCHOLY"}, "NOSTALGIA": {"NOSTALGIA", "MELANCHOLY", "LOVE"},
                "CALM": {"CALM", "RELAXATION"}, "RELAXATION": {"RELAXATION", "CALM"},
                "JOY": {"JOY", "EXCITEMENT", "LOVE"}}

    @staticmethod
    def truth_index(turn, spans):
        """Index of the ground-truth turn that overlaps this returned turn the most."""
        best, best_overlap = None, 0
        for i, (a, b) in enumerate(spans):
            ov = min(b, turn["endMs"]) - max(a, turn["startMs"])
            if ov > best_overlap:
                best, best_overlap = i, ov
        return best

    def run_realistic(self):
        m = json.loads((FIXTURES / "manifest_realistic.json").read_text(encoding="utf-8"))
        scenarios = [
            ("R01", "real_hindi_male_consultant_female_patient", "Hindi conversation (neural voices), male consultant / female patient", "strict"),
            ("R02", "real_indian_english_male_consultant_female_patient", "Indian-English + Hinglish conversation, male consultant / female patient", "strict"),
            ("R03", "real_two_male_voices", "Two MALE speakers (English consultant, Hindi patient)", "safe"),
            ("R04", "real_two_female_voices", "Two FEMALE speakers (Hindi consultant, English patient)", "safe"),
            ("R05", "real_hindi_noisy_phone", "R01 degraded: background noise + phone band + quieter patient", "safe"),
            ("R06", "real_long_emotional_arc", "Long 14-turn session with a 7-step emotional arc", "strict"),
            ("R07", "real_backchannel_turns", "Very short consultant backchannels ('Hmm.', 'I see.')", "safe"),
        ]
        self.emotion_agreement = []
        for sid, key, scenario, mode in scenarios:
            conv = m["conversations"][key]
            spans, roles, intended = conv["spansMs"], conv["roles"], conv["intendedEmotions"]
            resp, elapsed, limited, attempts = self.upload("/conversation/analyze", conv["file"], "audio/mpeg",
                                                           PACE_CONVERSATION_S, {"sessionId": f"e2e-{sid}"})
            checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}: {resp.text[:120] if resp.status_code != 200 else ''}")]
            observed = f"HTTP {resp.status_code}"
            if resp.status_code == 200:
                d = resp.json()
                turns = sorted(d.get("turns") or [], key=lambda t: t["order"])
                mapped = [(t, self.truth_index(t, spans)) for t in turns]
                correct = sum(1 for t, i in mapped if i is not None and t["speakerRole"] == roles[i])
                acc = correct / len(turns) if turns else 0
                uncertain = d.get("speakerAssignmentUncertain")
                prof = d.get("audioEmotionProfile") or {}
                var = d.get("emotionalVariability") or {}
                segs = var.get("segments") or []
                patient_orders = {t["order"] for t in turns if t["speakerRole"] == "PATIENT"}

                # Emotion agreement: each analysed patient response vs what that scripted line was written to express.
                agree = total = 0
                for sg in segs:
                    i = self.truth_index(sg, spans)
                    if i is not None and roles[i] == "PATIENT" and intended[i]:
                        total += 1
                        agree += sg["primaryEmotion"] in self.FAMILIES.get(intended[i], {intended[i]})
                if total:
                    self.emotion_agreement.append((sid, agree, total))

                checks += [
                    ("never confidently wrong", uncertain is True or acc == 1.0,
                     f"role accuracy {acc:.0%} ({correct}/{len(turns)}), uncertain={uncertain}"),
                    ("every turn has a valid timestamp", all(t["endMs"] > t["startMs"] for t in turns), f"{len(turns)} turns"),
                    ("emotion analysed only on turns labelled Patient", {sg["order"] for sg in segs} <= patient_orders,
                     f"segments {sorted(sg['order'] for sg in segs)} vs patient turns {sorted(patient_orders)}"),
                    ("acoustic arousal measured", prof.get("arousalLevel") not in (None, "UNKNOWN"),
                     f"arousal={prof.get('arousalLevel')} @ {prof.get('meanPitchHz')} Hz"),
                ]
                if mode == "strict":
                    checks.append(("speakers separated confidently and 100% correct", uncertain is False and acc == 1.0,
                                   f"accuracy {acc:.0%}, uncertain={uncertain}"))
                if key.startswith("real_hindi"):
                    lang = (d.get("analysis") or {}).get("detectedLanguage")
                    checks.append(("Hindi speech recognised as Hindi", (lang or "").lower() == "hindi", f"detectedLanguage={lang}"))
                if key == "real_long_emotional_arc":
                    checks.append(("arc shows >= 4 distinct emotions", (var.get("distinctEmotionCount") or 0) >= 4,
                                   f"{var.get('distinctEmotionCount')} distinct, {var.get('transitionCount')} shifts"))
                detected = [(d.get("analysis") or {}).get("primaryEmotionalState")] + [sg["primaryEmotion"] for sg in segs]
                checks += self.raga_check(d.get("ragaSelection"), [x for x in detected if x])
                checks += self.distribution_checks(d)
                checks += self.track_checks(d.get("recommendations") or [])
                observed = (f"roles {acc:.0%} ({correct}/{len(turns)}), uncertain={uncertain}; "
                            f"dominant {var.get('dominantEmotion')} {var.get('emotionDistribution')}; "
                            f"emotion agreement {agree}/{total}; arousal {prof.get('arousalLevel')} @ {prof.get('meanPitchHz')} Hz; "
                            f"raga {(d.get('ragaSelection') or {}).get('raga')}")
            expected = ("Roles 100% correct and confident" if mode == "strict" else "Roles correct, or honestly flagged uncertain") \
                + "; dominant/distribution exact; raga covers mix; Hindi songs"
            self.record(sid, "Realistic conversation", scenario, expected, checks, elapsed, limited, attempts, observed)

        for sid, name, scenario, family in [("RV1", "real_voice_hindi_sad", "Hindi voice clip - sadness", "SADNESS"),
                                            ("RV2", "real_voice_hindi_sad_noisy", "Same clip with noise + phone band", "SADNESS")]:
            resp, elapsed, limited, attempts = self.upload("/recommend/voice", m["voice"][name], "audio/mpeg", PACE_TEXT_S)
            checks = [("HTTP 200", resp.status_code == 200, f"HTTP {resp.status_code}: {resp.text[:100] if resp.status_code != 200 else ''}")]
            observed = f"HTTP {resp.status_code}"
            if resp.status_code == 200:
                d = resp.json()
                e = d.get("emotion") or {}
                prof = e.get("audio_emotion_profile") or {}
                transcript = d.get("transcript") or ""
                checks += [
                    ("Hindi transcript produced",
                     any("\u0900" <= ch <= "\u097f" for ch in transcript) or (e.get("language") or "").lower() == "hindi",
                     transcript[:60]),
                    (f"emotion in {sorted(self.FAMILIES[family])}", e.get("primary_emotion") in self.FAMILIES[family],
                     f"primary={e.get('primary_emotion')}"),
                    ("acoustic arousal measured", prof.get("arousalLevel") not in (None, "UNKNOWN"), f"arousal={prof.get('arousalLevel')}"),
                ] + self.track_checks(d.get("recommendations") or [])
                observed = f"{e.get('primary_emotion')}; arousal {prof.get('arousalLevel')} ({prof.get('arousalScore')}); '{transcript[:30]}'"
            self.record(sid, "Realistic voice", scenario, f"Hindi transcript; {family.lower()}; measured arousal",
                        checks, elapsed, limited, attempts, observed)

        scores = {}
        for name in ("real_voice_excited", "real_voice_calm"):
            resp, _, _, _ = self.upload("/recommend/voice", m["voice"][name], "audio/mpeg", PACE_TEXT_S)
            prof = ((resp.json().get("emotion") or {}).get("audio_emotion_profile") or {}) if resp.status_code == 200 else {}
            scores[name] = (prof.get("arousalLevel"), prof.get("arousalScore"))
        hi, lo = scores["real_voice_excited"], scores["real_voice_calm"]
        self.record("RV3", "Realistic voice", "Neural voice: excited vs calm delivery", "Arousal excited > calm",
                    [("both measured", None not in (hi[1], lo[1]), f"excited={hi}, calm={lo}"),
                     ("excited scores higher than calm", (hi[1] or 0) > (lo[1] or 0), f"{hi[1]} vs {lo[1]}")],
                    observed=f"excited {hi[0]} {hi[1]} vs calm {lo[0]} {lo[1]}")

        if self.emotion_agreement:
            a = sum(x[1] for x in self.emotion_agreement)
            t = sum(x[2] for x in self.emotion_agreement)
            print(f"\nPer-response emotion agreement with scripted intent: {a}/{t} = {a / t:.0%}  "
                  + ", ".join(f"{sid} {x}/{y}" for sid, x, y in self.emotion_agreement))
            RESULTS.mkdir(exist_ok=True)
            (RESULTS / "emotion_agreement.json").write_text(json.dumps(
                {"agree": a, "total": t,
                 "byScenario": [{"id": s_, "agree": x, "total": y} for s_, x, y in self.emotion_agreement]}, indent=2))

    # ── SYSTEM ───────────────────────────────────────────────────

    def run_system(self):
        resp, elapsed, _, _ = self.call("GET", "/health", 0)
        d = resp.json() if resp.status_code == 200 else {}
        self.record("S01", "System", "Health endpoint", "UP, DB connected, tracks loaded",
                    [("status UP", d.get("status") == "UP", str(d)), ("database connected", d.get("database") == "CONNECTED", str(d.get("database"))),
                     ("tracks loaded", (d.get("tracksLoaded") or 0) > 0, str(d.get("tracksLoaded")))], elapsed,
                    observed=f"{d.get('status')}, DB {d.get('database')}, {d.get('tracksLoaded')} tracks")

        resp, elapsed, _, _ = self.call("GET", "/history/e2e-T01", 0)
        body = resp.json() if resp.status_code == 200 else None
        n = len(body) if isinstance(body, list) else (len(body.get("history", [])) if isinstance(body, dict) else 0)
        self.record("S02", "System", "Session history persisted to MySQL", "History for session e2e-T01 is non-empty",
                    [("history saved", resp.status_code == 200 and n > 0, f"HTTP {resp.status_code}, {n} entries")], elapsed,
                    observed=f"{n} entries")

    # ── report ───────────────────────────────────────────────────

    def write(self):
        RESULTS.mkdir(exist_ok=True)
        (RESULTS / "e2e_results.json").write_text(json.dumps(self.results, indent=2, ensure_ascii=False), encoding="utf-8")
        passed = sum(r["status"] == "PASS" for r in self.results)
        lines = [f"# E2E results — {time.strftime('%Y-%m-%d %H:%M')}", "",
                 f"**{passed}/{len(self.results)} scenarios passed.**", "",
                 "| ID | Mode | Scenario | Expected | Observed | Result |", "|---|---|---|---|---|---|"]
        for r in self.results:
            lines.append(f"| {r['id']} | {r['mode']} | {r['scenario']} | {r['expected']} | {r['observed']} | {r['status']} |")
        failures = [r for r in self.results if r["status"] == "FAIL"]
        if failures:
            lines += ["", "## Failed checks", ""]
            for r in failures:
                for c in r["checks"]:
                    if not c["passed"]:
                        lines.append(f"- **{r['id']}** {c['check']} — {c['detail']}")
        (RESULTS / "e2e_results.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"\n{passed}/{len(self.results)} scenarios passed — see {RESULTS / 'e2e_results.md'}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:8080/api/v1")
    p.add_argument("--backend-log", default=None)
    p.add_argument("--only", default="text,voice,conversation,realistic,system")
    a = p.parse_args()
    s = Suite(a.base, a.backend_log)
    for part in a.only.split(","):
        getattr(s, f"run_{part.strip()}")()
    s.write()
