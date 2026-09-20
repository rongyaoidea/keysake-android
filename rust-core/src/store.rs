//! 收藏、学习快照、设置与统计的持久化（单文件 JSON + 原子写）。
//!
//! 原子写：先写 `<file>.json.tmp` 再 `rename`，避免进程被杀时留下半截 JSON。

use crate::{biglex, engine, english, s2t, userdic};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SavedPhrase {
    pub chinese: String,
    pub english: String,
    pub saved_at: u64,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct L0File {
    #[serde(default)]
    pub pins: Vec<(String, String)>,
    #[serde(default)]
    pub pick_counts: Vec<(String, String, u32)>,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    /// 模糊音（z/zh、n/l、an/ang…）
    #[serde(default = "default_true")]
    pub fuzzy: bool,
    /// 击键纠错（邻键/漏键/多键/换位）
    #[serde(default = "default_true")]
    pub correction: bool,
    /// 双拼方案（0=全拼 1=小鹤）
    #[serde(default)]
    pub shuangpin: u8,
    /// 输出字形（0=简体 1=繁体）
    #[serde(default)]
    pub script: u8,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            fuzzy: true,
            correction: true,
            shuangpin: 0,
            script: 0,
        }
    }
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct Stats {
    /// 累计上屏词数
    #[serde(default)]
    pub words: u64,
    /// 有输入的日期（本地时区，由宿主传入），最多保留 400 天
    #[serde(default)]
    pub days: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Correction {
    pub typed: String,
    pub word: String,
    #[serde(default = "default_one")]
    pub count: u32,
}

fn default_one() -> u32 {
    1
}

#[derive(Debug, Serialize, Deserialize)]
struct Db {
    #[serde(default = "default_version")]
    version: u32,
    #[serde(default)]
    saved: Vec<SavedPhrase>,
    #[serde(default)]
    l0: L0File,
    #[serde(default)]
    settings: Settings,
    #[serde(default)]
    stats: Stats,
    #[serde(default)]
    corrections: Vec<Correction>,
    /// 删词黑名单：(拼音串, 词)
    #[serde(default)]
    blocked: Vec<(String, String)>,
    /// 个人词库：(拼音, 姓名)
    #[serde(default)]
    user_words: Vec<(String, String)>,
    /// 邮箱域名记忆：(域名, 次数)
    #[serde(default)]
    mail_domains: Vec<(String, u32)>,
}

fn default_version() -> u32 {
    3
}

const MAX_DAYS: usize = 400;

#[derive(Debug, Default)]
struct Store {
    dir: String,
    saved: Vec<SavedPhrase>,
    settings: Settings,
    stats: Stats,
    user_words: Vec<(String, String)>,
}

fn store() -> &'static Mutex<Store> {
    static S: OnceLock<Mutex<Store>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(Store::default()))
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn db_path(dir: &str) -> PathBuf {
    Path::new(dir).join("typesake_db.json")
}

/// 原子写：tmp + rename（同目录，同文件系统）。
fn write_atomic(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let tmp = path.with_extension("json.tmp");
    {
        let mut f = std::fs::File::create(&tmp)?;
        use std::io::Write;
        f.write_all(data)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path)
}

/// 把收藏句子灌进英文翻译记忆（用户自己的表达最优先）。
fn sync_memory(items: &[SavedPhrase]) {
    english::set_memory(
        items
            .iter()
            .map(|p| (p.chinese.clone(), p.english.clone()))
            .collect(),
    );
}

fn write_db_locked(s: &Store) -> Result<(), String> {
    let l0 = engine::export_l0();
    let corrections: Vec<Correction> = engine::export_learned()
        .into_iter()
        .map(|(typed, word, count)| Correction { typed, word, count })
        .collect();
    let db = Db {
        version: 3,
        saved: s.saved.clone(),
        l0: L0File {
            pins: l0.pins,
            pick_counts: l0.pick_counts,
        },
        settings: s.settings.clone(),
        stats: s.stats.clone(),
        corrections,
        blocked: engine::export_blocked(),
        user_words: s.user_words.clone(),
        mail_domains: userdic::domains_snapshot(),
    };
    let data = serde_json::to_vec(&db).map_err(|e| e.to_string())?;
    write_atomic(&db_path(&s.dir), &data).map_err(|e| e.to_string())
}

