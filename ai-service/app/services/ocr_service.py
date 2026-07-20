import asyncio
import os
import tempfile
import time
from typing import Any

os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")

try:
    from PIL import Image, ImageOps
except Exception:
    Image = None
    ImageOps = None

from paddleocr import PaddleOCR

ocr_model_cache: dict[str, Any] = {}
ocr_model_name = None

# 默认使用稳定的回退模型，避免首次请求初始化失败。
# 只有在服务端完成移动模型预热和验证后，才设置 OCR_ENABLE_MOBILE=1。
OCR_ENABLE_MOBILE = os.getenv("OCR_ENABLE_MOBILE", "0") == "1"
OCR_FAST_MAX_SIDE = 768
OCR_FALLBACK_MAX_SIDE = 1024
OCR_FAST_JPEG_QUALITY = 70
OCR_FALLBACK_JPEG_QUALITY = 78
OCR_USE_ANGLE_CLS = False
OCR_FAST_ACCEPT_TEXT_LEN = 80
OCR_FAST_ACCEPT_CONF = 0.78
CHAT_OCR_FAST_MAX_SIDE = int(os.getenv("CHAT_OCR_FAST_MAX_SIDE", "640"))
CHAT_OCR_FAST_JPEG_QUALITY = int(os.getenv("CHAT_OCR_FAST_JPEG_QUALITY", "65"))
CHAT_OCR_ACCEPT_TEXT_LEN = int(os.getenv("CHAT_OCR_ACCEPT_TEXT_LEN", "10"))
CHAT_OCR_ACCEPT_CONF = float(os.getenv("CHAT_OCR_ACCEPT_CONF", "0.40"))
CHAT_OCR_TIMEOUT_SEC = float(os.getenv("CHAT_OCR_TIMEOUT_SEC", "8.0"))


def get_ocr_model(prefer_mobile: bool = False):
    global ocr_model_name

    if prefer_mobile:
        candidate_order = ["mobile", "fallback"]
    elif OCR_ENABLE_MOBILE:
        candidate_order = ["mobile", "fallback"]
    else:
        candidate_order = ["fallback"]

    last_exc = None

    for candidate in candidate_order:
        if candidate in ocr_model_cache:
            ocr_model_name = candidate
            return ocr_model_cache[candidate]

        if candidate == "mobile":
            mobile_kwargs = dict(
                text_detection_model_name="PP-OCRv5_mobile_det",
                text_recognition_model_name="PP-OCRv5_mobile_rec",
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )

            mobile_attempts = [
                dict(mobile_kwargs, show_log=False),
                dict(mobile_kwargs),
            ]

            for kwargs in mobile_attempts:
                try:
                    model = PaddleOCR(**kwargs)
                    ocr_model_cache["mobile"] = model
                    ocr_model_name = "mobile"
                    print("[ocr] init_model=mobile", flush=True)
                    return model
                except Exception as exc:
                    last_exc = exc
                    print(f"[ocr] mobile_init_failed: {type(exc).__name__}: {exc}", flush=True)
            continue

        fallback_attempts = [
            dict(use_angle_cls=False, lang="ch", show_log=False),
            dict(use_angle_cls=False, lang="ch"),
            dict(lang="ch"),
        ]

        for kwargs in fallback_attempts:
            try:
                model = PaddleOCR(**kwargs)
                ocr_model_cache["fallback"] = model
                ocr_model_name = "fallback"
                print("[ocr] init_model=fallback", flush=True)
                return model
            except Exception as exc:
                last_exc = exc
                print(f"[ocr] fallback_init_failed: {type(exc).__name__}: {exc}", flush=True)

    raise RuntimeError(f"Failed to initialize PaddleOCR: {last_exc}")

def _parse_legacy_result(result: Any) -> tuple[list[str], list[float]]:
    texts: list[str] = []
    confs: list[float] = []

    for block in result or []:
        if not block:
            continue
        for line in block:
            try:
                text = str(line[1][0]).strip()
                conf = float(line[1][1])
            except (IndexError, KeyError, TypeError, ValueError):
                continue

            if not text:
                continue

            texts.append(text)
            confs.append(conf)

    return texts, confs


