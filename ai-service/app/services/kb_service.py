import hashlib
import json
import os
import re
from collections import Counter
from functools import lru_cache
from pathlib import Path
from typing import Any

from FlagEmbedding import FlagModel
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_qdrant import QdrantVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter
from qdrant_client import QdrantClient

KB_COLLECTION_NAME = os.getenv("KB_COLLECTION_NAME", "fraud_knowledge")
KB_QDRANT_PATH = Path(os.getenv("KB_QDRANT_PATH", "app/data/qdrant_store"))
KB_SEED_PATH = Path(os.getenv("KB_SEED_PATH", "app/data/faq_seed.json"))
KB_MANIFEST_PATH = Path(os.getenv("KB_MANIFEST_PATH", "app/data/kb_manifest.json"))
KB_EMBED_MODEL = os.getenv("KB_EMBED_MODEL", "BAAI/bge-small-zh-v1.5")
KB_QUERY_INSTRUCTION = os.getenv(
    "KB_QUERY_INSTRUCTION",
    "为这个句子生成用于检索相似反诈知识的向量",
)
KB_TOP_K = int(os.getenv("KB_TOP_K", "3"))
KB_MIN_SCORE = float(os.getenv("KB_MIN_SCORE", "0.18"))
KB_NO_SIGNAL_MIN_SCORE = float(os.getenv("KB_NO_SIGNAL_MIN_SCORE", "0.75"))
KB_FIRST_STAGE_MULTIPLIER = int(os.getenv("KB_FIRST_STAGE_MULTIPLIER", "4"))
KB_FIRST_STAGE_MIN_CANDIDATES = int(os.getenv("KB_FIRST_STAGE_MIN_CANDIDATES", "10"))
KB_HYBRID_DENSE_WEIGHT = float(os.getenv("KB_HYBRID_DENSE_WEIGHT", "0.55"))
KB_HYBRID_LEXICAL_WEIGHT = float(os.getenv("KB_HYBRID_LEXICAL_WEIGHT", "0.45"))
KB_HYBRID_RRF_K = int(os.getenv("KB_HYBRID_RRF_K", "60"))
KB_HYBRID_RANK_BONUS_SCALE = float(os.getenv("KB_HYBRID_RANK_BONUS_SCALE", "10.0"))
KB_SECOND_STAGE_SIGNAL_CAP = float(os.getenv("KB_SECOND_STAGE_SIGNAL_CAP", "0.28"))
KB_SECOND_STAGE_NO_OVERLAP_PENALTY = float(os.getenv("KB_SECOND_STAGE_NO_OVERLAP_PENALTY", "0.18"))
KB_SECOND_STAGE_LEXICAL_BONUS = float(os.getenv("KB_SECOND_STAGE_LEXICAL_BONUS", "0.03"))
KB_RETRIEVAL_PIPELINE = os.getenv(
    "KB_RETRIEVAL_PIPELINE",
    "hybrid_recall_v1__heuristic_rerank_v1",
)
USE_QDRANT_KB = os.getenv("USE_QDRANT_KB", "true").lower() == "true"
RAG_SIGNAL_WEIGHTS = {
    "自动扣费": 0.1,
    "自动续费": 0.1,
    "取消会员": 0.12,
    "会员": 0.08,
    "百万保障": 0.14,
    "身份核验": 0.12,
    "确认身份": 0.12,
    "身份信息": 0.1,
    "身份资料": 0.1,
    "实名信息": 0.12,
    "个人资料": 0.12,
    "联系方式": 0.08,
    "补充资料": 0.08,
    "实名资料": 0.1,
    "重新核对": 0.1,
    "核对账户": 0.12,
    "收款信息": 0.12,
    "退款通道": 0.1,
    "退款失败": 0.08,
    "订单异常": 0.12,
    "风控拦截": 0.12,
    "确认收货信息": 0.1,
    "转账": 0.12,
    "安全账户": 0.14,
    "指定账户": 0.12,
    "账户异常": 0.12,
    "资金核验": 0.12,
    "验证码": 0.06,
    "共享屏幕": 0.1,
    "远程控制": 0.1,
    "远程配合": 0.08,
    "下载会议软件": 0.12,
    "下载软件": 0.06,
    "会议软件": 0.05,
    "私人微信": 0.12,
    "企业微信": 0.1,
    "加微信": 0.12,
    "私下处理": 0.1,
    "单独退款": 0.12,
    "离开官方平台": 0.14,
    "脱离平台": 0.12,
    "线下处理": 0.12,
    "中奖": 0.12,
    "中了": 0.12,
    "彩票": 0.12,
    "大奖": 0.12,
    "百万大奖": 0.14,
    "领奖": 0.12,
    "兑奖": 0.12,
    "中奖资格": 0.12,
    "礼品卡": 0.12,
    "活动奖励": 0.1,
    "激活费": 0.16,
    "手续费": 0.14,
    "税费": 0.14,
    "领奖费": 0.16,
    "兑奖费": 0.16,
    "先交钱": 0.16,
    "赔付款": 0.12,
    "资料认证": 0.1,
    "退款链接": 0.12,
    "二维码": 0.08,
    "链接": 0.04,
    "小程序": 0.08,
    "理赔": 0.1,
    "赔付": 0.1,
    "补偿": 0.08,
    "登记资料": 0.12,
    "银行卡": 0.08,
    "身份证": 0.08,
    "垫付": 0.12,
    "刷单": 0.14,
    "返佣": 0.12,
    "返利": 0.12,
    "连刷": 0.12,
    "佣金": 0.1,
    "投资群": 0.14,
    "内幕消息": 0.14,
    "稳赚不赔": 0.14,
    "保证收益": 0.12,
    "内部渠道": 0.12,
    "征信": 0.14,
    "刷流水": 0.14,
    "信用修复": 0.14,
    "贷款资质": 0.1,
    "解冻金": 0.14,
    "认证金": 0.14,
    "保证金": 0.12,
    "银行工作人员": 0.12,
    "洗钱": 0.14,
    "涉案": 0.14,
    "冻结": 0.1,
    "通缉令": 0.14,
    "神药": 0.16,
    "保健品": 0.14,
    "保健品骗局": 0.18,
    "药品骗局": 0.16,
    "养生讲座": 0.14,
    "包治百病": 0.18,
    "有病治病": 0.16,
    "没病强身": 0.16,
    "免费注射": 0.18,
    "免费领药": 0.16,
    "会销": 0.12,
    "线上会议": 0.10,
    "老人": 0.08,
    "奶奶": 0.08,
}
RAG_SIGNAL_KEYWORDS = list(RAG_SIGNAL_WEIGHTS.keys())


