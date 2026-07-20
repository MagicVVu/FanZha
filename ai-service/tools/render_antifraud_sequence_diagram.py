from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


PROJECT_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_PATH = PROJECT_ROOT / "docs" / "diagrams" / "antifraud_sequence_diagram.jpg"

WIDTH = 2600
MARGIN_X = 90
TOP_MARGIN = 130
BOTTOM_MARGIN = 90
TITLE_FONT_SIZE = 38
LABEL_FONT_SIZE = 24
TEXT_FONT_SIZE = 18
ROW_HEIGHT = 84
PARTICIPANT_BOX_HEIGHT = 68
LIFELINE_TOP_GAP = 18
ARROW_HEAD = 10


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    font_candidates = [
        "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "C:/Windows/Fonts/arial.ttf",
    ]
    for candidate in font_candidates:
        try:
            return ImageFont.truetype(candidate, size=size)
        except OSError:
            continue
    return ImageFont.load_default()


TITLE_FONT = load_font(TITLE_FONT_SIZE, bold=True)
LABEL_FONT = load_font(LABEL_FONT_SIZE, bold=True)
TEXT_FONT = load_font(TEXT_FONT_SIZE)


PARTICIPANTS = [
    ("U", "用户 / App"),
    ("R", "assistant.py\n路由层"),
    ("P", "pipeline.py\n编排层"),
    ("X", "多模态\n提取器"),
    ("RE", "risk_engine.py"),
    ("KB", "kb_service.py"),
    ("L", "deepseek_client.py"),
    ("N", "novel_case_service.py"),
]


