import argparse
import asyncio
import csv
import json
from collections import defaultdict
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from app.services.pipeline import analyze_input

DEFAULT_INPUT_FILE = Path("tests/generated_eval_dataset/generated_cases.json")
DEFAULT_OUTPUT_FILE = Path("tests/generated_eval_dataset/direct_text_test_results.csv")
DEFAULT_FAILED_FILE = Path("tests/generated_eval_dataset/direct_text_failed_cases.csv")

FIELDNAMES = [
    "id",
    "source_modality",
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
    "text",
    "extracted_text",
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Evaluate cases by forcing every sample through text analysis only."
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
    return parser


def format_reason(reason_value) -> str:
    if isinstance(reason_value, list):
        return " | ".join(str(x) for x in reason_value)
    if isinstance(reason_value, str):
        return reason_value
    return ""


def ensure_parent_dir(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def write_results(path: Path, rows: list[dict]) -> None:
    ensure_parent_dir(path)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDNAMES)
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


async def evaluate_case(case: dict) -> dict:
    expected_label = str(case.get("expected_label", "")).strip()
    text = str(case.get("text", "")).strip()

    result = {
        "id": str(case.get("id", "")).strip(),
        "source_modality": str(case.get("modality", "")).strip(),
        "request_modality": "text",
        "type": str(case.get("type", "")).strip(),
        "fraud_type": str(case.get("fraud_type", "")).strip(),
        "subtype": str(case.get("subtype", "")).strip(),
        "binary_label": str(case.get("binary_label", "")).strip(),
        "expected_label": expected_label,
        "predicted_label": "",
        "fraud_probability": "",
        "result_confidence": "",
        "reason": "",
        "correct": False,
        "text": text,
        "extracted_text": "",
    }

    try:
        response = await analyze_input(modality="text", text=text)
        predicted_label = str(response.get("risk_level", "")).strip()
        result["predicted_label"] = predicted_label
        result["fraud_probability"] = response.get("fraud_probability", "")
        result["result_confidence"] = response.get("result_confidence", "")
        result["reason"] = format_reason(response.get("reason", ""))
        result["extracted_text"] = str(response.get("extracted_text", "")).strip()
        result["correct"] = predicted_label == expected_label
    except Exception as exc:
        result["predicted_label"] = "EXCEPTION"
        result["reason"] = repr(exc)

    return result


async def main_async() -> None:
    args = build_parser().parse_args()
    input_file = args.input.resolve()
    rows = json.loads(input_file.read_text(encoding="utf-8"))

    results: list[dict] = []
    for case in rows:
        result = await evaluate_case(case)
        results.append(result)
        print(
            f"[{result['id']}] source={result['source_modality']} request=text "
            f"expected={result['expected_label']} predicted={result['predicted_label']} "
            f"correct={result['correct']}"
        )

    write_results(args.output.resolve(), results)
    failed = [row for row in results if not row["correct"]]
    write_results(args.failed_output.resolve(), failed)

    total = len(results)
    correct = sum(1 for row in results if row["correct"])
    accuracy = correct / total if total else 0

    print("\n===== 测试完成 =====")
    print(f"总样本数: {total}")
    print(f"预测正确数: {correct}")
    print(f"准确率: {accuracy:.2%}")
    print(f"结果已保存到: {args.output.resolve()}")
    print(f"误判样本已保存到: {args.failed_output.resolve()}")

    print_group_stats(results, "source_modality", "source_modality")
    print_group_stats(results, "type", "type")
    print_group_stats(results, "fraud_type", "fraud_type")
    print_group_stats(results, "subtype", "subtype")


def main() -> None:
    asyncio.run(main_async())


if __name__ == "__main__":
    main()
