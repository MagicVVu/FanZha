import os
import secrets

from fastapi import APIRouter, Depends, Header, HTTPException, Query

from app.schemas import (
    ReviewCandidateApplyRequest,
    ReviewCandidateApplyResponse,
    ReviewCandidateApproveRequest,
    ReviewCandidateItem,
    ReviewCandidateListResponse,
    ReviewCandidateRejectRequest,
)
from app.services.novel_case_service import (
    CandidateNotFoundError,
    CandidateReviewError,
    NovelCaseError,
    apply_approved_candidates,
    approve_candidate,
    get_candidate,
    list_candidates,
    reject_candidate,
)

router = APIRouter()


def _require_admin_token(x_admin_token: str | None = Header(default=None, alias="X-Admin-Token")) -> None:
    expected_token = str(os.getenv("ADMIN_REVIEW_TOKEN", "")).strip()
    if not expected_token:
        raise HTTPException(status_code=503, detail="Admin review API is not configured.")
    if not x_admin_token or not secrets.compare_digest(x_admin_token, expected_token):
        raise HTTPException(status_code=401, detail="Invalid admin token.")


@router.get("/candidates", response_model=ReviewCandidateListResponse, dependencies=[Depends(_require_admin_token)])
async def admin_list_candidates(
    status: str = Query(default="", description="按状态过滤: pending/approved/rejected/applied"),
    limit: int = Query(default=50, ge=1, le=200),
):
    return list_candidates(status=status, limit=limit)


@router.get("/candidates/{candidate_id}", response_model=ReviewCandidateItem, dependencies=[Depends(_require_admin_token)])
async def admin_get_candidate(candidate_id: str):
    try:
        return get_candidate(candidate_id)
    except CandidateNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.post(
    "/candidates/{candidate_id}/approve",
    response_model=ReviewCandidateItem,
    dependencies=[Depends(_require_admin_token)],
)
async def admin_approve_candidate(candidate_id: str, payload: ReviewCandidateApproveRequest):
    try:
        return approve_candidate(
            candidate_id,
            reviewer=payload.reviewer,
            fraud_type=payload.fraud_type,
            subtype=payload.subtype,
            title=payload.title,
            question=payload.question,
            answer=payload.answer,
            warning=payload.warning,
            safe_actions=payload.safe_actions,
            keywords=payload.keywords,
            comment=payload.comment,
        )
    except CandidateNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except CandidateReviewError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post(
    "/candidates/{candidate_id}/reject",
    response_model=ReviewCandidateItem,
    dependencies=[Depends(_require_admin_token)],
)
async def admin_reject_candidate(candidate_id: str, payload: ReviewCandidateRejectRequest):
    try:
        return reject_candidate(
            candidate_id,
            reviewer=payload.reviewer,
            comment=payload.comment,
        )
    except CandidateNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except CandidateReviewError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@router.post("/apply", response_model=ReviewCandidateApplyResponse, dependencies=[Depends(_require_admin_token)])
async def admin_apply_candidates(payload: ReviewCandidateApplyRequest):
    try:
        return apply_approved_candidates(rebuild=payload.rebuild, tag=payload.tag)
    except CandidateReviewError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except NovelCaseError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