ROWS: list[dict[str, str]] = [
    {"type": "msg", "from": "U", "to": "R", "text": "POST /api/assistant/chat/multimodal\n或 /analyze"},
    {"type": "msg", "from": "R", "to": "P", "text": "调用 chat_with_attachment()\n或 analyze_input()"},
    {"type": "section_start", "label": "文本输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "直接使用文本作为 evidence"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "section_start", "label": "图片输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "save_upload()"},
    {"type": "self", "actor": "X", "text": "PaddleOCR 提取文字"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "section_start", "label": "音频输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "save_upload()"},
    {"type": "self", "actor": "X", "text": "ffmpeg 预处理 +\nFaster-Whisper 转写"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "section_start", "label": "视频输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "save_upload()"},
    {"type": "self", "actor": "X", "text": "提取音轨"},
    {"type": "self", "actor": "X", "text": "音轨 ASR"},
    {"type": "self", "actor": "X", "text": "抽取关键帧 +\n关键帧 OCR"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "合并后的 extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "section_start", "label": "网页输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "传入 URL"},
    {"type": "self", "actor": "X", "text": "Playwright 抓取页面 +\nBeautifulSoup 清洗正文"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "section_start", "label": "文件输入"},
    {"type": "msg", "from": "P", "to": "X", "text": "save_upload()"},
    {"type": "self", "actor": "X", "text": "TXT / PDF / DOCX / JSON\n等解析"},
    {"type": "msg_return", "from": "X", "to": "P", "text": "extracted_text +\nextract_quality"},
    {"type": "section_end"},
    {"type": "msg", "from": "P", "to": "RE", "text": "rule_score() + override +\ncombine_probability()"},
    {"type": "msg_return", "from": "RE", "to": "P", "text": "fraud_probability /\nconfidence / risk_level / reason"},
    {"type": "msg", "from": "P", "to": "KB", "text": "search_faq(extracted_text\n或 fused_context)"},
    {"type": "self", "actor": "KB", "text": "lexical recall + dense recall\n+ hybrid fusion + rerank"},
    {"type": "msg_return", "from": "KB", "to": "P", "text": "kb_hits + safe_actions"},
    {"type": "section_start", "label": "聊天模式 /chat /chat-multimodal"},
    {"type": "self", "actor": "P", "text": "构建会话上下文\n与历史证据"},
    {"type": "self", "actor": "P", "text": "聚合当前轮与历史轮风险"},
    {"type": "msg", "from": "P", "to": "L", "text": "general_chat_reply()\n或 stream"},
    {"type": "msg_return", "from": "L", "to": "P", "text": "reply / suggestions /\nsafe_actions"},
    {"type": "section_end"},
    {"type": "section_start", "label": "检测模式 /analyze"},
    {"type": "self", "actor": "P", "text": "直接组装结构化分析结果"},
    {"type": "section_end"},
    {"type": "section_start", "label": "短信模式 /check-sms"},
    {"type": "self", "actor": "P", "text": "执行短信专用规则增强"},
    {"type": "section_end"},
    {"type": "section_start", "label": "可选：高风险但知识库弱匹配，疑似新兴案例"},
    {"type": "msg", "from": "P", "to": "N", "text": "enqueue_candidate()"},
    {"type": "self", "actor": "N", "text": "生成 fingerprint +\n写入 pending_candidates.json"},
    {"type": "msg_return", "from": "N", "to": "P", "text": "candidate queued"},
    {"type": "section_end"},
    {"type": "msg_return", "from": "P", "to": "R", "text": "返回统一结果"},
    {"type": "msg_return", "from": "R", "to": "U", "text": "reply / fraud_probability /\nconfidence / risk_level /\nreason / kb_hits / safe_actions"},
]


def text_size(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont) -> tuple[int, int]:
    bbox = draw.multiline_textbbox((0, 0), text, font=font, spacing=4)
    return bbox[2] - bbox[0], bbox[3] - bbox[1]


def center_text(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, font: ImageFont.ImageFont, fill: str) -> None:
    x1, y1, x2, y2 = box
    w, h = text_size(draw, text, font)
    draw.multiline_text(
        (x1 + ((x2 - x1 - w) / 2), y1 + ((y2 - y1 - h) / 2)),
        text,
        font=font,
        fill=fill,
        spacing=4,
        align="center",
    )


def arrow(draw: ImageDraw.ImageDraw, start: tuple[int, int], end: tuple[int, int], color: str, dashed: bool = False) -> None:
    x1, y1 = start
    x2, y2 = end
    if dashed:
        segment = 14
        gap = 8
        total = abs(x2 - x1)
        direction = 1 if x2 >= x1 else -1
        current = 0
        while current < total:
            seg_end = min(current + segment, total)
            draw.line((x1 + current * direction, y1, x1 + seg_end * direction, y1), fill=color, width=3)
            current += segment + gap
    else:
        draw.line((x1, y1, x2, y2), fill=color, width=3)

    if x2 >= x1:
        draw.polygon(
            [
                (x2, y2),
                (x2 - ARROW_HEAD, y2 - 6),
                (x2 - ARROW_HEAD, y2 + 6),
            ],
            fill=color,
        )
    else:
        draw.polygon(
            [
                (x2, y2),
                (x2 + ARROW_HEAD, y2 - 6),
                (x2 + ARROW_HEAD, y2 + 6),
            ],
            fill=color,
        )


def self_arrow(draw: ImageDraw.ImageDraw, x: int, y: int, color: str) -> tuple[int, int, int, int]:
    box_w = 110
    box_h = 34
    draw.line((x, y, x + box_w, y), fill=color, width=3)
    draw.line((x + box_w, y, x + box_w, y + box_h), fill=color, width=3)
    draw.line((x + box_w, y + box_h, x + 26, y + box_h), fill=color, width=3)
    draw.polygon(
        [
            (x + 26, y + box_h),
            (x + 38, y + box_h - 6),
            (x + 38, y + box_h + 6),
        ],
        fill=color,
    )
    return x + 10, y - 4, x + box_w - 10, y + box_h + 8


def main() -> None:
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    height = TOP_MARGIN + BOTTOM_MARGIN + (len(ROWS) * ROW_HEIGHT) + 140
    image = Image.new("RGB", (WIDTH, height), "#fcfcfd")
    draw = ImageDraw.Draw(image)

    center_text(
        draw,
        (0, 22, WIDTH, 74),
        "AntiFraudAI 反诈智能体主处理时序图",
        TITLE_FONT,
        "#16213e",
    )
    center_text(
        draw,
        (0, 72, WIDTH, 108),
        "当前在线主链路：App -> 路由 -> Pipeline -> 多模态提取 -> 风险判定 -> 检索增强 -> 回复输出",
        TEXT_FONT,
        "#4b5563",
    )

    participant_area_width = WIDTH - (MARGIN_X * 2)
    spacing = participant_area_width / (len(PARTICIPANTS) - 1)
    participant_x: dict[str, int] = {}

    for index, (key, label) in enumerate(PARTICIPANTS):
        x = int(MARGIN_X + (spacing * index))
        participant_x[key] = x
        box = (x - 92, TOP_MARGIN, x + 92, TOP_MARGIN + PARTICIPANT_BOX_HEIGHT)
        draw.rounded_rectangle(box, radius=16, fill="#dbeafe", outline="#3b82f6", width=3)
        center_text(draw, box, label, LABEL_FONT, "#0f172a")

    lifeline_top = TOP_MARGIN + PARTICIPANT_BOX_HEIGHT + LIFELINE_TOP_GAP
    lifeline_bottom = height - BOTTOM_MARGIN
    for x in participant_x.values():
        draw.line((x, lifeline_top, x, lifeline_bottom), fill="#cbd5e1", width=2)

    current_y = lifeline_top + 12
    open_section: tuple[str, int] | None = None
    section_blocks: list[tuple[str, int, int]] = []
    draw_rows: list[tuple[int, dict[str, str]]] = []

    for row in ROWS:
        if row["type"] == "section_start":
            open_section = (row["label"], current_y - 18)
            continue

        if row["type"] == "section_end":
            if open_section is not None:
                section_blocks.append((open_section[0], open_section[1], current_y - 10))
            open_section = None
            continue

        draw_rows.append((current_y, row))
        current_y += ROW_HEIGHT

    for label, start_y, end_y in section_blocks:
        left = MARGIN_X - 26
        right = WIDTH - MARGIN_X + 26
        draw.rounded_rectangle(
            (left, start_y, right, end_y),
            radius=14,
            outline="#f59e0b",
            fill="#fff7ed",
            width=2,
        )
        tag_box = (left + 14, start_y - 18, left + 380, start_y + 20)
        draw.rounded_rectangle(tag_box, radius=12, fill="#f59e0b", outline="#d97706", width=2)
        center_text(draw, tag_box, label, TEXT_FONT, "#ffffff")

    for y, row in draw_rows:
        color = "#334155"

        if row["type"] == "msg":
            x1 = participant_x[row["from"]]
            x2 = participant_x[row["to"]]
            arrow(draw, (x1, y), (x2, y), color)
            tx1 = min(x1, x2) + 12
            tx2 = max(x1, x2) - 12
            center_text(draw, (tx1, y - 34, tx2, y - 6), row["text"], TEXT_FONT, "#111827")

        elif row["type"] == "msg_return":
            x1 = participant_x[row["from"]]
            x2 = participant_x[row["to"]]
            arrow(draw, (x1, y), (x2, y), "#64748b", dashed=True)
            tx1 = min(x1, x2) + 12
            tx2 = max(x1, x2) - 12
            center_text(draw, (tx1, y - 34, tx2, y - 6), row["text"], TEXT_FONT, "#334155")

        elif row["type"] == "self":
            x = participant_x[row["actor"]]
            box = self_arrow(draw, x, y - 10, color)
            center_text(draw, box, row["text"], TEXT_FONT, "#111827")

    legend_box = (MARGIN_X, height - 72, WIDTH - MARGIN_X, height - 26)
    draw.rounded_rectangle(legend_box, radius=12, fill="#eef2ff", outline="#a5b4fc", width=2)
    center_text(
        draw,
        legend_box,
        "说明：实线表示调用，虚线表示返回；橙色分组表示不同输入模式或处理分支。",
        TEXT_FONT,
        "#3730a3",
    )

    image.save(OUTPUT_PATH, format="JPEG", quality=94, subsampling=0)
    print(str(OUTPUT_PATH))


if __name__ == "__main__":
    main()