class BGEEmbeddings(Embeddings):
    def __init__(self, model_name: str = KB_EMBED_MODEL):
        self.model_name = model_name
        self.model = FlagModel(
            model_name,
            use_fp16=False,
            query_instruction_for_retrieval=KB_QUERY_INSTRUCTION,
        )

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors = self.model.encode(texts)
        return [vector.tolist() for vector in vectors]

    def embed_query(self, text: str) -> list[float]:
        vector = self.model.encode_queries([text])[0]
        return vector.tolist()


@lru_cache(maxsize=1)
def get_embeddings() -> BGEEmbeddings:
    return BGEEmbeddings()


@lru_cache(maxsize=1)
def get_qdrant_client() -> QdrantClient:
    KB_QDRANT_PATH.mkdir(parents=True, exist_ok=True)
    return QdrantClient(path=str(KB_QDRANT_PATH))


@lru_cache(maxsize=1)
def get_vector_store() -> QdrantVectorStore:
    return QdrantVectorStore(
        client=get_qdrant_client(),
        collection_name=KB_COLLECTION_NAME,
        embedding=get_embeddings(),
    )


def clear_kb_caches() -> None:
    get_vector_store.cache_clear()
    get_qdrant_client.cache_clear()
    get_embeddings.cache_clear()


