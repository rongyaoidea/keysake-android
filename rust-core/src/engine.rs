//! 拼音 -> 中文候选、联想预测、词频学习（L0）。
//!
//! 候选组装顺序（贴合主流输入法手感）：
//! 1. 精确整词/单字匹配（词典按词频排序，用户 pin 自动置顶）
//! 2. 整句 Viterbi 组合（`top_k_compositions`，解决 "jintiankaihui" -> 今天开会）
//! 3. 前缀补全（输入未打完时给整词，如 "nih" -> 你好…）
//!
//! 每步结果去重并截断到 `limit`，全程无 panic 路径。

use inputx_pinyin::{L0Snapshot, PinyinEngine};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// 进程级引擎（首次调用时初始化，字典为 include_bytes 常量，初始化 ~34µs）。
pub fn engine() -> &'static PinyinEngine {
    static ENGINE: OnceLock<PinyinEngine> = OnceLock::new();
    ENGINE.get_or_init(PinyinEngine::new)
}

/// 热路径结果缓存：同一 buffer 的重复查询（连击/回退）直接命中，避免重复 Viterbi。
fn cache() -> &'static Mutex<HashMap<String, Vec<String>>> {
    static CACHE: OnceLock<Mutex<HashMap<String, Vec<String>>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

const CACHE_CAP: usize = 96;

/// 词典条目数（设置页展示用）。
pub fn lexicon_size() -> usize {
    engine().dict().len()
}

/// 规整输入：仅保留 ascii 字母并转小写（IME 里也不会出现别的字符）。
pub fn normalize(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphabetic())
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

fn push_unique(out: &mut Vec<String>, word: String, limit: usize) {
    if out.len() >= limit || word.is_empty() {
        return;
    }
    if !out.iter().any(|w| w == &word) {
        out.push(word);
    }
}

/// 针对指定引擎取候选（测试可传独立引擎，避免全局状态互扰）。
pub fn candidates_with(eng: &PinyinEngine, input: &str, limit: usize) -> Vec<String> {
    let compact = normalize(input);
    if compact.is_empty() || limit == 0 {
        return Vec::new();
    }
    let dict = eng.dict();
    let mut out: Vec<String> = Vec::with_capacity(limit);

    // 1) 精确匹配（整词/单字，freq 降序，L0 pin 置顶）
    let mut exact: Vec<String> = Vec::new();
    dict.lookup_into(&compact, &mut exact);
    for w in exact {
        push_unique(&mut out, w, limit);
    }

    // 2) 整句组合（仅长缓冲；短输入无意义且慢）
    if out.len() < limit && compact.len() >= 4 {
        for (_score, word) in dict.top_k_compositions(&compact, 6) {
            push_unique(&mut out, word, limit);
            if out.len() >= limit {
                break;
            }
        }
    }

    // 3) 前缀补全（输入未打完时给整词；限制收集条数，迭代本身 ≤ 数毫秒）
    if out.len() < limit && compact.len() >= 2 {
        let mut hits: Vec<(u64, String)> = Vec::new();
        let mut visited = 0usize;
        dict.prefix_for_each(&compact, |code, word, freq| {
            visited += 1;
            if visited <= 400 {
                // 只收“补全代价小”的词（剩余拼音 ≤ 5 个字母），避免推荐超长词条
                if code.len().saturating_sub(compact.len()) <= 5 {
                    hits.push((freq, word.to_string()));
                }
            }
        });
        hits.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
        for (_, w) in hits {
            push_unique(&mut out, w, limit);
            if out.len() >= limit {
                break;
            }
        }
    }

    out
}

/// 带缓存的候选查询（IME 热路径入口）。
pub fn candidates(input: &str, limit: usize) -> Vec<String> {
    let compact = normalize(input);
    if compact.is_empty() {
        return Vec::new();
    }
    if let Ok(c) = cache().lock() {
        if let Some(hit) = c.get(&compact) {
            let mut v = hit.clone();
            v.truncate(limit);
            return v;
        }
    }
    let out = candidates_with(engine(), &compact, limit);
    if let Ok(mut c) = cache().lock() {
        if c.len() >= CACHE_CAP {
            c.clear();
        }
        c.insert(compact, out.clone());
    }
    out
}

