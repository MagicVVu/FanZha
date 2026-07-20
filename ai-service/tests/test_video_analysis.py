import importlib
import sys
import types
import unittest
from io import BytesIO
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import UploadFile


def _clear_modules(module_names: list[str]) -> None:
    for module_name in module_names:
        sys.modules.pop(module_name, None)


def _stub_video_service_dependencies() -> None:
    fake_asr = types.ModuleType("app.services.asr_service")

    async def fake_transcribe_audio(path: str):
        return "", 0.0

    fake_asr.transcribe_audio = fake_transcribe_audio
    fake_asr.transcribe_audio_fast = fake_transcribe_audio

    fake_ocr = types.ModuleType("app.services.ocr_service")

    async def fake_extract_text_from_image(path: str):
        return "", 0.0

    fake_ocr.extract_text_from_image = fake_extract_text_from_image
    fake_ocr.extract_text_from_image_fast = fake_extract_text_from_image
    fake_ocr.extract_text_from_video_frame = fake_extract_text_from_image

    sys.modules["app.services.asr_service"] = fake_asr
    sys.modules["app.services.ocr_service"] = fake_ocr


def _import_video_service():
    _clear_modules(
        [
            "app.services.video_service",
            "app.services.asr_service",
            "app.services.ocr_service",
        ]
    )
    _stub_video_service_dependencies()
    return importlib.import_module("app.services.video_service")


def _import_pipeline():
    _clear_modules(
        [
            "app.services.pipeline",
            "app.services.asr_service",
            "app.services.deepseek_client",
            "app.services.kb_service",
            "app.services.ocr_service",
            "app.services.risk_engine",
            "app.services.video_service",
            "app.services.web_service",
        ]
    )

    fake_asr = types.ModuleType("app.services.asr_service")

    async def fake_transcribe_audio(path: str):
        return "", 0.0

    fake_asr.transcribe_audio = fake_transcribe_audio
    fake_asr.transcribe_audio_fast = fake_transcribe_audio

    fake_deepseek = types.ModuleType("app.services.deepseek_client")

    async def fake_llm_risk_analysis(text: str, faq_hits: list, *args, **kwargs):
        return {
            "llm_score": 0.0,
            "reason": [],
            "kb_score": 0.0,
            "reply": "",
            "next_actions": [],
        }

    fake_deepseek.llm_risk_analysis = fake_llm_risk_analysis

    async def fake_general_chat_reply(text: str, faq_hits: list, *args, **kwargs):
        return {"reply": "ok", "suggestions": [], "safe_actions": []}

    fake_deepseek.general_chat_reply = fake_general_chat_reply

    def fake_general_chat_reply_stream(text: str, faq_hits: list, *args, **kwargs):
        yield "ok"

    fake_deepseek.general_chat_reply_stream = fake_general_chat_reply_stream

    fake_kb = types.ModuleType("app.services.kb_service")
    fake_kb.search_faq = lambda text: []

    fake_ocr = types.ModuleType("app.services.ocr_service")

    async def fake_extract_text_from_image(path: str):
        return "", 0.0

    fake_ocr.extract_text_from_image = fake_extract_text_from_image
    fake_ocr.extract_text_from_image_fast = fake_extract_text_from_image
    fake_ocr.extract_text_from_video_frame = fake_extract_text_from_image

    fake_risk = types.ModuleType("app.services.risk_engine")

    def combine_probability(llm_score: float, rule_score_value: float, kb_score: float = 0.0):
        return max(0.0, min(1.0, 0.25 * llm_score + 0.60 * rule_score_value + 0.15 * kb_score))

    def combine_confidence(extract_quality: float, fraud_probability: float):
        margin = abs(fraud_probability - 0.5) * 2
        return max(0.0, min(1.0, 0.6 * extract_quality + 0.4 * margin))

    fake_risk.combine_probability = combine_probability
    fake_risk.combine_confidence = combine_confidence
    fake_risk.high_risk_override = lambda text, hits: (None, None)
    fake_risk.low_risk_override = lambda text, hits: (None, None)
    fake_risk.medium_risk_override = lambda text, hits: (None, None)
    fake_risk.risk_level = lambda prob: "high" if prob >= 0.78 else "medium" if prob >= 0.38 else "low"
    fake_risk.rule_hits_to_display = lambda hits: [f"rule:{item}" for item in hits]
    fake_risk.rule_score = lambda text: (0.0, [])

    fake_video = types.ModuleType("app.services.video_service")

    class FakeVideoProcessingError(RuntimeError):
        pass

    async def fake_extract_text_from_video(path: str, filename: str = ""):
        return "", 0.0, {"warnings": [], "errors": []}

    fake_video.VideoProcessingError = FakeVideoProcessingError
    fake_video.extract_text_from_video = fake_extract_text_from_video

    fake_web = types.ModuleType("app.services.web_service")

    async def fake_extract_text_from_website(url: str):
        return "", 0.0

    fake_web.extract_text_from_website = fake_extract_text_from_website

    sys.modules["app.services.asr_service"] = fake_asr
    sys.modules["app.services.deepseek_client"] = fake_deepseek
    sys.modules["app.services.kb_service"] = fake_kb
    sys.modules["app.services.ocr_service"] = fake_ocr
    sys.modules["app.services.risk_engine"] = fake_risk
    sys.modules["app.services.video_service"] = fake_video
    sys.modules["app.services.web_service"] = fake_web

    return importlib.import_module("app.services.pipeline")


