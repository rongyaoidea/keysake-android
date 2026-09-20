#!/usr/bin/env python3
"""生成句库 sentbank.bin（Tatoeba 中英对齐句对，CC-BY 2.0 FR）。

用法：
  python3 tools/gen_sentbank.py --en Tatoeba.en-zh_cn.en --zh Tatoeba.en-zh_cn.zh_cn \
      --out app/src/main/assets/sentbank.bin [--max 120000]

格式（与 rust-core/src/sentbank.rs 严格一致）：
  "TSSB"|ver u8|flags u8|rsv u16|sent_count u32|gram_count u32
  sent_idx: count×(zh_off u32, zh_len u16, en_off u32, en_len u16)   # 按 zh 升序
  gram_idx: gram_count×(g_off u32, g_len u16, first_posting u32, n_posting u16)
  postings: gram_count×60×u32（不足补 0）
  zh_blob | en_blob | gram_blob
"""
import argparse, re, struct, sys
from collections import defaultdict

MAX_POSTINGS = 60
BAD = re.compile(r"http|www\.|[0-9]{4,}|[\x00-\x1f]")


def bigrams(text: str):
    chars = [c for c in text if not c.isspace()]
    out = []
    for i in range(len(chars) - 1):
        g = chars[i] + chars[i + 1]
        if g not in out:
            out.append(g)
    return out


def ok_pair(zh: str, en: str) -> bool:
    if not zh or not en or BAD.search(zh) or BAD.search(en):
        return False
    if not (2 <= len(zh) <= 40) or not (1 <= len(en.split()) <= 40):
        return False
    ratio = len(en) / max(1, len(zh))
    return 0.3 <= ratio <= 6.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--en", required=True)
    ap.add_argument("--zh", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max", type=int, default=120000)
    a = ap.parse_args()

    pairs, seen = [], set()
    with open(a.en, encoding="utf-8", errors="ignore") as fe, open(
        a.zh, encoding="utf-8", errors="ignore"
    ) as fz:
        for en, zh in zip(fe, fz):
            zh, en = zh.strip(), en.strip()
            if not ok_pair(zh, en) or zh in seen:
                continue
            seen.add(zh)
            pairs.append((zh, en))
            if len(pairs) >= a.max:
                break
    if not pairs:
        print("sentbank: no usable pairs", file=sys.stderr)
        return 1
    pairs.sort(key=lambda p: p[0])

    zhb, enb, idx = bytearray(), bytearray(), bytearray()
    for zh, en in pairs:
        zo = len(zhb); zhb += zh.encode()
        eo = len(enb); enb += en.encode()
        idx += struct.pack("<IHIH", zo, len(zh.encode()), eo, len(en.encode()))

    grams = defaultdict(list)
    for i, (zh, _) in enumerate(pairs):
        for g in bigrams(zh):
            grams[g].append(i)

    gb, gidx, postings = bytearray(), bytearray(), []
    for g in sorted(grams):
        go = len(gb); gb += g.encode()
        ids = grams[g][:MAX_POSTINGS]
        first = len(postings)
        postings.extend(ids)
        gidx += struct.pack("<IHIH", go, len(g.encode()), first, len(ids))
    total_slots = len(grams) * MAX_POSTINGS
    postings.extend([0] * (total_slots - len(postings)))
    post = b"".join(struct.pack("<I", x) for x in postings)

    with open(a.out, "wb") as f:
        f.write(b"TSSB" + bytes([1, 0]) + struct.pack("<H", 0))
        f.write(struct.pack("<II", len(pairs), len(grams)))
        f.write(idx); f.write(gidx); f.write(post)
        f.write(zhb); f.write(enb); f.write(gb)
    print(f"sentbank: {len(pairs)} pairs, {len(grams)} grams -> {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