/// 联想：给定上一个词，预测下一个词（bigram FST）。
pub fn predict_next(prev: &str, limit: usize) -> Vec<String> {
    let p = prev.trim();
    if p.is_empty() || limit == 0 {
        return Vec::new();
    }
    engine()
        .dict()
        .predict_next_words(p, limit)
        .into_iter()
        .map(|(w, _c)| w)
        .collect()
}

/// 记录用户选词（3 连选自动 pin 到候选首位），返回该拼音的最新候选序。
pub fn record_pick(pinyin: &str, word: &str, limit: usize) -> Vec<String> {
    let compact = normalize(pinyin);
    let word = word.trim();
    if compact.is_empty() || word.is_empty() {
        return Vec::new();
    }
    engine().dict().record_pick(&compact, word);
    if let Ok(mut c) = cache().lock() {
        c.remove(&compact);
    }
    candidates_with(engine(), &compact, limit)
}

/// 导出 L0 学习快照（供持久化）。
pub fn export_l0() -> L0Snapshot {
    engine().dict().export_l0()
}

/// 导入 L0 学习快照，返回导入条数。
pub fn import_l0(pins: Vec<(String, String)>, pick_counts: Vec<(String, String, u32)>) -> usize {
    if let Ok(mut c) = cache().lock() {
        c.clear();
    }
    engine().dict().import_l0(L0Snapshot { pins, pick_counts })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh() -> PinyinEngine {
        PinyinEngine::new()
    }

    #[test]
    fn engine_loads_real_dict() {
        assert!(lexicon_size() > 100_000, "expected a large embedded dict");
    }

    #[test]
    fn exact_words_rank_first() {
        let e = fresh();
        let c = candidates_with(&e, "nihao", 8);
        assert_eq!(c.first().map(String::as_str), Some("你好"));
        let c = candidates_with(&e, "zhongguo", 8);
        assert!(c.contains(&"中国".to_string()));
        let c = candidates_with(&e, "woaini", 8);
        assert!(c.contains(&"我爱你".to_string()));
    }

    #[test]
    fn sentence_composition_works() {
        // P0 修复点：长句输入此前无候选
        let e = fresh();
        let c = candidates_with(&e, "jintiankaihui", 8);
        assert!(
            c.iter().any(|w| w.contains("今天")),
            "expected a full-sentence composition, got {c:?}"
        );
    }

    #[test]
    fn prefix_completion_for_partial_input() {
        let e = fresh();
        let c = candidates_with(&e, "nih", 8);
        assert!(!c.is_empty(), "partial input should offer completions");
    }

    #[test]
    fn single_syllable_gives_common_chars() {
        let e = fresh();
        let c = candidates_with(&e, "ni", 8);
        assert_eq!(c.first().map(String::as_str), Some("你"));
    }

    #[test]
    fn empty_and_junk_input_is_safe() {
        let e = fresh();
        assert!(candidates_with(&e, "", 8).is_empty());
        assert!(candidates_with(&e, "   ", 8).is_empty());
        assert!(candidates_with(&e, "!!!", 8).is_empty());
        let c = candidates_with(&e, "ni hao", 8);
        assert_eq!(c.first().map(String::as_str), Some("你好"));
    }

    #[test]
    fn user_learning_pins_after_three_picks() {
        let e = fresh();
        let before = candidates_with(&e, "ni", 8);
        assert_eq!(before.first().map(String::as_str), Some("你"));
        // 选一个非首选字三次 -> 自动 pin
        let mut target = String::new();
        for w in candidates_with(&e, "ni", 8) {
            if w != "你" {
                target = w;
                break;
            }
        }
        assert!(!target.is_empty());
        for _ in 0..3 {
            e.dict().record_pick("ni", &target);
        }
        let after = candidates_with(&e, "ni", 8);
        assert_eq!(
            after.first(),
            Some(&target),
            "L0 pin should promote the pick"
        );
        let snap = e.dict().export_l0();
        assert_eq!(snap.pins.len(), 1);
    }

    #[test]
    fn prediction_and_limits() {
        let e = fresh();
        let p = predict_next("我", 5);
        assert!(!p.is_empty());
        assert!(p.len() <= 5);
        assert!(predict_next("", 5).is_empty());
        let c = candidates_with(&e, "nihao", 2);
        assert!(c.len() <= 2);
    }
}
