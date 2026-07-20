import asyncio
import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from threading import Lock

from faster_whisper import WhisperModel

whisper_model = None
whisper_model_key = None
_model_lock = Lock()

# 默认改回 small，CPU 机器先求速度与稳定
WHISPER_MODEL_DIR = os.getenv(
    "WHISPER_MODEL_DIR",
    "/opt/anti_fraud_ai/models/faster-whisper-small",
)
WHISPER_FAST_MODEL_DIR = os.getenv(
    "WHISPER_FAST_MODEL_DIR",
    "/opt/anti_fraud_ai/models/faster-whisper-small",
)
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")

ASR_TEMP_DIR = os.path.join("uploads", "asr_artifacts")
ASR_FFMPEG_TIMEOUT_SEC = int(os.getenv("ASR_FFMPEG_TIMEOUT_SEC", "60"))
ASR_MAX_AUDIO_SECONDS = float(os.getenv("ASR_MAX_AUDIO_SECONDS", "30"))
ASR_FAST_MAX_AUDIO_SECONDS = float(os.getenv("ASR_FAST_MAX_AUDIO_SECONDS", "12"))
ASR_PASS1_CONF_THRESHOLD = float(os.getenv("ASR_PASS1_CONF_THRESHOLD", "0.45"))
ASR_MEANINGFUL_QUALITY_FLOOR = float(os.getenv("ASR_MEANINGFUL_QUALITY_FLOOR", "0.55"))
ASR_FAST_ALLOW_PASS2 = os.getenv("ASR_FAST_ALLOW_PASS2", "0") == "1"

ASR_PASS1_BEAM_SIZE = int(os.getenv("ASR_PASS1_BEAM_SIZE", "1"))
ASR_PASS1_BEST_OF = int(os.getenv("ASR_PASS1_BEST_OF", "1"))
ASR_PASS2_BEAM_SIZE = int(os.getenv("ASR_PASS2_BEAM_SIZE", "3"))
ASR_PASS2_BEST_OF = int(os.getenv("ASR_PASS2_BEST_OF", "3"))

ASR_DOMAIN_PROMPT = os.getenv(
    "ASR_DOMAIN_PROMPT",
    "验证码 转账 打款 客服 退款 理赔 公安 法院 安全账户 共享屏幕 投资群 刷单 退款 账户异常 百万保险 抖音客服 自动扣费 取消扣费",
)

os.makedirs(ASR_TEMP_DIR, exist_ok=True)


def get_whisper_model(model_dir: str | None = None):
    global whisper_model, whisper_model_key

    resolved_model_dir = model_dir or WHISPER_MODEL_DIR
    model_key = (resolved_model_dir, WHISPER_DEVICE, WHISPER_COMPUTE_TYPE)

    with _model_lock:
        if whisper_model is not None and whisper_model_key == model_key:
            return whisper_model, 0.0, True

        if not os.path.isdir(resolved_model_dir):
            raise FileNotFoundError(f"Whisper local model not found: {resolved_model_dir}")

        t0 = time.time()
        whisper_model = WhisperModel(
            resolved_model_dir,
            device=WHISPER_DEVICE,
            compute_type=WHISPER_COMPUTE_TYPE,
        )
        init_model_cost = time.time() - t0
        whisper_model_key = model_key

        print(
            f"[asr] init_model path={resolved_model_dir} "
            f"device={WHISPER_DEVICE} compute_type={WHISPER_COMPUTE_TYPE} "
            f"init_model_cost={init_model_cost:.3f}s cache_hit=False",
            flush=True,
        )

        return whisper_model, init_model_cost, False


def _join_segments(segments):
    texts = [seg.text.strip() for seg in segments if getattr(seg, "text", None) and seg.text.strip()]
    return " ".join(texts).strip()


def _normalize_text(text: str) -> str:
    return re.sub(r"\s+", "", str(text or "")).strip()


