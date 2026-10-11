"""Tests for the annotation tool:  ser-service/.venv/Scripts/python -m pytest evaluation/tools"""
import csv
import json

import pytest

import annotation as ann

DATASET = {"name": "t", "cases": [
    {"id": "C1", "title": "sad", "turns": [
        {"role": "HEALTH_CONSULTANT", "text": "How are you?"},
        {"role": "PATIENT", "text": "I feel low.", "emotion": "SADNESS"},
        {"role": "PATIENT", "text": "I miss home.", "emotion": "NOSTALGIA"}]},
    {"id": "C2", "title": "joy", "turns": [
        {"role": "PATIENT", "text": "Great day!", "emotion": "JOY"},
        {"role": "PATIENT", "text": "So happy.", "emotion": "JOY"}]},
]}

REPORT = {"cases": [
    {"id": "C1", "recommendedTrackIds": [1, 2], "recommendedTitles": ["Song A", "Song B"]},
    {"id": "C2", "recommendedTrackIds": [3], "recommendedTitles": ["Song C"]},
]}


@pytest.fixture
def files(tmp_path):
    ds = tmp_path / "dataset.json"
    ds.write_text(json.dumps(DATASET), encoding="utf-8")
    rep = tmp_path / "report.json"
    rep.write_text(json.dumps(REPORT), encoding="utf-8")
    return ds, rep, tmp_path / "sheets"


def read(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write(path, rows):
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)


def test_export_writes_one_row_per_patient_response_case_and_song(files):
    ds, rep, out = files
    ann.export(ds, rep, out)
    emotions = read(out / "emotion_labels.csv")
    assert [r["text"] for r in emotions] == ["I feel low.", "I miss home.", "Great day!", "So happy."]
    assert all(r["annotator2_emotion"] == "" for r in emotions)
    # The second annotator labels blind: the first annotator's label is not in their sheet.
    assert "annotator1_emotion" not in emotions[0]
    assert [r["case"] for r in read(out / "raga_genre_labels.csv")] == ["C1", "C2"]
    assert [r["title"] for r in read(out / "song_judgments.csv")] == ["Song A", "Song B", "Song C"]


def test_cohens_kappa():
    assert ann.cohens_kappa(["A", "B", "A", "B"], ["A", "B", "A", "B"]) == pytest.approx(1.0)
    # Agreement 0.5 when chance agreement is 0.5 → kappa 0.
    assert ann.cohens_kappa(["A", "A", "B", "B"], ["A", "B", "A", "B"]) == pytest.approx(0.0)


def test_import_applies_labels_and_reports_agreement(files):
    ds, rep, out = files
    ann.export(ds, rep, out)
    em = read(out / "emotion_labels.csv")
    for r, label in zip(em, ["SADNESS", "SADNESS", "JOY", "JOY"]):   # disagrees on "I miss home."
        r["annotator2_emotion"] = label
    write(out / "emotion_labels.csv", em)
    rg = read(out / "raga_genre_labels.csv")
    rg[0]["expert_ragas"] = "Bhairavi; Darbari Kanada"
    rg[0]["expert_genres"] = "Ghazal"
    write(out / "raga_genre_labels.csv", rg)
    sj = read(out / "song_judgments.csv")
    sj[0]["grade_0_to_3"] = "3"
    sj[1]["grade_0_to_3"] = "1"
    write(out / "song_judgments.csv", sj)

    summary = ann.import_(ds, out)

    data = json.loads(ds.read_text(encoding="utf-8"))
    c1 = data["cases"][0]
    assert c1["expectedRagas"] == ["Bhairavi", "Darbari Kanada"]
    assert c1["expectedGenres"] == ["Ghazal"]
    assert c1["songJudgments"] == {"Song A": 3, "Song B": 1}
    assert c1["turns"][2]["emotion2"] == "SADNESS"
    assert "songJudgments" not in data["cases"][1]          # nothing graded for C2
    assert summary["emotion_agreement"] == pytest.approx(0.75)
    assert summary["labelled_pairs"] == 4


def test_import_rejects_unknown_emotion_labels(files):
    ds, rep, out = files
    ann.export(ds, rep, out)
    em = read(out / "emotion_labels.csv")
    em[0]["annotator2_emotion"] = "GLOOMY"
    write(out / "emotion_labels.csv", em)
    with pytest.raises(ValueError, match="GLOOMY"):
        ann.import_(ds, out)
