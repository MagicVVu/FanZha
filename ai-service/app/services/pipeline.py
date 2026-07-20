import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from dataclasses import dataclass, field
from threading import Lock
from typing import Iterator, Optional
from xml.etree import ElementTree as ET

from fastapi import UploadFile

from app.services.asr_service import transcribe_audio, transcribe_audio_fast
from app.services.deepseek_client import general_chat_reply, general_chat_reply_stream
from app.services.kb_service import search_faq
from app.services.novel_case_service import enqueue_candidate
from app.services.ocr_service import extract_text_from_image, extract_text_from_image_fast
from app.services.risk_engine import (
    combine_confidence,
    combine_probability,
    high_risk_override,
    low_risk_override,
    medium_risk_override,
    risk_level,
    rule_hits_to_display,
    rule_score,
)
from app.services.video_service import VideoProcessingError, extract_text_from_video
from app.services.web_service import extract_text_from_website

logger = logging.getLogger("anti_fraud_ai.assistant")

UPLOAD_DIR = "uploads"
os.makedirs(UPLOAD_DIR, exist_ok=True)

MAX_ANALYZE_TEXT_LEN = 4000
MAX_EVIDENCE_TEXT_LEN = 2400
MAX_CONTEXT_CHARS = 6000
MAX_SESSION_EVIDENCE = 12
MAX_SESSION_TURNS = 20
SESSION_TTL_SECONDS = 6 * 60 * 60
CHAT_FAQ_TIMEOUT_SEC = float(os.getenv("CHAT_FAQ_TIMEOUT_SEC", "0.2"))
ANALYZE_FAQ_TIMEOUT_SEC = 1.2
CHAT_AUDIO_WORKER_WAIT_SEC = float(os.getenv("CHAT_AUDIO_WORKER_WAIT_SEC", "2.5"))
CHAT_AUDIO_JOB_TTL_SECONDS = int(os.getenv("CHAT_AUDIO_JOB_TTL_SECONDS", "900"))
CHAT_AUDIO_SUBPROCESS_ENABLED = os.getenv("CHAT_AUDIO_SUBPROCESS_ENABLED", "1") == "1"
CHAT_AUDIO_DEFER_NOTE = "已收到音频。当前聊天已优先基于图片、文字和历史证据完成判断；音频会在后台继续转写，如需立即深度分析建议到检测页单独分析音频。"

_FAQ_EXECUTOR = ThreadPoolExecutor(max_workers=2)
AUDIO_JOB_DIR = os.path.join(UPLOAD_DIR, "audio_jobs")
os.makedirs(AUDIO_JOB_DIR, exist_ok=True)


@dataclass
class EvidenceItem:
    evidence_id: str
    modality: str
    file_name: str = ""
    content_type: str = ""
    original_text: str = ""
    extracted_text: str = ""
    extract_quality: float = 1.0
    fraud_probability: float = 0.0
    result_confidence: float = 0.0
    risk_level: str = "low"
    reason: list[str] = field(default_factory=list)
    safe_actions: list[str] = field(default_factory=list)
    kb_hits: list[dict] = field(default_factory=list)
    rule_hits: list[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)


@dataclass
class PendingAudioJob:
    job_id: str
    file_name: str
    content_type: str
    audio_path: str
    result_path: str
    created_at: float = field(default_factory=time.time)


@dataclass
class ChatTurn:
    role: str
    text: str
    created_at: float = field(default_factory=time.time)


@dataclass
class ChatSessionState:
    session_id: str
    turns: list[ChatTurn] = field(default_factory=list)
    evidences: list[EvidenceItem] = field(default_factory=list)
    pending_audio_jobs: list[PendingAudioJob] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


_SESSION_LOCK = Lock()
_CHAT_SESSIONS: dict[str, ChatSessionState] = {}


def _dedup_text_list(items: list[str], limit: int) -> list[str]:
    result: list[str] = []
    seen = set()
    for item in items:
        normalized = str(item).strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        result.append(normalized)
    return result[:limit]


def _clip_text(text: str, limit: int) -> str:
    return str(text or "").strip()[:limit]


def _collect_safe_actions(faq_hits: list[dict]) -> list[str]:
    actions: list[str] = []
    seen = set()
    for hit in faq_hits:
        for action in hit.get("safe_actions", []):
            normalized = str(action).strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            actions.append(normalized)
    return actions[:6]


def _build_default_chat_response(session_id: str = "") -> dict:
    return {
        "session_id": session_id,
        "reply": "请先输入你想咨询的内容，或上传聊天截图、录音、文件，我会结合历史证据继续判断风险。",
        "fraud_probability": 0.0,
        "result_confidence": 0.0,
        "risk_level": "low",
        "reason": [],
        "safe_actions": [],
        "kb_hits": [],
        "suggestions": ["可以继续补充截图、录音、文件或对方原话，我会累计分析。"],
        "evidence_count": 0,
        "latest_evidence": [],
    }


def _sse_event(payload: dict, event: str) -> str:
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _cleanup_expired_sessions() -> None:
    now = time.time()
    expired_ids = []
    for session_id, session in _CHAT_SESSIONS.items():
        if now - session.updated_at > SESSION_TTL_SECONDS:
            expired_ids.append(session_id)
    for session_id in expired_ids:
        session = _CHAT_SESSIONS.pop(session_id, None)
        if session is None:
            continue
        for job in session.pending_audio_jobs:
            _cleanup_audio_job_files(job)


def _get_or_create_session(session_id: str | None) -> ChatSessionState:
    normalized = (session_id or "").strip() or uuid.uuid4().hex
    with _SESSION_LOCK:
        _cleanup_expired_sessions()
        session = _CHAT_SESSIONS.get(normalized)
        if session is None:
            session = ChatSessionState(session_id=normalized)
            _CHAT_SESSIONS[normalized] = session
        session.updated_at = time.time()
        return session


def _append_turn(session: ChatSessionState, role: str, text: str) -> None:
    clean_text = str(text or "").strip()
    if not clean_text:
        return
    with _SESSION_LOCK:
        session.turns.append(ChatTurn(role=role, text=clean_text))
        if len(session.turns) > MAX_SESSION_TURNS:
            session.turns = session.turns[-MAX_SESSION_TURNS:]
        session.updated_at = time.time()


def _append_evidence(session: ChatSessionState, evidence: EvidenceItem) -> None:
    with _SESSION_LOCK:
        session.evidences.append(evidence)
        if len(session.evidences) > MAX_SESSION_EVIDENCE:
            session.evidences = session.evidences[-MAX_SESSION_EVIDENCE:]
        session.updated_at = time.time()



def _cleanup_audio_job_files(job: PendingAudioJob) -> None:
    for path in {job.audio_path, job.result_path}:
        if path and os.path.exists(path):
            try:
                if os.path.isdir(path):
                    shutil.rmtree(path, ignore_errors=True)
                else:
                    os.remove(path)
            except Exception:
                pass


def _append_pending_audio_job(session: ChatSessionState, job: PendingAudioJob) -> None:
    with _SESSION_LOCK:
        session.pending_audio_jobs.append(job)
        session.updated_at = time.time()


def _remove_pending_audio_job(session: ChatSessionState, job_id: str) -> None:
    with _SESSION_LOCK:
        remaining: list[PendingAudioJob] = []
        removed: list[PendingAudioJob] = []
        for job in session.pending_audio_jobs:
            if job.job_id == job_id:
                removed.append(job)
            else:
                remaining.append(job)
        session.pending_audio_jobs = remaining
        session.updated_at = time.time()
    for job in removed:
        _cleanup_audio_job_files(job)


def _build_deferred_audio_evidence(file: UploadFile | None, note: str | None = None) -> EvidenceItem:
    deferred_reason = note or CHAT_AUDIO_DEFER_NOTE
    return EvidenceItem(
        evidence_id=uuid.uuid4().hex,
        modality="audio",
        file_name=file.filename if file else "audio",
        content_type=(file.content_type if file else ""),
        original_text="",
        extracted_text="",
        extract_quality=0.0,
        fraud_probability=0.0,
        result_confidence=0.2,
        risk_level="low",
        reason=[deferred_reason],
        safe_actions=[],
        kb_hits=[],
        rule_hits=[],
    )


