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
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for line in text.lines() {
        let word = line.split_whitespace().next().unwrap_or("").trim();
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
}
