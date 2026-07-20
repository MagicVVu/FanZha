from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock
from typing import Any


APP_ROOT = Path(__file__).resolve().parent.parent
NOVEL_CASE_QUEUE_PATH = Path(
    os.getenv(
        "NOVEL_CASE_QUEUE_PATH",
        str(APP_ROOT / "data" / "kb_updates" / "pending_candidates.json"),
    )
)
FAQ_SEED_PATH = Path(os.getenv("KB_SEED_PATH", str(APP_ROOT / "data" / "faq_seed.json")))
NOVEL_CASE_QUEUE_ENABLED = os.getenv("NOVEL_CASE_QUEUE_ENABLED", "1") == "1"
NOVEL_CASE_MIN_PROBABILITY = float(os.getenv("NOVEL_CASE_MIN_PROBABILITY", "0.72"))
NOVEL_CASE_MIN_CONFIDENCE = float(os.getenv("NOVEL_CASE_MIN_CONFIDENCE", "0.45"))
NOVEL_CASE_MIN_TEXT_LEN = int(os.getenv("NOVEL_CASE_MIN_TEXT_LEN", "30"))
NOVEL_CASE_KB_SCORE_THRESHOLD = float(os.getenv("NOVEL_CASE_KB_SCORE_THRESHOLD", "0.55"))
NOVEL_CASE_MAX_EXCERPT_LEN = int(os.getenv("NOVEL_CASE_MAX_EXCERPT_LEN", "600"))
NOVEL_CASE_REBUILD_TIMEOUT_SEC = int(os.getenv("NOVEL_CASE_REBUILD_TIMEOUT_SEC", "1800"))

STORE_VERSION = "novel_case_queue_v1"
PLACEHOLDER_FRAUD_TYPE = "待人工确认"
PLACEHOLDER_SUBTYPE = "novel_case_candidate"
PLACEHOLDER_TITLE = "待审核新兴诈骗案例"

_STORE_LOCK = Lock()


class NovelCaseError(RuntimeError):
    pass


class CandidateNotFoundError(NovelCaseError):
    pass


class CandidateReviewError(NovelCaseError):
    pass


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _today_str() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _default_store() -> dict[str, Any]:
    return {
        "version": STORE_VERSION,
        "updated_at": _utc_now_iso(),
        "items": [],
    }


