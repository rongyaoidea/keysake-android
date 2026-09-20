#!/usr/bin/env python3
"""从 CC-CEDICT 生成 Typesake 的离线中英词典（紧凑 TSV）。

用法：
    python3 tools/gen_english_dict.py --cedict cedict_1_0_ts_utf-8_mdbg.txt \
        --out app/src/main/assets/en_dict.tsv

数据来源：CC-CEDICT（https://www.mdbg.net/chinese/dictionary?page=cc-cedict）
许可：CC BY-SA 4.0 —— 生成的词典与 CC-CEDICT 同许可，署名见 THIRD_PARTY_NOTICES.md。

产出格式：每行 `简体<TAB>英文`，按（长度, 简体）排序，便于 diff 与最长匹配。
只保留 1–4 字、纯中文词条，英文释义取最干净的一条（去掉 CL:、variant of 等噪音）。
"""

import argparse
import re
import sys

CJK = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff]+$")
HEADER = re.compile(r"^#")

# 释义里出现这些说明性词条时直接跳过（对学习工具是噪音）
SKIP_DEF_PREFIXES = (
    "CL:",
    "variant of",
    "old variant",
    "archaic variant",
    "see ",
    "surname ",
    "abbr. for",
    "used in",
    "Japanese variant",
    "Korean variant",
    "erhua variant",
    "Taiwan pr.",
    "Cantonese ",
    "also pr.",
    "also written",
    "one of",
    "Japanese ",
    "Korean ",
    "Vietnam",
)
# 括号说明（(coll.) (fig.) (lit.) …）
PAREN = re.compile(r"\([^)]*\)")
TRAILING_SPACES = re.compile(r"\s+")


def pick_gloss(defs: list[str]) -> str | None:
    """挑一条最适合"组合进句子"的释义：首选第一义项（去掉 to 前缀），过长则退而取最短。"""
    if not defs:
        return None
    # 以第一义项为主（词典通常按常用度排序），过长才退回最短义项
    first = defs[0]
    chosen = first if len(first) <= 34 else min(defs, key=len)
    # 动词裸化：to learn -> learn（组合时避免出现 "to to learn"）
    if chosen.startswith("to ") and len(chosen) > 3:
        chosen = chosen[3:]
    chosen = chosen.strip().strip(";").strip()
    if len(chosen) > 30 or "," in chosen:
        # 过长/带解释性逗号的释义退回最短义项
        shortest = min(defs, key=len).strip()
        if len(shortest) <= 30 and "," not in shortest:
            chosen = shortest[3:] if shortest.startswith("to ") else shortest
    return chosen or None


def clean_definitions(raw: str) -> list[str]:
    out = []
    for d in raw.strip("/").split("/"):
        d = TRAILING_SPACES.sub(" ", PAREN.sub("", d)).strip()
        if not d:
            continue
        low = d.lower()
        if d.startswith(SKIP_DEF_PREFIXES) or low.startswith(("see also", "also see", "cf.")):
            continue
        # 释义里混入中文（"variant of 某"之类）通常是解释性条目，跳过
        if re.search(r"[\u4e00-\u9fff]", d):
            continue
        # 至少要有两个英文字母
        if len(re.findall(r"[A-Za-z]", d)) < 2:
            continue
        # 去掉词典标记
        d = d.replace("fig.", "").replace("lit.", "").strip()
        if not d:
            continue
        out.append(d)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cedict", required=True, help="CC-CEDICT .txt（utf-8）")
    ap.add_argument("--out", required=True, help="输出 TSV 路径")
    ap.add_argument("--max-chars", type=int, default=4)
    args = ap.parse_args()

    best: dict[str, tuple[int, str]] = {}
    total = kept = 0
    with open(args.cedict, encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if not line or HEADER.match(line):
                continue
            parts = line.rstrip("\n").split(" ", 2)
            if len(parts) < 3:
                continue
            _trad, simp, rest = parts
            total += 1
            if not CJK.match(simp) or not (1 <= len(simp) <= args.max_chars):
                continue
            m = re.match(r"^\[([^\]]*)\]\s*(.*)$", rest)
            if not m:
                continue
            defs = clean_definitions(m.group(2))
            en = pick_gloss(defs)
            if not en:
                continue
            prev = best.get(simp)
            # 同词条取更短的一条（保持紧凑）
            if prev is None or len(en) < len(prev[1]):
                best[simp] = (len(en), en)
            kept += 1

    rows = sorted(best.items(), key=lambda kv: (len(kv[0]), kv[0]))
    with open(args.out, "w", encoding="utf-8") as out:
        for simp, (_n, en) in rows:
            out.write(f"{simp}\t{en}\n")

    print(f"cedict lines={total} kept={kept} unique={len(rows)} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
