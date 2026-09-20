//! 收藏与学习快照的持久化（单文件 JSON + 原子写）。
//!
//! 原子写：先写 `<file>.tmp` 再 `rename`，避免进程被杀时留下半截 JSON。

use crate::engine;
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

#[derive(Debug, Serialize, Deserialize)]
struct Db {
    #[serde(default = "default_version")]
    version: u32,
    #[serde(default)]
    saved: Vec<SavedPhrase>,
    #[serde(default)]
    l0: L0File,
}

fn default_version() -> u32 {
    2
}

#[derive(Debug, Default)]
struct Store {
    dir: String,
    saved: Vec<SavedPhrase>,
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

fn write_db_locked(s: &Store) -> Result<(), String> {
    let l0 = engine::export_l0();
    let db = Db {
        version: 2,
        saved: s.saved.clone(),
        l0: L0File {
            pins: l0.pins,
            pick_counts: l0.pick_counts,
        },
    };
    let data = serde_json::to_vec(&db).map_err(|e| e.to_string())?;
    write_atomic(&db_path(&s.dir), &data).map_err(|e| e.to_string())
}

/// 初始化存储目录：载入收藏与 L0 学习数据。返回 (收藏数, pins 数)。
pub fn init(dir: &str) -> Result<(usize, usize), String> {
    let db = match std::fs::read(db_path(dir)) {
        Ok(bytes) => serde_json::from_slice::<Db>(&bytes).unwrap_or_else(|_| Db {
            version: 2,
            saved: Vec::new(),
            l0: L0File::default(),
        }),
        Err(_) => Db {
            version: 2,
            saved: Vec::new(),
            l0: L0File::default(),
        },
    };
    let pins = db.l0.pins.len();
    engine::import_l0(db.l0.pins, db.l0.pick_counts);
    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.dir = dir.to_string();
    s.saved = db.saved;
    Ok((s.saved.len(), pins))
}

/// 收藏（同 中文+英文 去重，更新时间戳）。
pub fn save_phrase(chinese: &str, english: &str) -> Result<SavedPhrase, String> {
    let (cn, en) = (chinese.trim(), english.trim());
    if cn.is_empty() || en.is_empty() {
        return Err("中文和英文都不能为空".to_string());
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
    Ok(p)
}

/// 收藏列表（新到旧）。
pub fn list_saved() -> Vec<SavedPhrase> {
    let mut items = store().lock().map(|s| s.saved.clone()).unwrap_or_default();
    items.sort_by_key(|p| std::cmp::Reverse(p.saved_at));
    items
}

/// 清空收藏（保留学习数据）。
pub fn clear_saved() -> Result<usize, String> {
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let n = s.saved.len();
    s.saved.clear();
    write_db_locked(&s)?;
    Ok(n)
}

/// 学习数据落盘（选词后调用；L0 很小，直接写）。
pub fn persist_l0() -> Result<(), String> {
    let s = store().lock().map_err(|e| e.to_string())?;
    if s.dir.is_empty() {
        return Ok(());
    }
    write_db_locked(&s)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 这些用例都会调用 `init()`（import_l0 是替换语义），必须串行，
    /// 否则并行用例的 init 会互相清空 L0 pin。
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        static L: OnceLock<Mutex<()>> = OnceLock::new();
        L.get_or_init(|| Mutex::new(()))
            .lock()
            .unwrap_or_else(|e| e.into_inner())
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
        assert_eq!(n, 0, "fresh dir starts empty");

        save_phrase("谢谢", "Thank you!").unwrap();
        save_phrase("谢谢", "Thank you!").unwrap();
        assert_eq!(list_saved().len(), 1);
        save_phrase("再见", "Goodbye! / See you!").unwrap();
        assert_eq!(list_saved().len(), 2);

        // 重新载入（模拟重启）
        let (n2, _) = init(&dir).unwrap();
        assert_eq!(n2, 2);
        assert_eq!(clear_saved().unwrap(), 2);
        let (n3, _) = init(&dir).unwrap();
        assert_eq!(n3, 0);

        assert!(save_phrase("", "x").is_err());
        assert!(save_phrase("x", "  ").is_err());
        assert!(!db_path(&dir).with_extension("json.tmp").exists());
    }

    #[test]
    fn l0_survives_restart() {
        let _g = lock();
        let dir = tmp_dir("l0");
        init(&dir).unwrap();
        // 学一个非首选词
        let cands = engine::candidates_with(engine::engine(), "ni", 8);
        let target = cands
            .iter()
            .find(|w| w.as_str() != "你")
            .cloned()
            .expect("need a non-default candidate");
        for _ in 0..3 {
            engine::record_pick("ni", &target, 8);
        }
        persist_l0().unwrap();

        // 重新载入后 pin 仍在
        init(&dir).unwrap();
        let after = engine::candidates_with(engine::engine(), "ni", 3);
        assert_eq!(after.first(), Some(&target));
    }

    #[test]
    fn corrupted_file_does_not_panic() {
        let _g = lock();
        let dir = tmp_dir("corrupt");
        std::fs::write(db_path(&dir), b"{not json").unwrap();
        let (n, _) = init(&dir).unwrap();
        assert_eq!(n, 0);
    }
}
