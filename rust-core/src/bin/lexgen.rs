//! 词库生成器：把开源词库（如 jieba dict.txt：`词 词频`）转成 `lex.bin`。
//!
//!   cargo run --release --bin lexgen -- --input dict.txt --out ../app/src/main/assets/lex.bin
//! 拼音由引擎反查（多音字取首选），只保留 2–6 字纯中文词，按拼音排序后写紧凑二进制。

use std::io::Write;

const MAX_WORDS: usize = 400_000;

fn main() {
    let mut args = std::env::args().skip(1);
    let (mut input, mut out) = (String::new(), String::new());
    while let Some(a) = args.next() {
        match a.as_str() {
            "--input" => input = args.next().unwrap_or_default(),
            "--out" => out = args.next().unwrap_or_default(),
            _ => {}
        }
    }
    if input.is_empty() || out.is_empty() {
        eprintln!("usage: lexgen --input <wordlist.txt> --out <lex.bin>");
        std::process::exit(2);
    }
    let text = match std::fs::read_to_string(&input) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("read {input}: {e}");
            std::process::exit(1);
        }
    };

    let mut pairs: Vec<(String, String)> = Vec::new();
    let mut freq_of: std::collections::HashMap<String, u64> = std::collections::HashMap::new();
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for line in text.lines() {
        let mut it = line.split_whitespace();
        let word = it.next().unwrap_or("").trim();
        let freq: u64 = it.next().and_then(|f| f.parse().ok()).unwrap_or(1);
        let n = word.chars().count();
        if !(2..=6).contains(&n) {
            continue;
        }
        if !word
            .chars()
            .all(|c| (0x4E00..=0x9FFF).contains(&(c as u32)))
        {
            continue;
        }
        let Some(py) = typesake_core::userdic::name_to_pinyin(word) else {
            continue;
        };
        if py.len() > 24 || !seen.insert(format!("{py}\t{word}")) {
            continue;
        }
        freq_of.insert(word.to_string(), freq);
        pairs.push((py, word.to_string()));
        if pairs.len() >= MAX_WORDS {
            break;
        }
    }
    pairs.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)));

    let (mut kb, mut vb, mut idx) = (Vec::new(), Vec::new(), Vec::new());
    for (k, v) in &pairs {
        let ko = kb.len() as u32;
        kb.extend_from_slice(k.as_bytes());
        let vo = vb.len() as u32;
        vb.extend_from_slice(v.as_bytes());
        idx.extend_from_slice(&ko.to_le_bytes());
        idx.extend_from_slice(&vo.to_le_bytes());
        idx.extend_from_slice(&(k.len() as u16).to_le_bytes());
        idx.extend_from_slice(&(v.len() as u16).to_le_bytes());
    }
    let mut f = match std::fs::File::create(&out) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("create {out}: {e}");
            std::process::exit(1);
        }
    };
    let _ = f.write_all(b"TSLX");
    let _ = f.write_all(&[1u8, 0u8]);
    let _ = f.write_all(&0u16.to_le_bytes());
    let _ = f.write_all(&(pairs.len() as u32).to_le_bytes());
    let _ = f.write_all(&idx);
    let _ = f.write_all(&kb);
    let _ = f.write_all(&vb);
    println!(
        "lexgen: {} entries -> {} ({} bytes)",
        pairs.len(),
        out,
        12 + idx.len() + kb.len() + vb.len()
    );

    // ---- 声母串索引（gram.bin）：混简拼用 ----
    let mut grams: std::collections::HashMap<String, Vec<(u64, String)>> =
        std::collections::HashMap::new();
    for (py, word) in &pairs {
        let n_chars = word.chars().count();
        if !(2..=4).contains(&n_chars) {
            continue;
        }
        let segs = inputx_pinyin::segment(py);
        let Some(first) = segs.first() else { continue };
        let syls = first.syllables.len();
        if !(2..=3).contains(&syls) || syls != n_chars {
            continue;
        }
        let gram: String = first
            .syllables
            .iter()
            .filter_map(|x| x.chars().next())
            .collect();
        if gram.len() < 2 {
            continue;
        }
        let f = freq_of.get(word).copied().unwrap_or(1);
        grams.entry(gram).or_default().push((f, word.clone()));
    }
    let mut keys: Vec<String> = grams.keys().cloned().collect();
    keys.sort();
    let (mut gb, mut vb2, mut gidx) = (Vec::new(), Vec::new(), Vec::new());
    let mut gram_count = 0usize;
    for g in &keys {
        let mut entries = grams.get(g).cloned().unwrap_or_default();
        // 词频降序（jieba 词频即常用度）：保证「今天」这类高频词不会被拼音序挤掉
        entries.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        let mut words: Vec<String> = entries.into_iter().map(|(_, w)| w).collect();
        words.dedup();
        words.truncate(32);
        if words.is_empty() {
            continue;
        }
        let go = gb.len() as u32;
        gb.extend_from_slice(g.as_bytes());
        let vo = vb2.len() as u32;
        vb2.extend_from_slice(words.join("\u{1F}").as_bytes());
        gidx.extend_from_slice(&go.to_le_bytes());
        gidx.extend_from_slice(&(g.len() as u16).to_le_bytes());
        gidx.extend_from_slice(&vo.to_le_bytes());
        gidx.extend_from_slice(&(words.join("\u{1F}").len() as u16).to_le_bytes());
        gram_count += 1;
    }
    let gram_out = out.replace("lex.bin", "gram.bin");
    if gram_out != out {
        if let Ok(mut f2) = std::fs::File::create(&gram_out) {
            let _ = f2.write_all(b"TSGR");
            let _ = f2.write_all(&[1u8, 0, 0, 0]);
            let _ = f2.write_all(&(gram_count as u32).to_le_bytes());
            let _ = f2.write_all(&gidx);
            let _ = f2.write_all(&gb);
            let _ = f2.write_all(&vb2);
            println!(
                "lexgen: {} gram keys -> {} ({} bytes)",
                gram_count,
                gram_out,
                8 + gidx.len() + gb.len() + vb2.len()
            );
        }
    }
}
