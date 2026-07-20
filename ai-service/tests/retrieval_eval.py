import argparse
import json
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

ROOT_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT_DIR))

from app.services.kb_service import _load_seed_data, load_kb_manifest, search_faq

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_CASE_FILE = BASE_DIR / "retrieval_cases.json"
FALLBACK_CASE_FILE = BASE_DIR / "test_cases_120.json"
LATEST_RESULT_FILE = BASE_DIR / "retrieval_eval_result.json"
RUN_ROOT = BASE_DIR / "retrieval_eval_runs"


def safe_rate(hit: int, total: int) -> float:
    return hit / total if total else 0.0


def metric(hit: int, total: int) -> dict[str, Any]:
    return {
        "hit": hit,
        "total": total,
        "rate": round(safe_rate(hit, total), 4),
    }


def sanitize_slug(value: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in {"-", "_", "."} else "-" for ch in value.strip())
    cleaned = cleaned.strip("-")
    return cleaned or "eval"


def choose_case_file(cli_value: str | None) -> Path:
    if cli_value:
        return Path(cli_value)
    if DEFAULT_CASE_FILE.exists():
        return DEFAULT_CASE_FILE
    if FALLBACK_CASE_FILE.exists():
        return FALLBACK_CASE_FILE
    raise FileNotFoundError("未找到检索评测数据文件。")


def build_subtype_to_type_map() -> dict[str, str]:
    mapping: dict[str, str] = {}
    for item in _load_seed_data():
        subtype = str(item.get("subtype") or "")
        fraud_type = str(item.get("fraud_type") or "")
        if subtype and fraud_type:
            mapping[subtype] = fraud_type
    return mapping


def normalize_case(raw_case: dict[str, Any], index: int, subtype_to_type: dict[str, str]) -> dict[str, Any]:
    text = str(raw_case.get("text") or "").strip()
    if not text:
        raise ValueError(f"case #{index} 缺少 text 字段")

    raw_type = str(raw_case.get("type") or raw_case.get("case_type") or "")
    raw_subtype = str(raw_case.get("subtype") or "")
    expected_type = str(raw_case.get("fraud_type") or "")
    expected_subtype = ""
    expected_empty = bool(raw_case.get("expect_empty", False))

    if "fraud_type" in raw_case or "expect_empty" in raw_case:
        expected_subtype = raw_subtype
    elif "expected_label" in raw_case and raw_type:
        expected_empty = raw_type == "normal"
        expected_subtype = raw_subtype if raw_type in {"fraud", "borderline"} else ""

    if not expected_type and expected_subtype:
        expected_type = subtype_to_type.get(expected_subtype, "")

    return {
        "case_id": raw_case.get("id", index),
        "text": text,
        "raw_type": raw_type,
        "raw_subtype": raw_subtype,
        "expected_type": expected_type,
        "expected_subtype": expected_subtype,
        "expected_empty": expected_empty,
        "expected_label": str(raw_case.get("expected_label") or ""),
    }


def load_cases(case_file: Path) -> list[dict[str, Any]]:
    raw_cases = json.loads(case_file.read_text(encoding="utf-8"))
    if not isinstance(raw_cases, list):
        raise ValueError(f"{case_file} 必须是 JSON 数组")
    subtype_to_type = build_subtype_to_type_map()
    return [normalize_case(case, index + 1, subtype_to_type) for index, case in enumerate(raw_cases)]


def hit_type(expected_type: str, hits: list[dict[str, Any]]) -> bool:
    return bool(expected_type) and any(hit.get("fraud_type") == expected_type for hit in hits)


def hit_subtype(expected_subtype: str, hits: list[dict[str, Any]]) -> bool:
    return bool(expected_subtype) and any(hit.get("subtype") == expected_subtype for hit in hits)


def top1_matches(case: dict[str, Any], top1: dict[str, Any]) -> bool:
    if not top1:
        return False
    if case["expected_subtype"]:
        return top1.get("subtype") == case["expected_subtype"]
    if case["expected_type"]:
        return top1.get("fraud_type") == case["expected_type"]
    return False


def failure_category(case: dict[str, Any], hits: list[dict[str, Any]]) -> str:
    if case["expected_empty"]:
        return "unexpected_nonempty" if hits else "ok"

    if not hits:
        return "unexpected_empty"

    top1 = hits[0]
    if case["expected_subtype"]:
        if top1.get("subtype") == case["expected_subtype"]:
            return "ok"
        if hit_subtype(case["expected_subtype"], hits[:3]):
            return "top1_wrong_but_top3_subtype_hit"
        return "wrong_subtype"

    if case["expected_type"]:
        if top1.get("fraud_type") == case["expected_type"]:
            return "ok"
        if hit_type(case["expected_type"], hits[:3]):
            return "top1_wrong_but_top3_type_hit"
        return "wrong_type"

    return "unscored"