fn empty_db() -> Db {
    Db {
        version: 3,
        saved: Vec::new(),
        l0: L0File::default(),
        settings: Settings::default(),
        stats: Stats::default(),
        corrections: Vec::new(),
        blocked: Vec::new(),
        user_words: Vec::new(),
        mail_domains: Vec::new(),
    }
}

/// 初始化存储目录：载入收藏 / L0 / 设置 / 统计 / 纠错记忆。返回 (收藏数, pins 数)。
pub fn init(dir: &str) -> Result<(usize, usize), String> {
    let db = match std::fs::read(db_path(dir)) {
        Ok(bytes) => serde_json::from_slice::<Db>(&bytes).unwrap_or_else(|_| empty_db()),
        Err(_) => empty_db(),
    };
    let pins = db.l0.pins.len();
    engine::import_l0(db.l0.pins, db.l0.pick_counts);
    engine::import_learned(
        db.corrections
            .into_iter()
            .map(|c| (c.typed, c.word, c.count))
            .collect(),
    );
    // 大词典（可选）：assets 由宿主拷到目录下的 en_dict.tsv
    let _ = english::load_dict(&Path::new(dir).join("en_dict.tsv").to_string_lossy());
    // 大词库（可选）：CI 生成的 lex.bin
    let _ = biglex::load(&Path::new(dir).join("lex.bin").to_string_lossy());
    engine::import_blocked(db.blocked);
    engine::set_options(
        db.settings.fuzzy,
        db.settings.correction,
        db.settings.shuangpin,
    );

    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.dir = dir.to_string();
    s.saved = db.saved;
    s.settings = db.settings;
    s.stats = db.stats;
    s.user_words = db.user_words;
    userdic::set_domains(db.mail_domains);
    sync_memory(&s.saved);
    // 预热简拼索引（16.5 万词条遍历一次，避免首次打字时卡一下）
    let _ = engine::lexicon_info();
    Ok((s.saved.len(), pins))
}

pub fn settings() -> Settings {
    store()
        .lock()
        .map(|s| s.settings.clone())
        .unwrap_or_default()
}

/// 写入输入选项并落盘。
pub fn set_settings(
    fuzzy: bool,
    correction: bool,
    shuangpin: u8,
    script: u8,
) -> Result<(), String> {
    engine::set_options(fuzzy, correction, shuangpin);
    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.settings = Settings {
        fuzzy,
        correction,
        shuangpin,
        script,
    };
    let dir = s.dir.clone();
    write_db_locked(&s)?;
    drop(s);
    let _ = s2t::load_dicts(
        &Path::new(&dir).join("s2t.tsv").to_string_lossy(),
        &Path::new(&dir).join("t2s.tsv").to_string_lossy(),
        script == 1,
    );
    Ok(())
}

/// 修改收藏的英文（收藏可编辑：反哺翻译记忆）。按 (中文, 旧英文) 定位。
pub fn update_saved(chinese: &str, old_english: &str, new_english: &str) -> Result<bool, String> {
    let (cn, old, new) = (chinese.trim(), old_english.trim(), new_english.trim());
    if cn.is_empty() || new.is_empty() {
        return Err("中文与新英文都不能为空".to_string());
    }
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let mut hit = false;
    for p in s.saved.iter_mut() {
        if p.chinese == cn && p.english == old {
            p.english = new.to_string();
            p.saved_at = now_secs();
            hit = true;
            break;
        }
    }
    if hit {
        // 去重：改完后若与既有条目重复，只保留一条
        let mut seen: Vec<(String, String)> = Vec::new();
        s.saved.retain(|p| {
            let k = (p.chinese.clone(), p.english.clone());
            if seen.contains(&k) {
                false
            } else {
                seen.push(k);
                true
            }
        });
        write_db_locked(&s)?;
        sync_memory(&s.saved);
    }
    Ok(hit)
}

