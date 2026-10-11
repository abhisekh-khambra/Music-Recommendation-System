"""Human annotation for the evaluation — the parts that need people other than the developer.

  export: writes three CSV sheets to fill in (Excel / Google Sheets open them):
    emotion_labels.csv     a second annotator labels every patient response BLIND (without seeing the first labels)
    raga_genre_labels.csv  an expert lists the acceptable ragas / genres per case (';'-separated)
    song_judgments.csv     a judge grades each recommended song 0–3 for that conversation (needs an evaluation report)
  import: reads the filled sheets back into the dataset and prints inter-annotator agreement (Cohen's kappa).
          The evaluation then uses expert ragas, genres and human song grades instead of its automatic proxies.

    python evaluation/tools/annotation.py export [--report evaluation/results/eval-XXXX.json]
    python evaluation/tools/annotation.py import
"""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "evaluation" / "dataset" / "conversations.json"
DEFAULT_SHEETS = ROOT / "evaluation" / "annotation"

CANONICAL = {"JOY", "SADNESS", "STRESS", "ANGER", "RELAXATION", "ANXIETY",
             "EXCITEMENT", "MELANCHOLY", "NOSTALGIA", "LOVE", "FRUSTRATION", "CALM"}


def _write(path: Path, rows, fields):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8-sig", newline="") as f:  # BOM so Excel shows Hindi correctly
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)


def _read(path: Path):
    if not path.exists():
        return []
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def _patient_turns(case):
    return [(i, t) for i, t in enumerate(case["turns"]) if t.get("role", "").upper() == "PATIENT"]


def export(dataset_path: Path, report_path, out_dir: Path):
    data = json.loads(Path(dataset_path).read_text(encoding="utf-8"))
    emotions, ragas = [], []
    for c in data["cases"]:
        for i, t in _patient_turns(c):
            emotions.append({"case": c["id"], "turn": i, "text": t["text"], "annotator2_emotion": ""})
        ragas.append({"case": c["id"], "title": c.get("title", ""),
                      "patient_responses": " | ".join(t["text"] for _, t in _patient_turns(c)),
                      "expert_ragas": "", "expert_genres": ""})
    _write(out_dir / "emotion_labels.csv", emotions, ["case", "turn", "text", "annotator2_emotion"])
    _write(out_dir / "raga_genre_labels.csv", ragas, ["case", "title", "patient_responses", "expert_ragas", "expert_genres"])

    if report_path:
        report = json.loads(Path(report_path).read_text(encoding="utf-8"))
        songs = [{"case": c["id"], "title": title, "grade_0_to_3": ""}
                 for c in report["cases"] for title in (c.get("recommendedTitles") or [])]
        _write(out_dir / "song_judgments.csv", songs, ["case", "title", "grade_0_to_3"])


def cohens_kappa(a, b):
    n = len(a)
    if n == 0:
        return float("nan")
    observed = sum(x == y for x, y in zip(a, b)) / n
    ca, cb = Counter(a), Counter(b)
    expected = sum(ca[k] * cb[k] for k in set(ca) | set(cb)) / (n * n)
    return 1.0 if expected == 1 else (observed - expected) / (1 - expected)


def _split(cell):
    return [x.strip() for x in (cell or "").replace(",", ";").split(";") if x.strip()]


def import_(dataset_path: Path, sheets_dir: Path):
    dataset_path = Path(dataset_path)
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases = {c["id"]: c for c in data["cases"]}

    first, second = [], []
    for r in _read(sheets_dir / "emotion_labels.csv"):
        label = r["annotator2_emotion"].strip().upper()
        if not label:
            continue
        if label not in CANONICAL:
            raise ValueError(f"Unknown emotion '{label}' for case {r['case']} turn {r['turn']} — use one of {sorted(CANONICAL)}")
        turn = cases[r["case"]]["turns"][int(r["turn"])]
        turn["emotion2"] = label
        first.append(turn["emotion"])
        second.append(label)

    for r in _read(sheets_dir / "raga_genre_labels.csv"):
        c = cases[r["case"]]
        if _split(r["expert_ragas"]):
            c["expectedRagas"] = _split(r["expert_ragas"])
        if _split(r["expert_genres"]):
            c["expectedGenres"] = _split(r["expert_genres"])

    for r in _read(sheets_dir / "song_judgments.csv"):
        grade = r["grade_0_to_3"].strip()
        if not grade:
            continue
        if grade not in {"0", "1", "2", "3"}:
            raise ValueError(f"Grade must be 0-3, got '{grade}' for {r['title']} in case {r['case']}")
        cases[r["case"]].setdefault("songJudgments", {})[r["title"]] = int(grade)

    dataset_path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    agreement = sum(x == y for x, y in zip(first, second)) / len(first) if first else None
    return {"labelled_pairs": len(first), "emotion_agreement": agreement,
            "cohens_kappa": cohens_kappa(first, second) if first else None}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["export", "import"])
    p.add_argument("--dataset", default=str(DEFAULT_DATASET))
    p.add_argument("--sheets", default=str(DEFAULT_SHEETS))
    p.add_argument("--report", default=None, help="evaluation report JSON whose recommended songs should be graded")
    a = p.parse_args()
    if a.command == "export":
        export(Path(a.dataset), a.report, Path(a.sheets))
        print(f"Sheets written to {a.sheets}")
    else:
        s = import_(Path(a.dataset), Path(a.sheets))
        print(f"Applied. Second-annotator labels: {s['labelled_pairs']}, raw agreement: {s['emotion_agreement']}, "
              f"Cohen's kappa: {s['cohens_kappa']}")
