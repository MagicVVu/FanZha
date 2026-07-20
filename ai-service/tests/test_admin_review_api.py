import importlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient


class AdminReviewApiTest(unittest.TestCase):
    def setUp(self):
        self.temp_context = tempfile.TemporaryDirectory(prefix="fanzha-admin-review-")
        self.temp_dir = Path(self.temp_context.name)
        self.queue_path = self.temp_dir / "pending_candidates.json"
        self.seed_path = self.temp_dir / "faq_seed.json"
        self.seed_path.write_text(
            json.dumps(
                {
                    "version": "rag_seed_v2",
                    "updated_at": "2026-04-20",
                    "source": "local_seed",
                    "fraud_types": [],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        self.env_patcher = patch.dict(os.environ, {"ADMIN_REVIEW_TOKEN": "secret-token"})
        self.env_patcher.start()

        self.service = importlib.import_module("app.services.novel_case_service")
        self.router_module = importlib.import_module("app.routers.admin_review")

        self.patchers = [
            patch.object(self.service, "NOVEL_CASE_QUEUE_PATH", self.queue_path),
            patch.object(self.service, "FAQ_SEED_PATH", self.seed_path),
            patch.object(self.service, "NOVEL_CASE_QUEUE_ENABLED", True),
            patch.object(self.service, "NOVEL_CASE_MIN_PROBABILITY", 0.72),
            patch.object(self.service, "NOVEL_CASE_MIN_CONFIDENCE", 0.45),
            patch.object(self.service, "NOVEL_CASE_MIN_TEXT_LEN", 30),
            patch.object(self.service, "NOVEL_CASE_KB_SCORE_THRESHOLD", 0.55),
        ]
        for patcher in self.patchers:
            patcher.start()

        app = FastAPI()
        app.include_router(self.router_module.router, prefix="/api/admin/review")
        self.client = TestClient(app)

    def tearDown(self):
        for patcher in reversed(self.patchers):
            patcher.stop()
        self.env_patcher.stop()
        self.temp_context.cleanup()

    def _admin_headers(self):
        return {"X-Admin-Token": "secret-token"}

    def _enqueue_candidate(self):
        return self.service.enqueue_candidate(
            source_channel="analyze",
            source_modality="image",
            text_excerpt="对方说关闭百万保障要下载会议软件并共享屏幕，还要我配合验证资金安全。",
            fraud_probability=0.88,
            result_confidence=0.79,
            risk_level="high",
            reason=["涉及客服扣费类高危处理诱导"],
            rule_hits=["customer_service", "remote"],
            kb_hits=[],
            safe_actions=["通过官方 App 核验", "不要共享屏幕"],
        )

    def test_admin_routes_require_token(self):
        response = self.client.get("/api/admin/review/candidates")
        self.assertEqual(response.status_code, 401)

    def test_candidate_can_be_approved_and_applied(self):
        candidate = self._enqueue_candidate()
        self.assertIsNotNone(candidate)

        list_response = self.client.get(
            "/api/admin/review/candidates",
            headers=self._admin_headers(),
        )
        self.assertEqual(list_response.status_code, 200)
        self.assertEqual(list_response.json()["total"], 1)

        approve_response = self.client.post(
            f"/api/admin/review/candidates/{candidate['candidate_id']}/approve",
            headers=self._admin_headers(),
            json={
                "reviewer": "admin",
                "fraud_type": "冒充客服",
                "subtype": "membership_fee_scam_variant",
                "title": "关闭保障服务要求下载会议软件的新变种",
                "question": "对方说关闭保障服务需要下载会议软件操作，是真的吗？",
                "answer": "这是高风险诈骗话术，应停止操作并通过官方渠道核验。",
                "warning": "凡是要求下载会议软件、共享屏幕或验证资金的，都应高度警惕。",
                "safe_actions": ["通过官方 App 核验", "不要共享屏幕", "不要转账"],
                "keywords": ["百万保障", "会议软件", "共享屏幕"],
                "comment": "确认收录为客服类新变种",
            },
        )
        self.assertEqual(approve_response.status_code, 200)
        self.assertEqual(approve_response.json()["status"], "approved")

        apply_response = self.client.post(
            "/api/admin/review/apply",
            headers=self._admin_headers(),
            json={"rebuild": False, "tag": "test_batch"},
        )
        self.assertEqual(apply_response.status_code, 200)
        self.assertEqual(apply_response.json()["applied_count"], 1)

        detail_response = self.client.get(
            f"/api/admin/review/candidates/{candidate['candidate_id']}",
            headers=self._admin_headers(),
        )
        self.assertEqual(detail_response.status_code, 200)
        self.assertEqual(detail_response.json()["status"], "applied")

        seed_payload = json.loads(self.seed_path.read_text(encoding="utf-8"))
        fraud_types = seed_payload.get("fraud_types") or []
        self.assertEqual(len(fraud_types), 1)
        self.assertEqual(fraud_types[0]["fraud_type"], "冒充客服")
        self.assertEqual(fraud_types[0]["subtypes"][0]["subtype"], "membership_fee_scam_variant")
        self.assertEqual(
            fraud_types[0]["subtypes"][0]["items"][0]["id"],
            candidate["candidate_id"],
        )


if __name__ == "__main__":
    unittest.main()
