import importlib
import sys
import types
import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient


class AssistantDualRoutesTest(unittest.TestCase):
    def setUp(self):
        sys.modules.pop("app.routers.assistant", None)

        fake_pipeline = types.ModuleType("app.services.pipeline")

        async def fake_chat_assistant(session_id: str, message: str):
            return {
                "session_id": session_id or "session-test",
                "reply": f"已收到：{message}",
                "safe_actions": ["不要转账"],
                "kb_hits": [],
                "suggestions": ["补充对方是否索要验证码。"],
            }

        def fake_stream_chat_assistant(message: str, session_id: str):
            yield 'event: start\ndata: {"type":"start"}\n\n'
            yield 'event: delta\ndata: {"type":"delta","content":"已收到"}\n\n'
            yield f'event: done\ndata: {{"type":"done","reply":"已收到：{message}"}}\n\n'

        async def fake_chat_with_attachment(**kwargs):
            return {
                "session_id": kwargs.get("session_id") or "session-test",
                "reply": "附件已分析",
                "safe_actions": [],
                "kb_hits": [],
                "suggestions": [],
            }

        def fake_stream_chat_with_attachment(**kwargs):
            yield 'event: start\ndata: {"type":"start"}\n\n'
            yield 'event: done\ndata: {"type":"done","reply":"附件已分析"}\n\n'

        async def fake_analyze_input(modality: str, text: str = "", url: str = "", file=None):
            return {
                "modality": modality,
                "fraud_probability": 0.86,
                "result_confidence": 0.88,
                "risk_level": "high",
                "reason": ["出现中奖领奖前先交费话术"],
                "extracted_text": text,
                "kb_hits": [],
                "safe_actions": ["不要转账"],
                "reply": "建议先暂停操作，并通过官方渠道核实。",
                "next_actions": ["保留短信记录"],
            }

        fake_pipeline.chat_assistant = fake_chat_assistant
        fake_pipeline.stream_chat_assistant = fake_stream_chat_assistant
        fake_pipeline.chat_with_attachment = fake_chat_with_attachment
        fake_pipeline.stream_chat_with_attachment = fake_stream_chat_with_attachment
        fake_pipeline.analyze_input = fake_analyze_input
        async def fake_check_sms_message(**kwargs):
            return {"sender": kwargs.get("sender", "")}

        async def fake_generate_report_advice(payload):
            return {"report_type": payload.report_type, "suggestions": []}

        fake_pipeline.check_sms_message = fake_check_sms_message
        fake_pipeline.generate_report_advice = fake_generate_report_advice
        self.original_pipeline = sys.modules.get("app.services.pipeline")
        sys.modules["app.services.pipeline"] = fake_pipeline

        assistant_router = importlib.import_module("app.routers.assistant")
        app = FastAPI()
        app.include_router(assistant_router.router, prefix="/api/assistant")
        self.client = TestClient(app)

    def tearDown(self):
        sys.modules.pop("app.routers.assistant", None)
        if self.original_pipeline is None:
            sys.modules.pop("app.services.pipeline", None)
        else:
            sys.modules["app.services.pipeline"] = self.original_pipeline

    def test_chat_route_returns_reply(self):
        response = self.client.post(
            "/api/assistant/chat",
            json={"message": "这个短信可信吗"},
        )

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("reply", data)
        self.assertEqual(data["safe_actions"], ["不要转账"])

    def test_chat_stream_route_returns_sse(self):
        response = self.client.post(
            "/api/assistant/chat/stream",
            json={"message": "你好"},
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn("text/event-stream", response.headers["content-type"])
        self.assertIn("event: delta", response.text)
        self.assertIn('"type":"done"', response.text)

    def test_analyze_route_returns_core_metrics(self):
        response = self.client.post(
            "/api/assistant/analyze",
            data={
                "modality": "text",
                "text": "短信告诉我我中了一百万彩票，先交一万块钱激活。",
            },
        )

        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["fraud_probability"], 0.86)
        self.assertEqual(data["result_confidence"], 0.88)
        self.assertEqual(data["risk_level"], "high")

if __name__ == "__main__":
    unittest.main()
