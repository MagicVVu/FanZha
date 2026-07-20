import re

RISK_KEYWORDS = {
    "transfer": [
        "转账", "打款", "汇款", "转入", "转到", "转去", "转至",
        "安全账户", "指定账户", "指定安全账户", "暂存账户",
        "保证金", "解冻金", "认证金", "刷流水", "验证资金",
        "缴纳", "交纳", "支付",
    ],
    "code": ["验证码", "短信验证码", "短信码", "动态码", "校验码"],
    "customer_service": [
        "客服", "平台客服", "官方客服", "售后", "退款专员", "理赔专员",
        "白条", "百万保障", "自动扣费", "自动续费", "会员服务",
        "直播会员", "保险服务", "扣费项目", "关闭扣费",
    ],
    "police": [
        "公安", "派出所", "检察院", "法院",
        "涉嫌", "涉嫌洗钱", "涉案", "配合调查", "立案", "起诉", "冻结资产",
    ],
    "investment": [
        "投资群", "内幕消息", "稳赚", "稳赚不赔", "高收益",
        "带单", "老师带单", "内部渠道", "保证收益",
    ],
    "remote": [
        "共享屏幕", "屏幕共享", "共享手机屏幕",
        "远程控制", "远程操作", "会议软件", "视频会议",
        "下载软件", "远程协助",
    ],
    "identity_verify": [
        "确认身份", "验证身份", "核验身份", "身份信息", "身份确认",
        "身份复核", "核对身份", "本人信息", "个人资料", "资料审核",
        "安全校验", "平台用户", "确认信息", "核对信息", "补充资料",
    ],
    "logistics_refund": [
        "快递丢失", "包裹丢失", "丢件", "申请理赔", "理赔流程",
        "赔付", "补偿", "影响退款", "退款流程异常", "退款失败",
        "重新核对订单", "重新确认订单", "订单状态异常", "订单异常", "收款信息",
        "快递异常", "物流异常", "运输异常", "运输问题", "运输出了问题", "出了问题",
    ],
    "deposit_unfreeze": [
        "冻结", "解冻", "解冻金", "保证金", "认证金", "先交", "垫付",
    ],
    "private_contact": [
        "加微信", "私人微信", "企业微信", "私下", "单独处理", "平台外",
        "离开平台", "脱离平台", "不要在平台", "不要在官方平台",
        "不要在官方平台操作",
    ],
    "brush_order": [
        "刷单", "返佣", "返利", "返还佣金", "垫付", "垫付一笔钱",
        "垫付任务", "连单", "连刷", "任务单", "本金和佣金", "本金", "佣金",
    ],
    "credit_repair": [
        "征信", "修复征信", "信用修复", "刷流水",
        "贷款征信", "转一笔流水", "做流水",
    ],
    "prize_info": [
        "中奖人", "中奖", "中了", "彩票", "大奖", "百万大奖", "领奖",
        "中奖登记", "登记个人资料", "登记资料", "礼品卡", "中奖资格", "活动奖励",
        "激活", "激活费", "手续费", "税费", "领奖费", "兑奖费", "先交钱",
    ],
    "health_product": [
        "神药", "保健品", "保健品骗局", "药品骗局", "养生讲座",
        "包治百病", "有病治病", "没病强身", "免费注射", "免费领药",
        "线上会议卖药", "老人买药", "会销", "免费体验", "药到病除",
    ],
}

RULE_WEIGHT = {
    "transfer": 0.34,
    "code": 0.24,
    "customer_service": 0.18,
    "police": 0.38,
    "investment": 0.28,
    "remote": 0.32,
    "identity_verify": 0.22,
    "logistics_refund": 0.24,
    "deposit_unfreeze": 0.28,
    "private_contact": 0.30,
    "brush_order": 0.30,
    "credit_repair": 0.28,
    "prize_info": 0.22,
    "health_product": 0.32,
}