def _make_upload(filename: str = "sample.mp4") -> UploadFile:
    return UploadFile(filename=filename, file=BytesIO(b"fake-video"))


class VideoServiceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.video_service = _import_video_service()

    async def test_extract_text_from_video_without_audio_uses_frame_ocr(self):
        with patch.object(
            self.video_service,
            "extract_audio_from_video",
            side_effect=self.video_service.VideoAudioNotFoundError("视频未检测到音轨，已跳过音频转写"),
        ), patch.object(
            self.video_service,
            "extract_keyframes_from_video",
            return_value=["frame_001.jpg", "frame_002.jpg"],
        ), patch.object(
            self.video_service,
            "extract_text_from_video_frame",
            new=AsyncMock(
                side_effect=[
                    ("退款页面显示官方客服，请点击验证", 0.88),
                    ("随后要求共享屏幕并输入验证码", 0.92),
                ]
            ),
        ):
            combined_text, extract_quality, meta = await self.video_service.extract_text_from_video(
                "demo.mp4",
                filename="demo.mp4",
            )

        self.assertIn("[视频关键帧OCR]", combined_text)
        self.assertIn("共享屏幕", combined_text)
        self.assertFalse(meta["has_audio"])
        self.assertEqual(meta["frame_count"], 2)
        self.assertGreater(meta["extracted_frame_text_len"], 0)
        self.assertGreater(extract_quality, 0.0)

    async def test_extract_text_from_video_dedups_frames_and_filters_ui_noise(self):
        with patch.object(
            self.video_service,
            "extract_audio_from_video",
            side_effect=self.video_service.VideoAudioNotFoundError("视频未检测到音轨，已跳过音频转写"),
        ), patch.object(
            self.video_service,
            "extract_keyframes_from_video",
            return_value=["frame_001.jpg", "frame_002.jpg", "frame_003.jpg"],
        ), patch.object(
            self.video_service,
            "extract_text_from_video_frame",
            new=AsyncMock(
                side_effect=[
                    ("神药骗局，七旬奶奶被神药吸引\n关注\n说点什么\n抢首评", 0.91),
                    ("神药骗局，七旬奶奶被神药吸引\n关注\n说点什么", 0.90),
                    ("最重要的是完全免费注射\n4\n关注", 0.93),
                ]
            ),
        ):
            combined_text, _, meta = await self.video_service.extract_text_from_video(
                "demo.mp4",
                filename="demo.mp4",
            )

        self.assertIn("神药骗局", combined_text)
        self.assertIn("完全免费注射", combined_text)
        self.assertNotIn("关注", combined_text)
        self.assertNotIn("说点什么", combined_text)
        self.assertEqual(meta["sampled_frame_count"], 3)
        self.assertEqual(meta["frame_count"], 2)
        self.assertEqual(meta["dropped_duplicate_frame_count"], 1)


class VideoPipelineTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pipeline = _import_pipeline()

    async def test_video_analyze_returns_structured_result(self):
        kb_hit = {
            "doc_id": "demo-1",
            "title": "保健品骗局",
            "question": "神药视频要不要信",
            "answer": "不要相信包治百病和免费注射话术。",
            "fraud_type": "保健品诈骗",
            "subtype": "fake_medicine",
            "risk_level": "high",
            "warning": "夸大疗效和免费领药是常见诱导手段",
            "safe_actions": ["停止联系", "通过正规医院核实"],
            "source": "local_seed",
            "keywords": ["神药", "免费注射"],
            "score": 0.96,
            "content": "神药骗局、免费注射、老人受骗",
            "retrieval_mode": "hybrid",
            "dense_score": 0.9,
            "lexical_score": 0.8,
            "weighted_score": 0.85,
            "rrf_score": 0.7,
            "hybrid_score": 0.88,
            "first_stage_score": 0.88,
            "second_stage_score": 0.91,
            "retrieval_pipeline": "hybrid_recall_v1__heuristic_rerank_v1",
            "candidate_sources": ["dense", "lexical"],
            "signal_terms": ["神药", "免费注射"],
            "signal_overlap_terms": ["神药"],
            "signal_overlap_count": 1,
            "signal_overlap_weight": 0.12,
            "rank_score": 0.91,
        }

        with patch.object(self.pipeline, "save_upload", new=AsyncMock(return_value="uploads/demo.mp4")), patch.object(
            self.pipeline,
            "extract_text_from_video",
            new=AsyncMock(
                return_value=(
                    "[视频音频转写]\n这款神药有病治病没病强身\n\n[视频关键帧OCR]\n[关键帧1]\n最重要的是完全免费注射",
                    0.82,
                    {"warnings": [], "errors": []},
                )
            ),
        ), patch.object(self.pipeline, "search_faq", new=MagicMock(return_value=[kb_hit])), patch.object(
            self.pipeline,
            "rule_score",
            new=MagicMock(return_value=(0.62, ["health_product"])),
        ):
            result = await self.pipeline.analyze_input(modality="video", file=_make_upload())

        self.assertEqual(result["modality"], "video")
        self.assertEqual(result["kb_hits"][0]["subtype"], "fake_medicine")
        self.assertGreater(result["fraud_probability"], 0.0)
        self.assertGreater(result["result_confidence"], 0.0)
        self.assertIn("神药", result["extracted_text"])

    async def test_video_empty_text_returns_low_confidence(self):
        with patch.object(self.pipeline, "save_upload", new=AsyncMock(return_value="uploads/empty.mp4")), patch.object(
            self.pipeline,
            "extract_text_from_video",
            new=AsyncMock(
                return_value=(
                    "",
                    0.12,
                    {"warnings": ["视频未提取到有效文本"], "errors": []},
                )
            ),
        ):
            result = await self.pipeline.analyze_input(modality="video", file=_make_upload("empty.mp4"))

        self.assertEqual(result["modality"], "video")
        self.assertEqual(result["risk_level"], "low")
        self.assertEqual(result["kb_hits"], [])
        self.assertGreater(result["result_confidence"], 0.0)
        self.assertLessEqual(result["result_confidence"], 0.2)
        self.assertIn("视频未提取到有效文本", result["reason"][0])

    async def test_video_processing_error_returns_clear_reason(self):
        with patch.object(self.pipeline, "save_upload", new=AsyncMock(return_value="uploads/broken.mp4")), patch.object(
            self.pipeline,
            "extract_text_from_video",
            new=AsyncMock(side_effect=self.pipeline.VideoProcessingError("视频无法解码")),
        ):
            result = await self.pipeline.analyze_input(modality="video", file=_make_upload("broken.mp4"))

        self.assertEqual(result["modality"], "video")
        self.assertEqual(result["fraud_probability"], 0.0)
        self.assertEqual(result["result_confidence"], 0.12)
        self.assertIn("视频分析失败", result["reason"][0])


if __name__ == "__main__":
    unittest.main()