def _create_audio_evidence_from_text(
    file_name: str,
    content_type: str,
    clean_text: str,
    extract_quality: float,
    notes: list[str] | None = None,
) -> EvidenceItem:
    score = _score_text_block("audio", clean_text, extract_quality, faq_timeout=CHAT_FAQ_TIMEOUT_SEC)
    return EvidenceItem(
        evidence_id=uuid.uuid4().hex,
        modality="audio",
        file_name=file_name,
        content_type=content_type,
        original_text="",
        extracted_text=clean_text,
        extract_quality=extract_quality,
        fraud_probability=score["fraud_probability"],
        result_confidence=score["result_confidence"],
        risk_level=score["risk_level"],
        reason=_dedup_text_list(list(notes or []) + score["reason"], limit=6),
        safe_actions=score["safe_actions"],
        kb_hits=score["kb_hits"],
        rule_hits=score["rule_hits"],
    )


def _launch_audio_worker(audio_path: str, result_path: str) -> subprocess.Popen:
    args = [
        sys.executable,
        "-m",
        "app.services.audio_worker",
        audio_path,
        result_path,
        "--fast",
    ]
    return subprocess.Popen(
        args,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        cwd=os.getcwd(),
    )


def _read_audio_worker_result(result_path: str) -> dict:
    if not os.path.exists(result_path):
        return {"ok": False, "error": "worker result missing"}
    try:
        with open(result_path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as exc:
        return {"ok": False, "error": f"read worker result failed: {type(exc).__name__}: {exc}"}


def _harvest_completed_audio_jobs(session: ChatSessionState) -> list[EvidenceItem]:
    harvested: list[EvidenceItem] = []
    now = time.time()
    pending_jobs = list(session.pending_audio_jobs)

    for job in pending_jobs:
        if now - job.created_at > CHAT_AUDIO_JOB_TTL_SECONDS:
            _remove_pending_audio_job(session, job.job_id)
            continue

        if not os.path.exists(job.result_path):
            continue

        result = _read_audio_worker_result(job.result_path)
        _remove_pending_audio_job(session, job.job_id)

        if not result.get("ok"):
            continue

        clean_text = _clip_text(result.get("text") or "", MAX_EVIDENCE_TEXT_LEN)
        if not clean_text:
            continue

        evidence = _create_audio_evidence_from_text(
            file_name=job.file_name,
            content_type=job.content_type,
            clean_text=clean_text,
            extract_quality=float(result.get("quality") or 0.55),
            notes=["已纳入上一轮音频后台转写结果。"],
        )
        _append_evidence(session, evidence)
        harvested.append(evidence)

    return harvested


def _start_audio_worker_job(
    session: ChatSessionState,
    file: UploadFile,
    audio_path: str,
    wait_seconds: float = CHAT_AUDIO_WORKER_WAIT_SEC,
) -> tuple[EvidenceItem, list[str]]:
    job_id = uuid.uuid4().hex
    result_path = os.path.join(AUDIO_JOB_DIR, f"{job_id}.json")
    proc = _launch_audio_worker(audio_path, result_path)

    try:
        proc.wait(timeout=wait_seconds)
    except subprocess.TimeoutExpired:
        print(f"[chat_audio] subprocess_timeout file_name={file.filename} wait={wait_seconds}s", flush=True)
        job = PendingAudioJob(
            job_id=job_id,
            file_name=file.filename or "audio",
            content_type=file.content_type or "",
            audio_path=audio_path,
            result_path=result_path,
        )
        _append_pending_audio_job(session, job)
        deferred = _build_deferred_audio_evidence(file)
        return deferred, deferred.reason

    result = _read_audio_worker_result(result_path)

    try:
        if os.path.exists(audio_path):
            os.remove(audio_path)
    except Exception:
        pass
    try:
        if os.path.exists(result_path):
            os.remove(result_path)
    except Exception:
        pass

    if not result.get("ok"):
        note = "已收到音频，但本轮未完成安全转写。建议先依据图片、文字与历史证据判断；如需更准确分析，可到检测页单独分析音频。"
        print(f"[chat_audio] worker_failed file_name={file.filename} error={result.get('error')}", flush=True)
        deferred = _build_deferred_audio_evidence(file, note=note)
        return deferred, deferred.reason

    clean_text = _clip_text(result.get("text") or "", MAX_EVIDENCE_TEXT_LEN)
    if not clean_text:
        note = "已收到音频，但本轮未提取到足够有效的语音文本。"
        deferred = _build_deferred_audio_evidence(file, note=note)
        return deferred, deferred.reason

    evidence = _create_audio_evidence_from_text(
        file_name=file.filename or "audio",
        content_type=file.content_type or "",
        clean_text=clean_text,
        extract_quality=float(result.get("quality") or 0.55),
    )
    return evidence, []


def _guess_upload_ext(file: UploadFile, modality: str) -> str:
    original_name = (file.filename or "").lower()
    ext = os.path.splitext(original_name)[1].lower()
    content_type = (file.content_type or "").lower()

    if modality == "image":
        if ext in {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".pdf"}:
            return ext
        if "png" in content_type:
            return ".png"
        if "bmp" in content_type:
            return ".bmp"
        if "webp" in content_type:
            return ".webp"
        if "pdf" in content_type:
            return ".pdf"
        return ".jpg"

    if modality == "audio":
        if ext:
            return ext
        if "wav" in content_type:
            return ".wav"
        if "mpeg" in content_type or "mp3" in content_type:
            return ".mp3"
        if "mp4" in content_type:
            return ".m4a"
        return ".mp3"

    if modality == "video":
        if ext:
            return ext
        if "quicktime" in content_type:
            return ".mov"
        if "matroska" in content_type:
            return ".mkv"
        return ".mp4"

    if modality == "file":
        return ext or ".bin"

    if modality == "website":
        return ".txt"

    return ext or ".bin"


async def save_upload(file: UploadFile, modality: str) -> str:
    ext = _guess_upload_ext(file, modality)
    path = os.path.join(UPLOAD_DIR, f"{uuid.uuid4().hex}{ext}")

    total_bytes = 0
    with open(path, "wb") as f:
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            total_bytes += len(chunk)
            f.write(chunk)

    print(
        f"[upload] modality={modality} path={path} size_mb={total_bytes / 1024 / 1024:.2f}",
        flush=True,
    )
    return path


def _search_faq_fast(text: str, tag: str = "chat_ctx", timeout: float = CHAT_FAQ_TIMEOUT_SEC) -> list[dict]:
    future = _FAQ_EXECUTOR.submit(search_faq, text)
    try:
        return future.result(timeout=timeout)
    except TimeoutError:
        print(f"[{tag}] search_faq timeout after {timeout:.1f}s", flush=True)
        return []
    except Exception as exc:
        print(f"[{tag}] search_faq error: {exc}", flush=True)
        traceback.print_exc()
        return []


def _empty_result(modality: str, reason: str) -> dict:
    return {
        "modality": modality,
        "fraud_probability": 0.0,
        "result_confidence": 0.0,
        "risk_level": "low",
        "reason": [reason],
        "extracted_text": "",
        "kb_hits": [],
        "safe_actions": [],
        "reply": reason,
        "next_actions": [],
    }


def _low_confidence_result(modality: str, reasons: list[str], extracted_text: str = "") -> dict:
    normalized_reasons = [str(item).strip() for item in reasons if str(item).strip()]
    if not normalized_reasons:
        normalized_reasons = ["未提取到足够有效文本，当前判断可信度较低。"]

    return {
        "modality": modality,
        "fraud_probability": 0.0,
        "result_confidence": 0.12,
        "risk_level": "low",
        "reason": normalized_reasons[:6],
        "extracted_text": extracted_text[:3000],
        "kb_hits": [],
        "safe_actions": [],
        "reply": "未提取到足够有效文本，建议换一份更清晰的内容再试。",
        "next_actions": ["重新上传更清晰的文件", "补充对方的文字话术"],
    }


def _infer_modality_from_upload(
    attachment_modality: str,
    file: UploadFile | None,
) -> str:
    normalized = (attachment_modality or "").strip().lower()
    if normalized and normalized != "auto":
        return normalized

    if file is None:
        return "text"

    content_type = (file.content_type or "").lower()
    filename = (file.filename or "").lower()

    if content_type.startswith("image/") or filename.endswith((".jpg", ".jpeg", ".png", ".bmp", ".webp")):
        return "image"
    if content_type.startswith("audio/") or filename.endswith((".mp3", ".wav", ".m4a", ".aac", ".ogg")):
        return "audio"
    if content_type.startswith("video/") or filename.endswith((".mp4", ".mov", ".mkv", ".avi")):
        return "video"
    return "file"


def _normalize_upload_files(files: list[UploadFile] | None) -> list[UploadFile]:
    return [item for item in (files or []) if item is not None]


def _infer_modalities_from_uploads(
    attachment_modality: str,
    files: list[UploadFile] | None,
) -> list[str]:
    normalized_files = _normalize_upload_files(files)
    if not normalized_files:
        return []

    normalized_modality = (attachment_modality or "").strip().lower()
    if normalized_modality and normalized_modality != "auto":
        return [normalized_modality for _ in normalized_files]

    return [_infer_modality_from_upload("auto", file) for file in normalized_files]


def _chat_attachment_priority(modality: str) -> int:
    mapping = {
        "audio": 0,
        "text": 1,
        "image": 2,
        "video": 3,
        "website": 4,
        "file": 5,
    }
    return mapping.get((modality or "").strip().lower(), 99)


def _iter_chat_uploads(files: list[UploadFile], modalities: list[str]):
    pairs = []
    for index, upload_file in enumerate(files):
        inferred_modality = modalities[index] if index < len(modalities) else _infer_modality_from_upload("auto", upload_file)
        pairs.append((index, upload_file, inferred_modality))
    pairs.sort(key=lambda item: (_chat_attachment_priority(item[2]), item[0]))
    return pairs


def _score_text_block(modality: str, clean_text: str, extract_quality: float, faq_timeout: float = CHAT_FAQ_TIMEOUT_SEC) -> dict:
    faq_hits = _search_faq_fast(clean_text, tag="evidence", timeout=faq_timeout)
    safe_actions = _collect_safe_actions(faq_hits)
    rs, rule_hits = rule_score(clean_text)

    kb_proxy_score = min(0.35, 0.12 * len(faq_hits))
    llm_proxy_score = 0.15
    if rule_hits:
        llm_proxy_score = min(0.72, 0.22 + 0.12 * len(rule_hits))
    elif faq_hits:
        llm_proxy_score = 0.28

    fraud_probability = combine_probability(
        llm_score=llm_proxy_score,
        rule_score_value=rs,
        kb_score=kb_proxy_score,
    )

    high_score, high_reason = high_risk_override(clean_text, rule_hits)
    medium_score, medium_reason = medium_risk_override(clean_text, rule_hits)
    low_score, low_reason = low_risk_override(clean_text, rule_hits)
    override_reason = None

    if high_score is not None:
        fraud_probability = max(fraud_probability, high_score)
        override_reason = high_reason
    if medium_score is not None:
        fraud_probability = max(fraud_probability, medium_score)
        if override_reason is None:
            override_reason = medium_reason
    if low_score is not None:
        fraud_probability = min(fraud_probability, low_score)
        override_reason = low_reason

    reasons = list(rule_hits_to_display(rule_hits))
    if override_reason:
        reasons = [override_reason] + reasons

    for hit in faq_hits[:2]:
        warning = str(hit.get("warning") or "").strip()
        title = str(hit.get("title") or "").strip()
        if warning:
            reasons.append(warning)
        elif title:
            reasons.append(title)

    dedup_reason = _dedup_text_list(reasons, limit=6)
    confidence = combine_confidence(
        extract_quality=max(0.12, extract_quality),
        fraud_probability=fraud_probability,
    )

    reply = ""
    if dedup_reason:
        reply = f"这份{modality}内容里出现了可疑信号：{dedup_reason[0]}。"

    return {
        "fraud_probability": round(fraud_probability, 4),
        "result_confidence": round(confidence, 4),
        "risk_level": risk_level(fraud_probability),
        "reason": dedup_reason,
        "kb_hits": faq_hits[:3],
        "safe_actions": safe_actions,
        "reply": reply,
        "rule_hits": rule_hits,
    }


def _enqueue_candidate_safe(
    *,
    source_channel: str,
    source_modality: str,
    text_excerpt: str,
    fraud_probability: float,
    result_confidence: float,
    risk_level_value: str,
    reason: list[str] | None = None,
    rule_hits: list[str] | None = None,
    kb_hits: list[dict] | None = None,
    safe_actions: list[str] | None = None,
) -> None:
    try:
        queued = enqueue_candidate(
            source_channel=source_channel,
            source_modality=source_modality,
            text_excerpt=text_excerpt,
            fraud_probability=fraud_probability,
            result_confidence=result_confidence,
            risk_level=risk_level_value,
            reason=reason,
            rule_hits=rule_hits,
            kb_hits=kb_hits,
            safe_actions=safe_actions,
        )
        if queued is not None:
            print(
                f"[novel_case] queued candidate_id={queued.get('candidate_id')} "
                f"channel={source_channel} modality={source_modality}",
                flush=True,
            )
    except Exception as exc:
        print(
            f"[novel_case] enqueue_failed channel={source_channel} "
            f"modality={source_modality} error={type(exc).__name__}: {exc}",
            flush=True,
        )


def _read_text_file(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def _read_pdf_text(path: str) -> str:
    try:
        from pypdf import PdfReader  # type: ignore
    except Exception:
        return ""

    try:
        reader = PdfReader(path)
        parts = []
        for page in reader.pages[:20]:
            parts.append(page.extract_text() or "")
        return "\n".join(parts).strip()
    except Exception:
        return ""


def _read_docx_text(path: str) -> str:
    try:
        with zipfile.ZipFile(path) as zf:
            xml_bytes = zf.read("word/document.xml")
    except Exception:
        return ""

    try:
        root = ET.fromstring(xml_bytes)
        texts = []
        for node in root.iter():
            if node.text and node.text.strip():
                texts.append(node.text.strip())
        return "\n".join(texts).strip()
    except Exception:
        return ""


async def extract_text_from_generic_file(path: str, filename: str = "") -> tuple[str, float, list[str]]:
    ext = os.path.splitext(filename or path)[1].lower()
    notes: list[str] = []

    try:
        if ext in {".txt", ".md", ".csv", ".json", ".log", ".xml", ".html", ".htm"}:
            return _read_text_file(path), 0.95, notes

        if ext == ".pdf":
            text = _read_pdf_text(path)
            if text.strip():
                return text, 0.8, notes
            notes.append("PDF 未提取到结构化文本，可能是扫描件。")
            return "", 0.25, notes

        if ext == ".docx":
            text = _read_docx_text(path)
            if text.strip():
                return text, 0.82, notes
            notes.append("DOCX 未提取到有效文本。")
            return "", 0.25, notes

        notes.append("当前文件类型暂不支持深度提取，建议上传文本、截图、录音或可复制内容。")
        return "", 0.15, notes
    except Exception as exc:
        notes.append(f"文件解析失败：{type(exc).__name__}: {exc}")
        return "", 0.15, notes


async def _extract_attachment_evidence(
    modality: str,
    file: UploadFile | None = None,
    url: str = "",
    text: str = "",
    fast_mode: bool = False,
    session: ChatSessionState | None = None,
) -> tuple[Optional[EvidenceItem], list[str]]:
    notes: list[str] = []
    path: str | None = None
    extracted_text = ""
    extract_quality = 1.0
    original_text = text.strip()

    if modality == "text":
        clean_text = original_text
        if not clean_text:
            return None, ["文本为空"]
        score = _score_text_block("text", clean_text, 1.0, faq_timeout=CHAT_FAQ_TIMEOUT_SEC if fast_mode else ANALYZE_FAQ_TIMEOUT_SEC)
        evidence = EvidenceItem(
            evidence_id=uuid.uuid4().hex,
            modality="text",
            original_text=clean_text,
            extracted_text=clean_text[:MAX_EVIDENCE_TEXT_LEN],
            extract_quality=1.0,
            fraud_probability=score["fraud_probability"],
            result_confidence=score["result_confidence"],
            risk_level=score["risk_level"],
            reason=score["reason"],
            safe_actions=score["safe_actions"],
            kb_hits=score["kb_hits"],
            rule_hits=score["rule_hits"],
        )
        return evidence, notes

    if modality == "website":
        if not url.strip():
            return None, ["未提供网址"]
        extracted_text, extract_quality = await extract_text_from_website(url.strip())
        original_text = url.strip()

    elif modality == "image":
        if file is None:
            return None, ["未上传图片"]
        path = await save_upload(file, "image")
        try:
            print(f"[chat_image] ocr_start path={path} fast_mode={fast_mode}", flush=True)
            if fast_mode:
                extracted_text, extract_quality = await extract_text_from_image_fast(path)
                text_len = len((extracted_text or "").strip())
                print(
                    f"[chat_image] fast_ocr_done text_len={text_len} conf={extract_quality:.4f}",
                    flush=True,
                )
                if text_len < 8:
                    notes.append("图片快速识别未提取到足够文本，本轮聊天先跳过全量OCR；如需更完整识别请到检测页单独分析图片。")
            else:
                extracted_text, extract_quality = await extract_text_from_image(path)
                print(
                    f"[analyze_image] full_ocr_done text_len={len((extracted_text or '').strip())} conf={extract_quality:.4f}",
                    flush=True,
                )
        except Exception as exc:
            return None, [f"图片 OCR 失败：{type(exc).__name__}: {exc}"]

    elif modality == "audio":
        if file is None:
            return None, ["未上传音频"]

        path = await save_upload(file, "audio")

        if fast_mode and CHAT_AUDIO_SUBPROCESS_ENABLED and session is not None:
            print(f"[chat_audio] spawn_worker file_name={file.filename}", flush=True)
            return _start_audio_worker_job(session, file, path)

        try:
            extracted_text, extract_quality = await transcribe_audio(path)
        except Exception as exc:
            return None, [f"音频转写失败：{type(exc).__name__}: {exc}"]

    elif modality == "video":
        if file is None:
            return None, ["未上传视频"]
        path = await save_upload(file, "video")
        try:
            extracted_text, extract_quality, video_meta = await extract_text_from_video(path, filename=file.filename or "")
            notes.extend(video_meta.get("warnings", []))
            notes.extend(video_meta.get("errors", []))
        except VideoProcessingError as exc:
            return None, [f"视频分析失败：{exc}"]

    elif modality == "file":
        if file is None:
            return None, ["未上传文件"]
        path = await save_upload(file, "file")
        extracted_text, extract_quality, notes = await extract_text_from_generic_file(path, filename=file.filename or "")

    else:
        return None, [f"不支持的附件类型：{modality}"]

    if path and os.path.exists(path):
        try:
            os.remove(path)
        except Exception:
            pass

    clean_text = _clip_text(extracted_text, MAX_EVIDENCE_TEXT_LEN)
    if not clean_text:
        if modality == "image" and fast_mode:
            notes = notes or ["图片快速识别未提取到足够文本，本轮聊天先不做全量OCR；如需更完整识别请到检测页单独分析图片。"]
        else:
            notes = notes or ["未提取到足够有效文本"]
        return None, notes

    score = _score_text_block(modality, clean_text, extract_quality, faq_timeout=CHAT_FAQ_TIMEOUT_SEC if fast_mode else ANALYZE_FAQ_TIMEOUT_SEC)
    evidence = EvidenceItem(
        evidence_id=uuid.uuid4().hex,
        modality=modality,
        file_name=file.filename if file else original_text,
        content_type=(file.content_type if file else ""),
        original_text=original_text,
        extracted_text=clean_text,
        extract_quality=extract_quality,
        fraud_probability=score["fraud_probability"],
        result_confidence=score["result_confidence"],
        risk_level=score["risk_level"],
        reason=_dedup_text_list(notes + score["reason"], limit=6),
        safe_actions=score["safe_actions"],
        kb_hits=score["kb_hits"],
        rule_hits=score["rule_hits"],
    )
    return evidence, notes


def _evidence_to_meta(evidence: EvidenceItem) -> dict:
    return {
        "attachment_id": evidence.evidence_id,
        "modality": evidence.modality,
        "file_name": evidence.file_name,
        "content_type": evidence.content_type,
        "extracted_text": _clip_text(evidence.extracted_text, 300),
        "extract_quality": round(evidence.extract_quality, 4),
        "fraud_probability": round(evidence.fraud_probability, 4),
        "result_confidence": round(evidence.result_confidence, 4),
        "risk_level": evidence.risk_level,
        "reason": evidence.reason[:4],
    }


def _build_evidence_summary(evidence: EvidenceItem) -> str:
    name = evidence.file_name or evidence.modality
    preview = _clip_text(evidence.extracted_text.replace("\n", " "), 120)
    reason = "；".join(evidence.reason[:2]) or "无明显结论"
    return (
        f"[{evidence.modality}] {name} | 风险={evidence.risk_level} "
        f"| 概率={evidence.fraud_probability:.2f} | 原因={reason} | 摘要={preview}"
    )


def _build_multimodal_context(session: ChatSessionState, current_message: str, current_evidence: list[EvidenceItem]) -> str:
    recent_turns = session.turns[-8:]
    recent_evidence = session.evidences[-6:]

    history_lines = []
    for turn in recent_turns:
        role = "用户" if turn.role == "user" else "助手"
        history_lines.append(f"{role}：{_clip_text(turn.text, 180)}")

    evidence_lines = [_build_evidence_summary(item) for item in recent_evidence]
    current_lines = [_build_evidence_summary(item) for item in current_evidence]

    blocks = [
        "【当前用户输入】",
        _clip_text(current_message, 600) or "（本轮没有新增文本）",
        "",
        "【本轮新增多模态证据】",
        "\n".join(current_lines) if current_lines else "（本轮没有新增附件）",
        "",
        "【历史聊天记录】",
        "\n".join(history_lines) if history_lines else "（暂无）",
        "",
        "【历史多模态证据】",
        "\n".join(evidence_lines) if evidence_lines else "（暂无）",
        "",
        "请你结合当前输入、历史聊天记录、历史附件提取结果，判断是否存在诈骗风险，沿用现有反诈助手的回答风格，给出稳妥、可执行的建议。",
    ]

    return _clip_text("\n".join(blocks), MAX_CONTEXT_CHARS)


def _build_multimodal_suggestions(rule_hits: list[str], faq_hits: list[dict], evidence_items: list[EvidenceItem]) -> list[str]:
    suggestions: list[str] = []

    if any(item.risk_level in {"medium", "high"} for item in evidence_items) or rule_hits:
        suggestions.append("先不要转账，也不要提供验证码、银行卡信息或共享屏幕。")

    if any(item.modality == "audio" and item.extracted_text for item in evidence_items):
        suggestions.append("若对方自称客服、公检法或平台工作人员，建议改用官方客服电话回拨核实。")

    if any(item.modality == "audio" and not item.extracted_text for item in evidence_items):
        suggestions.append("音频已收到，当前聊天先优先依据图片、文字和历史证据判断；如需更准确分析，建议到检测页单独分析音频。")

    if any(item.modality in {"image", "file"} for item in evidence_items):
        suggestions.append("如有聊天截图、转账页面、合同或通知文件，可继续补充，我会结合已有证据累计判断。")

    for hit in faq_hits[:2]:
        warning = str(hit.get("warning") or "").strip()
        if warning:
            suggestions.append(warning)

    if not suggestions:
        suggestions.append("可以继续补充对方原话、链接、截图、录音或文件，我会继续累计分析。")

    return _dedup_text_list(suggestions, limit=4)


def _aggregate_session_risk(
    fused_context: str,
    session: ChatSessionState,
    current_evidence: list[EvidenceItem],
) -> dict:
    faq_hits = _search_faq_fast(fused_context, tag="session", timeout=CHAT_FAQ_TIMEOUT_SEC)
    safe_actions = _collect_safe_actions(faq_hits)
    rs, rule_hits = rule_score(fused_context)

    all_evidence = session.evidences[-6:]
    max_evidence_score = max((item.fraud_probability for item in all_evidence), default=0.05)
    high_risk_count = sum(1 for item in all_evidence if item.risk_level == "high")
    medium_risk_count = sum(1 for item in all_evidence if item.risk_level == "medium")

    llm_proxy_score = max_evidence_score
    if high_risk_count >= 2:
        llm_proxy_score = max(llm_proxy_score, 0.82)
    elif high_risk_count >= 1 or medium_risk_count >= 2:
        llm_proxy_score = max(llm_proxy_score, 0.68)
    elif rule_hits:
        llm_proxy_score = max(llm_proxy_score, 0.42)
    elif faq_hits:
        llm_proxy_score = max(llm_proxy_score, 0.25)

    kb_proxy_score = min(0.35, 0.12 * len(faq_hits))
    fraud_probability = combine_probability(
        llm_score=llm_proxy_score,
        rule_score_value=rs,
        kb_score=kb_proxy_score,
    )

    high_score, high_reason = high_risk_override(fused_context, rule_hits)
    medium_score, medium_reason = medium_risk_override(fused_context, rule_hits)
    low_score, low_reason = low_risk_override(fused_context, rule_hits)
    override_reason = None
    if high_score is not None:
        fraud_probability = max(fraud_probability, high_score)
        override_reason = high_reason
    if medium_score is not None:
        fraud_probability = max(fraud_probability, medium_score)
        if override_reason is None:
            override_reason = medium_reason
    if low_score is not None:
        fraud_probability = min(fraud_probability, low_score)
        override_reason = low_reason

    extract_quality = max((item.extract_quality for item in current_evidence), default=0.92)
    result_confidence = combine_confidence(
        extract_quality=max(0.2, extract_quality),
        fraud_probability=fraud_probability,
    )

    reasons: list[str] = []
    if override_reason:
        reasons.append(override_reason)
    reasons.extend(rule_hits_to_display(rule_hits))
    for item in all_evidence[-3:]:
        reasons.extend(item.reason[:2])
    for hit in faq_hits[:2]:
        warning = str(hit.get("warning") or "").strip()
        title = str(hit.get("title") or "").strip()
        if warning:
            reasons.append(warning)
        elif title:
            reasons.append(title)

    return {
        "fraud_probability": round(fraud_probability, 4),
        "result_confidence": round(result_confidence, 4),
        "risk_level": risk_level(fraud_probability),
        "reason": _dedup_text_list(reasons, limit=6),
        "faq_hits": faq_hits[:3],
        "safe_actions": safe_actions,
        "rule_hits": rule_hits,
    }


async def chat_assistant(session_id: str = "", message: str = "") -> dict:
    session = _get_or_create_session(session_id)
    clean_message = (message or "").strip()
    harvested_audio = _harvest_completed_audio_jobs(session)

    if not clean_message:
        return _build_default_chat_response(session.session_id)

    text_evidence, _ = await _extract_attachment_evidence("text", text=clean_message, fast_mode=True, session=session)
    current_evidence = list(harvested_audio) + [item for item in [text_evidence] if item is not None]

    for item in current_evidence:
        _append_evidence(session, item)
    _append_turn(session, "user", clean_message)

    fused_context = _build_multimodal_context(session, clean_message, current_evidence)
    aggregate = _aggregate_session_risk(fused_context, session, current_evidence)

    llm_reply = await general_chat_reply(
        fused_context,
        aggregate["faq_hits"],
        rule_hits=aggregate["rule_hits"],
        safe_actions=aggregate["safe_actions"],
    )
    if isinstance(llm_reply, str):
        reply = llm_reply.strip()
        suggestions = _build_multimodal_suggestions(aggregate["rule_hits"], aggregate["faq_hits"], current_evidence)
        safe_actions = aggregate["safe_actions"]
    else:
        reply = str(llm_reply.get("reply") or "").strip()
        suggestions = _dedup_text_list(
            list(llm_reply.get("suggestions") or []) +
            _build_multimodal_suggestions(aggregate["rule_hits"], aggregate["faq_hits"], current_evidence),
            limit=4,
        )
        safe_actions = _dedup_text_list(
            list(llm_reply.get("safe_actions") or []) + aggregate["safe_actions"],
            limit=6,
        )

    if not reply:
        reply = "从你目前提供的多模态证据看，建议先暂停一切转账、验证码和共享屏幕操作，再通过官方渠道核实。"

    _append_turn(session, "assistant", reply)

    return {
        "session_id": session.session_id,
        "reply": reply,
        "fraud_probability": aggregate["fraud_probability"],
        "result_confidence": aggregate["result_confidence"],
        "risk_level": aggregate["risk_level"],
        "reason": aggregate["reason"],
        "safe_actions": safe_actions,
        "kb_hits": aggregate["faq_hits"],
        "suggestions": suggestions,
        "evidence_count": len(session.evidences),
        "latest_evidence": [_evidence_to_meta(item) for item in current_evidence],
    }


async def chat_with_attachment(
    session_id: str = "",
    message: str = "",
    attachment_modality: str = "",
    files: list[UploadFile] | None = None,
    url: str = "",
) -> dict:
    session = _get_or_create_session(session_id)
    clean_message = (message or "").strip()
    harvested_audio = _harvest_completed_audio_jobs(session)
    upload_files = _normalize_upload_files(files)
    inferred_modalities = _infer_modalities_from_uploads(attachment_modality, upload_files)

    current_evidence: list[EvidenceItem] = list(harvested_audio)
    attachment_notes: list[str] = []

    if clean_message:
        text_evidence, _ = await _extract_attachment_evidence("text", text=clean_message, fast_mode=True, session=session)
        if text_evidence is not None:
            current_evidence.append(text_evidence)

    if upload_files:
        for index, upload_file in enumerate(upload_files):
            inferred_modality = inferred_modalities[index] if index < len(inferred_modalities) else _infer_modality_from_upload("auto", upload_file)
            attachment_evidence, notes = await _extract_attachment_evidence(
                inferred_modality,
                file=upload_file,
                fast_mode=True,
            )
            if attachment_evidence is not None:
                current_evidence.append(attachment_evidence)
            if notes:
                file_name = upload_file.filename or inferred_modality
                attachment_notes.append(f"{file_name}：{'；'.join(notes)}")
    elif url.strip():
        website_evidence, notes = await _extract_attachment_evidence(
            "website",
            url=url,
            fast_mode=True,
            session=session,
        )
        if website_evidence is not None:
            current_evidence.append(website_evidence)
        if notes:
            attachment_notes.append(f"网址：{'；'.join(notes)}")

    harvested_after_uploads = _harvest_completed_audio_jobs(session)
    if harvested_after_uploads:
        current_evidence.extend(harvested_after_uploads)

    if attachment_notes:
        note_block = "；".join(attachment_notes)
        clean_message = f"{clean_message}\n附件解析结果：{note_block}".strip()

    if not clean_message and not current_evidence:
        return _build_default_chat_response(session.session_id)

    for item in current_evidence:
        _append_evidence(session, item)

    user_turn_text = clean_message or "（仅上传了附件）"
    if upload_files:
        file_names = ", ".join((item.filename or inferred_modalities[index] if index < len(inferred_modalities) else "attachment") for index, item in enumerate(upload_files))
        user_turn_text = f"{user_turn_text}\n[附件] {file_names}"
    elif url.strip():
        user_turn_text = f"{user_turn_text}\n[网址] {url.strip()}"
    _append_turn(session, "user", user_turn_text)

    fused_context = _build_multimodal_context(session, clean_message, current_evidence)
    aggregate = _aggregate_session_risk(fused_context, session, current_evidence)

    reply = ""
    suggestions = _build_multimodal_suggestions(aggregate["rule_hits"], aggregate["faq_hits"], current_evidence)
    safe_actions = aggregate["safe_actions"]

    llm_reply = await general_chat_reply(
        fused_context,
        aggregate["faq_hits"],
        rule_hits=aggregate["rule_hits"],
        safe_actions=aggregate["safe_actions"],
    )

    if isinstance(llm_reply, str):
        reply = llm_reply.strip()
    else:
        reply = str(llm_reply.get("reply") or "").strip()
        suggestions = _dedup_text_list(
            list(llm_reply.get("suggestions") or []) + suggestions,
            limit=4,
        )
        safe_actions = _dedup_text_list(
            list(llm_reply.get("safe_actions") or []) + safe_actions,
            limit=6,
        )

    if not reply:
        reply = "我已经把这次新增内容和历史证据合并判断了。当前建议先暂停操作，不要转账、不要提供验证码，并通过官方渠道核实。"

    _append_turn(session, "assistant", reply)

    return {
        "session_id": session.session_id,
        "reply": reply,
        "fraud_probability": aggregate["fraud_probability"],
        "result_confidence": aggregate["result_confidence"],
        "risk_level": aggregate["risk_level"],
        "reason": aggregate["reason"],
        "safe_actions": safe_actions,
        "kb_hits": aggregate["faq_hits"],
        "suggestions": suggestions,
        "evidence_count": len(session.evidences),
        "latest_evidence": [_evidence_to_meta(item) for item in current_evidence],
    }



async def stream_chat_assistant(message: str, session_id: str = ""):
    session = _get_or_create_session(session_id)
    clean_message = (message or "").strip()
    harvested_audio = _harvest_completed_audio_jobs(session)

    try:
        yield _sse_event(
            {
                "type": "start",
                "session_id": session.session_id,
                "safe_actions": [],
                "kb_hits": [],
                "suggestions": [],
            },
            event="start",
        )

        if not clean_message:
            response = _build_default_chat_response(session.session_id)
            yield _sse_event({"type": "done", **response}, event="done")
            return

        text_evidence, _ = await _extract_attachment_evidence("text", text=clean_message, fast_mode=True, session=session)
        current_evidence = [item for item in [text_evidence] if item is not None]
        for item in current_evidence:
            _append_evidence(session, item)
        _append_turn(session, "user", clean_message)

        fused_context = _build_multimodal_context(session, clean_message, current_evidence)
        aggregate = _aggregate_session_risk(fused_context, session, current_evidence)
        suggestions = _build_multimodal_suggestions(aggregate["rule_hits"], aggregate["faq_hits"], current_evidence)

        reply_parts: list[str] = []
        for chunk in general_chat_reply_stream(
            fused_context,
            aggregate["faq_hits"],
            rule_hits=aggregate["rule_hits"],
            safe_actions=aggregate["safe_actions"],
        ):
            normalized = str(chunk or "")
            if not normalized:
                continue
            reply_parts.append(normalized)
            yield _sse_event({"type": "delta", "content": normalized}, event="delta")

        reply = "".join(reply_parts).strip()
        if not reply:
            reply = "建议先暂停操作，不要转账，也不要提供验证码，并通过官方渠道核实。"

        _append_turn(session, "assistant", reply)

        response = {
            "session_id": session.session_id,
            "reply": reply,
            "fraud_probability": aggregate["fraud_probability"],
            "result_confidence": aggregate["result_confidence"],
            "risk_level": aggregate["risk_level"],
            "reason": aggregate["reason"],
            "safe_actions": aggregate["safe_actions"],
            "kb_hits": aggregate["faq_hits"],
            "suggestions": suggestions,
            "evidence_count": len(session.evidences),
            "latest_evidence": [_evidence_to_meta(item) for item in current_evidence],
        }
        yield _sse_event({"type": "done", **response}, event="done")
    except Exception as exc:
        print(f"[chat_stream] exception: {exc}", flush=True)
        traceback.print_exc()
        fallback = {
            "session_id": session.session_id,
            "reply": "服务端处理中断，请稍后重试。",
            "fraud_probability": 0.0,
            "result_confidence": 0.0,
            "risk_level": "low",
            "reason": [],
            "safe_actions": [],
            "kb_hits": [],
            "suggestions": [],
            "evidence_count": len(session.evidences),
            "latest_evidence": [],
        }
        yield _sse_event({"type": "done", **fallback}, event="done")


async def stream_chat_with_attachment(
    session_id: str = "",
    message: str = "",
    attachment_modality: str = "",
    files: list[UploadFile] | None = None,
    url: str = "",
):
    session = _get_or_create_session(session_id)
    harvested_audio = _harvest_completed_audio_jobs(session)

    try:
        yield _sse_event(
            {
                "type": "start",
                "session_id": session.session_id,
                "safe_actions": [],
                "kb_hits": [],
                "suggestions": [],
            },
            event="start",
        )

        clean_message = (message or "").strip()
        upload_files = _normalize_upload_files(files)
        inferred_modalities = _infer_modalities_from_uploads(attachment_modality, upload_files)
        current_evidence: list[EvidenceItem] = list(harvested_audio)
        attachment_notes: list[str] = []

        if clean_message:
            text_evidence, _ = await _extract_attachment_evidence("text", text=clean_message, fast_mode=True, session=session)
            if text_evidence is not None:
                current_evidence.append(text_evidence)

        if upload_files:
            for index, upload_file, inferred_modality in _iter_chat_uploads(upload_files, inferred_modalities):
                try:
                    attachment_evidence, notes = await _extract_attachment_evidence(
                        inferred_modality,
                        file=upload_file,
                        fast_mode=True,
                        session=session,
                    )
                    if attachment_evidence is not None:
                        current_evidence.append(attachment_evidence)
                    if notes:
                        file_name = upload_file.filename or inferred_modality
                        attachment_notes.append(f"{file_name}：{'；'.join(notes)}")
                except Exception as exc:
                    print(f"[chat_multi] single_attachment_error name={upload_file.filename} error={type(exc).__name__}: {exc}", flush=True)
                    attachment_notes.append(f"{upload_file.filename or inferred_modality}：处理失败，但本轮会继续综合其它证据。")
                    continue
        elif url.strip():
            website_evidence, notes = await _extract_attachment_evidence(
                "website",
                url=url,
                fast_mode=True,
                session=session,
            )
            if website_evidence is not None:
                current_evidence.append(website_evidence)
            if notes:
                attachment_notes.append(f"网址：{'；'.join(notes)}")

        if attachment_notes:
            note_block = "；".join(attachment_notes)
            clean_message = f"{clean_message}\n附件解析结果：{note_block}".strip()

        if not clean_message and not current_evidence:
            response = _build_default_chat_response(session.session_id)
            yield _sse_event({"type": "done", **response}, event="done")
            return

        for item in current_evidence:
            _append_evidence(session, item)

        user_turn_text = clean_message or "（仅上传了附件）"
        if upload_files:
            file_names = ", ".join((item.filename or inferred_modalities[index] if index < len(inferred_modalities) else "attachment") for index, item in enumerate(upload_files))
            user_turn_text = f"{user_turn_text}\n[附件] {file_names}"
        elif url.strip():
            user_turn_text = f"{user_turn_text}\n[网址] {url.strip()}"
        _append_turn(session, "user", user_turn_text)

        fused_context = _build_multimodal_context(session, clean_message, current_evidence)
        aggregate = _aggregate_session_risk(fused_context, session, current_evidence)
        suggestions = _build_multimodal_suggestions(aggregate["rule_hits"], aggregate["faq_hits"], current_evidence)

        reply_parts: list[str] = []
        for chunk in general_chat_reply_stream(
            fused_context,
            aggregate["faq_hits"],
            rule_hits=aggregate["rule_hits"],
            safe_actions=aggregate["safe_actions"],
        ):
            normalized = str(chunk or "")
            if not normalized:
                continue
            reply_parts.append(normalized)
            yield _sse_event({"type": "delta", "content": normalized}, event="delta")

        reply = "".join(reply_parts).strip()
        if not reply:
            reply = "我已经结合本轮新增附件和历史证据完成综合判断。建议先不要转账，也不要提供验证码。"

        _append_turn(session, "assistant", reply)

        response = {
            "session_id": session.session_id,
            "reply": reply,
            "fraud_probability": aggregate["fraud_probability"],
            "result_confidence": aggregate["result_confidence"],
            "risk_level": aggregate["risk_level"],
            "reason": aggregate["reason"],
            "safe_actions": aggregate["safe_actions"],
            "kb_hits": aggregate["faq_hits"],
            "suggestions": suggestions,
            "evidence_count": len(session.evidences),
            "latest_evidence": [_evidence_to_meta(item) for item in current_evidence],
        }
        yield _sse_event({"type": "done", **response}, event="done")

    except Exception as exc:
        print(f"[chat_stream_multimodal] exception: {exc}", flush=True)
        traceback.print_exc()
        fallback = {
            "session_id": session.session_id,
            "reply": "服务端处理中断，请稍后重试。",
            "fraud_probability": 0.0,
            "result_confidence": 0.0,
            "risk_level": "low",
            "reason": [],
            "safe_actions": [],
            "kb_hits": [],
            "suggestions": [],
            "evidence_count": len(session.evidences),
            "latest_evidence": [],
        }
        yield _sse_event({"type": "done", **fallback}, event="done")




async def analyze_input(modality: str, text: str = "", url: str = "", file: UploadFile | None = None):
    normalized_modality = (modality or "").strip().lower()
    if normalized_modality not in {"text", "image", "audio", "website", "video", "file"}:
        return _empty_result(normalized_modality or "text", "不支持的输入类型")

    evidence, notes = await _extract_attachment_evidence(
        modality=normalized_modality,
        file=file,
        url=url,
        text=text,
        fast_mode=False,
    )

    if evidence is None:
        if notes:
            return _low_confidence_result(normalized_modality, notes)
        return _empty_result(normalized_modality, "未提取到有效内容")

    _enqueue_candidate_safe(
        source_channel="analyze",
        source_modality=normalized_modality,
        text_excerpt=evidence.extracted_text or text or url,
        fraud_probability=evidence.fraud_probability,
        result_confidence=evidence.result_confidence,
        risk_level_value=evidence.risk_level,
        reason=evidence.reason,
        rule_hits=evidence.rule_hits,
        kb_hits=evidence.kb_hits,
        safe_actions=evidence.safe_actions,
    )

    return {
        "modality": normalized_modality,
        "fraud_probability": evidence.fraud_probability,
        "result_confidence": evidence.result_confidence,
        "risk_level": evidence.risk_level,
        "reason": evidence.reason,
        "extracted_text": _clip_text(evidence.extracted_text, 3000),
        "kb_hits": evidence.kb_hits[:3],
        "safe_actions": evidence.safe_actions,
        "reply": (f"检测到可疑点：{evidence.reason[0]}。" if evidence.reason else "建议先暂停操作并通过官方渠道核实。"),
        "next_actions": evidence.safe_actions[:6],
    }

def _sms_link_risk_boost(text: str) -> tuple[float | None, str | None]:
    normalized = re.sub(r"\s+", "", str(text or "")).strip().lower()
    if not normalized:
        return None, None

    has_link = any(token in normalized for token in ["http://", "https://", "www.", ".com", ".cn", "链接"])
    has_urgent = any(token in normalized for token in ["立即", "马上", "尽快", "速点", "点击处理", "点击查看"])
    has_account = any(token in normalized for token in ["账户异常", "账号异常", "冻结", "风险", "限制登录", "停用"])

    if has_link and (has_urgent or has_account):
        return 0.82, "短信中出现链接并伴随紧急处置或账户异常诱导"

    return None, None


async def check_sms_message(sender: str = "", message: str = "") -> dict:
    normalized_sender = str(sender or "").strip()
    normalized_message = str(message or "").strip()

    if not normalized_message:
        return {
            "sender": normalized_sender,
            "normalized_message": "",
            "is_fraud": False,
            "fraud_probability": 0.0,
            "result_confidence": 0.0,
            "risk_level": "low",
            "reason": ["短信内容为空"],
            "safe_actions": [],
        }

    combined_text = f"{normalized_sender}\n{normalized_message}".strip()
    faq_hits = _search_faq_fast(combined_text, tag="sms", timeout=0.6)
    safe_actions = _collect_safe_actions(faq_hits)
    rs, rule_hits = rule_score(combined_text)

    kb_proxy_score = min(0.35, 0.12 * len(faq_hits))
    llm_proxy_score = 0.15
    if rule_hits:
        llm_proxy_score = min(0.72, 0.22 + 0.12 * len(rule_hits))
    elif faq_hits:
        llm_proxy_score = 0.28

    fraud_probability = combine_probability(
        llm_score=llm_proxy_score,
        rule_score_value=rs,
        kb_score=kb_proxy_score,
    )

    high_score, high_reason = high_risk_override(combined_text, rule_hits)
    medium_score, medium_reason = medium_risk_override(combined_text, rule_hits)
    low_score, low_reason = low_risk_override(combined_text, rule_hits)
    override_reason = None

    if high_score is not None:
        fraud_probability = max(fraud_probability, high_score)
        override_reason = high_reason
    if medium_score is not None:
        fraud_probability = max(fraud_probability, medium_score)
        if override_reason is None:
            override_reason = medium_reason
    if low_score is not None:
        fraud_probability = min(fraud_probability, low_score)
        override_reason = low_reason

    link_score, link_reason = _sms_link_risk_boost(combined_text)
    if link_score is not None:
        fraud_probability = max(fraud_probability, link_score)
        if override_reason is None:
            override_reason = link_reason

    reasons: list[str] = []
    if override_reason:
        reasons.append(override_reason)
    reasons.extend(rule_hits_to_display(rule_hits))

    for hit in faq_hits[:2]:
        warning = str(hit.get("warning") or "").strip()
        title = str(hit.get("title") or "").strip()
        if warning:
            reasons.append(warning)
        elif title:
            reasons.append(title)

    reasons = _dedup_text_list(reasons, limit=6)

    if not safe_actions:
        safe_actions = [
            "不要点击短信中的链接，也不要回拨短信里提供的电话。",
            "通过官方 App 或官网核实账户、订单、扣费或物流状态。",
            "不要提供验证码、银行卡号、身份证号等敏感信息。",
        ]

    result_confidence = combine_confidence(
        extract_quality=0.95,
        fraud_probability=fraud_probability,
    )

    response = {
        "sender": normalized_sender,
        "normalized_message": normalized_message,
        "is_fraud": fraud_probability >= 0.38,
        "fraud_probability": round(fraud_probability, 4),
        "result_confidence": round(result_confidence, 4),
        "risk_level": risk_level(fraud_probability),
        "reason": reasons,
        "safe_actions": safe_actions[:6],
    }

    _enqueue_candidate_safe(
        source_channel="check_sms",
        source_modality="sms",
        text_excerpt=combined_text,
        fraud_probability=response["fraud_probability"],
        result_confidence=response["result_confidence"],
        risk_level_value=response["risk_level"],
        reason=reasons,
        rule_hits=rule_hits,
        kb_hits=faq_hits,
        safe_actions=response["safe_actions"],
    )

    return response



def _normalize_report_level(level: str) -> str:
    normalized = str(level or "").strip().lower()
    if normalized in {"high", "h", "严重", "高"}:
        return "high"
    if normalized in {"low", "l", "轻微", "低"}:
        return "low"
    return "medium"


def _report_behavior_weight(level: str) -> float:
    normalized = _normalize_report_level(level)
    if normalized == "high":
        return 1.0
    if normalized == "medium":
        return 0.65
    return 0.35


def _pick_priority_from_level(level: str) -> str:
    normalized = _normalize_report_level(level)
    if normalized == "high":
        return "high"
    if normalized == "medium":
        return "medium"
    return "low"


def _contains_any(text: str, tokens: list[str]) -> bool:
    haystack = str(text or "").lower()
    return any(token.lower() in haystack for token in tokens)


def _build_report_advice_items(
    report_type: str,
    behaviors: list[dict],
    intercept_overview: dict,
    user_profile: dict | None,
    overall_risk_level: str,
) -> list[dict]:
    suggestions: list[dict] = []

    def add_suggestion(title: str, content: str, priority: str = "medium") -> None:
        normalized_title = str(title).strip()
        normalized_content = str(content).strip()
        if not normalized_title or not normalized_content:
            return
        for item in suggestions:
            if item["content"] == normalized_content:
                return
        suggestions.append({
            "id": len(suggestions) + 1,
            "title": normalized_title,
            "content": normalized_content,
            "priority": priority,
        })

    profile_name = str((user_profile or {}).get("name") or "").strip()
    age = int((user_profile or {}).get("age") or 0)
    occupation = str((user_profile or {}).get("occupation") or "").strip()
    user_prefix = f"针对{profile_name}当前的风险表现，" if profile_name else "针对当前的风险表现，"

    if overall_risk_level == "high":
        add_suggestion(
            "先做紧急止损",
            user_prefix + "建议优先暂停转账、验证码提供、共享屏幕和陌生链接点击，并通过官方渠道逐项核实异常操作。",
            "high",
        )
    elif overall_risk_level == "medium":
        add_suggestion(
            "先做重点核查",
            user_prefix + "建议优先核查高频异常行为对应的账号、设备和通信来源，避免风险继续累积。",
            "high",
        )
    else:
        add_suggestion(
            "保持当前防护习惯",
            user_prefix + "当前整体风险可控，建议继续保持陌生来电核验、链接不点击和账号双重验证等习惯。",
            "medium",
        )

    for behavior in behaviors:
        title = str(behavior.get("title") or "")
        description = str(behavior.get("description") or "")
        merged_text = f"{title} {description}"
        priority = _pick_priority_from_level(behavior.get("level") or "medium")

        if _contains_any(merged_text, ["境外", "来电", "电话", "未知号码", "陌生号码"]):
            add_suggestion(
                "加强来电拦截",
                "建议开启境外来电和陌生号码拦截，对自称客服、公检法、平台工作人员的来电一律先挂断，再用官方号码回拨核实。",
                priority,
            )
        if _contains_any(merged_text, ["短信", "链接", "短链接", "网址"]):
            add_suggestion(
                "处理短信和链接风险",
                "建议关闭来自陌生短信的自动跳转入口，不点击任何不明短链接；涉及扣费、中奖、退款或认证的内容，统一到官方 App 内核实。",
                priority,
            )
        if _contains_any(merged_text, ["登录", "异地", "账号", "密码"]):
            add_suggestion(
                "保护账号安全",
                "建议立即修改重点账号密码，开启短信以外的双重验证，并检查最近登录设备列表，移除不认识的设备。",
                priority,
            )
        if _contains_any(merged_text, ["非官方应用", "apk", "安装", "未知来源", "应用商店"]):
            add_suggestion(
                "清理高风险应用",
                "建议卸载非官方来源应用，关闭“允许安装未知来源应用”，并重点检查无障碍、悬浮窗、读取短信和读取剪贴板权限。",
                priority,
            )
        if _contains_any(merged_text, ["高危网站", "涉赌", "涉黄", "网站", "浏览"]):
            add_suggestion(
                "限制高危网页访问",
                "建议清理浏览器缓存和可疑下载记录，关闭高危网页通知权限，避免再次进入博彩、贷款或仿冒客服页面。",
                priority,
            )
        if _contains_any(merged_text, ["剪切板", "剪贴板"]):
            add_suggestion(
                "减少剪贴板泄露",
                "建议减少在剪贴板中保存验证码、身份证号和银行卡信息，并检查近期安装应用是否存在频繁读取剪贴板的行为。",
                priority,
            )

    phone_count = int(intercept_overview.get("phone_week_intercept_count") or 0)
    sms_count = int(intercept_overview.get("sms_week_intercept_count") or 0)
    app_count = int(intercept_overview.get("app_week_intercept_count") or 0)
    clipboard_count = int(intercept_overview.get("clipboard_week_intercept_count") or 0)

    if phone_count >= 3:
        add_suggestion(
            "关注高频通信骚扰",
            f"当前周拦截电话达到{phone_count}次，建议把常见诈骗关键词和高频陌生号码加入重点拦截名单，并提醒家人不要回拨未知号码。",
            "high",
        )
    if sms_count >= 3:
        add_suggestion(
            "复核高危短信来源",
            f"当前周拦截短信达到{sms_count}次，建议重点检查是否存在冒充快递、银行、平台客服或验证码诱导类短信。",
            "high",
        )
    if app_count >= 1:
        add_suggestion(
            "检查应用权限",
            f"当前周可疑应用拦截达到{app_count}次，建议逐个检查最近安装应用的来源和权限，发现异常立即卸载。",
            "medium",
        )
    if clipboard_count >= 1:
        add_suggestion(
            "关注敏感信息复制",
            f"当前周剪切板风险拦截达到{clipboard_count}次，建议避免复制验证码、身份证号、银行卡号等敏感信息到剪贴板。",
            "medium",
        )

    if age >= 55:
        add_suggestion(
            "补充家庭协同防护",
            "考虑到当前账号使用者年龄较大，建议开启家人协助提醒机制；遇到转账、退款、投资、领奖等内容时先与家人确认。",
            "medium",
        )
    elif _contains_any(occupation, ["学生", "school", "大学", "研究生"]):
        add_suggestion(
            "重点防范校园常见骗局",
            "建议重点防范兼职刷单、游戏账号交易、冒充老师同学借钱和校园贷类骗局，涉及先付款或先垫资的一律停止。",
            "medium",
        )
    elif _contains_any(occupation, ["财务", "会计", "出纳"]):
        add_suggestion(
            "防范对公转账诈骗",
            "考虑到职业场景，建议重点防范冒充老板、伪造付款指令和邮箱钓鱼类诈骗，所有转账操作实行二次确认。",
            "medium",
        )

    add_suggestion(
        "建立固定核验动作",
        f"建议把“陌生电话不回拨、陌生链接不点击、转账前电话核验、异常登录先改密”作为本{ '周' if report_type == 'weekly' else '月' }固定安全动作持续执行。",
        "low",
    )

    return suggestions[:6]


async def generate_report_advice(payload) -> dict:
    report_type = str(getattr(payload, "report_type", "weekly") or "weekly").strip().lower()
    if report_type not in {"weekly", "monthly"}:
        report_type = "weekly"

    risk_behaviors_raw = list(getattr(payload, "risk_behaviors", []) or [])
    intercept_obj = getattr(payload, "intercept_overview", None)
    user_obj = getattr(payload, "user_profile", None)

    behaviors: list[dict] = []
    for item in risk_behaviors_raw:
        if hasattr(item, "model_dump"):
            data = item.model_dump()
        elif isinstance(item, dict):
            data = item
        else:
            data = {}
        behaviors.append({
            "title": str(data.get("title") or "").strip(),
            "description": str(data.get("description") or "").strip(),
            "level": _normalize_report_level(data.get("level") or "medium"),
            "frequency": max(0, int(data.get("frequency") or 0)),
        })

    intercept_overview = intercept_obj.model_dump() if hasattr(intercept_obj, "model_dump") else (intercept_obj or {})
    user_profile = user_obj.model_dump() if hasattr(user_obj, "model_dump") else (user_obj or None)

    behavior_risk_score = sum((item["frequency"] or 0) * _report_behavior_weight(item["level"]) for item in behaviors)
    intercept_score = (
        int(intercept_overview.get("phone_week_intercept_count") or 0) * 0.8 +
        int(intercept_overview.get("sms_week_intercept_count") or 0) * 0.7 +
        int(intercept_overview.get("app_week_intercept_count") or 0) * 1.2 +
        int(intercept_overview.get("clipboard_week_intercept_count") or 0) * 0.9
    )
    total_score = behavior_risk_score + intercept_score

    if any(item["level"] == "high" and item["frequency"] >= 2 for item in behaviors) or total_score >= 12:
        overall_risk_level = "high"
    elif any(item["level"] == "high" for item in behaviors) or total_score >= 5:
        overall_risk_level = "medium"
    else:
        overall_risk_level = "low"

    top_behaviors = sorted(behaviors, key=lambda x: (_report_behavior_weight(x["level"]), x["frequency"]), reverse=True)[:3]
    reasons = []
    for item in top_behaviors:
        if not item["title"]:
            continue
        reasons.append(f"{item['title']}出现{item['frequency']}次，风险等级为{item['level']}")

    phone_count = int(intercept_overview.get("phone_week_intercept_count") or 0)
    sms_count = int(intercept_overview.get("sms_week_intercept_count") or 0)
    app_count = int(intercept_overview.get("app_week_intercept_count") or 0)
    clipboard_count = int(intercept_overview.get("clipboard_week_intercept_count") or 0)
    if phone_count + sms_count + app_count + clipboard_count > 0:
        reasons.append(
            f"本周拦截统计为电话{phone_count}次、短信{sms_count}次、可疑应用{app_count}次、剪切板{clipboard_count}次"
        )
    reasons = _dedup_text_list(reasons, limit=4)

    profile_name = str((user_profile or {}).get("name") or "").strip()
    subject = f"{profile_name}的账户" if profile_name else "当前账户"
    period_label = "周报" if report_type == "weekly" else "月报"
    if overall_risk_level == "high":
        summary = f"根据{period_label}中的风险行为与拦截数据，{subject}目前属于高风险状态，建议优先处理通信、账号与设备侧异常。"
    elif overall_risk_level == "medium":
        summary = f"根据{period_label}中的风险行为与拦截数据，{subject}目前存在持续性风险信号，建议尽快完成重点核验与防护设置。"
    else:
        summary = f"根据{period_label}中的风险行为与拦截数据，{subject}整体风险可控，建议继续保持当前防护习惯并针对薄弱项做补强。"

    suggestions = _build_report_advice_items(
        report_type=report_type,
        behaviors=behaviors,
        intercept_overview=intercept_overview,
        user_profile=user_profile,
        overall_risk_level=overall_risk_level,
    )

    if not suggestions:
        suggestions = [
            {
                "id": 1,
                "title": "保持基础防护",
                "content": "建议继续保持陌生链接不点击、验证码不外传、转账前二次核验的基本安全习惯。",
                "priority": "medium",
            }
        ]

    return {
        "report_type": report_type,
        "summary": summary,
        "risk_level": overall_risk_level,
        "reason": reasons,
        "suggestions": suggestions,
    }