def _parse_v3_result(result: Any) -> tuple[list[str], list[float]]:
    texts: list[str] = []
    confs: list[float] = []

    for item in result or []:
        if not isinstance(item, dict):
            continue

        rec_texts = item.get("rec_texts") or []
        rec_scores = item.get("rec_scores") or []

        for index, raw_text in enumerate(rec_texts):
            text = str(raw_text).strip()
            if not text:
                continue

            texts.append(text)

            try:
                confs.append(float(rec_scores[index]))
            except (IndexError, TypeError, ValueError):
                confs.append(0.0)

    return texts, confs


def _resample_filter():
    if Image is None:
        return None
    try:
        return Image.Resampling.LANCZOS
    except AttributeError:
        return Image.LANCZOS


def _preprocess_image(path: str, max_side: int, jpeg_quality: int) -> str:
    if Image is None or ImageOps is None:
        return path

    lower = path.lower()
    if lower.endswith(".pdf"):
        return path

    fd, out_path = tempfile.mkstemp(prefix="ocr_pre_", suffix=".jpg")
    os.close(fd)

    try:
        with Image.open(path) as img:
            img = ImageOps.exif_transpose(img)

            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")

            current_max = max(img.size)
            if current_max > max_side:
                scale = max_side / float(current_max)
                new_size = (
                    max(1, int(img.width * scale)),
                    max(1, int(img.height * scale)),
                )
                img = img.resize(new_size, _resample_filter())

            img = ImageOps.grayscale(img)
            img = ImageOps.autocontrast(img)
            img.save(out_path, format="JPEG", quality=jpeg_quality, optimize=True)

        return out_path
    except Exception:
        if os.path.exists(out_path):
            try:
                os.remove(out_path)
            except Exception:
                pass
        return path


def _run_ocr(path: str, prefer_mobile: bool = False) -> Any:
    ocr = get_ocr_model(prefer_mobile=prefer_mobile)

    try:
        return ocr.ocr(path, cls=OCR_USE_ANGLE_CLS)
    except TypeError as exc:
        if "cls" not in str(exc):
            raise

        if hasattr(ocr, "predict"):
            return ocr.predict(path)

        return ocr.ocr(path)


def _extract_from_processed_path(processed_path: str, prefer_mobile: bool = False) -> tuple[str, float, float]:
    t1 = time.time()
    result = _run_ocr(processed_path, prefer_mobile=prefer_mobile)
    ocr_cost = time.time() - t1

    if result and isinstance(result[0], dict):
        texts, confs = _parse_v3_result(result)
    else:
        texts, confs = _parse_legacy_result(result)

    full_text = "\n".join(texts)
    mean_conf = sum(confs) / len(confs) if confs else 0.0
    return full_text, mean_conf, ocr_cost


