import argparse
import json
import os
import re
import sys
import traceback

from app.services.asr_service import transcribe_audio_fast_sync, transcribe_audio_sync


def _is_meaningful_text(text: str) -> bool:
    normalized = re.sub(r"\s+", "", str(text or "")).strip()
    if len(normalized) >= 60:
        return True
    return any(k in normalized for k in ["抖音", "客服", "保险", "收费", "转账", "验证码", "安全账户"])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("audio_path")
    parser.add_argument("result_path")
    parser.add_argument("--fast", action="store_true")
    args = parser.parse_args()

    os.makedirs(os.path.dirname(args.result_path) or ".", exist_ok=True)

    payload = {
        "ok": False,
        "text": "",
        "quality": 0.0,
        "error": "",
    }

    try:
        if args.fast:
            text, quality = transcribe_audio_fast_sync(args.audio_path)
            if (not _is_meaningful_text(text)) or float(quality or 0.0) < 0.85:
                text, quality = transcribe_audio_sync(args.audio_path)
        else:
            text, quality = transcribe_audio_sync(args.audio_path)

        payload["ok"] = True
        payload["text"] = text or ""
        payload["quality"] = float(quality or 0.0)
    except BaseException as exc:
        payload["ok"] = False
        payload["error"] = f"{type(exc).__name__}: {exc}"
        traceback.print_exc()

    try:
        with open(args.result_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception:
        return 2

    return 0 if payload["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
