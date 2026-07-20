import argparse
import csv
import json
import mimetypes
from collections import defaultdict
from pathlib import Path

import httpx

DEFAULT_API_URL = "http://127.0.0.1:8000/api/assistant/analyze"
DEFAULT_INPUT_FILE = Path("tests/test_cases_120.json")
DEFAULT_OUTPUT_FILE = Path("tests/test_results.csv")
DEFAULT_FAILED_FILE = Path("tests/failed_cases.csv")

SUPPORTED_MODALITIES = {"text", "image", "audio", "video", "website"}
FILE_MODALITIES = {"image", "audio", "video"}

RESULT_FIELDNAMES = [
    "id",
    "modality",
    "request_modality",
    "type",
    "fraud_type",
    "subtype",
    "binary_label",
    "expected_label",
    "predicted_label",
    "fraud_probability",
    "result_confidence",
    "reason",
    "correct",
    "file_path",
    "url",
    "text",
    "extracted_text",
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Batch-evaluate the assistant analyze API with text, audio, image, video, "
            "or website cases from a JSON dataset."
        )
    )
    parser.add_argument(
        "--api-url",
        default=DEFAULT_API_URL,
        help=f"Analyze API endpoint. Default: {DEFAULT_API_URL}",
    )
    parser.add_argument(
        "--input",
        type=Path,
        default=DEFAULT_INPUT_FILE,
        help=f"Input JSON file. Default: {DEFAULT_INPUT_FILE}",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_FILE,
        help=f"Output CSV file. Default: {DEFAULT_OUTPUT_FILE}",
    )
    parser.add_argument(
        "--failed-output",
        type=Path,
        default=DEFAULT_FAILED_FILE,
        help=f"CSV file for failed cases. Default: {DEFAULT_FAILED_FILE}",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="Request timeout in seconds. Default: 60.0",
    )
    return parser


def is_prediction_correct(expected_label: str, predicted_label: str) -> bool:
    return expected_label == predicted_label


def format_reason(reason_value) -> str:
    if isinstance(reason_value, list):
        return " | ".join(str(x) for x in reason_value)
    if isinstance(reason_value, str):
        return reason_value
    return ""


