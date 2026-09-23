//! 简繁转换（OpenCC 词典，本地最长匹配）。
//!
//! 两张表：`s2t.tsv`（简->繁）与 `t2s.tsv`（繁->简），由 `tools/gen_s2t_dict.py` 生成。
//! 转换策略：最长匹配（最多 8 字短语），未命中则按单字表，仍无映射则原样保留。
//! 词典只在用户开启"输出繁体"时载入，避免常驻内存。

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// 首字 -> [(键长, 键, 值)]，按键长降序
type Index = HashMap<char, Vec<(usize, String, String)>>;

fn slot(trad: bool) -> &'static Mutex<Option<Index>> {
    static S2T: OnceLock<Mutex<Option<Index>>> = OnceLock::new();
    static T2S: OnceLock<Mutex<Option<Index>>> = OnceLock::new();
    if trad {
        S2T.get_or_init(|| Mutex::new(None))
    } else {
        T2S.get_or_init(|| Mutex::new(None))
    }
}

fn build(path: &str) -> Option<Index> {
    let data = std::fs::read_to_string(path).ok()?;
    let mut idx: Index = HashMap::new();
    for line in data.lines() {
        let Some((k, v)) = line.split_once('\t') else {
            continue;
        };
        let (k, v) = (k.trim(), v.trim());
        if k.is_empty() || v.is_empty() {
            continue;
        }
        if let Some(first) = k.chars().next() {
            idx.entry(first)
                .or_default()
                .push((k.chars().count(), k.to_string(), v.to_string()));
        }
    }
    for list in idx.values_mut() {
        list.sort_by_key(|b| std::cmp::Reverse(b.0));
    }
    Some(idx)
}

fn entries(idx: &Index) -> usize {
    idx.values().map(|v| v.len()).sum()
}

/// 载入 / 释放两张表。返回 (s2t 词条数, t2s 词条数)。
pub fn load_dicts(s2t_path: &str, t2s_path: &str, enabled: bool) -> (usize, usize) {
    let mut n1 = 0;
    let mut n2 = 0;
    if enabled {
        {
            let mut g = slot(true).lock().unwrap_or_else(|e| e.into_inner());
            *g = build(s2t_path);
            n1 = g.as_ref().map(entries).unwrap_or(0);
        }
        {
            let mut g = slot(false).lock().unwrap_or_else(|e| e.into_inner());
            *g = build(t2s_path);
            n2 = g.as_ref().map(entries).unwrap_or(0);
        }
    } else {
        {
            let mut g = slot(true).lock().unwrap_or_else(|e| e.into_inner());
            *g = None;
        }
        {
            let mut g = slot(false).lock().unwrap_or_else(|e| e.into_inner());
            *g = None;
        }
    }
    (n1, n2)
}

pub fn ready(trad: bool) -> bool {
    slot(trad)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .is_some()
}

fn convert(trad: bool, text: &str) -> String {
    let guard = slot(trad).lock().unwrap_or_else(|e| e.into_inner());
    let Some(idx) = guard.as_ref() else {
        return text.to_string();
    };
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut i = 0usize;
    while i < chars.len() {
        let mut hit: Option<(usize, &str)> = None;
        if let Some(list) = idx.get(&chars[i]) {
            for (len, k, v) in list {
                if i + len <= chars.len() {
                    let seg: String = chars[i..i + len].iter().collect();
                    if seg == *k {
                        hit = Some((*len, v));
                        break;
                    }
                }
            }
        }
        match hit {
            Some((len, v)) => {
                out.push_str(v);
                i += len;
            }
            None => {
                out.push(chars[i]);
                i += 1;
            }
        }
    }
    out
}

/// 简 -> 繁
pub fn to_traditional(text: &str) -> String {
    convert(true, text)
}

/// 繁 -> 简
pub fn to_simplified(text: &str) -> String {
    convert(false, text)
}

/// 简单工具：判断文本里是否已经有繁体（用于设置页提示，可选）。
pub fn looks_traditional(text: &str) -> bool {
    to_simplified(text) != text
}

#[cfg(test)]
mod tests {
    use super::*;

    fn load_real() -> (usize, usize) {
        let base = concat!(env!("CARGO_MANIFEST_DIR"), "/../app/src/main/assets");
        load_dicts(&format!("{base}/s2t.tsv"), &format!("{base}/t2s.tsv"), true)
    }

    #[test]
    fn loads_real_tables() {
        let _g = crate::test_lock();
        let (n1, n2) = load_real();
        assert!(n1 > 30_000, "s2t table too small: {n1}");
        assert!(n2 > 3_000, "t2s table too small: {n2}");
        assert!(ready(true) && ready(false));
    }

    #[test]
    fn converts_phrases_and_chars() {
        let _g = crate::test_lock();
        load_real();
        // 短语级（一简对多繁）：发 -> 髮 / 發 由短语表决定
        assert_eq!(to_traditional("头发"), "頭髮");
        assert_eq!(to_traditional("理发"), "理髮");
        // 字级
        assert_eq!(to_traditional("学习英语"), "學習英語");
        assert_eq!(to_traditional("软件"), "軟件");
        // 繁 -> 简
        assert_eq!(to_simplified("頭髮"), "头发");
        assert_eq!(to_simplified("學習英語"), "学习英语");
        // 无映射内容原样保留
        assert_eq!(to_traditional("hello 123"), "hello 123");
    }

    #[test]
    fn disable_releases_tables() {
        let _g = crate::test_lock();
        load_real();
        assert!(ready(true));
        load_dicts("/nonexistent", "/nonexistent", false);
        assert!(!ready(true) && !ready(false));
        assert_eq!(to_traditional("头发"), "头发");
        load_real();
    }
}