RULE_DISPLAY = {
    "transfer": "涉及转账、保证金或安全账户话术",
    "code": "涉及验证码索取",
    "customer_service": "涉及客服、退款或扣费类风险话术",
    "police": "涉及冒充公检法话术",
    "investment": "涉及高收益投资话术",
    "remote": "涉及远程控制或共享屏幕",
    "identity_verify": "涉及身份核验或资料确认诱导",
    "logistics_refund": "涉及订单异常、快递理赔或退款复核诱导",
    "deposit_unfreeze": "涉及冻结解冻或保证金话术",
    "private_contact": "涉及脱离平台私下联系",
    "brush_order": "涉及刷单返佣话术",
    "credit_repair": "涉及征信修复或刷流水话术",
    "prize_info": "涉及中奖登记或资料诱导",
    "health_product": "涉及神药、保健品夸大疗效或免费领药类诈骗话术",
}

SAFE_CLAUSE_PATTERNS = [
    re.compile(
        r"(?:\u65e0\u9700|\u4e0d\u9700|\u4e0d\u9700\u8981)"
        r"[^,，。；;\n]{0,18}"
        r"(?:\u8f6c\u8d26|\u4ed8\u6b3e|\u652f\u4ed8|\u4ea4\u94b1|\u9a8c\u8bc1\u7801|\u79c1\u4e0b\u8054\u7cfb|\u52a0\u5fae\u4fe1|\u79bb\u5f00\u5e73\u53f0)"
    ),
    re.compile(
        r"(?:\u4e0d\u7d22\u8981|\u4e0d\u4f1a\u7d22\u8981)"
        r"[^,，。；;\n]{0,18}"
        r"(?:\u9a8c\u8bc1\u7801|\u94f6\u884c\u5361|\u8eab\u4efd\u4fe1\u606f|\u654f\u611f\u4fe1\u606f)"
    ),
    re.compile(
        r"(?:\u8bf7\u901a\u8fc7|\u4ec5\u9650|\u5747\u5728|\u5168\u90e8\u5728)"
        r"[^,，。；;\n]{0,24}"
        r"(?:\u5b98\u65b9|\u5e73\u53f0|App|\u8ba2\u5355\u9875)"
        r"[^,，。；;\n]{0,24}"
        r"(?:\u5b8c\u6210|\u5904\u7406|\u67e5\u770b|\u7533\u8bf7|\u9886\u53d6|\u64cd\u4f5c)"
    ),
    re.compile(
        r"(?:\u539f\u8def\u9000\u6b3e|\u5b98\u65b9\u6e20\u9053|\u5b98\u65b9App|\u5e73\u53f0\u5185|\u81ea\u4e3b\u64cd\u4f5c|\u81ea\u52a9\u5173\u95ed|\u65e0\u9700\u4ed8\u6b3e|\u4e0d\u6536\u53d6\u4efb\u4f55\u8d39\u7528|\u514d\u624b\u7eed\u8d39)"
    ),
]

LOW_RISK_STRONG_SIGNALS = [
    "\u8bf7\u901a\u8fc7\u5b98\u65b9\u9875\u9762\u5b8c\u6210\u64cd\u4f5c",
    "\u8bf7\u901a\u8fc7\u5b98\u65b9\u6e20\u9053",
    "\u4ec5\u9650\u5b98\u65b9\u6e20\u9053\u5b8c\u6210",
    "\u5728App\u5185\u5904\u7406",
    "\u5728App\u5185\u67e5\u770b",
    "\u5728\u8ba2\u5355\u9875\u67e5\u770b",
    "\u539f\u8def\u9000\u6b3e",
    "\u4e0d\u6536\u8d39",
    "\u4e0d\u6536\u53d6\u4efb\u4f55\u8d39\u7528",
    "\u4e0d\u9700\u5148\u4ea4\u94b1",
    "\u65e0\u9700\u8f6c\u8d26",
    "\u65e0\u9700\u63d0\u4f9b\u9a8c\u8bc1\u7801",
    "\u4e0d\u7d22\u8981\u9a8c\u8bc1\u7801",
]

