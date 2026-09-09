"""Build and verify the bundled 1,000-word English vocabulary pack."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CURATED_SOURCE = ROOT / "tools/data/ecdict_common_1000.csv"
LEGACY_SOURCE = ROOT / "app/src/main/assets/knowledge_packs/english_vocabulary_100_v1.json"
OUTPUT = ROOT / "app/src/main/assets/knowledge_packs/local/english_vocabulary/pack.json"

PACKAGE_ID = "local_english_vocabulary_1000_v1"
TITLE = "英语核心词汇考察 1000 题"
SIGNING_KEY_ID = "bundled_local_v1"
SIGNATURE = "CONTROLFREE_BUNDLED_LOCAL_V1"
CONTENT_TYPES = ["core_vocabulary", "english_vocabulary"]
WORD_PATTERN = re.compile(r"[a-z]{2,24}")
LEGACY_WORD_PATTERN = re.compile(r"英文单词“([^”]+)”")
CHINESE_PATTERN = re.compile(r"[\u3400-\u9fff]")
POS_PREFIX_PATTERN = re.compile(
    r"^(?P<pos>art|aux|conj|pron|prep|num|int|adj|adv|ad|vt|vi|v|n|a|s)\.\s*",
    re.IGNORECASE,
)
SENSE_SEPARATOR_PATTERN = re.compile(r"[，,；;]")
CONTENT_PARTS_OF_SPEECH = frozenset({"noun", "verb", "adjective", "adverb"})


@dataclass(frozen=True)
class VocabularyEntry:
    word: str
    meaning: str
    part_of_speech: str
    frequency: int


def normalize_part_of_speech(raw: str | None) -> str:
    value = (raw or "").lower()
    if value.startswith("n"):
        return "noun"
    if value.startswith("v") or value == "aux":
        return "verb"
    if value in {"a", "s", "ad", "adj"}:
        return "adjective"
    if value == "adv":
        return "adverb"
    if value in {"prep", "pron", "conj", "art", "num", "int"}:
        return value
    return "other"


def extract_primary_meaning(translation: str) -> tuple[str, str] | None:
    normalized = translation.replace("\\r", "").replace("\\n", "\n")
    meanings: list[str] = []
    primary_part_of_speech = "other"
    for raw_line in normalized.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("["):
            continue
        match = POS_PREFIX_PATTERN.match(line)
        part_of_speech = normalize_part_of_speech(match.group("pos") if match else None)
        if not meanings:
            primary_part_of_speech = part_of_speech
        if match:
            line = line[match.end():]
        line = re.sub(r"\[[^]]+]", "", line).strip(" .")
        accepted_from_line = 0
        for raw_meaning in SENSE_SEPARATOR_PATTERN.split(line):
            meaning = raw_meaning.strip(" .")
            candidate = "；".join(meanings + [meaning])
            if (
                CHINESE_PATTERN.search(meaning)
                and 1 <= len(meaning) <= 16
                and not any(character.isspace() for character in meaning)
                and meaning not in meanings
                and len(candidate) <= 28
            ):
                meanings.append(meaning)
                accepted_from_line += 1
            if accepted_from_line >= 2 or len(meanings) >= 4:
                break
        if len(meanings) >= 4:
            break
    return ("；".join(meanings), primary_part_of_speech) if meanings else None


def legacy_words() -> set[str]:
    source = json.loads(LEGACY_SOURCE.read_text(encoding="utf-8"))
    result = set()
    for fact in source["facts"]:
        match = LEGACY_WORD_PATTERN.search(fact["stem"])
        if match:
            result.add(match.group(1).lower())
    return result


def refresh_curated_source(ecdict_csv: Path) -> None:
    excluded_words = legacy_words()
    candidates: list[VocabularyEntry] = []
    seen_words: set[str] = set()
    with ecdict_csv.open(encoding="utf-8", newline="") as source_file:
        for row in csv.DictReader(source_file):
            word = row.get("word", "").strip().lower()
            if word in seen_words or word in excluded_words or not WORD_PATTERN.fullmatch(word):
                continue
            frequency_text = row.get("frq", "").strip()
            if not frequency_text.isdigit() or int(frequency_text) <= 0:
                continue
            parsed_meaning = extract_primary_meaning(row.get("translation", ""))
            if parsed_meaning is None:
                continue
            meaning, part_of_speech = parsed_meaning
            if part_of_speech not in CONTENT_PARTS_OF_SPEECH:
                continue
            seen_words.add(word)
            candidates.append(
                VocabularyEntry(word, meaning, part_of_speech, int(frequency_text))
            )

    selected = sorted(candidates, key=lambda entry: (entry.frequency, entry.word))[:1_000]
    if len(selected) != 1_000:
        raise ValueError(f"ECDICT 清洗后仅得到 {len(selected)} 个有效词条")
    if len({entry.word for entry in selected}) != len(selected):
        raise AssertionError("筛选后的英文词汇不得重复")

    CURATED_SOURCE.parent.mkdir(parents=True, exist_ok=True)
    with CURATED_SOURCE.open("w", encoding="utf-8", newline="") as output_file:
        writer = csv.writer(output_file, lineterminator="\n")
        writer.writerow(["word", "meaning", "part_of_speech", "frequency"])
        writer.writerows(
            (entry.word, entry.meaning, entry.part_of_speech, entry.frequency)
            for entry in selected
        )


def read_curated_entries() -> list[VocabularyEntry]:
    with CURATED_SOURCE.open(encoding="utf-8", newline="") as source_file:
        entries = [
            VocabularyEntry(
                word=row["word"],
                meaning=row["meaning"],
                part_of_speech=row["part_of_speech"],
                frequency=int(row["frequency"]),
            )
            for row in csv.DictReader(source_file)
        ]
    if len(entries) != 1_000:
        raise ValueError("词汇源必须恰好包含 1,000 个词条")
    if len({entry.word for entry in entries}) != len(entries):
        raise ValueError("词汇源中的英文单词不得重复")
    if legacy_words() & {entry.word for entry in entries}:
        raise ValueError("千题包不得与六领域目录包复用旧词汇")
    return entries


def distractors_for(
    entry: VocabularyEntry,
    index: int,
    entries: list[VocabularyEntry],
) -> list[str]:
    same_pos = [
        candidate
        for candidate in entries
        if candidate.part_of_speech == entry.part_of_speech
        and candidate.word != entry.word
        and candidate.meaning != entry.meaning
    ]
    pool = same_pos if len(same_pos) >= 3 else [
        candidate
        for candidate in entries
        if candidate.word != entry.word and candidate.meaning != entry.meaning
    ]
    result: list[str] = []
    start = (index * 37 + 11) % len(pool)
    for candidate in pool[start:] + pool[:start]:
        meaning = candidate.meaning
        if meaning != entry.meaning and meaning not in result:
            result.append(meaning)
        if len(result) == 3:
            break
    if len(result) != 3:
        raise ValueError(f"无法为词汇 {entry.word} 生成三个唯一干扰项")
    return result


def build_facts(entries: list[VocabularyEntry]) -> list[dict[str, object]]:
    facts = []
    for index, entry in enumerate(entries):
        difficulty = 1 if index < 350 else 2 if index < 700 else 3
        facts.append(
            {
                "id": f"en_core_{index + 1:04d}_{entry.word}",
                "category": "literature_art",
                "difficulty": difficulty,
                "stem": f"英文单词“{entry.word}”最接近以下哪个中文意思？",
                "answer": entry.meaning,
                "accepted_aliases": [],
                "distractors": distractors_for(entry, index, entries),
                "explanation": f"“{entry.word}”的常用中文含义是“{entry.meaning}”。",
            }
        )
    return facts


def append_field(parts: list[str], name: str, value: str) -> None:
    parts.append(f"{len(name)}:{name}={len(value.encode('utf-8'))}:{value}\n")


def canonical_hash(metadata: dict[str, object], facts: list[dict[str, object]]) -> str:
    parts: list[str] = []
    append_field(parts, "schema", "1")
    append_field(parts, "package_id", str(metadata["package_id"]))
    append_field(parts, "title", str(metadata["title"]))
    append_field(parts, "version", str(metadata["version"]))
    append_field(parts, "locale", str(metadata["locale"]))
    append_field(parts, "published_at", str(metadata["published_at_epoch_seconds"]))
    append_field(parts, "minimum_app_version", str(metadata["minimum_app_version_code"]))
    for content_type in sorted(metadata["content_types"]):
        append_field(parts, "content_type", content_type)
    for fact in sorted(facts, key=lambda item: item["id"]):
        append_field(parts, "id", fact["id"])
        append_field(parts, "category", fact["category"])
        append_field(parts, "difficulty", str(fact["difficulty"]))
        append_field(parts, "stem", fact["stem"])
        append_field(parts, "answer", fact["answer"])
        for alias in sorted(fact["accepted_aliases"]):
            append_field(parts, "alias", alias)
        for distractor in fact["distractors"]:
            append_field(parts, "distractor", distractor)
        append_field(parts, "explanation", fact["explanation"])
    return hashlib.sha256("".join(parts).encode("utf-8")).hexdigest()


def build_pack(entries: list[VocabularyEntry]) -> tuple[dict[str, object], str]:
    facts = build_facts(entries)
    metadata = {
        "package_id": PACKAGE_ID,
        "title": TITLE,
        "version": 1,
        "locale": "zh-CN",
        "published_at_epoch_seconds": 1784937600,
        "minimum_app_version_code": 17,
        "question_count": len(facts),
        "content_types": CONTENT_TYPES,
        "signing_key_id": SIGNING_KEY_ID,
    }
    pack = {
        "schema_version": 1,
        "metadata": metadata,
        "facts": facts,
        "signature": SIGNATURE,
    }
    return pack, canonical_hash(metadata, facts)


def serialized_pack(pack: dict[str, object]) -> str:
    return json.dumps(pack, ensure_ascii=False, indent=2) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh-source", type=Path, metavar="ECDICT_CSV")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.refresh_source:
        refresh_curated_source(args.refresh_source)

    entries = read_curated_entries()
    pack, content_hash = build_pack(entries)
    rendered = serialized_pack(pack)
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != rendered:
            raise SystemExit("英语题包与生成器输出不一致")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"words={len(entries)}")
    print(f"questions={len(pack['facts'])}")
    print(f"sha256={content_hash}")
    print(f"bytes={len(rendered.encode('utf-8'))}")


if __name__ == "__main__":
    main()
