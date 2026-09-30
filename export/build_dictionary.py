"""Construit la base SQLite du dictionnaire embarquée dans l'appli.

Sources (jmdict-simplified, https://github.com/scriptin/jmdict-simplified), dans export/data/ :
  jmdict-eng-*.json, jmdict-fre-*.json, kanjidic2-all-*.json

Usage : .venv/bin/python export/build_dictionary.py
Sortie : export/out/dictionary.db
"""
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "export" / "data"
OUT = ROOT / "export" / "out" / "dictionary.db"


def load(prefix):
    return json.loads(next(DATA.glob(f"{prefix}-*.json")).read_text(encoding="utf-8"))


def compact_senses(senses, lang):
    """Garde l'utile : nature, remarques, gloses dans la langue voulue."""
    out = []
    for s in senses:
        glosses = [g["text"] for g in s["gloss"] if g["lang"] == lang]
        if not glosses:
            continue
        sense = {"g": glosses}
        if s.get("partOfSpeech"):
            sense["p"] = s["partOfSpeech"]
        if s.get("misc"):
            sense["m"] = s["misc"]
        if s.get("info"):
            sense["i"] = s["info"]
        # Restrictions d'écriture (sens valable seulement pour certaines formes)
        if s.get("appliesToKanji") and s["appliesToKanji"] != ["*"]:
            sense["k"] = s["appliesToKanji"]
        if s.get("appliesToKana") and s["appliesToKana"] != ["*"]:
            sense["r"] = s["appliesToKana"]
        out.append(sense)
    return out


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.unlink(missing_ok=True)
    db = sqlite3.connect(OUT)
    db.executescript("""
        CREATE TABLE entry (
            id INTEGER PRIMARY KEY,
            kanji TEXT NOT NULL,      -- JSON [[forme, courant(0/1)], ...]
            kana TEXT NOT NULL,       -- JSON [[forme, courant(0/1)], ...]
            senses_en TEXT NOT NULL,  -- JSON [{g: [gloses], p: [natures], m: [remarques], ...}]
            senses_fr TEXT,           -- idem, français (JMdict fre), si disponible
            common INTEGER NOT NULL
        );
        CREATE TABLE form (
            text TEXT NOT NULL,       -- forme écrite (kanji ou kana)
            entry_id INTEGER NOT NULL,
            rank INTEGER NOT NULL     -- tri : 0 = forme courante d'un mot courant, plus = moins pertinent
        );
        CREATE TABLE kanji (
            literal TEXT PRIMARY KEY,
            onyomi TEXT, kunyomi TEXT, -- JSON
            meanings_fr TEXT, meanings_en TEXT, -- JSON
            grade INTEGER, strokes INTEGER, jlpt INTEGER, freq INTEGER
        );
        CREATE TABLE tag (name TEXT PRIMARY KEY, description TEXT);
    """)

    eng = load("jmdict-eng")
    fre = {w["id"]: w for w in load("jmdict-fre")["words"]}
    db.executemany("INSERT INTO tag VALUES (?, ?)", eng["tags"].items())

    entries, forms = [], []
    for w in eng["words"]:
        wid = int(w["id"])
        kanji = [[k["text"], int(k["common"])] for k in w["kanji"]]
        kana = [[k["text"], int(k["common"])] for k in w["kana"]]
        common = int(any(c for _, c in kanji + kana))
        fr = compact_senses(fre[w["id"]]["sense"], "fre") if w["id"] in fre else []
        entries.append((
            wid,
            json.dumps(kanji, ensure_ascii=False),
            json.dumps(kana, ensure_ascii=False),
            json.dumps(compact_senses(w["sense"], "eng"), ensure_ascii=False),
            json.dumps(fr, ensure_ascii=False) if fr else None,
            common,
        ))
        for text, c in kanji + kana:
            forms.append((text, wid, (0 if c else 1) + (0 if common else 2)))
    db.executemany("INSERT INTO entry VALUES (?, ?, ?, ?, ?, ?)", entries)
    db.executemany("INSERT INTO form VALUES (?, ?, ?)", forms)
    db.execute("CREATE INDEX form_text ON form(text)")

    kd = load("kanjidic2-all")
    rows = []
    for c in kd["characters"]:
        on, kun, mfr, men = [], [], [], []
        for g in (c.get("readingMeaning") or {}).get("groups", []):
            for r in g["readings"]:
                if r["type"] == "ja_on":
                    on.append(r["value"])
                elif r["type"] == "ja_kun":
                    kun.append(r["value"])
            for m in g["meanings"]:
                if m["lang"] == "fr":
                    mfr.append(m["value"])
                elif m["lang"] == "en":
                    men.append(m["value"])
        misc = c["misc"]
        rows.append((
            c["literal"],
            json.dumps(on, ensure_ascii=False), json.dumps(kun, ensure_ascii=False),
            json.dumps(mfr, ensure_ascii=False), json.dumps(men, ensure_ascii=False),
            misc.get("grade"), (misc.get("strokeCounts") or [None])[0], misc.get("jlptLevel"), misc.get("frequency"),
        ))
    db.executemany("INSERT INTO kanji VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)

    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"-> {OUT} : {len(entries)} mots ({len(fre)} avec français), {len(rows)} kanji, "
          f"{OUT.stat().st_size / 1e6:.0f} Mo")


if __name__ == "__main__":
    main()