/// 删除单条收藏。
pub fn delete_saved(chinese: &str, english: &str) -> Result<bool, String> {
    let (cn, en) = (chinese.trim(), english.trim());
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let before = s.saved.len();
    s.saved.retain(|p| !(p.chinese == cn && p.english == en));
    let hit = s.saved.len() != before;
    if hit {
        write_db_locked(&s)?;
        sync_memory(&s.saved);
    }
    Ok(hit)
}

/// 记一次上屏（词数 + 当天活跃）。
pub fn bump_stats(words: u64, today: &str) -> Result<(), String> {
    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.stats.words = s.stats.words.saturating_add(words);
    let day = today.trim();
    if !day.is_empty() && !s.stats.days.iter().any(|d| d == day) {
        s.stats.days.push(day.to_string());
        if s.stats.days.len() > MAX_DAYS {
            let cut = s.stats.days.len() - MAX_DAYS;
            s.stats.days.drain(0..cut);
        }
    }
    write_db_locked(&s)
}

pub fn stats() -> Stats {
    store().lock().map(|s| s.stats.clone()).unwrap_or_default()
}

pub fn saved_count() -> usize {
    store().lock().map(|s| s.saved.len()).unwrap_or(0)
}

/// 收藏（同 中文+英文 去重，更新时间戳）。
pub fn save_phrase(chinese: &str, english: &str) -> Result<SavedPhrase, String> {
    let (cn, en) = (chinese.trim(), english.trim());
    if cn.is_empty() {
        return Err("中文不能为空".to_string());
    }
    let mut s = store().lock().map_err(|e| e.to_string())?;
    if let Some(p) = s
        .saved
        .iter_mut()
        .find(|p| p.chinese == cn && p.english == en)
    {
        p.saved_at = now_secs();
        let out = p.clone();
        write_db_locked(&s)?;
        return Ok(out);
    }
    let p = SavedPhrase {
        chinese: cn.to_string(),
        english: en.to_string(),
        saved_at: now_secs(),
    };
    s.saved.push(p.clone());
    write_db_locked(&s)?;
    sync_memory(&s.saved);
    Ok(p)
}

pub fn list_saved() -> Vec<SavedPhrase> {
    let mut items = store().lock().map(|s| s.saved.clone()).unwrap_or_default();
    items.sort_by_key(|p| std::cmp::Reverse(p.saved_at));
    items
}

pub fn clear_saved() -> Result<usize, String> {
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let n = s.saved.len();
    s.saved.clear();
    write_db_locked(&s)?;
    sync_memory(&s.saved);
    Ok(n)
}

/// 导入姓名（从剪贴板粘的一串名字），返回新增条数。
pub fn import_names(text: &str) -> Result<usize, String> {
    let names = userdic::parse_names(text);
    if names.is_empty() {
        return Err("没有解析到姓名（每行一个，或用逗号分隔）".to_string());
    }
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let mut added = 0usize;
    for name in names {
        let Some(py) = userdic::name_to_pinyin(&name) else {
            continue;
        };
        if s.user_words.iter().any(|(p, w)| p == &py && w == &name) {
            continue;
        }
        s.user_words.push((py, name));
        added += 1;
        if s.user_words.len() >= 2000 {
            break;
        }
    }
    write_db_locked(&s)?;
    Ok(added)
}

pub fn user_words() -> Vec<(String, String)> {
    store()
        .lock()
        .map(|s| s.user_words.clone())
        .unwrap_or_default()
}

/// 命中给定拼音（精确或前缀）的姓名，最多 limit 条。
pub fn user_words_for(pinyin: &str, limit: usize) -> Vec<String> {
    let key = pinyin.trim().to_ascii_lowercase();
    if key.is_empty() {
        return Vec::new();
    }
    let Ok(s) = store().lock() else {
        return Vec::new();
    };
    let mut out: Vec<String> = Vec::new();
    for (py, word) in s.user_words.iter().filter(|(py, _)| py == &key) {
        let _ = py;
        if !out.contains(word) {
            out.push(word.clone());
        }
    }
    if out.len() < limit {
        for (_py, word) in s
            .user_words
            .iter()
            .filter(|(py, _)| py.starts_with(&key) && py != &key)
        {
            if !out.contains(word) {
                out.push(word.clone());
                if out.len() >= limit {
                    break;
                }
            }
        }
    }
    out.truncate(limit);
    out
}

