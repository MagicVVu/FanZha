import asyncio
import glob
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from app.services.asr_service import transcribe_audio, transcribe_audio_fast
from app.services.ocr_service import extract_text_from_video_frame

VIDEO_MAX_DURATION_SEC = 60
VIDEO_MAX_DISTINCT_OCR_FRAMES = 8
VIDEO_TEMP_DIR = os.path.join("uploads", "video_artifacts")

VIDEO_MAX_OCR_FRAMES = 6
VIDEO_AUDIO_GOOD_ENOUGH_LEN = 18
VIDEO_FFMPEG_TIMEOUT_SEC = 45

VIDEO_UI_NOISE_MARKERS = {
    "关注",
    "说点什么",
    "抢首评",
    "直播间",
    "直播",
    "评论",
    "转发",
    "收藏",
    "点赞",
    "粉丝",
    "首页",
    "私信",
    "橱窗",
    "购物车",
    "商品",
    "小店",
    "资料画面",
}

VIDEO_RISK_KEEP_MARKERS = {
    "骗局",
    "诈骗",
    "神药",
    "保健品",
    "免费",
    "注射",
    "治病",
    "强身",
    "老人",
    "奶奶",
    "会议",
    "养生",
    "药品",
}

os.makedirs(VIDEO_TEMP_DIR, exist_ok=True)


class VideoProcessingError(RuntimeError):
    pass


class VideoAudioNotFoundError(VideoProcessingError):
    pass


class VideoDecodeError(VideoProcessingError):
    pass


class VideoFrameExtractionError(VideoProcessingError):
    pass


def _clamp_quality(value: float, default: float = 0.0) -> float:
    try:
        return max(0.0, min(1.0, float(value)))
    except (TypeError, ValueError):
        return default


def _detect_file_type(path: str, filename: str = "") -> str:
    suffix = Path(filename or path).suffix.lower().lstrip(".")
    return suffix or "video"


def _normalize_compare_text(text: str) -> str:
    return re.sub(r"[\W_]+", "", str(text or ""))


def _contains_risk_keep_marker(text: str) -> bool:
    return any(marker in text for marker in VIDEO_RISK_KEEP_MARKERS)


def _is_noise_line(line: str) -> bool:
    candidate = str(line or "").strip()
    if not candidate:
        return True

    candidate = re.sub(r"\s+", "", candidate)
    candidate = re.sub(r"#\S+", "", candidate).strip("，。；;：:,.!?！？·")
    if not candidate:
        return True

    if len(candidate) <= 1:
        return True

    if candidate.isdigit():
        return True

    if re.fullmatch(r"[A-Za-z0-9]{1,4}", candidate):
        return True

    if _contains_risk_keep_marker(candidate):
        return False

    if any(marker in candidate for marker in VIDEO_UI_NOISE_MARKERS):
        return True

    if candidate.endswith(("小店", "橱窗", "首页")) and len(candidate) <= 8:
        return True

    return False


def _clean_frame_lines(raw_text: str) -> list[str]:
    lines: list[str] = []
    seen = set()

    for raw_line in str(raw_text or "").splitlines():
        candidate = str(raw_line).strip()
        candidate = re.sub(r"#\S+", "", candidate).strip("，。；;：:,.!?！？·")
        if _is_noise_line(candidate):
            continue

        fingerprint = _normalize_compare_text(candidate)
        if not fingerprint or fingerprint in seen:
            continue

        seen.add(fingerprint)
        lines.append(candidate)

    return lines


def _is_meaningful_text(text: str) -> bool:
    normalized = _normalize_compare_text(text)
    if len(normalized) >= 6:
        return True
    chinese_count = sum(1 for ch in normalized if "\u4e00" <= ch <= "\u9fff")
    return chinese_count >= 4


def _probe_video_duration(path: str) -> float:
    command = [
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        path,
    ]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )
        if completed.returncode != 0:
            return 0.0
        return max(0.0, float((completed.stdout or "0").strip() or 0.0))
    except Exception:
        return 0.0


def _build_frame_plan(duration_sec: float) -> tuple[int, int]:
    duration_sec = min(max(duration_sec, 0.0), float(VIDEO_MAX_DURATION_SEC))

    if duration_sec <= 15:
        return 4, 4
    if duration_sec <= 30:
        return 5, 5
    return 6, VIDEO_MAX_OCR_FRAMES


def _select_evenly_spaced(items: list[str], limit: int) -> list[str]:
    if len(items) <= limit:
        return items

    if limit <= 1:
        return items[:1]

    last = len(items) - 1
    indexes = [round(i * last / (limit - 1)) for i in range(limit)]

    result = []
    seen = set()
    for idx in indexes:
        if idx in seen:
            continue
        seen.add(idx)
        result.append(items[idx])

    return result


