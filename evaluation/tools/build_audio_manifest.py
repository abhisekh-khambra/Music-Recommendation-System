"""Builds evaluation/audio/manifest_synthetic.json from the e2e text-to-speech fixtures.

Turn texts and intended patient emotions come from e2e/make_realistic_fixtures.py (CONVERSATIONS);
turn boundaries come from e2e/fixtures/manifest_realistic.json (spansMs), written when the audio was built.

    python evaluation/tools/build_audio_manifest.py
"""
import json
import sys
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "e2e" / "fixtures"
OUT = ROOT / "evaluation" / "audio" / "manifest_synthetic.json"


def load_conversations():
    # The fixture script imports edge_tts at module level; it isn't needed to read its data.
    sys.modules.setdefault("edge_tts", types.ModuleType("edge_tts"))
    source = (ROOT / "e2e" / "make_realistic_fixtures.py").read_text(encoding="utf-8")
    module = types.ModuleType("fixtures_script")
    module.__file__ = str(ROOT / "e2e" / "make_realistic_fixtures.py")
    exec(compile(source, module.__file__, "exec"), module.__dict__)
    return module.CONVERSATIONS, getattr(module, "NOISY_VARIANT", None)


def main():
    conversations, noisy = load_conversations()
    if noisy:
        conversations = {**conversations, noisy[0]: conversations[noisy[1]]}
    timing = json.loads((FIXTURES / "manifest_realistic.json").read_text(encoding="utf-8-sig"))["conversations"]

    recordings = []
    for name, turns in conversations.items():
        t = timing.get(name)
        if not t or len(t["spansMs"]) != len(turns):
            print(f"skip {name}: no matching timing")
            continue
        recordings.append({
            "id": name,
            "file": f"../../e2e/fixtures/{t['file']}",
            "turns": [{"role": role, "startMs": span[0], "endMs": span[1], "text": text, "emotion": emotion}
                      for (role, _voice, text, emotion), span in zip(turns, t["spansMs"])],
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "name": "synthetic-tts-v1",
        "source": "synthetic-tts",
        "description": "Text-to-speech conversations from the e2e fixtures (Microsoft neural voices). Checks the audio "
                       "pipeline end to end; not evidence of performance on real human speech.",
        "recordings": recordings,
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {OUT} with {len(recordings)} recordings")


if __name__ == "__main__":
    main()