pub fn clear_user_words() -> usize {
    let Ok(mut s) = store().lock() else { return 0 };
    let n = s.user_words.len();
    s.user_words.clear();
    let _ = write_db_locked(&s);
    n
}

/// 记住邮箱域名（用户点过哪个后缀）。
pub fn remember_mail_domain(domain: &str) -> Result<usize, String> {
    userdic::remember_domain(domain);
    let s = store().lock().map_err(|e| e.to_string())?;
    write_db_locked(&s)?;
    Ok(userdic::domains_snapshot().len())
}

pub fn mail_domains() -> Vec<(String, u32)> {
    userdic::domains_snapshot()
}

/// 导出整库 JSON（本地备份，无网络无权限）。
pub fn export_json() -> Result<String, String> {
    let s = store().lock().map_err(|e| e.to_string())?;
    let l0 = engine::export_l0();
    let db = Db {
        version: 3,
        saved: s.saved.clone(),
        l0: L0File {
            pins: l0.pins,
            pick_counts: l0.pick_counts,
        },
        settings: s.settings.clone(),
        stats: s.stats.clone(),
        corrections: engine::export_learned()
            .into_iter()
            .map(|(typed, word, count)| Correction { typed, word, count })
            .collect(),
        blocked: engine::export_blocked(),
        user_words: s.user_words.clone(),
        mail_domains: userdic::domains_snapshot(),
    };
    serde_json::to_string_pretty(&db).map_err(|e| e.to_string())
}

/// 从 JSON 恢复整库（覆盖当前数据），返回 (收藏数, pins 数)。
pub fn import_json(text: &str) -> Result<(usize, usize), String> {
    serde_json::from_str::<Db>(text).map_err(|e| format!("JSON 解析失败：{e}"))?;
    let dir = {
        let s = store().lock().map_err(|e| e.to_string())?;
        s.dir.clone()
    };
    if dir.is_empty() {
        return Err("存储目录未初始化".to_string());
    }
    write_atomic(&db_path(&dir), text.as_bytes()).map_err(|e| e.to_string())?;
    init(&dir)
}

