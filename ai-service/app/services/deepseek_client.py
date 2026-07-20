import json
import os
import time
from typing import Any, Iterator

from openai import OpenAI

from app.services.kb_service import estimate_kb_score, format_kb_context

try:
    from app.config import settings
except Exception:
    settings = None


DEEPSEEK_BASE_URL_DEFAULT = "https://api.deepseek.com"
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")


def _get_setting(name: str, default: Any = None) -> Any:
    if settings is None:
        return default
    return getattr(settings, name, default)


DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY") or _get_setting("deepseek_api_key")
DEEPSEEK_BASE_URL = (
    os.getenv("DEEPSEEK_BASE_URL")
    or _get_setting("deepseek_base_url", DEEPSEEK_BASE_URL_DEFAULT)
    or DEEPSEEK_BASE_URL_DEFAULT
)


def _build_client() -> OpenAI | None:
    if not DEEPSEEK_API_KEY:
        return None

    try:
        return OpenAI(api_key=DEEPSEEK_API_KEY, base_url=DEEPSEEK_BASE_URL)
    except Exception:
        return None


client = _build_client()


ANALYZE_SYSTEM_PROMPT = """
你是反诈 App 后端的证据整合器，不是唯一裁判。
规则引擎负责高危硬兜底，RAG 负责召回相似诈骗知识，你负责结合证据生成结构化解释。

你必须只输出严格 JSON，不要输出 Markdown。JSON 字段固定为：
{
  "llm_score": 0.0,
  "kb_score": 0.0,
  "reason": ["原因1", "原因2"],
  "reply": "给用户的一句话简短建议",
  "next_actions": ["建议1", "建议2"]
}

要求：
- llm_score 范围 0 到 1，表示仅从语义证据看诈骗风险的强度。
- kb_score 范围 0 到 0.5，表示知识库证据支持强度，可参考 kb_score_hint。
- reason 使用简体中文，最多 4 条。
- next_actions 使用简体中文，最多 5 条。
- 如果规则或知识库已命中高危证据，不要轻易降风险，只做解释和建议。
- 遇到转账、验证码、安全账户、共享屏幕、公检法、刷单返佣、虚假投资、中奖先交费、神药保健品等模式，应提高 llm_score。
"""


CHAT_SYSTEM_PROMPT = """
你是反诈 App 的聊天助手。
你需要自然、简洁地回答用户问题，并结合规则命中和知识库证据给出反诈建议。

你必须只输出严格 JSON，不要输出 Markdown。JSON 字段固定为：
{
  "reply": "自然语言回复",
  "suggestions": ["建议1", "建议2"],
  "safe_actions": ["处置建议1", "处置建议2"]
}

要求：
- reply 用简体中文，语气清楚、稳妥、不过度恐吓。
- 如果用户描述可疑话术，要提醒不要转账、不要提供验证码、不要共享屏幕，并建议通过官方渠道核验。
- 如果证据不足，可以追问关键信息，例如对方是否要求转账、验证码、下载软件或离开官方平台。
"""


CHAT_STREAM_SYSTEM_PROMPT = """
You are the chat assistant for an anti-fraud app.
Use the user's message, rule hits, and KB evidence to answer in plain Simplified Chinese.
Return plain text only.
Do not return JSON, markdown, code fences, titles, or speaker labels.
Keep the answer practical, concise, and safety-first.
If the scenario looks suspicious, clearly advise the user not to transfer money,
not to share verification codes, and to verify through official channels.
"""


async def llm_risk_analysis(
    text: str,
    faq_hits: list[dict[str, Any]],
    rule_hits: list[str] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any]:
    kb_context = format_kb_context(faq_hits)
    kb_score_hint = estimate_kb_score(faq_hits)
    normalized_rule_hits = rule_hits or []
    normalized_safe_actions = safe_actions or []

    if client is None:
        return _fallback_analysis(text, faq_hits, normalized_rule_hits, normalized_safe_actions)

    payload = {
        "mode": "analyze",
        "text": text,
        "rule_hits": normalized_rule_hits,
        "kb_context": kb_context,
        "kb_hits": faq_hits,
        "kb_score_hint": kb_score_hint,
        "safe_actions": normalized_safe_actions,
    }

    try:
        response = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {"role": "system", "content": ANALYZE_SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            temperature=0.2,
            max_tokens=600,
            response_format={"type": "json_object"},
        )
        data = _loads_json_object(response.choices[0].message.content)
        return _normalize_analysis_result(data, kb_score_hint, normalized_safe_actions)
    except Exception:
        return _fallback_analysis(text, faq_hits, normalized_rule_hits, normalized_safe_actions)