def _extract_text_from_image_sync(path: str, *, fast_only: bool = False, max_side: int | None = None, jpeg_quality: int | None = None, accept_text_len: int | None = None, accept_conf: float | None = None, prefer_mobile: bool = False):
    chosen_max_side = max_side or OCR_FAST_MAX_SIDE
    chosen_quality = jpeg_quality or OCR_FAST_JPEG_QUALITY
    chosen_accept_text_len = accept_text_len if accept_text_len is not None else OCR_FAST_ACCEPT_TEXT_LEN
    chosen_accept_conf = accept_conf if accept_conf is not None else OCR_FAST_ACCEPT_CONF

    t0 = time.time()
    fast_path = _preprocess_image(path, chosen_max_side, chosen_quality)
    fast_preprocess = time.time() - t0

    try:
        fast_text, fast_conf, fast_ocr = _extract_from_processed_path(fast_path, prefer_mobile=prefer_mobile)
        print(
            f"[ocr] fast preprocess={fast_preprocess:.3f}s ocr={fast_ocr:.3f}s text_len={len(fast_text)} conf={fast_conf:.4f} model={ocr_model_name}",
            flush=True,
        )
        if fast_only or len(fast_text) >= chosen_accept_text_len or fast_conf >= chosen_accept_conf:
            return fast_text, fast_conf
    finally:
        if fast_path != path and os.path.exists(fast_path):
            try:
                os.remove(fast_path)
            except Exception:
                pass

    t2 = time.time()
    fallback_path = _preprocess_image(path, OCR_FALLBACK_MAX_SIDE, OCR_FALLBACK_JPEG_QUALITY)
    fallback_preprocess = time.time() - t2

    try:
        text, conf, ocr_cost = _extract_from_processed_path(fallback_path, prefer_mobile=prefer_mobile)
        print(
            f"[ocr] fallback preprocess={fallback_preprocess:.3f}s ocr={ocr_cost:.3f}s text_len={len(text)} conf={conf:.4f} model={ocr_model_name}",
            flush=True,
        )
        return text, conf
    finally:
        if fallback_path != path and os.path.exists(fallback_path):
            try:
                os.remove(fallback_path)
            except Exception:
                pass


async def extract_text_from_image(path: str):
    return await asyncio.to_thread(_extract_text_from_image_sync, path)


async def extract_text_from_image_fast(path: str):
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(
                _extract_text_from_image_sync,
                path,
                fast_only=True,
                max_side=CHAT_OCR_FAST_MAX_SIDE,
                jpeg_quality=CHAT_OCR_FAST_JPEG_QUALITY,
                accept_text_len=CHAT_OCR_ACCEPT_TEXT_LEN,
                accept_conf=CHAT_OCR_ACCEPT_CONF,
                prefer_mobile=True,
            ),
            timeout=CHAT_OCR_TIMEOUT_SEC,
        )
    except asyncio.TimeoutError:
        print(f"[ocr][chat_fast] timeout after {CHAT_OCR_TIMEOUT_SEC:.1f}s", flush=True)
        return "", 0.12
VIDEO_OCR_FAST_ACCEPT_TEXT_LEN = 12
VIDEO_OCR_FAST_ACCEPT_CONF = 0.55


async def extract_text_from_video_frame(path: str):
    prefer_mobile = OCR_ENABLE_MOBILE
    t0 = time.time()
    fast_path = _preprocess_image(path, OCR_FAST_MAX_SIDE, OCR_FAST_JPEG_QUALITY)
    fast_preprocess = time.time() - t0

    try:
        fast_text, fast_conf, fast_ocr = _extract_from_processed_path(fast_path, prefer_mobile=prefer_mobile)
        print(
            f"[ocr][video] fast preprocess={fast_preprocess:.3f}s "
            f"ocr={fast_ocr:.3f}s text_len={len(fast_text)} conf={fast_conf:.4f} "
            f"model={ocr_model_name}",
            flush=True,
        )

        if len(fast_text.strip()) >= VIDEO_OCR_FAST_ACCEPT_TEXT_LEN or fast_conf >= VIDEO_OCR_FAST_ACCEPT_CONF:
            return fast_text, fast_conf
    finally:
        if fast_path != path and os.path.exists(fast_path):
            try:
                os.remove(fast_path)
            except Exception:
                pass

    t2 = time.time()
    fallback_path = _preprocess_image(path, OCR_FALLBACK_MAX_SIDE, OCR_FALLBACK_JPEG_QUALITY)
    fallback_preprocess = time.time() - t2

    try:
        text, conf, ocr_cost = _extract_from_processed_path(fallback_path, prefer_mobile=prefer_mobile)
        print(
            f"[ocr][video] fallback preprocess={fallback_preprocess:.3f}s "
            f"ocr={ocr_cost:.3f}s text_len={len(text)} conf={conf:.4f} "
            f"model={ocr_model_name}",
            flush=True,
        )
        return text, conf
    finally:
        if fallback_path != path and os.path.exists(fallback_path):
            try:
                os.remove(fallback_path)
            except Exception:
                pass
