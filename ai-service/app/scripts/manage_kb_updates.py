import argparse
import hashlib
import json
from datetime import datetime
from pathlib import Path
from typing import Any

from app.scripts.rebuild_kb import build_knowledge_base
from app.services.kb_service import (
    KB_SOURCE_DIR,
    _apply_entry_defaults,
    _normalize_seed_payload,
    load_kb_source_catalog,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
KB_UPDATE_DIR = PROJECT_ROOT / "app" / "data" / "kb_updates"
KB_PENDING_CANDIDATES_PATH = KB_UPDATE_DIR / "pending_candidates.json"


def utc_now_iso() -> str:
    return datetime.utcnow().isoformat()


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_entries(payload: Any, defaults: dict[str, Any]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for item in _normalize_seed_payload(payload):
        if not isinstance(item, dict):
            continue
        normalized = _apply_entry_defaults(item, defaults)
        if not str(normalized.get("id") or "").strip():
            digest = hashlib.sha256(
                json.dumps(normalized, ensure_ascii=False, sort_keys=True).encode("utf-8")
            ).hexdigest()[:12]
            normalized["id"] = f"candidate_{digest}"
        entries.append(normalized)
    return entries


def load_target_source(target_source_id: str) -> dict[str, Any]:
    catalog = load_kb_source_catalog()
    for item in catalog:
        if item.get("id") == target_source_id:
            return item
    raise ValueError(f"找不到知识源: {target_source_id}")


def candidate_signature(entry: dict[str, Any], target_source_id: str) -> str:
    digest_input = {
        "target_source_id": target_source_id,
        "id": str(entry.get("id") or ""),
        "title": str(entry.get("title") or ""),
        "question": str(entry.get("question") or ""),
        "knowledge_type": str(entry.get("knowledge_type") or ""),
    }
    return hashlib.sha256(
        json.dumps(digest_input, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()


def submit_candidates(
    *,
    input_path: Path,
    target_source_id: str,
    knowledge_type: str = "",
    source: str = "",
    authority: str = "",
    reference: str = "",
    published_at: str = "",
    applicable_roles: list[str] | None = None,
    keywords: list[str] | None = None,
) -> dict[str, Any]:
    raw_payload = read_json(input_path, default=[])
    target_source = load_target_source(target_source_id)

    defaults = {
        "knowledge_type": knowledge_type or target_source.get("knowledge_type") or "case",
        "source": source or target_source.get("source") or "manual_candidate",
        "authority": authority or target_source.get("authority") or "",
        "reference": reference or target_source.get("reference") or "",
        "published_at": published_at or target_source.get("published_at") or "",
        "applicable_roles": applicable_roles or target_source.get("applicable_roles") or [],
        "keywords": keywords or target_source.get("keywords") or [],
        "source_file": target_source.get("file") or "",
    }
    normalized_entries = normalize_entries(raw_payload, defaults)

    pending_candidates = read_json(KB_PENDING_CANDIDATES_PATH, default=[])
    if not isinstance(pending_candidates, list):
        pending_candidates = []

    known_signatures = {
        str(item.get("signature") or "")
        for item in pending_candidates
        if isinstance(item, dict)
    }

    submitted_count = 0
    for entry in normalized_entries:
        signature = candidate_signature(entry, target_source_id)
        if signature in known_signatures:
            continue
        pending_candidates.append(
            {
                "candidate_id": f"{target_source_id}_{signature[:12]}",
                "target_source_id": target_source_id,
                "status": "pending",
                "submitted_at": utc_now_iso(),
                "signature": signature,
                "entry": entry,
            }
        )
        known_signatures.add(signature)
        submitted_count += 1

    write_json(KB_PENDING_CANDIDATES_PATH, pending_candidates)
    return {
        "submitted_count": submitted_count,
        "pending_path": str(KB_PENDING_CANDIDATES_PATH),
    }


def merge_pending_candidates(
    *,
    target_source_id: str,
    rebuild: bool = False,
    tag: str = "",
) -> dict[str, Any]:
    pending_candidates = read_json(KB_PENDING_CANDIDATES_PATH, default=[])
    if not isinstance(pending_candidates, list):
        pending_candidates = []

    target_source = load_target_source(target_source_id)
    target_path = Path(target_source["file_path"])
    target_payload = read_json(target_path, default={})
    if not isinstance(target_payload, dict):
        raise ValueError(f"目标知识源格式不正确: {target_path}")

    entries = target_payload.get("entries")
    if not isinstance(entries, list):
        raise ValueError(f"目标知识源不支持 merge 模式: {target_path}")

    existing_signatures = {
        candidate_signature(entry, target_source_id)
        for entry in entries
        if isinstance(entry, dict)
    }

    merged_count = 0
    duplicate_count = 0
    updated_candidates: list[dict[str, Any]] = []

    for candidate in pending_candidates:
        if not isinstance(candidate, dict):
            continue
        if candidate.get("target_source_id") != target_source_id:
            updated_candidates.append(candidate)
            continue

        entry = candidate.get("entry") or {}
        signature = str(candidate.get("signature") or candidate_signature(entry, target_source_id))
        status = str(candidate.get("status") or "pending")
        if status != "pending":
            updated_candidates.append(candidate)
            continue

        if signature in existing_signatures:
            candidate["status"] = "duplicate"
            candidate["processed_at"] = utc_now_iso()
            duplicate_count += 1
            updated_candidates.append(candidate)
            continue

        entries.append(entry)
        existing_signatures.add(signature)
        candidate["status"] = "merged"
        candidate["processed_at"] = utc_now_iso()
        merged_count += 1
        updated_candidates.append(candidate)

    entries.sort(key=lambda item: str((item or {}).get("id") or ""))
    target_payload["entries"] = entries
    target_payload["updated_at"] = datetime.now().strftime("%Y-%m-%d")

    write_json(target_path, target_payload)
    write_json(KB_PENDING_CANDIDATES_PATH, updated_candidates)

    rebuild_result = None
    if rebuild:
        rebuild_result = build_knowledge_base(tag=tag)

    return {
        "merged_count": merged_count,
        "duplicate_count": duplicate_count,
        "target_path": str(target_path),
        "rebuild_result": rebuild_result,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="管理反诈知识库候选更新。")
    parser.add_argument("--mode", choices=["submit", "apply"], required=True, help="submit 提交候选，apply 合并入库")
    parser.add_argument("--target-source", required=True, help="目标知识源 id，来自 kb_sources/index.json")
    parser.add_argument("--input", default="", help="submit 模式下的输入 JSON 文件")
    parser.add_argument("--knowledge-type", default="", help="候选条目的知识类型")
    parser.add_argument("--source", default="", help="候选条目的来源标识")
    parser.add_argument("--authority", default="", help="候选条目的机构来源")
    parser.add_argument("--reference", default="", help="候选条目的参考依据")
    parser.add_argument("--published-at", default="", help="候选条目的发布日期")
    parser.add_argument("--applicable-role", action="append", default=[], help="可多次传入适用人群")
    parser.add_argument("--keyword", action="append", default=[], help="可多次传入关键词")
    parser.add_argument("--rebuild", action="store_true", help="apply 后自动重建知识库")
    parser.add_argument("--tag", default="", help="重建知识库时的版本标签")
    args = parser.parse_args()

    if args.mode == "submit":
        if not args.input:
            raise ValueError("submit 模式必须提供 --input")
        result = submit_candidates(
            input_path=Path(args.input),
            target_source_id=args.target_source,
            knowledge_type=args.knowledge_type,
            source=args.source,
            authority=args.authority,
            reference=args.reference,
            published_at=args.published_at,
            applicable_roles=args.applicable_role,
            keywords=args.keyword,
        )
        print(f"已提交候选条目: {result['submitted_count']}")
        print(f"待审核文件: {result['pending_path']}")
        return

    result = merge_pending_candidates(
        target_source_id=args.target_source,
        rebuild=args.rebuild,
        tag=args.tag,
    )
    print(f"已合并条目: {result['merged_count']}")
    print(f"重复跳过: {result['duplicate_count']}")
    print(f"目标文件: {result['target_path']}")
    if result["rebuild_result"]:
        print("知识库已触发重建")


if __name__ == "__main__":
    main()