def load_kb_manifest(manifest_path: Path | str = KB_MANIFEST_PATH) -> dict[str, Any]:
    path = Path(manifest_path)
    if not path.exists():
        return {}

    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def get_retrieval_runtime_config() -> dict[str, Any]:
    return {
        "pipeline": KB_RETRIEVAL_PIPELINE,
        "top_k_default": KB_TOP_K,
        "min_score": KB_MIN_SCORE,
        "no_signal_min_score": KB_NO_SIGNAL_MIN_SCORE,
        "first_stage_multiplier": KB_FIRST_STAGE_MULTIPLIER,
        "first_stage_min_candidates": KB_FIRST_STAGE_MIN_CANDIDATES,
        "hybrid_dense_weight": KB_HYBRID_DENSE_WEIGHT,
        "hybrid_lexical_weight": KB_HYBRID_LEXICAL_WEIGHT,
        "hybrid_rrf_k": KB_HYBRID_RRF_K,
        "hybrid_rank_bonus_scale": KB_HYBRID_RANK_BONUS_SCALE,
        "second_stage_signal_cap": KB_SECOND_STAGE_SIGNAL_CAP,
        "second_stage_no_overlap_penalty": KB_SECOND_STAGE_NO_OVERLAP_PENALTY,
        "second_stage_lexical_bonus": KB_SECOND_STAGE_LEXICAL_BONUS,
        "use_qdrant": USE_QDRANT_KB,
    }


def _normalize_seed_payload(data: Any) -> list[dict[str, Any]]:
    if isinstance(data, dict):
        if isinstance(data.get("data"), list):
            data = data["data"]
        else:
            return _flatten_grouped_seed_payload(data)
    return data if isinstance(data, list) else []