def _is_meaningful_text(text: str) -> bool:
    normalized = _normalize_text(text)
    if len(normalized) >= 6:
        return True
    chinese_count = sum(1 for ch in normalized if "\u4e00" <= ch <= "\u9fff")
    return chinese_count >= 4


def _normalize_audio_for_asr(src_path: str, max_audio_seconds: float) -> tuple[str, dict]:
    temp_dir = tempfile.mkdtemp(prefix="asr_", dir=ASR_TEMP_DIR)
    out_path = os.path.join(temp_dir, f"{Path(src_path).stem}_norm.wav")

    command = [
        "ffmpeg",
        "-y",
        "-i",
        src_path,
        "-vn",
    ]

    if max_audio_seconds > 0:
        command.extend(["-t", f"{max_audio_seconds:g}"])

    command.extend(
        [
            "-ac",
            "1",
            "-ar",
            "16000",
            "-af",
            "highpass=f=120,lowpass=f=7600,loudnorm=I=-16:TP=-1.5:LRA=11",
            "-c:a",
            "pcm_s16le",
            out_path,
        ]
    )

    ffmpeg_t0 = time.time()
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=ASR_FFMPEG_TIMEOUT_SEC,
        )
    except FileNotFoundError as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise RuntimeError("系统未安装 ffmpeg，无法进行 ASR 音频预处理") from exc
    except subprocess.TimeoutExpired as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise RuntimeError("音频预处理超时") from exc

    ffmpeg_cost = time.time() - ffmpeg_t0

    if completed.returncode != 0 or not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
        stderr = (completed.stderr or "").strip()[:500]
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise RuntimeError(f"音频预处理失败：{stderr or 'unknown ffmpeg error'}")

    meta = {
        "ffmpeg_cost": ffmpeg_cost,
        "limited_seconds": max_audio_seconds,
        "normalized_size_kb": round(os.path.getsize(out_path) / 1024.0, 2),
    }
    return out_path, meta


def _transcribe_once(
    model: WhisperModel,
    path: str,
    *,
    language: str | None,
    vad_filter: bool,
    beam_size: int,
    best_of: int,
):
    kwargs = dict(
        language=language,
        vad_filter=vad_filter,
        beam_size=beam_size,
        best_of=best_of,
        temperature=0.0,
        condition_on_previous_text=False,
        initial_prompt=ASR_DOMAIN_PROMPT,
    )
    if vad_filter:
        kwargs["vad_parameters"] = {
            "min_silence_duration_ms": 350,
            "speech_pad_ms": 200,
        }

    transcribe_t0 = time.time()
    segments, info = model.transcribe(path, **kwargs)
    transcribe_cost = time.time() - transcribe_t0

    decode_t0 = time.time()
    segments = list(segments)
    decode_cost = time.time() - decode_t0

    full_text = _join_segments(segments)
    lang_prob = float(getattr(info, "language_probability", 0.0) or 0.0)

    metrics = {
        "transcribe_cost": transcribe_cost,
        "decode_cost": decode_cost,
        "pass_cost": transcribe_cost + decode_cost,
        "segment_count": len(segments),
    }

    return full_text, lang_prob, info, metrics


def _pick_better_candidate(a: tuple[str, float], b: tuple[str, float]) -> tuple[str, float]:
    text_a, conf_a = a
    text_b, conf_b = b

    score_a = (
        (1.0 if _is_meaningful_text(text_a) else 0.0)
        + min(len(_normalize_text(text_a)), 80) / 80.0
        + conf_a
    )
    score_b = (
        (1.0 if _is_meaningful_text(text_b) else 0.0)
        + min(len(_normalize_text(text_b)), 80) / 80.0
        + conf_b
    )

    return b if score_b > score_a else a


