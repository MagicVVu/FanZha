from __future__ import annotations

import json
import os
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from threading import Lock
from typing import Any

APP_ROOT = Path(__file__).resolve().parent.parent
USER_MEMORY_STORE_PATH = Path(
    os.getenv("USER_MEMORY_STORE_PATH", str(APP_ROOT / "data" / "user_memory_store.json"))
)
USER_MEMORY_MAX_RECENT_EVENTS = int(os.getenv("USER_MEMORY_MAX_RECENT_EVENTS", "12"))

ROLE_ALIASES = {
    "": "general",
    "general": "general",
    "normal": "general",
    "default": "general",
    "普通": "general",
    "普通用户": "general",
    "unknown": "general",
    "elder": "elder",
    "senior": "elder",
    "old": "elder",
    "老人": "elder",
    "老年人": "elder",
    "长辈": "elder",
    "student": "student",
    "学生": "student",
    "college_student": "student",
    "finance": "finance_staff",
    "finance_staff": "finance_staff",
    "accountant": "finance_staff",
    "财务": "finance_staff",
    "财会": "finance_staff",
    "会计": "finance_staff",
}

ROLE_LABELS = {
    "general": "普通用户",
    "elder": "老人用户",
    "student": "学生用户",
    "finance_staff": "财会人员",
}

ROLE_FRAUD_TYPE_HINTS = {
    "general": set(),
    "elder": {"保健品诈骗", "中奖领奖诈骗", "冒充公检法", "冒充客服", "冒充银行客服"},
    "student": {"刷单返利诈骗", "投资理财诈骗", "快递理赔诈骗", "中奖领奖诈骗"},
    "finance_staff": {"冒充客服", "投资理财诈骗", "冻结解冻诈骗", "征信修复诈骗", "冒充银行客服"},
}

ROLE_KEYWORD_HINTS = {
    "general": set(),
    "elder": {"保健品", "养生", "中奖", "公检法", "客服"},
    "student": {"刷单", "返利", "快递", "兼职", "投资"},
    "finance_staff": {"转账", "对公", "验证码", "远程", "客服"},
}

_memory_lock = Lock()


def normalize_user_role(role: str | None) -> str:
    normalized = str(role or "").strip().lower()
    return ROLE_ALIASES.get(normalized, ROLE_ALIASES.get(str(role or "").strip(), "general"))


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _default_profile(user_id: str = "", role: str = "general") -> dict[str, Any]:
    normalized_role = normalize_user_role(role)
    timestamp = _utc_now_iso()
    return {
        "user_id": user_id,
        "role": normalized_role,
        "created_at": timestamp,
        "updated_at": timestamp,
        "total_interactions": 0,
        "recent_events": [],
        "recent_high_risk_count": 0,
        "recent_medium_risk_count": 0,
        "recent_intents": [],
        "recent_fraud_types": [],
    }