def extract_audio_from_video(path: str) -> str:
    temp_dir = tempfile.mkdtemp(prefix="video_audio_", dir=VIDEO_TEMP_DIR)
    audio_path = os.path.join(temp_dir, f"{Path(path).stem}_audio.wav")

    command = [
        "ffmpeg",
        "-y",
        "-i",
        path,
        "-map",
        "0:a:0?",
        "-vn",
        "-ac",
        "1",
        "-ar",
        "16000",
        "-af",
        "highpass=f=120,lowpass=f=7600,loudnorm=I=-16:TP=-1.5:LRA=11",
        "-c:a",
        "pcm_s16le",
        audio_path,
    ]

    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=VIDEO_FFMPEG_TIMEOUT_SEC,
        )
    except FileNotFoundError as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise VideoProcessingError("系统未安装 ffmpeg，无法提取视频音轨") from exc
    except subprocess.TimeoutExpired as exc:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise VideoProcessingError("视频音轨提取超时") from exc

    if completed.returncode != 0:
        stderr = (completed.stderr or "").lower()
        shutil.rmtree(temp_dir, ignore_errors=True)

        no_audio_markers = [
            "does not contain any stream",
            "stream map",
            "output file #0 does not contain any stream",
        ]
        if any(marker in stderr for marker in no_audio_markers):
            raise VideoAudioNotFoundError("视频未检测到音轨，已跳过音频转写")

        raise VideoDecodeError("视频音轨提取失败，可能文件损坏或编码不受支持")

    if not os.path.exists(audio_path) or os.path.getsize(audio_path) == 0:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise VideoAudioNotFoundError("视频未检测到音轨，已跳过音频转写")

    return audio_path


def extract_keyframes_from_video(
    path: str,
    output_dir: str,
    duration_sec: float = 0.0,
) -> list[str]:
    os.makedirs(output_dir, exist_ok=True)

    interval_sec, max_frames = _build_frame_plan(duration_sec)
    fps_value = 1.0 / max(interval_sec, 1)

    output_pattern = os.path.join(output_dir, "frame_%03d.jpg")
    command = [
        "ffmpeg",
        "-y",
        "-i",
        path,
        "-vf",
        f"fps={fps_value:.6f}",
        "-frames:v",
        str(max_frames),
        "-q:v",
        "5",
        output_pattern,
    ]

    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=VIDEO_FFMPEG_TIMEOUT_SEC,
        )
    except FileNotFoundError as exc:
        raise VideoProcessingError("系统未安装 ffmpeg，无法抽取视频关键帧") from exc
    except subprocess.TimeoutExpired as exc:
        raise VideoFrameExtractionError("视频关键帧抽取超时") from exc

    frame_paths = sorted(glob.glob(os.path.join(output_dir, "frame_*.jpg")))
    if frame_paths:
        return frame_paths

    stderr = (completed.stderr or "").strip()[:500]
    raise VideoFrameExtractionError(f"未能从视频中抽取关键帧：{stderr or 'unknown ffmpeg error'}")