/// 学习/设置数据落盘（选词、纠错记忆后调用）。
pub fn persist() -> Result<(), String> {
    let s = store().lock().map_err(|e| e.to_string())?;
    if s.dir.is_empty() {
        return Ok(());
    }
    write_db_locked(&s)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 这些用例都会调用 `init()`（import 是替换语义）并动全局引擎，必须串行。
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        crate::test_lock()
    }

    fn tmp_dir(name: &str) -> String {
        let d = std::env::temp_dir().join(format!("typesake-test-{name}-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        d.to_string_lossy().to_string()
    }

    #[test]
    fn save_dedupe_list_clear_roundtrip() {
        let _g = lock();
        let dir = tmp_dir("store");
        let (n, _pins) = init(&dir).unwrap();
        assert_eq!(n, 0);

        save_phrase("谢谢", "Thank you!").unwrap();
        save_phrase("谢谢", "Thank you!").unwrap();
        assert_eq!(list_saved().len(), 1);
        save_phrase("再见", "Goodbye! / See you!").unwrap();
        assert_eq!(list_saved().len(), 2);
        save_phrase("待补英文", "").unwrap();

        let (n2, _) = init(&dir).unwrap();
        assert_eq!(n2, 3);
        assert_eq!(clear_saved().unwrap(), 3);
        let (n3, _) = init(&dir).unwrap();
        assert_eq!(n3, 0);

        assert!(save_phrase("", "x").is_err());
        assert!(!db_path(&dir).with_extension("json.tmp").exists());
    }

    #[test]
    fn l0_survives_restart() {
        let _g = lock();
        let dir = tmp_dir("l0");
        init(&dir).unwrap();
        let cands = engine::candidates_with(engine::engine(), "ni", 8);
        let target = cands
            .iter()
            .find(|w| w.as_str() != "你")
            .cloned()
            .expect("need a non-default candidate");
        for _ in 0..3 {
            engine::record_pick("ni", &target, 8);
        }
        persist().unwrap();

        init(&dir).unwrap();
        let after = engine::candidates_with(engine::engine(), "ni", 3);
        assert_eq!(after.first(), Some(&target));
    }

    #[test]
    fn settings_stats_and_learned_roundtrip() {
        let _g = lock();
        let dir = tmp_dir("meta");
        init(&dir).unwrap();

        set_settings(false, true, 0, 0).unwrap();
        assert_eq!(engine::options(), (false, true, 0));
        set_settings(true, true, 1, 0).unwrap();
        assert_eq!(engine::options().2, 1);
        set_settings(true, true, 0, 0).unwrap();

        engine::remember("nihap", "你好");
        bump_stats(3, "2026-09-19").unwrap();
        bump_stats(2, "2026-09-20").unwrap();
        bump_stats(1, "2026-09-20").unwrap();
        persist().unwrap();

        init(&dir).unwrap();
        let st = stats();
        assert_eq!(st.words, 6);
        assert_eq!(
            st.days,
            vec!["2026-09-19".to_string(), "2026-09-20".to_string()]
        );
        assert!(engine::export_learned()
            .iter()
            .any(|(t, w, c)| t == "nihap" && w == "你好" && *c >= 1));
        assert_eq!(engine::options(), (true, true, 0));
    }

    #[test]
    fn saved_edit_and_delete() {
        let _g = lock();
        let dir = tmp_dir("edit");
        init(&dir).unwrap();
        save_phrase("你好", "Hello!").unwrap();
        assert!(update_saved("你好", "Hello!", "Hi there!").unwrap());
        assert_eq!(list_saved()[0].english, "Hi there!");
        assert!(!update_saved("你好", "Hello!", "Nope").unwrap());
        assert!(delete_saved("你好", "Hi there!").unwrap());
        assert!(list_saved().is_empty());
        assert!(update_saved("你好", "x", "  ").is_err());
    }

    #[test]
    fn backup_roundtrip() {
        let _g = lock();
        let dir = tmp_dir("backup");
        init(&dir).unwrap();
        save_phrase("你好", "Hello!").unwrap();
        bump_stats(5, "2026-09-20").unwrap();
        engine::remember("nihap", "你好");
        let json = export_json().unwrap();
        assert!(json.contains("Hello!"));

        // 清空后再导入恢复
        clear_saved().unwrap();
        assert!(list_saved().is_empty());
        let (saved, _pins) = import_json(&json).unwrap();
        assert_eq!(saved, 1);
        assert_eq!(list_saved()[0].english, "Hello!");
        assert!(engine::export_learned()
            .iter()
            .any(|(t, w, _)| t == "nihap" && w == "你好"));
        assert_eq!(stats().words, 5);

        assert!(import_json("{oops").is_err());
    }

    #[test]
    fn learned_words_and_clear() {
        let _g = lock();
        let dir = tmp_dir("learned");
        init(&dir).unwrap();
        engine::clear_learned();
        let cands = engine::candidates_with(engine::engine(), "ni", 8);
        let target = cands.iter().find(|w| w.as_str() != "你").cloned().unwrap();
        for _ in 0..3 {
            engine::record_pick("ni", &target, 8);
        }
        let words = engine::learned_words();
        assert!(words
            .iter()
            .any(|(p, w, c)| p == "ni" && w == &target && *c == 0));
        engine::clear_learned();
        assert!(engine::learned_words().is_empty());
    }

    #[test]
    fn corrupted_file_does_not_panic() {
        let _g = lock();
        let dir = tmp_dir("corrupt");
        std::fs::write(db_path(&dir), b"{not json").unwrap();
        let (n, _) = init(&dir).unwrap();
        assert_eq!(n, 0);
        assert_eq!(stats().words, 0);
    }
}