def _transcribe_audio_sync(
    path: str,
    model_dir: str | None = None,
    max_audio_seconds: float = ASR_MAX_AUDIO_SECONDS,
    allow_pass2: bool = True,
):
    total_t0 = time.time()
    normalized_path = None

    resolved_model_dir = model_dir or WHISPER_MODEL_DIR
    model, init_model_cost, cache_hit = get_whisper_model(resolved_model_dir)

    try:
        preprocess_t0 = time.time()
        normalized_path, preprocess_meta = _normalize_audio_for_asr(path, max_audio_seconds)
        preprocess_cost = time.time() - preprocess_t0

        text1, conf1, _info1, metrics1 = _transcribe_once(
            model,
            normalized_path,
            language="zh",
            vad_filter=False,
            beam_size=ASR_PASS1_BEAM_SIZE,
            best_of=ASR_PASS1_BEST_OF,
        )
        print(
            f"[asr] pass1 path={normalized_path} vad=False language=zh "
            f"text_len={len(text1)} language_prob={conf1:.4f} "
            f"transcribe_cost={metrics1['transcribe_cost']:.3f}s "
            f"decode_cost={metrics1['decode_cost']:.3f}s "
            f"pass_cost={metrics1['pass_cost']:.3f}s "
            f"segments={metrics1['segment_count']} "
            f"text={text1}",
            flush=True,
        )

        best = (text1, conf1)

        need_pass2 = allow_pass2 and ((not _is_meaningful_text(text1)) or (conf1 < ASR_PASS1_CONF_THRESHOLD))
        if need_pass2:
            text2, conf2, _info2, metrics2 = _transcribe_once(
                model,
                normalized_path,
                language=None,
                vad_filter=True,
                beam_size=ASR_PASS2_BEAM_SIZE,
                best_of=ASR_PASS2_BEST_OF,
            )
            print(
                f"[asr] pass2 path={normalized_path} vad=True language=auto "
                f"text_len={len(text2)} language_prob={conf2:.4f} "
                f"transcribe_cost={metrics2['transcribe_cost']:.3f}s "
                f"decode_cost={metrics2['decode_cost']:.3f}s "
                f"pass_cost={metrics2['pass_cost']:.3f}s "
                f"segments={metrics2['segment_count']} "
                f"text={text2}",
                flush=True,
            )
            best = _pick_better_candidate(best, (text2, conf2))

        full_text, quality = best

        if _is_meaningful_text(full_text):
            quality = max(quality, ASR_MEANINGFUL_QUALITY_FLOOR)

        total_cost = time.time() - total_t0

        print(
            f"[asr] transcribe_done path={path} model_path={resolved_model_dir} "
            f"model_cache_hit={cache_hit} init_model_cost={init_model_cost:.3f}s "
            f"preprocess_cost={preprocess_cost:.3f}s ffmpeg_cost={preprocess_meta['ffmpeg_cost']:.3f}s "
            f"audio_limit={preprocess_meta['limited_seconds']}s "
            f"normalized_size_kb={preprocess_meta['normalized_size_kb']} "
            f"total_cost={total_cost:.3f}s text_len={len(full_text)} quality={quality:.4f}",
            flush=True,
        )

        return full_text, float(max(0.0, min(1.0, quality)))

    finally:
        if normalized_path:
            shutil.rmtree(str(Path(normalized_path).parent), ignore_errors=True)


async def transcribe_audio(path: str):
    return await asyncio.to_thread(_transcribe_audio_sync, path)


async def transcribe_audio_fast(path: str):
    return await asyncio.to_thread(
        _transcribe_audio_sync,
        path,
        WHISPER_FAST_MODEL_DIR,
        ASR_FAST_MAX_AUDIO_SECONDS,
        ASR_FAST_ALLOW_PASS2,
    )


def transcribe_audio_sync(path: str):
    return _transcribe_audio_sync(path)


def transcribe_audio_fast_sync(path: str):
    return _transcribe_audio_sync(
        path,
        WHISPER_FAST_MODEL_DIR,
        ASR_FAST_MAX_AUDIO_SECONDS,
        ASR_FAST_ALLOW_PASS2,
    )