LOW_RISK_NORMAL_SCENES = [
    "\u552e\u540e\u56de\u8bbf",
    "\u786e\u8ba4\u6536\u8d27\u5730\u5740",
    "\u9001\u8fbe\u65f6\u95f4",
    "\u53ef\u56de\u62e8\u6838\u9a8c",
    "\u89c6\u9891\u786e\u8ba4\u8eab\u4efd",
    "\u4e0d\u50ac\u4fc3",
    "\u5b98\u65b9\u6d3b\u52a8",
    "\u5e73\u53f0\u6d3b\u52a8\u9875",
    "\u5b98\u65b9\u6559\u5b66",
]

LOW_RISK_BLOCKERS = [
    "\u5b89\u5168\u8d26\u6237",
    "\u5171\u4eab\u5c4f\u5e55",
    "\u8fdc\u7a0b\u63a7\u5236",
    "\u5237\u5355",
    "\u516c\u5b89",
    "\u6d17\u94b1",
    "\u5185\u5e55\u6d88\u606f",
    "\u7a33\u8d5a\u4e0d\u8d54",
    "\u89e3\u51bb\u91d1",
    "\u8ba4\u8bc1\u91d1",
    "\u795e\u836f",
    "\u4fdd\u5065\u54c1",
]


def normalize_text(text: str) -> str:
    return text.replace(" ", "").replace("\n", "").strip()


def strip_safe_clauses(text: str) -> str:
    sanitized = text
    for pattern in SAFE_CLAUSE_PATTERNS:
        sanitized = pattern.sub("", sanitized)
    return sanitized


def has_any(text: str, words: list[str]) -> bool:
    return any(word in text for word in words)


def count_hit_groups(text: str, keyword_groups: list[list[str]]) -> int:
    return sum(1 for group in keyword_groups if has_any(text, group))


def rule_score(text: str):
    text = strip_safe_clauses(normalize_text(text))
    hits = []
    score = 0.0

    for key, words in RISK_KEYWORDS.items():
        if has_any(text, words):
            hits.append(key)
            score += RULE_WEIGHT[key]

    return min(score, 1.0), hits


def rule_hits_to_display(rule_hits: list[str]):
    return [RULE_DISPLAY.get(hit, hit) for hit in rule_hits]


def medium_risk_override(text: str, rule_hits: list[str]):
    text = strip_safe_clauses(normalize_text(text))

    if count_hit_groups(text, [
        ["核验", "验证", "确认", "复核", "校验", "核对"],
        ["身份", "账户", "本人", "资料", "信息"],
    ]) >= 2:
        return 0.56, "出现身份核验或资料确认诱导"

    if count_hit_groups(text, [
        ["退款", "售后", "理赔", "收款"],
        ["异常", "失败", "影响", "缺少", "不完整"],
        ["确认", "核对", "补充", "重新"],
    ]) >= 2:
        return 0.54, "出现退款复核或资料补充诱导"

    if count_hit_groups(text, [
        ["订单", "支付", "收货"],
        ["异常", "风控", "拦截", "异常记录"],
        ["确认", "核实", "配合", "复核"],
    ]) >= 2:
        return 0.54, "出现订单异常并要求配合核实"

    if has_any(text, ["快递", "包裹", "物流"]) and \
       has_any(text, ["丢失", "损坏", "理赔", "赔付", "补偿"]) and \
       has_any(text, ["登记", "资料", "审核", "认证", "联系方式", "确认"]):
        return 0.55, "出现快递理赔并要求登记资料"

    if has_any(text, ["快递", "包裹", "物流"]) and \
       has_any(text, ["出问题", "出了问题", "有问题", "异常", "运输问题", "运输出了问题", "运输异常", "物流异常", "快递异常", "包裹异常"]) and \
       has_any(text, ["登记", "资料", "信息", "联系方式", "确认"]):
        return 0.50, "出现快递异常并要求登记资料"

    if count_hit_groups(text, [
        ["中奖", "中了", "彩票", "大奖", "奖励", "礼品卡", "活动", "领奖"],
        ["登记", "资料", "审核", "认证", "联系方式"],
    ]) >= 2:
        return 0.52, "出现中奖登记或资料提交诱导"

    if count_hit_groups(text, [
        ["中奖", "中了", "彩票", "大奖", "百万", "领奖", "兑奖"],
        ["激活", "激活费", "手续费", "税费", "领奖费", "兑奖费", "先交钱", "保证金"],
    ]) >= 2:
        return 0.64, "出现中奖领奖前先交激活费、手续费或税费的话术"

    if count_hit_groups(text, [
        ["神药", "保健品", "养生讲座", "免费体验", "会销"],
        ["包治百病", "有病治病", "没病强身", "药到病除", "免费注射", "免费领药"],
    ]) >= 2:
        return 0.62, "出现神药、保健品夸大疗效或免费领药诱导"

    if any(hit in rule_hits for hit in ["identity_verify", "logistics_refund", "prize_info"]):
        return 0.50, "出现可疑核验、理赔或领奖话术"

    if "health_product" in rule_hits:
        return 0.58, "出现神药、保健品或养生会销类可疑诈骗话术"

    return None, None