def _read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return deepcopy(default)
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return deepcopy(default)


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _normalize_store(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return _default_store()

    items = payload.get("items")
    if not isinstance(items, list):
        items = []

    return {
        "version": str(payload.get("version") or STORE_VERSION),
        "updated_at": str(payload.get("updated_at") or _utc_now_iso()),
        "items": [item for item in items if isinstance(item, dict)],
    }


def load_candidate_store() -> dict[str, Any]:
    with _STORE_LOCK:
        return _normalize_store(_read_json(NOVEL_CASE_QUEUE_PATH, _default_store()))


def save_candidate_store(payload: dict[str, Any]) -> dict[str, Any]:
    normalized = _normalize_store(payload)
    normalized["updated_at"] = _utc_now_iso()
    with _STORE_LOCK:
        _write_json(NOVEL_CASE_QUEUE_PATH, normalized)
    return normalized


def _normalize_text_excerpt(text: str, limit: int = NOVEL_CASE_MAX_EXCERPT_LEN) -> str:
    normalized = re.sub(r"\s+", " ", str(text or "")).strip()
    return normalized[:limit]


def _mask_sensitive_text(text: str) -> str:
    masked = str(text or "")
    masked = re.sub(r"(?<!\d)(1\d{2})\d{4}(\d{4})(?!\d)", r"\1****\2", masked)
    masked = re.sub(r"(?<!\d)(\d{6})\d{8}([\dXx]{4})(?![\dXx])", r"\1********\2", masked)
    masked = re.sub(r"(?<!\d)(\d{4})\d{8,11}(\d{4})(?!\d)", r"\1********\2", masked)
    return masked


def _normalize_reason_list(values: list[str] | None) -> list[str]:
    result: list[str] = []
    seen = set()
    for value in values or []:
        normalized = str(value or "").strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result[:6]


def _normalize_keyword_list(values: list[str] | None) -> list[str]:
    result: list[str] = []
    seen = set()
    for value in values or []:
        normalized = str(value or "").strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result[:8]


def _sanitize_kb_hit(hit: dict[str, Any]) -> dict[str, Any]:
    return {
        "doc_id": str(hit.get("doc_id") or ""),
        "title": str(hit.get("title") or ""),
        "fraud_type": str(hit.get("fraud_type") or ""),
        "subtype": str(hit.get("subtype") or ""),
        "risk_level": str(hit.get("risk_level") or ""),
        "warning": str(hit.get("warning") or ""),
        "score": float(hit.get("score", 0.0) or 0.0),
        "retrieval_mode": str(hit.get("retrieval_mode") or ""),
        "signal_overlap_count": int(hit.get("signal_overlap_count", 0) or 0),
    }


def _top_kb_score(kb_hits: list[dict[str, Any]] | None) -> float:
    if not kb_hits:
        return 0.0
    try:
        return float((kb_hits[0] or {}).get("score", 0.0) or 0.0)
    except (TypeError, ValueError):
        return 0.0


def _top_overlap_count(kb_hits: list[dict[str, Any]] | None) -> int:
    if not kb_hits:
        return 0
    try:
        return int((kb_hits[0] or {}).get("signal_overlap_count", 0) or 0)
    except (TypeError, ValueError):
        return 0


def build_candidate_fingerprint(
    *,
    source_modality: str,
    text_excerpt: str,
    rule_hits: list[str] | None = None,
) -> str:
    normalized_excerpt = _normalize_text_excerpt(text_excerpt, limit=400)
    normalized_hits = sorted(_normalize_keyword_list(rule_hits or []))
    digest_input = {
        "source_modality": str(source_modality or "").strip().lower(),
        "text_excerpt": normalized_excerpt,
        "rule_hits": normalized_hits,
    }
    encoded = json.dumps(digest_input, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def should_enqueue_candidate(
    *,
    text_excerpt: str,
    fraud_probability: float,
    result_confidence: float,
    risk_level: str,
    rule_hits: list[str] | None = None,
    kb_hits: list[dict[str, Any]] | None = None,
) -> bool:
    if not NOVEL_CASE_QUEUE_ENABLED:
        return False

    normalized_excerpt = _normalize_text_excerpt(text_excerpt)
    if len(normalized_excerpt) < NOVEL_CASE_MIN_TEXT_LEN:
        return False

    if str(risk_level or "").strip().lower() not in {"medium", "high"}:
        return False

    if float(fraud_probability or 0.0) < NOVEL_CASE_MIN_PROBABILITY:
        return False

    if float(result_confidence or 0.0) < NOVEL_CASE_MIN_CONFIDENCE:
        return False

    normalized_rule_hits = _normalize_keyword_list(rule_hits or [])
    normalized_kb_hits = list(kb_hits or [])
    top_kb_score = _top_kb_score(normalized_kb_hits)
    top_overlap_count = _top_overlap_count(normalized_kb_hits)

    if not normalized_kb_hits:
        return True

    if top_kb_score < NOVEL_CASE_KB_SCORE_THRESHOLD:
        return True

    if normalized_rule_hits and top_overlap_count == 0:
        return True

    return False


def _derive_title(reason: list[str], text_excerpt: str) -> str:
    if reason:
        prefix = _normalize_text_excerpt(reason[0], limit=28)
        if prefix:
            return f"{prefix}的新变种案例"

    excerpt = _normalize_text_excerpt(text_excerpt, limit=24)
    if excerpt:
        return f"{excerpt} 的待审核案例"

    return PLACEHOLDER_TITLE


def _derive_question(source_modality: str, text_excerpt: str) -> str:
    excerpt = _normalize_text_excerpt(text_excerpt, limit=64)
    modality_label = str(source_modality or "text").strip() or "text"
    if excerpt:
        return f"这段{modality_label}内容是否属于新的诈骗变种：{excerpt}？"
    return "这是否属于一个新的诈骗变种案例？"


def _default_safe_actions() -> list[str]:
    return [
        "先暂停当前操作，不要转账或继续配合",
        "通过官方 App、官网或客服电话独立核验",
        "不要提供验证码、银行卡号或身份证信息",
        "保留聊天记录、截图、通话记录等证据",
    ]


def build_candidate_payload(
    *,
    source_channel: str,
    source_modality: str,
    text_excerpt: str,
    fraud_probability: float,
    result_confidence: float,
    risk_level: str,
    reason: list[str] | None = None,
    rule_hits: list[str] | None = None,
    kb_hits: list[dict[str, Any]] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any]:
    normalized_excerpt = _mask_sensitive_text(_normalize_text_excerpt(text_excerpt))
    normalized_reason = _normalize_reason_list(reason)
    normalized_rule_hits = _normalize_keyword_list(rule_hits)
    normalized_kb_hits = [_sanitize_kb_hit(hit) for hit in (kb_hits or [])[:3]]
    normalized_actions = _normalize_reason_list(safe_actions) or _default_safe_actions()

    fingerprint = build_candidate_fingerprint(
        source_modality=source_modality,
        text_excerpt=normalized_excerpt,
        rule_hits=normalized_rule_hits,
    )
    candidate_id = f"nc_{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}_{fingerprint[:8]}"

    return {
        "candidate_id": candidate_id,
        "status": "pending",
        "created_at": _utc_now_iso(),
        "last_seen_at": _utc_now_iso(),
        "occurrence_count": 1,
        "source_channel": str(source_channel or "").strip(),
        "source_modality": str(source_modality or "").strip(),
        "fingerprint": fingerprint,
        "evidence": {
            "text_excerpt": normalized_excerpt,
            "risk_level": str(risk_level or "").strip().lower() or "medium",
            "fraud_probability": round(float(fraud_probability or 0.0), 4),
            "result_confidence": round(float(result_confidence or 0.0), 4),
            "reason": normalized_reason,
            "rule_hits": normalized_rule_hits,
            "kb_hits": normalized_kb_hits,
        },
        "proposal": {
            "fraud_type": PLACEHOLDER_FRAUD_TYPE,
            "subtype": PLACEHOLDER_SUBTYPE,
            "title": _derive_title(normalized_reason, normalized_excerpt),
            "question": _derive_question(source_modality, normalized_excerpt),
            "answer": "这条内容呈现出较高诈骗风险，但当前知识库尚未稳定匹配到现有案例，建议人工复核后决定是否纳入知识库。",
            "warning": normalized_reason[0] if normalized_reason else "疑似新兴诈骗案例，建议人工复核。",
            "safe_actions": normalized_actions[:6],
            "keywords": normalized_rule_hits[:5],
        },
        "review": {
            "reviewer": "",
            "reviewed_at": "",
            "decision": "",
            "comment": "",
        },
    }


def enqueue_candidate(
    *,
    source_channel: str,
    source_modality: str,
    text_excerpt: str,
    fraud_probability: float,
    result_confidence: float,
    risk_level: str,
    reason: list[str] | None = None,
    rule_hits: list[str] | None = None,
    kb_hits: list[dict[str, Any]] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any] | None:
    if not should_enqueue_candidate(
        text_excerpt=text_excerpt,
        fraud_probability=fraud_probability,
        result_confidence=result_confidence,
        risk_level=risk_level,
        rule_hits=rule_hits,
        kb_hits=kb_hits,
    ):
        return None

    new_candidate = build_candidate_payload(
        source_channel=source_channel,
        source_modality=source_modality,
        text_excerpt=text_excerpt,
        fraud_probability=fraud_probability,
        result_confidence=result_confidence,
        risk_level=risk_level,
        reason=reason,
        rule_hits=rule_hits,
        kb_hits=kb_hits,
        safe_actions=safe_actions,
    )

    with _STORE_LOCK:
        store = _normalize_store(_read_json(NOVEL_CASE_QUEUE_PATH, _default_store()))
        for item in store["items"]:
            if item.get("fingerprint") != new_candidate["fingerprint"]:
                continue
            item["last_seen_at"] = _utc_now_iso()
            item["occurrence_count"] = int(item.get("occurrence_count", 1) or 1) + 1
            item["evidence"]["fraud_probability"] = max(
                float(item["evidence"].get("fraud_probability", 0.0) or 0.0),
                new_candidate["evidence"]["fraud_probability"],
            )
            item["evidence"]["result_confidence"] = max(
                float(item["evidence"].get("result_confidence", 0.0) or 0.0),
                new_candidate["evidence"]["result_confidence"],
            )
            merged_reason = _normalize_reason_list(
                list(item["evidence"].get("reason") or []) + new_candidate["evidence"]["reason"]
            )
            item["evidence"]["reason"] = merged_reason
            item["proposal"]["warning"] = merged_reason[0] if merged_reason else item["proposal"].get("warning", "")
            store["updated_at"] = _utc_now_iso()
            _write_json(NOVEL_CASE_QUEUE_PATH, store)
            return deepcopy(item)

        store["items"].append(new_candidate)
        store["updated_at"] = _utc_now_iso()
        _write_json(NOVEL_CASE_QUEUE_PATH, store)
        return deepcopy(new_candidate)


def list_candidates(status: str = "", limit: int = 50) -> dict[str, Any]:
    store = load_candidate_store()
    normalized_status = str(status or "").strip().lower()

    items = list(store["items"])
    if normalized_status:
        items = [item for item in items if str(item.get("status") or "").strip().lower() == normalized_status]

    items.sort(
        key=lambda item: (
            str(item.get("last_seen_at") or ""),
            str(item.get("created_at") or ""),
        ),
        reverse=True,
    )
    if limit > 0:
        items = items[:limit]

    counts: dict[str, int] = {}
    for item in store["items"]:
        item_status = str(item.get("status") or "pending")
        counts[item_status] = counts.get(item_status, 0) + 1

    return {
        "items": items,
        "counts": counts,
        "total": len(store["items"]),
        "filtered_total": len(items),
        "updated_at": store["updated_at"],
    }


def get_candidate(candidate_id: str) -> dict[str, Any]:
    normalized_id = str(candidate_id or "").strip()
    if not normalized_id:
        raise CandidateNotFoundError("candidate_id 不能为空")

    store = load_candidate_store()
    for item in store["items"]:
        if item.get("candidate_id") == normalized_id:
            return item

    raise CandidateNotFoundError(f"未找到候选案例: {normalized_id}")


def _update_candidate(
    candidate_id: str,
    mutator,
) -> dict[str, Any]:
    normalized_id = str(candidate_id or "").strip()
    if not normalized_id:
        raise CandidateNotFoundError("candidate_id 不能为空")

    with _STORE_LOCK:
        store = _normalize_store(_read_json(NOVEL_CASE_QUEUE_PATH, _default_store()))
        for item in store["items"]:
            if item.get("candidate_id") != normalized_id:
                continue
            mutator(item)
            store["updated_at"] = _utc_now_iso()
            _write_json(NOVEL_CASE_QUEUE_PATH, store)
            return deepcopy(item)

    raise CandidateNotFoundError(f"未找到候选案例: {normalized_id}")


def approve_candidate(
    candidate_id: str,
    *,
    reviewer: str,
    fraud_type: str,
    subtype: str,
    title: str = "",
    question: str = "",
    answer: str = "",
    warning: str = "",
    safe_actions: list[str] | None = None,
    keywords: list[str] | None = None,
    comment: str = "",
) -> dict[str, Any]:
    normalized_reviewer = str(reviewer or "").strip()
    normalized_fraud_type = str(fraud_type or "").strip()
    normalized_subtype = str(subtype or "").strip()
    if not normalized_reviewer:
        raise CandidateReviewError("reviewer 不能为空")
    if not normalized_fraud_type:
        raise CandidateReviewError("fraud_type 不能为空")
    if not normalized_subtype:
        raise CandidateReviewError("subtype 不能为空")

    def _mutate(item: dict[str, Any]) -> None:
        if str(item.get("status") or "") == "applied":
            raise CandidateReviewError("该候选案例已发布，不能重复审批")

        proposal = item.setdefault("proposal", {})
        evidence = item.setdefault("evidence", {})
        proposal["fraud_type"] = normalized_fraud_type
        proposal["subtype"] = normalized_subtype
        proposal["title"] = str(title or proposal.get("title") or PLACEHOLDER_TITLE).strip()
        proposal["question"] = str(question or proposal.get("question") or "").strip()
        proposal["answer"] = str(answer or proposal.get("answer") or "").strip()
        proposal["warning"] = str(warning or proposal.get("warning") or "").strip()
        proposal["safe_actions"] = _normalize_reason_list(safe_actions) or proposal.get("safe_actions") or _default_safe_actions()
        proposal["keywords"] = _normalize_keyword_list(keywords) or proposal.get("keywords") or list(evidence.get("rule_hits") or [])
        item["status"] = "approved"
        item["review"] = {
            "reviewer": normalized_reviewer,
            "reviewed_at": _utc_now_iso(),
            "decision": "approve",
            "comment": str(comment or "").strip(),
        }

    return _update_candidate(candidate_id, _mutate)


def reject_candidate(candidate_id: str, *, reviewer: str, comment: str = "") -> dict[str, Any]:
    normalized_reviewer = str(reviewer or "").strip()
    if not normalized_reviewer:
        raise CandidateReviewError("reviewer 不能为空")

    def _mutate(item: dict[str, Any]) -> None:
        if str(item.get("status") or "") == "applied":
            raise CandidateReviewError("该候选案例已发布，不能驳回")
        item["status"] = "rejected"
        item["review"] = {
            "reviewer": normalized_reviewer,
            "reviewed_at": _utc_now_iso(),
            "decision": "reject",
            "comment": str(comment or "").strip(),
        }

    return _update_candidate(candidate_id, _mutate)


def _load_seed_payload() -> dict[str, Any]:
    payload = _read_json(
        FAQ_SEED_PATH,
        {
            "version": "rag_seed_v2",
            "updated_at": _today_str(),
            "source": "local_seed",
            "fraud_types": [],
        },
    )
    if not isinstance(payload, dict):
        payload = {
            "version": "rag_seed_v2",
            "updated_at": _today_str(),
            "source": "local_seed",
            "fraud_types": [],
        }
    fraud_types = payload.get("fraud_types")
    if not isinstance(fraud_types, list):
        payload["fraud_types"] = []
    return payload


def _find_or_create_fraud_type(payload: dict[str, Any], fraud_type: str) -> dict[str, Any]:
    fraud_types = payload.setdefault("fraud_types", [])
    for group in fraud_types:
        if str(group.get("fraud_type") or "").strip() == fraud_type:
            return group

    group = {
        "fraud_type": fraud_type,
        "keywords": [],
        "subtypes": [],
    }
    fraud_types.append(group)
    return group


def _find_or_create_subtype(group: dict[str, Any], subtype: str, candidate: dict[str, Any]) -> dict[str, Any]:
    subtypes = group.setdefault("subtypes", [])
    for item in subtypes:
        if str(item.get("subtype") or "").strip() == subtype:
            return item

    proposal = candidate.get("proposal") or {}
    evidence = candidate.get("evidence") or {}
    subtype_group = {
        "subtype": subtype,
        "risk_level": str(evidence.get("risk_level") or "medium"),
        "warning": str(proposal.get("warning") or ""),
        "safe_actions": list(proposal.get("safe_actions") or _default_safe_actions()),
        "keywords": list(proposal.get("keywords") or []),
        "items": [],
    }
    subtypes.append(subtype_group)
    return subtype_group


def _merge_values(existing: list[str], incoming: list[str]) -> list[str]:
    seen = set()
    result: list[str] = []
    for value in list(existing or []) + list(incoming or []):
        normalized = str(value or "").strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result


def _append_candidate_to_seed(payload: dict[str, Any], candidate: dict[str, Any]) -> bool:
    proposal = candidate.get("proposal") or {}
    candidate_id = str(candidate.get("candidate_id") or "").strip()
    fraud_type = str(proposal.get("fraud_type") or "").strip()
    subtype = str(proposal.get("subtype") or "").strip()
    if not candidate_id or not fraud_type or not subtype:
        raise CandidateReviewError("候选案例缺少发布所需的 fraud_type/subtype 信息")

    group = _find_or_create_fraud_type(payload, fraud_type)
    subtype_group = _find_or_create_subtype(group, subtype, candidate)

    group["keywords"] = _merge_values(group.get("keywords") or [], proposal.get("keywords") or [])
    subtype_group["keywords"] = _merge_values(subtype_group.get("keywords") or [], proposal.get("keywords") or [])
    subtype_group["safe_actions"] = _merge_values(subtype_group.get("safe_actions") or [], proposal.get("safe_actions") or [])
    if str(proposal.get("warning") or "").strip():
        subtype_group["warning"] = str(proposal["warning"]).strip()
    if str((candidate.get("evidence") or {}).get("risk_level") or "").strip():
        subtype_group["risk_level"] = str(candidate["evidence"]["risk_level"]).strip()

    items = subtype_group.setdefault("items", [])
    for item in items:
        if str(item.get("id") or "").strip() == candidate_id:
            return False

    items.append(
        {
            "id": candidate_id,
            "title": str(proposal.get("title") or PLACEHOLDER_TITLE).strip(),
            "question": str(proposal.get("question") or "").strip(),
            "answer": str(proposal.get("answer") or "").strip(),
            "keywords": list(proposal.get("keywords") or []),
        }
    )
    return True


def _run_rebuild_command(tag: str = "") -> dict[str, Any]:
    command = [sys.executable, "-m", "app.scripts.rebuild_kb"]
    if str(tag or "").strip():
        command.extend(["--tag", str(tag).strip()])

    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=NOVEL_CASE_REBUILD_TIMEOUT_SEC,
        check=False,
    )
    return {
        "returncode": completed.returncode,
        "stdout": (completed.stdout or "").strip(),
        "stderr": (completed.stderr or "").strip(),
        "command": command,
    }


def apply_approved_candidates(*, rebuild: bool = False, tag: str = "") -> dict[str, Any]:
    with _STORE_LOCK:
        store = _normalize_store(_read_json(NOVEL_CASE_QUEUE_PATH, _default_store()))
        payload = _load_seed_payload()
        merged_count = 0
        skipped_count = 0
        applied_ids: list[str] = []

        for item in store["items"]:
            if str(item.get("status") or "").strip() != "approved":
                continue
            merged = _append_candidate_to_seed(payload, item)
            if merged:
                merged_count += 1
            else:
                skipped_count += 1
            item["status"] = "applied"
            item["applied_at"] = _utc_now_iso()
            applied_ids.append(str(item.get("candidate_id") or ""))

        payload["updated_at"] = _today_str()
        _write_json(FAQ_SEED_PATH, payload)
        store["updated_at"] = _utc_now_iso()
        _write_json(NOVEL_CASE_QUEUE_PATH, store)

    rebuild_result = None
    if rebuild and applied_ids:
        rebuild_result = _run_rebuild_command(tag=tag)
        if int(rebuild_result.get("returncode", 1) or 1) != 0:
            raise NovelCaseError(
                f"知识库重建失败: {(rebuild_result.get('stderr') or rebuild_result.get('stdout') or '').strip()}"
            )

    return {
        "applied_count": merged_count,
        "skipped_count": skipped_count,
        "applied_candidate_ids": applied_ids,
        "rebuild_result": rebuild_result,
    }