async def extract_text_from_video(path: str, filename: str = "") -> tuple[str, float, dict[str, Any]]:
    working_dir = tempfile.mkdtemp(prefix="video_extract_", dir=VIDEO_TEMP_DIR)
    frame_dir = os.path.join(working_dir, "frames")
    audio_path = None

    meta: dict[str, Any] = {
        "file_type": _detect_file_type(path, filename),
        "duration_sec": 0.0,
        "frame_count": 0,
        "sampled_frame_count": 0,
        "dropped_duplicate_frame_count": 0,
        "has_audio": False,
        "extracted_audio_text_len": 0,
        "extracted_frame_text_len": 0,
        "warnings": [],
        "errors": [],
    }

    audio_text = ""
    frame_text_block = ""
    quality_parts: list[float] = []

    try:
        duration_sec = await asyncio.to_thread(_probe_video_duration, path)
        duration_sec = min(duration_sec, float(VIDEO_MAX_DURATION_SEC))
        meta["duration_sec"] = round(duration_sec, 2)

        audio_result, frame_result = await asyncio.gather(
            asyncio.to_thread(extract_audio_from_video, path),
            asyncio.to_thread(extract_keyframes_from_video, path, frame_dir, duration_sec),
            return_exceptions=True,
        )

        if isinstance(audio_result, Exception):
            if isinstance(audio_result, VideoAudioNotFoundError):
                meta["warnings"].append(str(audio_result))
            elif isinstance(audio_result, VideoProcessingError):
                meta["errors"].append(str(audio_result))
            else:
                meta["errors"].append(f"视频音频提取失败：{audio_result}")
        else:
            audio_path = audio_result
            meta["has_audio"] = True
            try:
                raw_audio_text, audio_quality = await transcribe_audio_fast(audio_path)
                audio_text = (raw_audio_text or "").strip()
                retry_normal = (
                    len(re.sub(r"\s+", "", audio_text)) < 60
                    or _clamp_quality(audio_quality, default=0.0) < 0.85
                    or not any(k in audio_text for k in ["抖音", "客服", "保险", "收费", "转账", "验证码", "安全账户"])
                )
                if retry_normal:
                    raw_audio_text, audio_quality = await transcribe_audio(audio_path)
                    audio_text = (raw_audio_text or "").strip()

                meta["extracted_audio_text_len"] = len(audio_text)

                if audio_text:
                    quality_parts.append(_clamp_quality(audio_quality, default=0.65))
                else:
                    meta["warnings"].append("视频音轨已提取，但未转写出有效文本")
            except Exception as exc:
                meta["errors"].append(f"视频音频转写失败：{exc}")

        frame_paths: list[str] = []
        if isinstance(frame_result, Exception):
            if isinstance(frame_result, VideoProcessingError):
                meta["errors"].append(str(frame_result))
            else:
                meta["errors"].append(f"关键帧抽取失败：{frame_result}")
        else:
            frame_paths = frame_result
            meta["sampled_frame_count"] = len(frame_paths)

        if _is_meaningful_text(audio_text) and len(_normalize_compare_text(audio_text)) >= VIDEO_AUDIO_GOOD_ENOUGH_LEN:
            frame_paths = _select_evenly_spaced(frame_paths, 3)
        else:
            frame_paths = _select_evenly_spaced(frame_paths, VIDEO_MAX_OCR_FRAMES)

        frame_text_sections: list[str] = []
        frame_quality_parts: list[float] = []
        seen_line_fingerprints: set[str] = set()

        for index, frame_path in enumerate(frame_paths, start=1):
            if len(frame_text_sections) >= VIDEO_MAX_DISTINCT_OCR_FRAMES:
                meta["warnings"].append(
                    f"关键帧文本变化片段较多，已仅保留前 {VIDEO_MAX_DISTINCT_OCR_FRAMES} 个有效片段"
                )
                break

            try:
                raw_frame_text, frame_quality = await extract_text_from_video_frame(frame_path)
            except Exception as exc:
                print(f"[video_ocr] frame_{index}_failed: {type(exc).__name__}: {exc}", flush=True)
                continue

            cleaned_lines = _clean_frame_lines(raw_frame_text)
            if not cleaned_lines:
                continue

            novel_lines: list[str] = []
            for line in cleaned_lines:
                fingerprint = _normalize_compare_text(line)
                if not fingerprint or fingerprint in seen_line_fingerprints:
                    continue
                seen_line_fingerprints.add(fingerprint)
                novel_lines.append(line)

            if not novel_lines:
                continue

            frame_text_sections.append(
                f"[关键帧{len(frame_text_sections) + 1}]\n" + "\n".join(novel_lines)
            )
            frame_quality_parts.append(_clamp_quality(frame_quality, default=0.55))

        meta["frame_count"] = len(frame_text_sections)
        meta["dropped_duplicate_frame_count"] = max(
            0,
            meta["sampled_frame_count"] - meta["frame_count"],
        )

        frame_text_block = "\n\n".join(frame_text_sections).strip()
        meta["extracted_frame_text_len"] = len(frame_text_block)

        if frame_text_block:
            mean_frame_quality = (
                sum(frame_quality_parts) / len(frame_quality_parts)
                if frame_quality_parts
                else 0.55
            )
            quality_parts.append(mean_frame_quality)
        elif frame_paths:
            meta["warnings"].append("已抽取关键帧，但未识别出有效文字")

        combined_sections: list[str] = []
        if audio_text:
            combined_sections.append(f"[视频音频转写]\n{audio_text}")
        if frame_text_block:
            combined_sections.append(f"[视频关键帧OCR]\n{frame_text_block}")

        combined_text = "\n\n".join(combined_sections).strip()

        if not combined_text and meta["errors"]:
            raise VideoProcessingError("；".join(meta["errors"][:2]))

        if not combined_text:
            meta["warnings"].append("视频未提取到有效文本")
            return "", 0.12, meta

        extract_quality = (
            sum(quality_parts) / len(quality_parts)
            if quality_parts
            else 0.65
        )

        if meta["warnings"]:
            extract_quality *= 0.94
        if meta["errors"]:
            extract_quality *= 0.88

        return combined_text, _clamp_quality(extract_quality, default=0.65), meta

    finally:
        if audio_path:
            shutil.rmtree(str(Path(audio_path).parent), ignore_errors=True)
        shutil.rmtree(working_dir, ignore_errors=True)
