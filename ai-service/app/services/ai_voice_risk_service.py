from __future__ import annotations

from typing import Any

AI_VOICE_SIGNAL_TYPE = "ai_voice_scene"
AI_VOICE_FRAUD_TYPES = {"AI合成语音诈骗"}

RELATION_KEYWORDS = {
    "妈妈",
    "妈",
    "爸爸",
    "爸",
    "儿子",
    "女儿",
    "孩子",
    "家里人",
    "亲戚",
    "老公",
    "老婆",
    "奶奶",
    "爷爷",
    "叔叔",
    "阿姨",
    "表哥",
    "表姐",
}

AUTHORITY_KEYWORDS = {
    "领导",
    "老板",
    "总经理",
    "张总",
    "王总",
    "李总",
    "主管",
    "主任",
    "校长",
    "老师",
    "班主任",
    "财务总监",
    "经理",
}

VOICE_CUE_KEYWORDS = {
    "声音很像",
    "声音特别像",
    "声音一模一样",
    "听起来像",
    "像本人",
    "像我儿子",
    "像我妈",
    "像我爸",
    "像我领导",
    "电话里说",
    "语音里说",
    "语音电话",
    "录音",
    "变声",
    "拟声",
    "AI语音",
    "AI合成",
    "合成语音",
    "冒充声音",
}

URGENCY_KEYWORDS = {
    "赶紧",
    "马上",
    "立刻",
    "现在",
    "来不及",
    "救急",
    "急用钱",
    "出事了",
    "先帮我",
    "先打给",
    "快一点",
    "赶快",
    "立即处理",
    "今晚之前",
}

TRANSFER_KEYWORDS = {
    "转账",
    "打钱",
    "汇款",
    "借我",
    "借钱",
    "垫付",
    "代付",
    "先付",
    "先转",
    "收款账户",
    "银行卡号",
    "账号",
    "卡号",
    "对公账户",
}

VERIFY_EVASION_KEYWORDS = {
    "不要回拨",
    "别回拨",
    "别告诉别人",
    "先别和家里说",
    "先保密",
    "不方便视频",
    "不能视频",
    "别打原号码",
    "手机坏了",
    "卡坏了",
    "信号不好",
    "会议中",
    "正在开会",
    "不方便接电话",
    "换号了",
}

HELP_CONTEXT_KEYWORDS = {
    "帮忙",
    "求助",
    "救我",
    "出事",
    "事故",
    "住院",
    "被扣了",
    "被抓了",
    "急事",
}


def _normalize_text(text: str) -> str:
    return str(text or "").replace(" ", "").replace("\n", "").strip()


def _has_any(text: str, keywords: set[str]) -> bool:
    return any(keyword in text for keyword in keywords)


def _dedup(items: list[str], limit: int) -> list[str]:
    result: list[str] = []
    seen = set()
    for item in items:
        normalized = str(item).strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result[:limit]


def _default_signal() -> dict[str, Any]:
    return {
        "signal_type": AI_VOICE_SIGNAL_TYPE,
        "scene": "",
        "active": False,
        "score": 0.0,
        "confidence": 0.0,
        "risk_tags": [],
        "reason": [],
        "recommended_actions": [],
    }


