from __future__ import annotations

import json
from typing import Any

from app.services.deepseek_client import DEEPSEEK_MODEL, client

INTENT_SYSTEM_PROMPT = """
你是反诈 App 的意图路由器。
你的任务不是直接回答用户，而是判断这条输入最主要的对话意图，方便后续选择合适的处理策略。

你必须只输出严格 JSON，不要输出 Markdown。JSON 字段固定为：
{
  "primary_intent": "risk_check",
  "secondary_intent": "",
  "confidence": 0.0,
  "needs_rag": true,
  "should_force_analysis": false,
  "reason": ["原因1", "原因2"]
}

primary_intent 只能从以下枚举中选择：
- risk_check: 用户在判断某段对话、链接、来电、短信或要求是否可疑
- knowledge_qa: 用户在了解反诈知识、套路解释、案例说明
- guidance_request: 用户在问接下来该怎么办、如何处置、如何核验
- report_help: 用户在问举报、报警、冻结、留证、求助
- casual_chat: 问候、闲聊、无明确反诈任务

判定要求：
- 涉及“是不是诈骗、能不能信、要不要转账、验证码、共享屏幕、安全账户、客服、公检法、刷单、投资、征信修复”等高风险话术时，优先判为 risk_check。
- 如果用户已经在问“怎么办、怎么处理、如何追回、要不要报警”，优先判为 guidance_request 或 report_help。
- needs_rag 表示是否需要结合知识库检索。
- should_force_analysis 表示是否应走更谨慎的风险分析路径。
- reason 使用简体中文，最多 3 条。
"""

HIGH_RISK_INTENT_KEYWORDS = {
    "risk_check": [
        "是不是诈骗",
        "诈骗吗",
        "可疑吗",
        "靠谱吗",
        "能信吗",
        "帮我看看",
        "要我转账",
        "验证码",
        "安全账户",
        "共享屏幕",
        "公检法",
        "刷单",
        "投资",
        "征信修复",
        "客服",
        "领奖",
    ],
    "guidance_request": [
        "怎么办",
        "怎么处理",
        "如何处理",
        "怎么核实",
        "怎么核验",
        "如何核验",
        "怎么做",
        "下一步",
    ],
    "report_help": [
        "报警",
        "报案",
        "举报",
        "投诉",
        "冻结",
        "止付",
        "追回",
        "留证",
        "96110",
        "110",
    ],
    "knowledge_qa": [
        "什么是",
        "有哪些套路",
        "怎么识别",
        "如何识别",
        "怎么防范",
        "如何防范",
        "典型案例",
        "法律法规",
        "反诈知识",
    ],
}

HIGH_RISK_RULE_HINTS = {
    "transfer",
    "code",
    "police",
    "remote",
    "brush_order",
    "investment",
    "credit_repair",
    "health_product",
}


def _loads_json_object(content: str | None) -> dict[str, Any]:
    if not content:
        return {}

    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _normalize_reason_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def _fallback_intent_result(
    text: str,
    rule_hits: list[str] | None = None,
) -> dict[str, Any]:
    clean_text = (text or "").strip()
    normalized_rule_hits = set(rule_hits or [])
    lowered_text = clean_text.lower()

    primary_intent = "casual_chat"
    secondary_intent = ""
    confidence = 0.42
    needs_rag = False
    should_force_analysis = False
    reasons: list[str] = []

    if any(keyword in clean_text for keyword in HIGH_RISK_INTENT_KEYWORDS["report_help"]):
        primary_intent = "report_help"
        secondary_intent = "guidance_request"
        confidence = 0.83
        needs_rag = True
        should_force_analysis = True
        reasons.append("用户在询问报警、举报、冻结或追回等求助动作")
    elif any(keyword in clean_text for keyword in HIGH_RISK_INTENT_KEYWORDS["guidance_request"]):
        primary_intent = "guidance_request"
        confidence = 0.78
        needs_rag = True
        should_force_analysis = True
        reasons.append("用户在询问如何处置或核验")

    if (
        normalized_rule_hits.intersection(HIGH_RISK_RULE_HINTS)
        or any(keyword in clean_text for keyword in HIGH_RISK_INTENT_KEYWORDS["risk_check"])
    ):
        primary_intent = "risk_check"
        confidence = max(confidence, 0.86)
        needs_rag = True
        should_force_analysis = True
        reasons.append("输入中包含高风险诈骗线索或命中高危规则")

    if primary_intent == "casual_chat" and any(
        keyword in clean_text for keyword in HIGH_RISK_INTENT_KEYWORDS["knowledge_qa"]
    ):
        primary_intent = "knowledge_qa"
        confidence = 0.72
        needs_rag = True
        reasons.append("输入更像在询问反诈知识或案例说明")

    if primary_intent == "casual_chat" and lowered_text in {"hi", "hello", "你好", "在吗"}:
        confidence = 0.64
        reasons.append("输入更像闲聊或问候")

    return {
        "primary_intent": primary_intent,
        "secondary_intent": secondary_intent,
        "confidence": round(confidence, 4),
        "needs_rag": needs_rag,
        "should_force_analysis": should_force_analysis,
        "reason": reasons[:3],
    }


def _normalize_intent_result(data: dict[str, Any]) -> dict[str, Any]:
    allowed_intents = {
        "risk_check",
        "knowledge_qa",
        "guidance_request",
        "report_help",
        "casual_chat",
    }
    primary_intent = str(data.get("primary_intent") or "risk_check").strip()
    secondary_intent = str(data.get("secondary_intent") or "").strip()
    if primary_intent not in allowed_intents:
        primary_intent = "risk_check"
    if secondary_intent and secondary_intent not in allowed_intents:
        secondary_intent = ""

    try:
        confidence = float(data.get("confidence", 0.5))
    except (TypeError, ValueError):
        confidence = 0.5

    return {
        "primary_intent": primary_intent,
        "secondary_intent": secondary_intent,
        "confidence": max(0.0, min(1.0, confidence)),
        "needs_rag": bool(data.get("needs_rag", True)),
        "should_force_analysis": bool(data.get("should_force_analysis", primary_intent == "risk_check")),
        "reason": _normalize_reason_list(data.get("reason"))[:3],
    }


def detect_dialog_intent(
    text: str,
    rule_hits: list[str] | None = None,
) -> dict[str, Any]:
    clean_text = (text or "").strip()
    normalized_rule_hits = rule_hits or []
    if not clean_text:
        return _fallback_intent_result(clean_text, normalized_rule_hits)

    if client is None:
        return _fallback_intent_result(clean_text, normalized_rule_hits)

    payload = {
        "text": clean_text,
        "rule_hits": normalized_rule_hits,
    }

    try:
        response = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {"role": "system", "content": INTENT_SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            temperature=0.1,
            max_tokens=300,
            response_format={"type": "json_object"},
        )
        data = _loads_json_object(response.choices[0].message.content)
        normalized = _normalize_intent_result(data)
        if normalized["primary_intent"] == "casual_chat" and normalized_rule_hits:
            return _fallback_intent_result(clean_text, normalized_rule_hits)
        return normalized
    except Exception:
        return _fallback_intent_result(clean_text, normalized_rule_hits)
