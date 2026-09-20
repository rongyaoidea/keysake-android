#!/usr/bin/env python3
"""从 OpenCC 词典生成 Typesake 的简繁转换表（两个紧凑 TSV）。

用法：
    python3 tools/gen_s2t_dict.py --opencc-dir <含 ST/TS 四个 txt 的目录> \
        --out-s2t app/src/main/assets/s2t.tsv --out-t2s app/src/main/assets/t2s.tsv

数据来源：OpenCC（https://github.com/BYVoid/OpenCC），词典许可 Apache-2.0。
输出格式：`源<TAB>目标`，按（长度降序, 源）排序，运行时做最长匹配。
多目标（如 发 -> 發/髮）只取第一个；短语表优先（由长度排序天然保证）。
"""

import argparse
import os
import sys

MAX_KEY = 8  # 最长键（字/短语），控制内存与匹配成本


def read_pairs(path: str):
    if not os.path.exists(path):
        return []
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            key = parts[0].strip()
            value = parts[1].strip().split(" ")[0].strip()  # 多目标取第一个
            if not key or not value or key == value:
                continue
            if len(key) > MAX_KEY or len(value) > MAX_KEY:
                continue
            out.append((key, value))
    return out


def write_tsv(path: str, pairs) -> int:
    best = {}
    for k, v in pairs:
        # 同键只保留第一条（OpenCC 顺序即可信）
        if k not in best:
            best[k] = v
    rows = sorted(best.items(), key=lambda kv: (-len(kv[0]), kv[0]))
    with open(path, "w", encoding="utf-8") as out:
        for k, v in rows:
            out.write(f"{k}\t{v}\n")
    return len(rows)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--opencc-dir", required=True)
    ap.add_argument("--out-s2t", required=True, help="简 -> 繁")
    ap.add_argument("--out-t2s", required=True, help="繁 -> 简")
    args = ap.parse_args()

    d = args.opencc_dir
    s2t = read_pairs(os.path.join(d, "STCharacters.txt")) + read_pairs(
        os.path.join(d, "STPhrases.txt")
    )
    t2s = read_pairs(os.path.join(d, "TSCharacters.txt")) + read_pairs(
        os.path.join(d, "TSPhrases.txt")
    )
    n1 = write_tsv(args.out_s2t, s2t)
    n2 = write_tsv(args.out_t2s, t2s)
    print(f"s2t={n1} entries -> {args.out_s2t}")
    print(f"t2s={n2} entries -> {args.out_t2s}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