def _read_store() -> dict[str, Any]:
    if not USER_MEMORY_STORE_PATH.exists():
        return {}

    try:
        data = json.loads(USER_MEMORY_STORE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _write_store(payload: dict[str, Any]) -> None:
    USER_MEMORY_STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    USER_MEMORY_STORE_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def _normalize_recent_events(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized_events: list[dict[str, Any]] = []
    for event in events[-USER_MEMORY_MAX_RECENT_EVENTS:]:
        if not isinstance(event, dict):
            continue
        normalized_events.append(
            {
                "timestamp": str(event.get("timestamp") or _utc_now_iso()),
                "intent": str(event.get("intent") or "").strip(),
                "risk_level": str(event.get("risk_level") or "low").strip(),
                "fraud_type": str(event.get("fraud_type") or "").strip(),
                "probability": float(event.get("probability", 0.0) or 0.0),
            }
        )
    return normalized_events


def _enrich_profile(profile: dict[str, Any]) -> dict[str, Any]:
    recent_events = _normalize_recent_events(list(profile.get("recent_events") or []))
    risk_counter = Counter(str(item.get("risk_level") or "low") for item in recent_events)

    recent_intents = []
    recent_fraud_types = []

    for item in reversed(recent_events):
        intent = str(item.get("intent") or "").strip()
        fraud_type = str(item.get("fraud_type") or "").strip()
        if intent and intent not in recent_intents:
            recent_intents.append(intent)
        if fraud_type and fraud_type not in recent_fraud_types:
            recent_fraud_types.append(fraud_type)

    profile["recent_events"] = recent_events
    profile["recent_high_risk_count"] = int(risk_counter.get("high", 0))
    profile["recent_medium_risk_count"] = int(risk_counter.get("medium", 0))
    profile["recent_intents"] = recent_intents[:5]
    profile["recent_fraud_types"] = recent_fraud_types[:5]
    profile["role"] = normalize_user_role(profile.get("role"))
    return profile


def _role_label(role: str) -> str:
    normalized_role = normalize_user_role(role)
    return ROLE_LABELS.get(normalized_role, ROLE_LABELS["general"])


def get_user_profile(user_id: str = "", user_role: str = "general") -> dict[str, Any]:
    normalized_role = normalize_user_role(user_role)
    normalized_user_id = str(user_id or "").strip()

    if not normalized_user_id:
        return _enrich_profile(_default_profile("", normalized_role))

    with _memory_lock:
        store = _read_store()
        profile = store.get(normalized_user_id) or _default_profile(normalized_user_id, normalized_role)
        if normalized_role != "general" or not str(profile.get("role") or "").strip():
            profile["role"] = normalized_role
        return _enrich_profile(profile)


def snapshot_user_profile(profile: dict[str, Any]) -> dict[str, Any]:
    normalized_profile = _enrich_profile(dict(profile))
    normalized_user_id = str(normalized_profile.get("user_id") or "").strip()
    role = normalize_user_role(normalized_profile.get("role"))
    return {
        "user_id": normalized_user_id,
        "role": role,
        "role_label": _role_label(role),
        "memory_enabled": bool(normalized_user_id),
        "total_interactions": int(normalized_profile.get("total_interactions", 0) or 0),
        "recent_high_risk_count": int(normalized_profile.get("recent_high_risk_count", 0) or 0),
        "recent_medium_risk_count": int(normalized_profile.get("recent_medium_risk_count", 0) or 0),
        "recent_intents": list(normalized_profile.get("recent_intents") or [])[:4],
        "recent_fraud_types": list(normalized_profile.get("recent_fraud_types") or [])[:4],
    }


def describe_user_context(profile: dict[str, Any]) -> str:
    snapshot = snapshot_user_profile(profile)
    summary_parts = [f"用户角色：{snapshot['role_label']}"]

    if snapshot["memory_enabled"]:
        summary_parts.append(f"累计交互：{snapshot['total_interactions']} 次")

    if snapshot["recent_high_risk_count"] > 0:
        summary_parts.append(f"近期高风险对话：{snapshot['recent_high_risk_count']} 次")
    elif snapshot["recent_medium_risk_count"] > 0:
        summary_parts.append(f"近期中风险对话：{snapshot['recent_medium_risk_count']} 次")

    if snapshot["recent_fraud_types"]:
        summary_parts.append(f"近期涉及：{'、'.join(snapshot['recent_fraud_types'][:3])}")

    return "；".join(summary_parts)


def build_user_context(user_id: str = "", user_role: str = "general") -> dict[str, Any]:
    profile = get_user_profile(user_id=user_id, user_role=user_role)
    snapshot = snapshot_user_profile(profile)
    return {
        "profile": snapshot,
        "prompt_context": {
            **snapshot,
            "context_summary": describe_user_context(profile),
        },
    }


def rerank_hits_for_profile(hits: list[dict[str, Any]], profile: dict[str, Any]) -> list[dict[str, Any]]:
    if not hits:
        return []

    snapshot = snapshot_user_profile(profile)
    role = snapshot["role"]
    hinted_fraud_types = ROLE_FRAUD_TYPE_HINTS.get(role, set())
    hinted_keywords = ROLE_KEYWORD_HINTS.get(role, set())
    recent_fraud_types = set(snapshot.get("recent_fraud_types") or [])

    reranked: list[dict[str, Any]] = []
    for index, hit in enumerate(hits, start=1):
        candidate = dict(hit)
        fraud_type = str(candidate.get("fraud_type") or "").strip()
        haystack = "\n".join(
            [
                fraud_type,
                str(candidate.get("subtype") or ""),
                str(candidate.get("question") or ""),
                str(candidate.get("warning") or ""),
                " ".join(str(item).strip() for item in candidate.get("keywords", []) if str(item).strip()),
            ]
        )

        applicable_roles = {
            normalize_user_role(item)
            for item in candidate.get("applicable_roles", [])
            if str(item).strip()
        }
        base_score = float(candidate.get("rank_score", candidate.get("second_stage_score", candidate.get("score", 0.0))))
        bonus = 0.0

        if role != "general" and role in applicable_roles:
            bonus += 0.05
        elif role != "general":
            if fraud_type in hinted_fraud_types:
                bonus += 0.03
            elif any(keyword in haystack for keyword in hinted_keywords):
                bonus += 0.02

        if fraud_type and fraud_type in recent_fraud_types:
            bonus += 0.04

        candidate["score"] = round(min(float(candidate.get("score", 0.0)) + bonus, 1.0), 4)
        candidate["rank_score"] = round(min(base_score + bonus, 1.0), 4)
        candidate["_profile_rank"] = index
        reranked.append(candidate)

    reranked.sort(
        key=lambda item: (
            float(item.get("rank_score", 0.0)),
            float(item.get("second_stage_score", item.get("score", 0.0))),
            -int(item.get("_profile_rank", 0)),
        ),
        reverse=True,
    )

    for item in reranked:
        item.pop("_profile_rank", None)
    return reranked


def record_user_interaction(
    *,
    user_id: str = "",
    user_role: str = "general",
    intent_result: dict[str, Any] | None = None,
    risk_level: str = "low",
    probability: float = 0.0,
    kb_hits: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    normalized_user_id = str(user_id or "").strip()
    normalized_role = normalize_user_role(user_role)
    if not normalized_user_id:
        return build_user_context(normalized_user_id, normalized_role)

    normalized_intent = str((intent_result or {}).get("primary_intent") or "").strip()
    top_fraud_type = ""
    if kb_hits:
        top_fraud_type = str((kb_hits[0] or {}).get("fraud_type") or "").strip()

    with _memory_lock:
        store = _read_store()
        profile = store.get(normalized_user_id) or _default_profile(normalized_user_id, normalized_role)
        if normalized_role != "general" or not str(profile.get("role") or "").strip():
            profile["role"] = normalized_role

        profile["total_interactions"] = int(profile.get("total_interactions", 0) or 0) + 1
        profile["updated_at"] = _utc_now_iso()

        recent_events = list(profile.get("recent_events") or [])
        recent_events.append(
            {
                "timestamp": _utc_now_iso(),
                "intent": normalized_intent,
                "risk_level": str(risk_level or "low").strip() or "low",
                "fraud_type": top_fraud_type,
                "probability": round(max(0.0, min(1.0, float(probability or 0.0))), 4),
            }
        )
        profile["recent_events"] = recent_events[-USER_MEMORY_MAX_RECENT_EVENTS:]
        profile = _enrich_profile(profile)
        store[normalized_user_id] = profile
        _write_store(store)

    return build_user_context(normalized_user_id, normalized_role)