def _dedup_preserve_order(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        normalized = str(value).strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result


def _flatten_grouped_seed_payload(data: dict[str, Any]) -> list[dict[str, Any]]:
    fraud_type_groups = data.get("fraud_types") or data.get("groups") or []
    if not isinstance(fraud_type_groups, list):
        return []

    flattened: list[dict[str, Any]] = []
    global_source = data.get("source")

    for group in fraud_type_groups:
        if not isinstance(group, dict):
            continue

        fraud_type = str(group.get("fraud_type") or group.get("type") or "")
        group_source = group.get("source") or global_source
        group_keywords = _normalize_actions(group.get("keywords"))
        subtype_groups = group.get("subtypes") or []
        if not isinstance(subtype_groups, list):
            continue

        for subtype_group in subtype_groups:
            if not isinstance(subtype_group, dict):
                continue

            subtype = str(subtype_group.get("subtype") or "")
            risk_level = str(subtype_group.get("risk_level") or "unknown")
            warning = str(subtype_group.get("warning") or "")
            safe_actions = _normalize_actions(subtype_group.get("safe_actions"))
            subtype_source = subtype_group.get("source") or group_source
            subtype_keywords = _normalize_actions(subtype_group.get("keywords"))
            items = subtype_group.get("items") or []
            if not isinstance(items, list):
                continue

            for index, item in enumerate(items, start=1):
                if not isinstance(item, dict):
                    continue

                normalized = dict(item)
                normalized.setdefault("fraud_type", fraud_type)
                normalized.setdefault("subtype", subtype)
                normalized.setdefault("risk_level", risk_level)
                normalized.setdefault("warning", warning)
                normalized.setdefault("source", subtype_source)
                normalized.setdefault("safe_actions", safe_actions)
                normalized["keywords"] = _dedup_preserve_order(
                    group_keywords
                    + subtype_keywords
                    + _normalize_actions(normalized.get("keywords"))
                )
                normalized.setdefault("id", f"{subtype}_{index:02d}")
                flattened.append(normalized)

    return flattened


def _load_seed_data(seed_path: Path | str = KB_SEED_PATH) -> list[dict[str, Any]]:
    path = Path(seed_path)
    if not path.exists():
        return []

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []

    items: list[dict[str, Any]] = []
    for item in _normalize_seed_payload(data):
        if isinstance(item, dict):
            items.append(item)
    return items


def compute_seed_sha256(seed_items: list[dict[str, Any]]) -> str:
    canonical = json.dumps(seed_items, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def summarize_seed_items(seed_items: list[dict[str, Any]]) -> dict[str, Any]:
    fraud_type_counter: Counter[str] = Counter()
    subtype_counter: Counter[str] = Counter()
    risk_level_counter: Counter[str] = Counter()

    for item in seed_items:
        fraud_type = str(item.get("fraud_type") or "unknown")
        subtype = str(item.get("subtype") or "")
        risk_level = str(item.get("risk_level") or "unknown")
        fraud_type_counter[fraud_type] += 1
        if subtype:
            subtype_counter[subtype] += 1
        risk_level_counter[risk_level] += 1

    return {
        "seed_count": len(seed_items),
        "fraud_type_count": len(fraud_type_counter),
        "subtype_count": len(subtype_counter),
        "risk_level_count": dict(sorted(risk_level_counter.items())),
        "fraud_type_distribution": dict(sorted(fraud_type_counter.items())),
        "subtype_distribution": dict(sorted(subtype_counter.items())),
    }


def _normalize_actions(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str) and value.strip():
        return [value.strip()]
    return []


def _build_document_content(item: dict[str, Any], index: int) -> tuple[str, dict[str, Any]]:
    title = str(item.get("title") or item.get("question") or f"fraud_doc_{index}")
    question = str(item.get("question") or "")
    answer = str(item.get("answer") or "")
    fraud_type = str(item.get("fraud_type") or "未知类型")
    subtype = str(item.get("subtype") or "")
    risk_level = str(item.get("risk_level") or "unknown")
    warning = str(item.get("warning") or "")
    safe_actions = _normalize_actions(item.get("safe_actions"))
    source = str(item.get("source") or "local_seed")
    keywords = [str(word).strip() for word in item.get("keywords", []) if str(word).strip()]

    content = "\n".join(
        [
            f"标题：{title}",
            f"诈骗类型：{fraud_type}",
            f"子类型：{subtype}",
            f"风险等级：{risk_level}",
            f"问题：{question}",
            f"回答：{answer}",
            f"提醒：{warning}",
            f"建议：{'；'.join(safe_actions)}",
            f"关键词：{'、'.join(keywords)}",
        ]
    ).strip()

    metadata = {
        "doc_id": str(item.get("id") or index),
        "title": title,
        "question": question,
        "answer": answer,
        "fraud_type": fraud_type,
        "subtype": subtype,
        "risk_level": risk_level,
        "warning": warning,
        "safe_actions": safe_actions,
        "source": source,
        "keywords": keywords,
    }
    return content, metadata


def _build_documents(seed_items: list[dict[str, Any]]) -> list[Document]:
    docs: list[Document] = []

    for index, item in enumerate(seed_items):
        content, metadata = _build_document_content(item, index)
        docs.append(Document(page_content=content, metadata=metadata))

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=300,
        chunk_overlap=50,
        separators=["\n\n", "\n", "。", "，", " ", ""],
    )
    return splitter.split_documents(docs)


def _normalize_score(raw_score: float) -> float:
    score = float(raw_score)
    if score < 0:
        return max(0.0, min(1.0, (score + 1.0) / 2.0))
    if score <= 1:
        return score
    return 1.0 / (1.0 + score)


def _build_hit(
    payload: dict[str, Any],
    *,
    score: float,
    content: str,
    retrieval_mode: str,
) -> dict[str, Any]:
    safe_actions = _normalize_actions(payload.get("safe_actions"))
    keywords = [str(word).strip() for word in payload.get("keywords", []) if str(word).strip()]
    question = str(payload.get("question") or "")
    answer = str(payload.get("answer") or content or "")

    return {
        "doc_id": str(payload.get("doc_id") or ""),
        "title": str(payload.get("title") or question or ""),
        "question": question,
        "answer": answer,
        "fraud_type": str(payload.get("fraud_type") or "未知类型"),
        "subtype": str(payload.get("subtype") or ""),
        "risk_level": str(payload.get("risk_level") or "unknown"),
        "warning": str(payload.get("warning") or ""),
        "safe_actions": safe_actions,
        "source": str(payload.get("source") or "local_seed"),
        "keywords": keywords,
        "score": round(float(score), 4),
        "content": content,
        "retrieval_mode": retrieval_mode,
    }


def _hit_identity(hit: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        str(hit.get("doc_id") or ""),
        str(hit.get("title") or ""),
        str(hit.get("question") or ""),
        str(hit.get("subtype") or ""),
    )


def _rrf_score(rank: int | None, weight: float) -> float:
    if rank is None:
        return 0.0
    return weight / (KB_HYBRID_RRF_K + rank)


def _dedup_hits(hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[tuple[str, str, str, str]] = set()
    result: list[dict[str, Any]] = []

    for hit in hits:
        identity = _hit_identity(hit)
        if identity in seen:
            continue
        seen.add(identity)
        result.append(hit)

    return result


def _extract_query_terms(query: str) -> list[str]:
    query = (query or "").strip()
    if not query:
        return []

    terms = {query}

    for token in re.findall(r"[A-Za-z0-9_]+", query):
        if len(token) >= 2:
            terms.add(token)

    for span in re.findall(r"[\u4e00-\u9fff]{2,}", query):
        terms.add(span)
        if len(span) >= 4:
            for index in range(len(span) - 1):
                terms.add(span[index:index + 2])
        if len(span) >= 6:
            for index in range(len(span) - 2):
                terms.add(span[index:index + 3])

    return sorted(terms, key=len, reverse=True)


def _extract_signal_terms(query: str) -> list[str]:
    query = (query or "").strip()
    if not query:
        return []
    return [keyword for keyword in RAG_SIGNAL_KEYWORDS if keyword in query]


def _build_hit_haystack(hit: dict[str, Any]) -> str:
    return "\n".join(
        [
            str(hit.get("title") or ""),
            str(hit.get("question") or ""),
            str(hit.get("fraud_type") or ""),
            str(hit.get("subtype") or ""),
            " ".join(str(word).strip() for word in hit.get("keywords", []) if str(word).strip()),
        ]
    )


def _hybrid_search(query: str, dense_hits: list[dict[str, Any]], lexical_hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    fused: dict[tuple[str, str, str, str], dict[str, Any]] = {}

    for rank, hit in enumerate(dense_hits, start=1):
        key = _hit_identity(hit)
        candidate = dict(hit)
        candidate["dense_score"] = float(hit.get("score", 0.0))
        candidate["dense_rank"] = rank
        candidate["lexical_score"] = 0.0
        candidate["lexical_rank"] = None
        candidate["candidate_sources"] = ["dense"]
        fused[key] = candidate

    for rank, hit in enumerate(lexical_hits, start=1):
        key = _hit_identity(hit)
        if key in fused:
            candidate = fused[key]
            candidate["lexical_score"] = float(hit.get("score", 0.0))
            candidate["lexical_rank"] = rank
            if "lexical" not in candidate["candidate_sources"]:
                candidate["candidate_sources"].append("lexical")
        else:
            candidate = dict(hit)
            candidate["dense_score"] = 0.0
            candidate["dense_rank"] = None
            candidate["lexical_score"] = float(hit.get("score", 0.0))
            candidate["lexical_rank"] = rank
            candidate["candidate_sources"] = ["lexical"]
            fused[key] = candidate

    results: list[dict[str, Any]] = []
    for candidate in fused.values():
        dense_score = float(candidate.get("dense_score", 0.0))
        lexical_score = float(candidate.get("lexical_score", 0.0))
        dense_rank = candidate.get("dense_rank")
        lexical_rank = candidate.get("lexical_rank")
        rrf_score = _rrf_score(dense_rank, KB_HYBRID_DENSE_WEIGHT) + _rrf_score(
            lexical_rank, KB_HYBRID_LEXICAL_WEIGHT
        )
        weighted_score = (KB_HYBRID_DENSE_WEIGHT * dense_score) + (
            KB_HYBRID_LEXICAL_WEIGHT * lexical_score
        )
        hybrid_score = weighted_score + (rrf_score * KB_HYBRID_RANK_BONUS_SCALE)

        candidate["weighted_score"] = round(weighted_score, 4)
        candidate["rrf_score"] = round(rrf_score, 6)
        candidate["hybrid_score"] = round(min(hybrid_score, 1.0), 4)
        candidate["retrieval_mode"] = (
            "hybrid" if len(candidate.get("candidate_sources", [])) > 1 else candidate["candidate_sources"][0]
        )
        candidate["retrieval_pipeline"] = KB_RETRIEVAL_PIPELINE
        results.append(candidate)

    results.sort(
        key=lambda item: (
            float(item.get("hybrid_score", 0.0)),
            float(item.get("dense_score", 0.0)),
            float(item.get("lexical_score", 0.0)),
        ),
        reverse=True,
    )
    return results


def _second_stage_rerank(query: str, hits: list[dict[str, Any]], top_k: int) -> list[dict[str, Any]]:
    signal_terms = _extract_signal_terms(query)
    reranked: list[dict[str, Any]] = []

    for hit in hits:
        haystack = _build_hit_haystack(hit)
        overlap_terms = [term for term in signal_terms if term in haystack]
        overlap_count = len(overlap_terms)
        overlap_weight = sum(RAG_SIGNAL_WEIGHTS.get(term, 0.05) for term in overlap_terms)
        lexical_bonus = (
            KB_SECOND_STAGE_LEXICAL_BONUS
            if overlap_terms and hit.get("retrieval_mode") in {"lexical", "hybrid"}
            else 0.0
        )
        no_overlap_penalty = KB_SECOND_STAGE_NO_OVERLAP_PENALTY if signal_terms and overlap_count == 0 else 0.0
        first_stage_score = float(hit.get("hybrid_score", hit.get("score", 0.0)))
        rank_score = (
            first_stage_score
            + min(overlap_weight, KB_SECOND_STAGE_SIGNAL_CAP)
            + lexical_bonus
            - no_overlap_penalty
        )
        final_score = max(0.0, min(rank_score, 1.0))

        reranked_hit = dict(hit)
        reranked_hit.setdefault("dense_score", 0.0)
        reranked_hit.setdefault("lexical_score", float(hit.get("score", 0.0)))
        reranked_hit.setdefault("weighted_score", float(hit.get("score", 0.0)))
        reranked_hit.setdefault("rrf_score", 0.0)
        reranked_hit.setdefault("hybrid_score", float(hit.get("score", 0.0)))
        reranked_hit.setdefault("candidate_sources", [hit.get("retrieval_mode", "lexical")])
        reranked_hit["retrieval_pipeline"] = KB_RETRIEVAL_PIPELINE
        reranked_hit["signal_terms"] = signal_terms
        reranked_hit["signal_overlap_terms"] = overlap_terms
        reranked_hit["signal_overlap_count"] = overlap_count
        reranked_hit["signal_overlap_weight"] = round(overlap_weight, 4)
        reranked_hit["first_stage_score"] = round(first_stage_score, 4)
        reranked_hit["second_stage_score"] = round(final_score, 4)
        reranked_hit["score"] = round(final_score, 4)
        reranked_hit["rank_score"] = round(rank_score, 4)
        reranked.append(reranked_hit)

    reranked.sort(
        key=lambda item: (
            float(item.get("rank_score", item.get("second_stage_score", 0.0))),
            int(item.get("signal_overlap_count", 0)),
            float(item.get("second_stage_score", item.get("score", 0.0))),
            item.get("retrieval_mode") == "lexical",
        ),
        reverse=True,
    )
    return reranked[:top_k]


def _should_return_empty(query: str, hits: list[dict[str, Any]]) -> bool:
    if not hits:
        return True

    signal_terms = _extract_signal_terms(query)
    top_hit = hits[0]
    top_score = float(top_hit.get("score", 0.0))
    top_overlap = int(top_hit.get("signal_overlap_count", 0))

    if not signal_terms:
        return top_score < KB_NO_SIGNAL_MIN_SCORE

    if top_overlap == 0 and top_score < 0.72:
        return True

    return False


def _lexical_fallback_search(query: str, top_k: int = KB_TOP_K) -> list[dict[str, Any]]:
    seed_items = _load_seed_data()
    if not seed_items:
        return []

    query = (query or "").strip()
    if not query:
        return []

    query_terms = _extract_query_terms(query)
    ranked: list[tuple[float, dict[str, Any]]] = []

    for index, item in enumerate(seed_items):
        haystack = json.dumps(item, ensure_ascii=False)
        score = 0.0

        if query in haystack:
            score += 3.0

        for token in query_terms:
            if token in haystack:
                if len(token) >= 6:
                    score += 1.5
                elif len(token) >= 3:
                    score += 1.0
                else:
                    score += 0.4

        if score <= 0:
            continue

        _, metadata = _build_document_content(item, index)
        ranked.append((score, metadata))

    ranked.sort(key=lambda item: item[0], reverse=True)

    hits: list[dict[str, Any]] = []
    for score, metadata in ranked[:top_k]:
        hits.append(
            _build_hit(
                metadata,
                score=min(score / 8.0, 0.99),
                content=str(metadata.get("answer") or ""),
                retrieval_mode="lexical",
            )
        )
    return hits


def _dense_search(query: str, top_k: int) -> list[dict[str, Any]]:
    results = get_vector_store().similarity_search_with_score(query, k=max(top_k * 2, 6))

    hits: list[dict[str, Any]] = []
    for doc, raw_score in results:
        score = _normalize_score(raw_score)
        if score < KB_MIN_SCORE:
            continue

        hits.append(
            _build_hit(
                doc.metadata,
                score=score,
                content=doc.page_content,
                retrieval_mode="dense",
            )
        )
    return hits


def search_faq(query: str, top_k: int = KB_TOP_K) -> list[dict[str, Any]]:
    query = (query or "").strip()
    if not query:
        return []

    candidate_k = max(top_k * KB_FIRST_STAGE_MULTIPLIER, KB_FIRST_STAGE_MIN_CANDIDATES)
    lexical_hits = _lexical_fallback_search(query, top_k=candidate_k)
    if not USE_QDRANT_KB:
        reranked_lexical = _second_stage_rerank(query, lexical_hits, top_k=top_k)
        return [] if _should_return_empty(query, reranked_lexical) else reranked_lexical

    try:
        dense_hits = _dense_search(query, top_k=candidate_k)
    except Exception:
        reranked_lexical = _second_stage_rerank(query, lexical_hits, top_k=top_k)
        return [] if _should_return_empty(query, reranked_lexical) else reranked_lexical

    merged = _dedup_hits(_hybrid_search(query, dense_hits, lexical_hits))
    reranked = _second_stage_rerank(query, merged, top_k=top_k)
    return [] if _should_return_empty(query, reranked) else reranked


def estimate_kb_score(hits: list[dict[str, Any]]) -> float:
    if not hits:
        return 0.0

    top_score = max(
        float(hit.get("second_stage_score", hit.get("score", 0.0)))
        for hit in hits
    )
    if top_score >= 0.75:
        return 0.45
    if top_score >= 0.55:
        return 0.32
    if top_score >= 0.35:
        return 0.18
    return 0.08


def format_kb_context(hits: list[dict[str, Any]]) -> str:
    if not hits:
        return ""

    blocks: list[str] = []
    for index, hit in enumerate(hits, start=1):
        actions = "；".join(_normalize_actions(hit.get("safe_actions")))
        keywords = "、".join(str(word).strip() for word in hit.get("keywords", []) if str(word).strip())
        blocks.append(
            "\n".join(
                [
                    f"[知识片段{index}]",
                    f"文档ID：{hit.get('doc_id', '')}",
                    f"标题：{hit.get('title', '')}",
                    f"诈骗类型：{hit.get('fraud_type', '')}",
                    f"子类型：{hit.get('subtype', '')}",
                    f"风险等级：{hit.get('risk_level', '')}",
                    f"相似度：{hit.get('score', 0.0)}",
                    f"检索方式：{hit.get('retrieval_mode', '')}",
                    f"一阶段融合分：{hit.get('hybrid_score', hit.get('score', 0.0))}",
                    f"二阶段重排分：{hit.get('second_stage_score', hit.get('score', 0.0))}",
                    f"检索流水线：{hit.get('retrieval_pipeline', KB_RETRIEVAL_PIPELINE)}",
                    f"问题：{hit.get('question', '')}",
                    f"回答：{hit.get('answer', '')}",
                    f"提醒：{hit.get('warning', '')}",
                    f"建议：{actions}",
                    f"关键词：{keywords}",
                    f"来源：{hit.get('source', '')}",
                ]
            )
        )
    return "\n\n".join(blocks)