async def general_chat_reply(
    text: str,
    faq_hits: list[dict[str, Any]],
    rule_hits: list[str] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any]:
    normalized_rule_hits = rule_hits or []
    normalized_safe_actions = safe_actions or []
    kb_context = format_kb_context(faq_hits)

    if client is None:
        return _fallback_chat_reply(text, faq_hits, normalized_rule_hits, normalized_safe_actions)

    payload = {
        "mode": "chat",
        "message": text,
        "rule_hits": normalized_rule_hits,
        "kb_context": kb_context,
        "kb_hits": faq_hits,
        "safe_actions": normalized_safe_actions,
    }

    try:
        response = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {"role": "system", "content": CHAT_SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            temperature=0.3,
            max_tokens=600,
            response_format={"type": "json_object"},
        )
        data = _loads_json_object(response.choices[0].message.content)
        return _normalize_chat_result(data, faq_hits, normalized_safe_actions)
    except Exception:
        return _fallback_chat_reply(text, faq_hits, normalized_rule_hits, normalized_safe_actions)


def general_chat_reply_stream(
    text: str,
    faq_hits: list[dict[str, Any]],
    rule_hits: list[str] | None = None,
    safe_actions: list[str] | None = None,
) -> Iterator[str]:
    normalized_rule_hits = rule_hits or []
    normalized_safe_actions = safe_actions or []
    kb_context = format_kb_context(faq_hits)

    if client is None:
        fallback = _fallback_chat_reply(text, faq_hits, normalized_rule_hits, normalized_safe_actions)
        t_fallback = time.time()
        print(f"[deepseek_stream] fallback_without_client ts={t_fallback:.6f}", flush=True)
        yield from _iter_text_chunks(fallback.get("reply", ""))
        return

    payload = {
        "mode": "chat_stream",
        "message": text,
        "rule_hits": normalized_rule_hits,
        "kb_context": kb_context,
        "kb_hits": faq_hits,
        "safe_actions": normalized_safe_actions,
    }

    produced = False
    try:
        t1 = time.time()
        print(f"[deepseek_stream] create_start ts={t1:.6f}", flush=True)
        stream = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {"role": "system", "content": CHAT_STREAM_SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            temperature=0.3,
            max_tokens=600,
            stream=True,
        )
        first_chunk_time = None
        for chunk in stream:
            if not getattr(chunk, "choices", None):
                continue
            delta = getattr(chunk.choices[0].delta, "content", "") or ""
            if not delta:
                continue
            if first_chunk_time is None:
                first_chunk_time = time.time()
                print(
                    f"[deepseek_stream] first_chunk ts={first_chunk_time:.6f} "
                    f"cost={first_chunk_time - t1:.3f}s",
                    flush=True,
                )
            produced = True
            yield delta
    except Exception as e:
        import traceback

        print(f"[deepseek_stream] stream_error: {e}", flush=True)
        traceback.print_exc()
        produced = False

    if produced:
        return

    fallback = _fallback_chat_reply(text, faq_hits, normalized_rule_hits, normalized_safe_actions)
    t_fallback = time.time()
    print(f"[deepseek_stream] fallback_after_stream_error ts={t_fallback:.6f}", flush=True)
    yield from _iter_text_chunks(fallback.get("reply", ""))


def _fallback_analysis(
    text: str,
    faq_hits: list[dict[str, Any]],
    rule_hits: list[str] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any]:
    score = 0.12
    reasons: list[str] = []
    actions = list(safe_actions or [])

    if any(word in text for word in ["转账", "汇款", "打款", "安全账户"]):
        score += 0.30
        reasons.append("出现转账或安全账户相关内容")

    if any(word in text for word in ["验证码", "短信码"]):
        score += 0.22
        reasons.append("出现验证码相关内容")

    if any(word in text for word in ["客服", "退款", "白条", "扣费"]):
        score += 0.22
        reasons.append("出现客服、退款或扣费类话术")

    if any(word in text for word in ["公安", "检察院", "法院", "涉嫌洗钱", "配合调查", "涉案"]):
        score += 0.35
        reasons.append("出现冒充公检法或洗钱调查类话术")

    if any(word in text for word in ["共享屏幕", "远程控制", "会议软件", "下载软件"]):
        score += 0.28
        reasons.append("出现远程控制相关内容")

    if any(word in text for word in ["神药", "保健品", "养生讲座", "包治百病", "免费注射", "没病强身"]):
        score += 0.32
        reasons.append("出现神药或保健品诈骗相关内容")

    if any(word in text for word in ["中奖", "中了", "彩票", "大奖", "百万", "领奖"]) and any(
        word in text for word in ["激活", "激活费", "手续费", "税费", "领奖费", "兑奖费", "先交钱", "保证金", "一万"]
    ):
        score += 0.45
        reasons.append("出现中奖领奖前先交激活费、手续费或税费的高危话术")

    if rule_hits:
        score += min(0.18, len(rule_hits) * 0.06)

    kb_score = estimate_kb_score(faq_hits)
    if faq_hits:
        score += min(0.15, kb_score)
        top_hit = faq_hits[0]
        reasons.append(f"知识库命中相似场景：{top_hit.get('fraud_type', '未知类型')}")

    if not actions:
        actions = _default_safe_actions()

    reply = _build_reply(reasons, actions)

    return {
        "llm_score": min(score, 1.0),
        "kb_score": kb_score,
        "reason": reasons[:4],
        "reply": reply,
        "next_actions": actions[:5],
    }


