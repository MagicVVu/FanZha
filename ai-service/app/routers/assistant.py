from typing import Optional

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from app.schemas import (
    AnalyzeResponse,
    ChatRequest,
    ChatResponse,
    SmsCheckRequest,
    SmsCheckResponse,
    ReportAdviceRequest,
    ReportAdviceResponse,
)
from app.services.pipeline import (
    analyze_input,
    chat_assistant,
    chat_with_attachment,
    check_sms_message,
    generate_report_advice,
    stream_chat_assistant,
    stream_chat_with_attachment,
)

router = APIRouter()

SUPPORTED_MODALITIES = {"text", "image", "audio", "website", "video", "file"}


@router.post("/chat", response_model=ChatResponse)
async def chat(payload: ChatRequest):
    return await chat_assistant(
        session_id=payload.session_id,
        message=payload.message,
    )


@router.post("/chat/stream")
async def chat_stream(payload: ChatRequest):
    return StreamingResponse(
        stream_chat_assistant(
            message=payload.message,
            session_id=payload.session_id,
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/chat/multimodal", response_model=ChatResponse)
async def chat_multimodal(
    session_id: str = Form(default=""),
    message: str = Form(default=""),
    attachment_modality: str = Form(default="auto"),
    url: str = Form(default=""),
    file: list[UploadFile] = File(default_factory=list),
):
    normalized_modality = attachment_modality.strip().lower()
    if normalized_modality not in {"", "auto"} and normalized_modality not in SUPPORTED_MODALITIES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported attachment_modality: {attachment_modality}. Use text, image, audio, website, video, file, or auto.",
        )

    return await chat_with_attachment(
        session_id=session_id,
        message=message,
        attachment_modality=attachment_modality,
        files=file,
        url=url,
    )


@router.post("/chat/stream/multimodal")
async def chat_stream_multimodal(
    session_id: str = Form(default=""),
    message: str = Form(default=""),
    attachment_modality: str = Form(default="auto"),
    url: str = Form(default=""),
    file: list[UploadFile] = File(default_factory=list),
):
    normalized_modality = attachment_modality.strip().lower()
    if normalized_modality not in {"", "auto"} and normalized_modality not in SUPPORTED_MODALITIES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported attachment_modality: {attachment_modality}. Use text, image, audio, website, video, file, or auto.",
        )

    return StreamingResponse(
        stream_chat_with_attachment(
            session_id=session_id,
            message=message,
            attachment_modality=attachment_modality,
            files=file,
            url=url,
        ),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze(
    modality: str = Form(...),
    text: str = Form(default=""),
    url: str = Form(default=""),
    file: Optional[UploadFile] = File(default=None),
):
    normalized_modality = modality.strip().lower()
    if normalized_modality not in SUPPORTED_MODALITIES:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported modality: {modality}. Use text, image, audio, website, video, or file.",
        )

    return await analyze_input(
        modality=normalized_modality,
        text=text,
        url=url,
        file=file,
    )


@router.post("/check-sms", response_model=SmsCheckResponse)
async def check_sms(payload: SmsCheckRequest):
    return await check_sms_message(
        sender=payload.sender,
        message=payload.message,
    )


@router.post("/report/advice", response_model=ReportAdviceResponse)
async def report_advice(payload: ReportAdviceRequest):
    return await generate_report_advice(payload)
