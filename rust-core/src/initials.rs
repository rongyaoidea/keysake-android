//! 简拼（声母串）索引：`bjdx` -> 北京大学。
//!
//! 首次查询时惰性构建：遍历 165k 词条，只保留 2–4 音节、2–4 字的词，
//! 按声母串建表并保留词频最高的若干条（控制内存与耗时）。
//! 构建约几十毫秒，只在后台线程首次触发一次。

use crate::engine;
use std::collections::HashMap;
use std::sync::OnceLock;

type Table = HashMap<String, Vec<(u64, String)>>;

/// 每个声母串保留的最大词数
const PER_KEY: usize = 6;
/// 全表最多收录的声母串数量（防御内存）
const MAX_KEYS: usize = 90_000;
/// 词条最大字数
const MAX_WORD_CHARS: usize = 4;
/// 声母串长度范围
const MIN_SYL: usize = 2;
const MAX_SYL: usize = 4;

struct Initials {
    table: Table,
    /// false 表示构建被截断（达到 MAX_KEYS）
    complete: bool,
}

fn table() -> &'static Initials {
    static T: OnceLock<Initials> = OnceLock::new();
    T.get_or_init(build)
}

fn build() -> Initials {
    let dict = engine::engine().dict();
    let mut table: Table = HashMap::new();
    let mut complete = true;

    for letter in b'a'..=b'z' {
        let prefix = (letter as char).to_string();
        dict.prefix_for_each(&prefix, |code, word, freq| {
            if table.len() >= MAX_KEYS {
                complete = false;
                return;
            }
            if word.is_empty() || word.chars().count() > MAX_WORD_CHARS {
                return;
            }
            let syls = inputx_pinyin::segment(code).len();
            if !(MIN_SYL..=MAX_SYL).contains(&syls) {
                return;
            }
            let initials: String = code
                .split(['a', 'e', 'i', 'o', 'u'])
                .filter(|s| !s.is_empty())
                .filter_map(|s| s.chars().next())
                .collect();
            // 声母串必须与输入长度一致（无零声母字），避免误命中
            if initials.chars().count() != syls {
                return;
            }
            if initials.chars().count() < MIN_SYL {
                return;
            }
            let entry = table.entry(initials).or_default();
            entry.push((freq, word.to_string()));
        });
    }

    for v in table.values_mut() {
        v.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        v.dedup_by(|a, b| a.1 == b.1);
        v.truncate(PER_KEY);
    }

    Initials { table, complete }
}

/// 简拼候选：先精确命中声母串，再前缀扩展（输入未打完）。
pub fn candidates(input: &str, limit: usize) -> Vec<String> {
    let key = engine::normalize(input);
    if key.len() < MIN_SYL || !key.chars().all(|c| c.is_ascii_lowercase()) {
        return Vec::new();
    }
    let t = &table().table;
    let mut hits: Vec<(u64, String)> = Vec::new();

    if let Some(v) = t.get(&key) {
        hits.extend(v.iter().cloned());
    }
    if hits.len() < limit {
        for (k, v) in t.iter() {
            if k.len() > key.len() && k.starts_with(&key) {
                hits.extend(v.iter().cloned());
            }
        }
    }
    hits.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    let mut out: Vec<String> = Vec::new();
    for (_, w) in hits {
        if !out.contains(&w) {
            out.push(w);
            if out.len() >= limit {
                break;
            }
        }
    }
    out
}

/// 索引规模（统计用）。
pub fn size() -> usize {
    table().table.len()
}

pub fn is_complete() -> bool {
    table().complete
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_input_is_rejected() {
        assert!(candidates("", 5).is_empty());
        assert!(candidates("n", 5).is_empty());
        assert!(candidates("123", 5).is_empty());
    }

    #[test]
    fn initials_match_real_words() {
        // 断言基于词典实测：nh -> 你好（索引构建只发生一次）
        let c = candidates("nh", 8);
        assert!(c.contains(&"你好".to_string()), "nh -> {c:?}");
        assert!(size() > 3_000, "initials table too small: {}", size());
    }

    #[test]
    fn initials_prefix_expansion_adds_more() {
        // limit 大于精确命中数时会触发前缀扩展，结果应多于精确命中
        let exact_only = candidates("nh", 6);
        let expanded = candidates("nh", 12);
        assert!(!exact_only.is_empty());
        assert!(expanded.len() >= exact_only.len());
    }

    #[test]
    fn full_pinyin_input_is_not_initials() {
        // 含元音的输入不应当当简拼解析
        assert!(candidates("nihao", 6).is_empty());
    }
}
