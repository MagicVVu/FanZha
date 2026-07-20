from typing import Literal

from pydantic import BaseModel, Field


class KBHit(BaseModel):
    doc_id: str = ""
    title: str = ""
    question: str = ""
    answer: str = ""
    fraud_type: str = ""
    subtype: str = ""
    risk_level: str = "unknown"
    warning: str = ""
    safe_actions: list[str] = Field(default_factory=list)
    source: str = ""
    keywords: list[str] = Field(default_factory=list)
    score: float = 0.0
    content: str = ""
    retrieval_mode: str = ""
    dense_score: float = 0.0
    lexical_score: float = 0.0
    weighted_score: float = 0.0
    rrf_score: float = 0.0
    hybrid_score: float = 0.0
    first_stage_score: float = 0.0
    second_stage_score: float = 0.0
    retrieval_pipeline: str = ""
    candidate_sources: list[str] = Field(default_factory=list)
    signal_terms: list[str] = Field(default_factory=list)
    signal_overlap_terms: list[str] = Field(default_factory=list)
    signal_overlap_count: int = 0
    signal_overlap_weight: float = 0.0
    rank_score: float = 0.0


class ChatAttachmentMeta(BaseModel):
    attachment_id: str = ""
    modality: Literal["text", "image", "audio", "website", "video", "file"] = "text"
    file_name: str = ""
    content_type: str = ""
    extracted_text: str = ""
    extract_quality: float = 0.0
    fraud_probability: float = 0.0
    result_confidence: float = 0.0
    risk_level: Literal["low", "medium", "high"] = "low"
    reason: list[str] = Field(default_factory=list)


class ChatRequest(BaseModel):
    session_id: str = Field(default="", description="聊天会话ID；前端首次可留空，服务端会返回新ID")
    message: str = Field(default="", description="用户发送给聊天助手的文本")


class ChatResponse(BaseModel):
    session_id: str = ""
    reply: str
    fraud_probability: float = 0.0
    result_confidence: float = 0.0
    risk_level: Literal["low", "medium", "high"] = "low"
    reason: list[str] = Field(default_factory=list)
    safe_actions: list[str] = Field(default_factory=list)
    kb_hits: list[KBHit] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    evidence_count: int = 0
    latest_evidence: list[ChatAttachmentMeta] = Field(default_factory=list)


class AnalyzeResponse(BaseModel):
    modality: Literal["text", "image", "audio", "website", "video", "file"]
    fraud_probability: float
    result_confidence: float
    risk_level: Literal["low", "medium", "high"]
    reason: list[str] = Field(default_factory=list)
    extracted_text: str = ""
    kb_hits: list[KBHit] = Field(default_factory=list)
    safe_actions: list[str] = Field(default_factory=list)
    reply: str = ""
    next_actions: list[str] = Field(default_factory=list)


class SmsCheckRequest(BaseModel):
    sender: str = Field(default="", description="短信发送方号码或签名")
    message: str = Field(default="", description="短信正文内容")


class SmsCheckResponse(BaseModel):
    sender: str = ""
    normalized_message: str = ""
    is_fraud: bool = False
    fraud_probability: float = 0.0
    result_confidence: float = 0.0
    risk_level: Literal["low", "medium", "high"] = "low"
    reason: list[str] = Field(default_factory=list)
    safe_actions: list[str] = Field(default_factory=list)


class ReportRiskBehavior(BaseModel):
    title: str = ""
    description: str = ""
    level: str = "medium"
    frequency: int = 0


class ReportInterceptOverview(BaseModel):
    phone_week_intercept_count: int = 0
    sms_week_intercept_count: int = 0
    app_week_intercept_count: int = 0
    clipboard_week_intercept_count: int = 0


class ReportUserProfile(BaseModel):
    user_id: str = ""
    name: str = ""
    age: int = 0
    gender: str = ""
    occupation: str = ""


class ReportAdviceRequest(BaseModel):
    report_type: Literal["weekly", "monthly"] = "weekly"
    risk_behaviors: list[ReportRiskBehavior] = Field(default_factory=list)
    intercept_overview: ReportInterceptOverview = Field(default_factory=ReportInterceptOverview)
    user_profile: ReportUserProfile | None = None


class ReportAdviceItem(BaseModel):
    id: int
    title: str = ""
    content: str = ""
    priority: Literal["high", "medium", "low"] = "medium"


class ReportAdviceResponse(BaseModel):
    report_type: Literal["weekly", "monthly"] = "weekly"
    summary: str = ""
    risk_level: Literal["low", "medium", "high"] = "low"
    reason: list[str] = Field(default_factory=list)
    suggestions: list[ReportAdviceItem] = Field(default_factory=list)


class ReviewCandidateEvidence(BaseModel):
    text_excerpt: str = ""
    risk_level: Literal["low", "medium", "high"] = "low"
    fraud_probability: float = 0.0
    result_confidence: float = 0.0
    reason: list[str] = Field(default_factory=list)
    rule_hits: list[str] = Field(default_factory=list)
    kb_hits: list[KBHit] = Field(default_factory=list)


class ReviewCandidateProposal(BaseModel):
    fraud_type: str = ""
    subtype: str = ""
    title: str = ""
    question: str = ""
    answer: str = ""
    warning: str = ""
    safe_actions: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)


class ReviewCandidateRecord(BaseModel):
    reviewer: str = ""
    reviewed_at: str = ""
    decision: str = ""
    comment: str = ""


class ReviewCandidateItem(BaseModel):
    candidate_id: str = ""
    status: str = "pending"
    created_at: str = ""
    last_seen_at: str = ""
    applied_at: str = ""
    occurrence_count: int = 0
    source_channel: str = ""
    source_modality: str = ""
    fingerprint: str = ""
    evidence: ReviewCandidateEvidence = Field(default_factory=ReviewCandidateEvidence)
    proposal: ReviewCandidateProposal = Field(default_factory=ReviewCandidateProposal)
    review: ReviewCandidateRecord = Field(default_factory=ReviewCandidateRecord)


class ReviewCandidateListResponse(BaseModel):
    items: list[ReviewCandidateItem] = Field(default_factory=list)
    counts: dict[str, int] = Field(default_factory=dict)
    total: int = 0
    filtered_total: int = 0
    updated_at: str = ""


class ReviewCandidateApproveRequest(BaseModel):
    reviewer: str = Field(..., description="审核人标识")
    fraud_type: str = Field(..., description="审核通过后的诈骗大类")
    subtype: str = Field(..., description="审核通过后的诈骗子类")
    title: str = ""
    question: str = ""
    answer: str = ""
    warning: str = ""
    safe_actions: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)
    comment: str = ""


class ReviewCandidateRejectRequest(BaseModel):
    reviewer: str = Field(..., description="审核人标识")
    comment: str = ""


class ReviewCandidateApplyRequest(BaseModel):
    rebuild: bool = False
    tag: str = ""


class ReviewCandidateApplyResponse(BaseModel):
    applied_count: int = 0
    skipped_count: int = 0
    applied_candidate_ids: list[str] = Field(default_factory=list)
    rebuild_result: dict[str, str | int | list[str] | None] | None = None