def ensure_parent_dir(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def read_cases(input_file: Path) -> list[dict]:
    if not input_file.exists():
        raise FileNotFoundError(f"找不到测试文件: {input_file}")

    with input_file.open("r", encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        raise ValueError("测试文件必须是 JSON 数组，每个元素代表一条测试样本。")

    return data


def normalize_case_value(case: dict, key: str, default: str = "") -> str:
    return str(case.get(key, default) or default).strip()


def normalize_text_payload(case: dict) -> str:
    for key in ("text", "transcript", "content", "prompt"):
        value = normalize_case_value(case, key)
        if value:
            return value
    return ""


def resolve_case_file(input_file: Path, raw_path: str) -> Path | None:
    if not raw_path:
        return None

    candidate = Path(raw_path)
    candidates = []
    if candidate.is_absolute():
        candidates.append(candidate)
    else:
        repo_root = Path(__file__).resolve().parent.parent
        candidates.extend(
            [
                input_file.parent / candidate,
                repo_root / candidate,
                Path.cwd() / candidate,
            ]
        )

    for item in candidates:
        if item.exists():
            return item.resolve()

    return None


def prepare_request_payload(case: dict, input_file: Path) -> tuple[dict, object | None, str]:
    modality = (
        normalize_case_value(case, "request_modality")
        or normalize_case_value(case, "modality", "text")
    ).lower()
    if modality not in SUPPORTED_MODALITIES:
        raise ValueError(
            f"不支持的 modality: {modality}。支持的类型有: {', '.join(sorted(SUPPORTED_MODALITIES))}"
        )

    text = normalize_text_payload(case)
    url = normalize_case_value(case, "url")

    multipart = {
        "modality": (None, modality),
        "text": (None, text if modality == "text" else ""),
        "url": (None, url if modality == "website" else ""),
    }

    file_handle = None
    resolved_path = ""

    if modality == "text" and not text:
        raise ValueError("text 样本必须包含 text/transcript/content/prompt 字段。")

    if modality == "website" and not url:
        raise ValueError("website 样本必须包含 url 字段。")

    if modality in FILE_MODALITIES:
        raw_file_path = normalize_case_value(case, "file_path") or normalize_case_value(case, "file")
        if not raw_file_path:
            raise ValueError(f"{modality} 样本必须包含 file_path 字段。")

        resolved = resolve_case_file(input_file, raw_file_path)
        if resolved is None:
            raise FileNotFoundError(f"找不到样本文件: {raw_file_path}")

        content_type = mimetypes.guess_type(resolved.name)[0] or "application/octet-stream"
        file_handle = resolved.open("rb")
        multipart["file"] = (resolved.name, file_handle, content_type)
        resolved_path = str(resolved)
    else:
        resolved_path = normalize_case_value(case, "file_path") or normalize_case_value(case, "file")

    return multipart, file_handle, resolved_path


def init_result_row(case: dict) -> dict:
    return {
        "id": normalize_case_value(case, "id"),
        "modality": normalize_case_value(case, "modality", "text").lower(),
        "request_modality": (
            normalize_case_value(case, "request_modality")
            or normalize_case_value(case, "modality", "text").lower()
        ),
        "type": normalize_case_value(case, "type"),
        "fraud_type": normalize_case_value(case, "fraud_type"),
        "subtype": normalize_case_value(case, "subtype"),
        "binary_label": normalize_case_value(case, "binary_label"),
        "expected_label": normalize_case_value(case, "expected_label"),
        "predicted_label": "",
        "fraud_probability": "",
        "result_confidence": "",
        "reason": "",
        "correct": False,
        "file_path": normalize_case_value(case, "file_path") or normalize_case_value(case, "file"),
        "url": normalize_case_value(case, "url"),
        "text": normalize_text_payload(case),
        "extracted_text": "",
    }


def write_results(path: Path, rows: list[dict]) -> None:
    ensure_parent_dir(path)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=RESULT_FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)


def print_group_stats(results: list[dict], field_name: str, title: str) -> None:
    total_by_field = defaultdict(int)
    correct_by_field = defaultdict(int)

    for row in results:
        value = row.get(field_name, "")
        if not value:
            continue
        total_by_field[value] += 1
        if row["correct"]:
            correct_by_field[value] += 1

    if not total_by_field:
        return

    print(f"\n===== 按 {title} 统计 =====")
    for value in sorted(total_by_field.keys()):
        total = total_by_field[value]
        correct = correct_by_field[value]
        accuracy = correct / total if total else 0
        print(f"{value}: {correct}/{total} = {accuracy:.2%}")


def main():
    args = build_parser().parse_args()
    input_file = args.input.resolve()
    output_file = args.output.resolve()
    failed_file = args.failed_output.resolve()

    try:
        test_cases = read_cases(input_file)
    except Exception as exc:
        print(exc)
        return

    results = []

    with httpx.Client(trust_env=False, timeout=args.timeout) as client:
        for case in test_cases:
            result = init_result_row(case)
            file_handle = None

            try:
                multipart, file_handle, resolved_path = prepare_request_payload(case, input_file)
                if resolved_path:
                    result["file_path"] = resolved_path

                response = client.post(args.api_url, files=multipart)

                if response.status_code != 200:
                    result["predicted_label"] = "ERROR"
                    result["reason"] = f"HTTP {response.status_code} | {response.text[:300]}"
                else:
                    data = response.json()
                    predicted_label = str(data.get("risk_level", "")).strip()
                    result["predicted_label"] = predicted_label
                    result["fraud_probability"] = data.get("fraud_probability", "")
                    result["result_confidence"] = data.get("result_confidence", "")
                    result["reason"] = format_reason(data.get("reason", ""))
                    result["extracted_text"] = str(data.get("extracted_text", "")).strip()
                    result["correct"] = is_prediction_correct(
                        result["expected_label"],
                        predicted_label,
                    )

            except Exception as exc:
                result["predicted_label"] = "EXCEPTION"
                result["reason"] = repr(exc)

            finally:
                if file_handle is not None:
                    file_handle.close()

            results.append(result)
            print(
                f"[{result['id']}] modality={result['modality']} "
                f"request={result['request_modality']} "
                f"type={result['type']} subtype={result['subtype']} "
                f"expected={result['expected_label']} "
                f"predicted={result['predicted_label']} "
                f"correct={result['correct']}"
            )

    write_results(output_file, results)

    failed = [row for row in results if not row["correct"]]
    write_results(failed_file, failed)

    total = len(results)
    correct = sum(1 for row in results if row["correct"])
    accuracy = correct / total if total > 0 else 0

    print("\n===== 测试完成 =====")
    print(f"总样本数: {total}")
    print(f"预测正确数: {correct}")
    print(f"准确率: {accuracy:.2%}")
    print(f"结果已保存到: {output_file}")
    print(f"误判样本已保存到: {failed_file}")

    print_group_stats(results, "modality", "modality")
    print_group_stats(results, "type", "type")
    print_group_stats(results, "fraud_type", "fraud_type")
    print_group_stats(results, "subtype", "subtype")


if __name__ == "__main__":
    main()