def _fallback_chat_reply(
    text: str,
    faq_hits: list[dict[str, Any]],
    rule_hits: list[str] | None = None,
    safe_actions: list[str] | None = None,
) -> dict[str, Any]:
    actions = list(safe_actions or [])
    suggestions: list[str] = []

    if rule_hits:
        suggestions.append("这段描述存在可疑风险点，建议先暂停操作。")

    if faq_hits:
        top_hit = faq_hits[0]
        answer = str(top_hit.get("answer") or "").strip()
        fraud_type = str(top_hit.get("fraud_type") or "可疑诈骗场景").strip()
        reply = (
            f"{answer} 当前更像{fraud_type}。"
            "如果对方要求转账、验证码、共享屏幕或离开官方平台，请立即停止。"
        ) if answer else (
            f"这类情况更像{fraud_type}。建议先不要转账或提供验证码，并通过官方渠道核实。"
        )
    else:
        reply = (
            "我可以帮你判断风险。请补充对方是否要求你转账、提供验证码、下载软件、"
            "共享屏幕，或者离开官方平台私下处理。"
        )
        suggestions.append("把对方原话、链接或截图发来会更容易判断。")

    if not actions:
        actions = _default_safe_actions()

    return {
        "reply": reply,
        "suggestions": suggestions[:4],
        "safe_actions": actions[:6],
    }


def _normalize_analysis_result(
    data: dict[str, Any],
    kb_score_hint: float,
    safe_actions: list[str],
) -> dict[str, Any]:
    reasons = _normalize_str_list(data.get("reason"))[:4]
    next_actions = _normalize_str_list(data.get("next_actions"))[:5]
    if not next_actions:
        next_actions = safe_actions[:5] or _default_safe_actions()[:5]

    reply = str(data.get("reply") or "").strip()
    if not reply:
        reply = _build_reply(reasons, next_actions)

    return {
        "llm_score": _clamp_float(data.get("llm_score", 0.5), 0.0, 1.0),
        "kb_score": _clamp_float(data.get("kb_score", kb_score_hint), 0.0, 0.5),
        "reason": reasons,
        "reply": reply,
        "next_actions": next_actions,
    }


def _normalize_chat_result(
    data: dict[str, Any],
    faq_hits: list[dict[str, Any]],
    safe_actions: list[str],
) -> dict[str, Any]:
    reply = str(data.get("reply") or "").strip()
    suggestions = _normalize_str_list(data.get("suggestions"))[:4]
    actions = _normalize_str_list(data.get("safe_actions"))[:6] or safe_actions[:6]

    if not reply:
        fallback = _fallback_chat_reply("", faq_hits, safe_actions=actions)
        reply = fallback["reply"]
        suggestions = suggestions or fallback["suggestions"]
        actions = actions or fallback["safe_actions"]

    return {
        "reply": reply,
        "suggestions": suggestions,
        "safe_actions": actions,
    }


def _loads_json_object(content: str | None) -> dict[str, Any]:
    if not content:
        return {}
    data = json.loads(content)
    return data if isinstance(data, dict) else {}


def _normalize_str_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def _iter_text_chunks(text: Any, chunk_size: int = 18) -> Iterator[str]:
    content = str(text or "").strip()
    if not content:
        return

    buffer = ""
    for char in content:
        buffer += char
        if char in "，。！？；\n" or len(buffer) >= chunk_size:
            yield buffer
            buffer = ""

    if buffer:
        yield buffer


def _build_reply(reasons: list[str], actions: list[str]) -> str:
    if reasons:
        return f"这段内容存在风险：{reasons[0]}。建议先暂停操作，并通过官方渠道核实。"
    if actions:
        return f"建议先暂停操作，{actions[0]}。"
    return "建议先暂停操作，不要转账、不要提供验证码，并通过官方渠道核实。"


def _default_safe_actions() -> list[str]:
    return [
        "不要转账或支付任何费用",
        "不要提供验证码、银行卡号或身份证照片",
        "不要共享屏幕或让对方远程操作手机",
        "通过官方 App、官网或客服电话核实",
        "保留聊天记录、短信和转账信息",
    ]


def _clamp_float(value: Any, lower: float, upper: float) -> float:
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        numeric = lower
    return max(lower, min(upper, numeric))