def low_risk_override(text: str, rule_hits: list[str]):
    original_text = normalize_text(text)
    sanitized_text = strip_safe_clauses(original_text)

    if has_any(sanitized_text, LOW_RISK_BLOCKERS):
        return None, None

    risky_rule_hits = {
        "transfer",
        "police",
        "remote",
        "private_contact",
        "brush_order",
        "credit_repair",
        "deposit_unfreeze",
        "investment",
        "health_product",
    }
    if any(hit in risky_rule_hits for hit in rule_hits):
        return None, None

    if has_any(original_text, LOW_RISK_STRONG_SIGNALS) and has_any(original_text, LOW_RISK_NORMAL_SCENES):
        return 0.08, "\u51fa\u73b0\u5b98\u65b9\u6e20\u9053\u3001\u65e0\u9700\u8f6c\u8d26\u6216\u53ef\u56de\u62e8\u6838\u9a8c\u7b49\u4f4e\u98ce\u9669\u4fe1\u53f7"

    if has_any(
        original_text,
        [
            "\u65e0\u9700\u8f6c\u8d26",
            "\u65e0\u9700\u63d0\u4f9b\u9a8c\u8bc1\u7801",
            "\u4e0d\u7d22\u8981\u9a8c\u8bc1\u7801",
            "\u4e0d\u9700\u5148\u4ea4\u94b1",
            "\u4e0d\u6536\u8d39",
            "\u4e0d\u6536\u53d6\u4efb\u4f55\u8d39\u7528",
        ],
    ) and has_any(
        original_text,
        LOW_RISK_NORMAL_SCENES
        + [
            "\u552e\u540e",
            "\u8ba2\u5355",
            "\u9000\u8d27",
            "\u5feb\u9012",
            "\u6d3b\u52a8",
            "App",
        ],
    ):
        return 0.06, "\u5185\u5bb9\u4ee5\u5b98\u65b9\u6e20\u9053\u529e\u7406\u548c\u65e0\u9700\u8d44\u91d1\u64cd\u4f5c\u4e3a\u4e3b\uff0c\u6574\u4f53\u98ce\u9669\u8f83\u4f4e"

    if has_any(
        original_text,
        [
            "\u53ef\u56de\u62e8\u6838\u9a8c",
            "\u89c6\u9891\u786e\u8ba4\u8eab\u4efd",
            "\u4e0d\u50ac\u4fc3",
        ],
    ) and not has_any(
        sanitized_text,
        [
            "\u5b89\u5168\u8d26\u6237",
            "\u8f6c\u8d26",
            "\u9a8c\u8bc1\u7801",
            "\u5171\u4eab\u5c4f\u5e55",
            "\u8fdc\u7a0b\u63a7\u5236",
            "\u79c1\u4e0b",
        ],
    ):
        return 0.10, "\u5185\u5bb9\u5305\u542b\u53ef\u56de\u62e8\u6838\u9a8c\u6216\u89c6\u9891\u786e\u8ba4\u7b49\u6b63\u5e38\u9632\u9a97\u4fe1\u53f7"

    return None, None