def build_run_id(case_file: Path, kb_manifest: dict[str, Any], tag: str = "") -> str:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    case_stem = sanitize_slug(case_file.stem)
    kb_version = sanitize_slug(str(kb_manifest.get("build_version") or "kb-unknown"))
    tag_part = f"_{sanitize_slug(tag)}" if tag else ""
    return f"{timestamp}_{case_stem}_{kb_version}{tag_part}"


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load_previous_summary(case_file: Path, current_run_id: str) -> dict[str, Any] | None:
    candidates: list[dict[str, Any]] = []
    for summary_path in RUN_ROOT.glob("*/summary.json"):
        if summary_path.parent.name == current_run_id:
            continue
        try:
            summary = read_json(summary_path)
        except (OSError, json.JSONDecodeError):
            continue
        if summary.get("case_file_name") == case_file.name:
            candidates.append(summary)

    if not candidates:
        return None

    candidates.sort(key=lambda item: str(item.get("created_at", "")), reverse=True)
    return candidates[0]


def build_comparison(current: dict[str, Any], previous: dict[str, Any] | None) -> dict[str, Any] | None:
    if not previous:
        return None

    tracked_metrics = [
        "top1_type_hit",
        "top3_type_hit",
        "top1_subtype_hit",
        "top3_subtype_hit",
        "expected_empty_success",
        "wrong_top1",
    ]

    delta: dict[str, float] = {}
    for key in tracked_metrics:
        current_rate = float(current["metrics"].get(key, {}).get("rate", 0.0))
        previous_rate = float(previous.get("metrics", {}).get(key, {}).get("rate", 0.0))
        delta[key] = round(current_rate - previous_rate, 4)

    return {
        "previous_run_id": previous.get("run_id"),
        "previous_created_at": previous.get("created_at"),
        "metric_rate_delta": delta,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="运行知识库检索评测。")
    parser.add_argument("--case-file", default=None, help="评测用例 JSON 文件路径。")
    parser.add_argument("--top-k", type=int, default=3, help="检索返回条数。")
    parser.add_argument("--tag", default="", help="可选 run 标签。")
    args = parser.parse_args()

    case_file = choose_case_file(args.case_file)
    cases = load_cases(case_file)
    kb_manifest = load_kb_manifest()
    run_id = build_run_id(case_file, kb_manifest, args.tag)
    run_dir = RUN_ROOT / run_id

    total = len(cases)
    cases_with_expected_type = 0
    cases_with_expected_subtype = 0
    cases_with_expectation = 0
    cases_expected_empty = 0
    top1_type_hit = 0
    top3_type_hit = 0
    top1_subtype_hit = 0
    top3_subtype_hit = 0
    empty_hits = 0
    expected_empty_success = 0
    wrong_top1 = 0
    unexpected_empty = 0
    avg_top1_score = 0.0
    score_count = 0

    case_type_counter: Counter[str] = Counter()
    subtype_counter: Counter[str] = Counter()
    subtype_top1_counter: Counter[str] = Counter()
    subtype_top3_counter: Counter[str] = Counter()
    failure_counter: Counter[str] = Counter()

    rows: list[dict[str, Any]] = []

    for case in cases:
        text = case["text"]
        expected_type = case["expected_type"]
        expected_subtype = case["expected_subtype"]
        expected_empty = case["expected_empty"]

        hits = search_faq(text, top_k=args.top_k)
        top1 = hits[0] if hits else {}
        category = failure_category(case, hits)

        case_type_key = case["raw_type"] or ("expected_empty" if expected_empty else "retrieval_case")
        case_type_counter[case_type_key] += 1
        failure_counter[category] += 1

        if expected_type:
            cases_with_expected_type += 1
        if expected_subtype:
            cases_with_expected_subtype += 1
            subtype_counter[expected_subtype] += 1
        if expected_type or expected_subtype:
            cases_with_expectation += 1
        if expected_empty:
            cases_expected_empty += 1

        if not hits:
            empty_hits += 1
            if expected_empty:
                expected_empty_success += 1
            else:
                unexpected_empty += 1

        if hits:
            avg_top1_score += float(top1.get("score", 0.0))
            score_count += 1

        if expected_type:
            if top1 and top1.get("fraud_type") == expected_type:
                top1_type_hit += 1
            if hit_type(expected_type, hits[:args.top_k]):
                top3_type_hit += 1

        if expected_subtype:
            if top1 and top1.get("subtype") == expected_subtype:
                top1_subtype_hit += 1
                subtype_top1_counter[expected_subtype] += 1
            if hit_subtype(expected_subtype, hits[:args.top_k]):
                top3_subtype_hit += 1
                subtype_top3_counter[expected_subtype] += 1

        if (expected_type or expected_subtype) and hits and not top1_matches(case, top1):
            wrong_top1 += 1

        rows.append(
            {
                "case_id": case["case_id"],
                "text": text,
                "raw_type": case["raw_type"],
                "raw_subtype": case["raw_subtype"],
                "expected_type": expected_type,
                "expected_subtype": expected_subtype,
                "expected_empty": expected_empty,
                "top1_type": top1.get("fraud_type", ""),
                "top1_subtype": top1.get("subtype", ""),
                "top1_title": top1.get("title", ""),
                "top1_score": top1.get("score", 0.0),
                "hit_count": len(hits),
                "failure_category": category,
                "flags": {
                    "top1_type_hit": bool(expected_type and top1 and top1.get("fraud_type") == expected_type),
                    "top3_type_hit": hit_type(expected_type, hits[:args.top_k]),
                    "top1_subtype_hit": bool(expected_subtype and top1 and top1.get("subtype") == expected_subtype),
                    "top3_subtype_hit": hit_subtype(expected_subtype, hits[:args.top_k]),
                    "empty_hit": not hits,
                    "expected_empty_success": bool(expected_empty and not hits),
                    "wrong_top1": bool((expected_type or expected_subtype) and hits and not top1_matches(case, top1)),
                },
                "hits": hits,
            }
        )

    by_subtype = []
    for subtype, subtype_total in sorted(subtype_counter.items()):
        by_subtype.append(
            {
                "subtype": subtype,
                "total": subtype_total,
                "top1_hit": subtype_top1_counter[subtype],
                "top1_rate": round(safe_rate(subtype_top1_counter[subtype], subtype_total), 4),
                "top3_hit": subtype_top3_counter[subtype],
                "top3_rate": round(safe_rate(subtype_top3_counter[subtype], subtype_total), 4),
            }
        )

    summary = {
        "run_id": run_id,
        "created_at": datetime.now().isoformat(),
        "case_file": str(case_file).replace("\\", "/"),
        "case_file_name": case_file.name,
        "top_k": args.top_k,
        "tag": args.tag,
        "kb_manifest": kb_manifest,
        "dataset": {
            "total_cases": total,
            "cases_with_expected_type": cases_with_expected_type,
            "cases_with_expected_subtype": cases_with_expected_subtype,
            "cases_expected_empty": cases_expected_empty,
            "case_type_distribution": dict(sorted(case_type_counter.items())),
        },
        "metrics": {
            "top1_type_hit": metric(top1_type_hit, cases_with_expected_type),
            "top3_type_hit": metric(top3_type_hit, cases_with_expected_type),
            "top1_subtype_hit": metric(top1_subtype_hit, cases_with_expected_subtype),
            "top3_subtype_hit": metric(top3_subtype_hit, cases_with_expected_subtype),
            "expected_empty_success": metric(expected_empty_success, cases_expected_empty),
            "empty_hits": metric(empty_hits, total),
            "unexpected_empty": metric(unexpected_empty, total - cases_expected_empty),
            "wrong_top1": metric(wrong_top1, cases_with_expectation),
            "avg_top1_score": round(avg_top1_score / score_count, 4) if score_count else 0.0,
        },
        "breakdowns": {
            "failure_category_distribution": dict(sorted(failure_counter.items())),
            "by_subtype": by_subtype,
        },
    }

    previous_summary = load_previous_summary(case_file, run_id)
    comparison = build_comparison(summary, previous_summary)
    if comparison:
        summary["comparison_to_previous"] = comparison

    latest_payload = {
        "summary": summary,
        "cases": rows,
    }

    write_json(run_dir / "summary.json", summary)
    write_json(run_dir / "cases.json", rows)
    write_json(LATEST_RESULT_FILE, latest_payload)

    print(f"run_id={run_id}")
    print(f"case_file={case_file}")
    print(f"kb_version={kb_manifest.get('build_version', 'unknown')}")
    print(f"total={total}")
    print(
        f"top1_type_hit={top1_type_hit}/{cases_with_expected_type}"
        f"={safe_rate(top1_type_hit, cases_with_expected_type):.2%}"
    )
    print(
        f"top3_type_hit={top3_type_hit}/{cases_with_expected_type}"
        f"={safe_rate(top3_type_hit, cases_with_expected_type):.2%}"
    )
    print(
        f"top1_subtype_hit={top1_subtype_hit}/{cases_with_expected_subtype}"
        f"={safe_rate(top1_subtype_hit, cases_with_expected_subtype):.2%}"
    )
    print(
        f"top3_subtype_hit={top3_subtype_hit}/{cases_with_expected_subtype}"
        f"={safe_rate(top3_subtype_hit, cases_with_expected_subtype):.2%}"
    )
    print(
        f"expected_empty_success={expected_empty_success}/{cases_expected_empty}"
        f"={safe_rate(expected_empty_success, cases_expected_empty):.2%}"
    )
    print(f"empty_hits={empty_hits}/{total}={safe_rate(empty_hits, total):.2%}")
    print(
        f"wrong_top1={wrong_top1}/{cases_with_expectation}"
        f"={safe_rate(wrong_top1, cases_with_expectation):.2%}"
    )
    if score_count:
        print(f"avg_top1_score={avg_top1_score / score_count:.4f}")
    print(f"saved_run={run_dir}")
    print(f"saved_latest={LATEST_RESULT_FILE}")


if __name__ == "__main__":
    main()