def detect_ai_voice_scenario_risk(
    text: str,
    *,
    modality: str = "text",
    intent_result: dict[str, Any] | None = None,
    kb_hits: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    clean_text = _normalize_text(text)
    if not clean_text:
        return _default_signal()

    normalized_intent = dict(intent_result or {})
    normalized_kb_hits = list(kb_hits or [])

    relation_hit = _has_any(clean_text, RELATION_KEYWORDS)
    authority_hit = _has_any(clean_text, AUTHORITY_KEYWORDS)
    familiar_identity_hit = relation_hit or authority_hit
    voice_cue_hit = _has_any(clean_text, VOICE_CUE_KEYWORDS)
    urgency_hit = _has_any(clean_text, URGENCY_KEYWORDS)
    transfer_hit = _has_any(clean_text, TRANSFER_KEYWORDS)
    evasion_hit = _has_any(clean_text, VERIFY_EVASION_KEYWORDS)
    help_context_hit = _has_any(clean_text, HELP_CONTEXT_KEYWORDS)
    modality_boost_hit = modality in {"audio", "video"}
    kb_ai_voice_hit = any(
        str(hit.get("fraud_type") or "").strip() in AI_VOICE_FRAUD_TYPES
        for hit in normalized_kb_hits
    )

    score = 0.0
    confidence = 0.38
    risk_tags: list[str] = []
    reasons: list[str] = []
    recommended_actions = [
        "先挂断后回拨原有号码或发起视频核验",
        "让家人、同事或共同熟人交叉确认身份",
        "未完成独立核验前不要转账、代付或提供验证码",
        "保留通话时间、录音、账号和聊天证据",
    ]

    if relation_hit:
        score += 0.16
        confidence += 0.08
        risk_tags.append("familiar_impersonation")
        reasons.append("内容涉及亲属或熟人身份，存在熟人冒充风险")

    if authority_hit:
        score += 0.15
        confidence += 0.06
        risk_tags.append("authority_impersonation")
        reasons.append("内容涉及领导或权威身份，存在身份冒充风险")

    if voice_cue_hit:
        score += 0.18
        confidence += 0.10
        risk_tags.append("voice_identity_reliance")
        reasons.append("内容明显依赖声音相似来建立信任")

    if urgency_hit:
        score += 0.12
        confidence += 0.06
        risk_tags.append("urgent_pressure")
        reasons.append("内容带有强催促或紧急施压特征")

    if transfer_hit:
        score += 0.18
        confidence += 0.08
        risk_tags.append("money_request")
        reasons.append("内容涉及借款、转账或代付请求")

    if evasion_hit:
        score += 0.12
        confidence += 0.08
        risk_tags.append("verification_evasion")
        reasons.append("内容出现回避回拨、视频或第三方核验的迹象")

    if help_context_hit:
        score += 0.05
        confidence += 0.03
        risk_tags.append("emergency_help_context")

    if modality_boost_hit and familiar_identity_hit:
        score += 0.10
        confidence += 0.05
        risk_tags.append("audio_video_identity_channel")
        reasons.append("当前输入为音频或视频，且场景依赖身份与声音建立信任")

    if modality_boost_hit and voice_cue_hit:
        score += 0.07
        confidence += 0.04

    if familiar_identity_hit and transfer_hit:
        score += 0.10
        confidence += 0.06
        reasons.append("熟人或领导身份与资金请求同时出现，符合高风险冒充借款场景")

    if voice_cue_hit and urgency_hit:
        score += 0.06
        confidence += 0.04

    if evasion_hit and transfer_hit:
        score += 0.06
        confidence += 0.04

    if kb_ai_voice_hit:
        score += 0.16
        confidence += 0.10
        risk_tags.append("kb_ai_voice_match")
        reasons.append("知识库命中了 AI 拟声或熟人冒充相关案例")

    if normalized_intent.get("primary_intent") in {"risk_check", "report_help"} and (
        familiar_identity_hit or voice_cue_hit
    ):
        score += 0.04
        confidence += 0.03

    active = bool(
        score >= 0.56
        or (modality_boost_hit and familiar_identity_hit and transfer_hit)
        or (voice_cue_hit and transfer_hit and urgency_hit)
        or (kb_ai_voice_hit and (familiar_identity_hit or voice_cue_hit))
    )
    if not active:
        return _default_signal()

    scene = "ai_voice_familiar_impersonation"
    if authority_hit and not relation_hit:
        scene = "ai_voice_authority_impersonation"

    return {
        "signal_type": AI_VOICE_SIGNAL_TYPE,
        "scene": scene,
        "active": True,
        "score": round(min(score, 0.95), 4),
        "confidence": round(min(confidence, 0.98), 4),
        "risk_tags": _dedup(risk_tags, limit=6),
        "reason": _dedup(reasons, limit=5),
        "recommended_actions": _dedup(recommended_actions, limit=5),
    }
