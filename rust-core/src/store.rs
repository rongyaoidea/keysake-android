//! 收藏、学习快照、设置与统计的持久化（单文件 JSON + 原子写）。
//!
//! 原子写：先写 `<file>.json.tmp` 再 `rename`，避免进程被杀时留下半截 JSON。

use crate::{engine, english};
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
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            fuzzy: true,
            correction: true,
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
    engine::import_blocked(db.blocked);
    engine::set_options(db.settings.fuzzy, db.settings.correction);

    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.dir = dir.to_string();
    s.saved = db.saved;
    s.settings = db.settings;
    s.stats = db.stats;
    sync_memory(&s.saved);
    Ok((s.saved.len(), pins))
}

pub fn settings() -> Settings {
    store()
        .lock()
        .map(|s| s.settings.clone())
        .unwrap_or_default()
}

/// 写入输入选项并落盘。
pub fn set_settings(fuzzy: bool, correction: bool) -> Result<(), String> {
    engine::set_options(fuzzy, correction);
    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.settings = Settings { fuzzy, correction };
    write_db_locked(&s)
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

        set_settings(false, true).unwrap();
        assert_eq!(engine::options(), (false, true));
        set_settings(true, true).unwrap();

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
        assert_eq!(engine::options(), (true, true));
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