def high_risk_override(text: str, rule_hits: list[str]):
    text = strip_safe_clauses(normalize_text(text))

    if has_any(text, ["\u516c\u5b89", "\u68c0\u5bdf\u9662", "\u6cd5\u9662", "\u6d89\u6848", "\u6d17\u94b1", "\u7acb\u6848", "\u8d77\u8bc9"]) and \
       has_any(text, ["\u8f6c\u8d26", "\u6253\u6b3e", "\u6c47\u6b3e", "\u5b89\u5168\u8d26\u6237", "\u6307\u5b9a\u8d26\u6237"]):
        return 0.92, "\u51fa\u73b0\u5192\u5145\u516c\u68c0\u6cd5\u5e76\u8981\u6c42\u8f6c\u8d26\u6216\u8f6c\u5165\u5b89\u5168\u8d26\u6237"

    if has_any(text, ["\u4f1a\u5458\u670d\u52a1", "\u76f4\u64ad\u4f1a\u5458", "\u4fdd\u9669\u670d\u52a1", "\u767e\u4e07\u4fdd\u969c", "\u81ea\u52a8\u6263\u8d39", "\u81ea\u52a8\u7eed\u8d39"]) and \
       has_any(text, ["\u4e0b\u8f7d\u8f6f\u4ef6", "\u4f1a\u8bae\u8f6f\u4ef6", "\u5171\u4eab\u5c4f\u5e55", "\u5171\u4eab\u624b\u673a\u5c4f\u5e55", "\u8fdc\u7a0b\u534f\u52a9", "\u9a8c\u8bc1\u8d44\u91d1", "\u7acb\u5373\u5904\u7406", "\u9a6c\u4e0a\u5904\u7406", "\u534f\u52a9\u5173\u95ed"]):
        return 0.90, "\u51fa\u73b0\u4f1a\u5458\u6263\u8d39\u7c7b\u9ad8\u5371\u5904\u7406\u8bf1\u5bfc"

    if has_any(text, ["\u767e\u4e07\u4fdd\u969c", "\u4f1a\u5458\u670d\u52a1", "\u76f4\u64ad\u4f1a\u5458", "\u4fdd\u9669\u670d\u52a1", "\u81ea\u52a8\u6263\u8d39", "\u81ea\u52a8\u7eed\u8d39"]) and \
       has_any(text, ["\u4e0d\u53d6\u6d88", "\u4e0d\u5173\u95ed", "\u4eca\u665a", "\u6bcf\u6708", "\u7acb\u5373\u5904\u7406", "\u9a6c\u4e0a\u5904\u7406", "\u914d\u5408\u5904\u7406"]):
        return 0.84, "\u51fa\u73b0\u4f1a\u5458\u6216\u4fdd\u969c\u670d\u52a1\u6263\u8d39\u5a01\u80c1\u8bdd\u672f"

    if has_any(text, ["\u5ba2\u670d", "\u5e73\u53f0\u5ba2\u670d", "\u5b98\u65b9\u5ba2\u670d", "\u767d\u6761", "\u4f1a\u5458\u670d\u52a1", "\u81ea\u52a8\u6263\u8d39", "\u81ea\u52a8\u7eed\u8d39", "\u767e\u4e07\u4fdd\u969c"]) and \
       has_any(text, ["\u8f6c\u8d26", "\u5b89\u5168\u8d26\u6237", "\u9a8c\u8bc1\u7801", "\u77ed\u4fe1\u9a8c\u8bc1\u7801"]):
        return 0.90, "\u51fa\u73b0\u5ba2\u670d\u6263\u8d39\u7c7b\u8bdd\u672f\u5e76\u8981\u6c42\u8f6c\u8d26\u6216\u9a8c\u8bc1\u7801"

    if has_any(text, ["\u5ba2\u670d", "\u9000\u6b3e", "\u552e\u540e"]) and \
       has_any(text, ["\u52a0\u5fae\u4fe1", "\u79c1\u4eba\u5fae\u4fe1", "\u4f01\u4e1a\u5fae\u4fe1", "\u79c1\u4e0b", "\u79bb\u5f00\u5e73\u53f0", "\u4e0d\u8981\u5728\u5e73\u53f0", "\u4e0d\u8981\u5728\u5b98\u65b9\u5e73\u53f0\u64cd\u4f5c"]):
        return 0.89, "\u51fa\u73b0\u8131\u79bb\u5e73\u53f0\u79c1\u4e0b\u5904\u7406\u9000\u6b3e\u6216\u552e\u540e"

    if has_any(text, ["\u5171\u4eab\u5c4f\u5e55", "\u5c4f\u5e55\u5171\u4eab", "\u5171\u4eab\u624b\u673a\u5c4f\u5e55", "\u8fdc\u7a0b\u63a7\u5236", "\u8fdc\u7a0b\u64cd\u4f5c", "\u4f1a\u8bae\u8f6f\u4ef6", "\u4e0b\u8f7d\u8f6f\u4ef6", "\u8fdc\u7a0b\u534f\u52a9"]) and \
       has_any(text, ["\u9000\u6b3e", "\u53d6\u6d88\u6263\u8d39", "\u5173\u95ed\u670d\u52a1", "\u5173\u95ed\u81ea\u52a8\u6263\u8d39", "\u81ea\u52a8\u6263\u8d39", "\u9a8c\u8bc1\u7801", "\u767b\u5f55\u624b\u673a\u94f6\u884c", "\u534f\u52a9\u5173\u95ed"]):
        return 0.89, "\u51fa\u73b0\u8fdc\u7a0b\u63a7\u5236\u3001\u5c4f\u5e55\u5171\u4eab\u6216\u4f1a\u8bae\u8f6f\u4ef6\u534f\u52a9"

    if has_any(text, ["\u5b89\u5168\u8d26\u6237", "\u6307\u5b9a\u8d26\u6237", "\u6307\u5b9a\u5b89\u5168\u8d26\u6237", "\u6682\u5b58\u8d26\u6237"]) and \
       has_any(text, ["\u8f6c\u8d26", "\u8f6c\u5165", "\u8f6c\u5230", "\u8f6c\u53bb", "\u8f6c\u81f3", "\u6253\u6b3e", "\u6682\u5b58", "\u9a8c\u8bc1\u8d44\u91d1"]):
        return 0.90, "\u51fa\u73b0\u5b89\u5168\u8d26\u6237\u6216\u6307\u5b9a\u8d26\u6237\u8f6c\u8d26\u8bdd\u672f"

    if has_any(text, ["\u5feb\u9012", "\u5305\u88f9", "\u7406\u8d54", "\u8d54\u4ed8", "\u8865\u507f"]) and \
       has_any(text, ["\u94fe\u63a5", "\u4e8c\u7ef4\u7801", "\u70b9\u51fb", "\u626b\u7801", "\u586b\u5199"]) and \
       has_any(text, ["\u94f6\u884c\u5361", "\u8eab\u4efd\u8bc1", "\u4e2a\u4eba\u4fe1\u606f", "\u6536\u6b3e\u4fe1\u606f", "\u654f\u611f\u4fe1\u606f"]):
        return 0.88, "\u51fa\u73b0\u5feb\u9012\u7406\u8d54\u5e76\u8bf1\u5bfc\u70b9\u51fb\u94fe\u63a5\u6216\u586b\u5199\u654f\u611f\u4fe1\u606f"

    if has_any(text, ["\u51bb\u7ed3", "\u89e3\u51bb", "\u89e3\u51bb\u91d1", "\u4fdd\u8bc1\u91d1", "\u8ba4\u8bc1\u91d1"]) and \
       has_any(text, ["\u8f6c\u8d26", "\u6253\u6b3e", "\u5148\u4ea4", "\u57ab\u4ed8", "\u7f34\u7eb3", "\u4ea4\u7eb3", "\u652f\u4ed8"]):
        return 0.87, "\u51fa\u73b0\u51bb\u7ed3\u89e3\u51bb\u5e76\u8981\u6c42\u8d44\u91d1\u64cd\u4f5c"

    if has_any(text, ["\u5237\u5355", "\u8fd4\u4f63", "\u8fd4\u5229", "\u8fde\u5355", "\u8fde\u5237", "\u4efb\u52a1\u5355", "\u57ab\u4ed8\u4efb\u52a1"]) and \
       has_any(text, ["\u57ab\u4ed8", "\u672c\u91d1", "\u4f63\u91d1"]):
        return 0.87, "\u51fa\u73b0\u5237\u5355\u8fd4\u4f63\u7c7b\u9ad8\u5371\u8bdd\u672f"

    if has_any(text, ["\u5f81\u4fe1", "\u4fee\u590d\u5f81\u4fe1", "\u4fe1\u7528\u4fee\u590d", "\u8d37\u6b3e\u5f81\u4fe1"]) and \
       has_any(text, ["\u5237\u6d41\u6c34", "\u8f6c\u8d26", "\u9a8c\u8bc1\u8d44\u91d1", "\u8f6c\u4e00\u7b14\u6d41\u6c34", "\u505a\u6d41\u6c34"]):
        return 0.86, "\u51fa\u73b0\u5f81\u4fe1\u4fee\u590d\u5e76\u8981\u6c42\u5237\u6d41\u6c34\u6216\u8f6c\u8d26"

    if has_any(text, ["\u6295\u8d44\u7fa4", "\u5185\u5e55\u6d88\u606f", "\u7a33\u8d5a", "\u7a33\u8d5a\u4e0d\u8d54", "\u5e26\u5355", "\u5185\u90e8\u6e20\u9053", "\u4fdd\u8bc1\u6536\u76ca"]):
        return 0.84, "\u51fa\u73b0\u6295\u8d44\u7fa4\u3001\u5185\u5e55\u6d88\u606f\u6216\u7a33\u8d5a\u4e0d\u8d54\u7c7b\u9ad8\u5371\u8bdd\u672f"

    if count_hit_groups(text, [
        ["\u4e2d\u5956", "\u4e2d\u4e86", "\u5f69\u7968", "\u5927\u5956", "\u767e\u4e07", "\u9886\u5956", "\u5151\u5956"],
        ["\u6fc0\u6d3b", "\u6fc0\u6d3b\u8d39", "\u624b\u7eed\u8d39", "\u7a0e\u8d39", "\u9886\u5956\u8d39", "\u5151\u5956\u8d39", "\u5148\u4ea4\u94b1", "\u4fdd\u8bc1\u91d1"],
    ]) >= 2:
        return 0.86, "\u51fa\u73b0\u4e2d\u5956\u9886\u5956\u524d\u8981\u6c42\u5148\u4ea4\u6fc0\u6d3b\u8d39\u3001\u624b\u7eed\u8d39\u6216\u7a0e\u8d39\u7684\u9ad8\u5371\u8bc8\u9a97\u8bdd\u672f"

    if count_hit_groups(text, [
        ["\u795e\u836f", "\u4fdd\u5065\u54c1", "\u517b\u751f\u8bb2\u5ea7", "\u4f1a\u9500"],
        ["\u9a97\u5c40", "\u88ab\u9a97", "\u53d7\u9a97", "\u5438\u5f15", "\u6d17\u8111"],
        ["\u5305\u6cbb\u767e\u75c5", "\u6709\u75c5\u6cbb\u75c5", "\u6ca1\u75c5\u5f3a\u8eab", "\u514d\u8d39\u6ce8\u5c04", "\u514d\u8d39\u9886\u836f", "\u836f\u5230\u75c5\u9664"],
    ]) >= 2:
        return 0.84, "\u51fa\u73b0\u795e\u836f\u3001\u4fdd\u5065\u54c1\u9a97\u5c40\u6216\u5938\u5927\u7597\u6548\u8bf1\u5bfc"

    return None, None


def combine_probability(llm_score: float, rule_score_value: float, kb_score: float = 0.0):
    base = 0.25 * llm_score + 0.60 * rule_score_value + 0.15 * kb_score

    if rule_score_value >= 0.88:
        base = max(base, 0.88)
    elif rule_score_value >= 0.78:
        base = max(base, 0.78)
    elif rule_score_value >= 0.52:
        base = max(base, 0.52)

    return max(0.0, min(1.0, base))


def combine_confidence(extract_quality: float, fraud_probability: float):
    margin = abs(fraud_probability - 0.5) * 2
    confidence = 0.6 * extract_quality + 0.4 * margin
    return max(0.0, min(1.0, confidence))


def risk_level(prob: float):
    if prob >= 0.78:
        return "high"
    if prob >= 0.38:
        return "medium"
    return "low"
