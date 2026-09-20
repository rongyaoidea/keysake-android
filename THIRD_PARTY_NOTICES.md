# 第三方数据与许可（THIRD PARTY NOTICES）

本仓库包含第三方数据，其许可与本项目代码（MIT）不同：

## CC-CEDICT（中英词典数据）

- 文件：`app/src/main/assets/en_dict.tsv`（由 `tools/gen_english_dict.py` 从 CC-CEDICT 生成）
- 上游：<https://www.mdbg.net/chinese/dictionary?page=cc-cedict>
- 许可：**Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）**
  <https://creativecommons.org/licenses/by-sa/4.0/>
- 署名：CC-CEDICT 由社区维护并由 MDBG 发布（CC-CEDICT project, published by MDBG）。
- 说明：
  - 生成方式：`python3 tools/gen_english_dict.py --cedict <cedict_ts.u8> --out app/src/main/assets/en_dict.tsv`
    （下载 <https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip> 解压得到 `cedict_ts.u8`）
  - 我们对原始数据做了以下**修改**：仅保留 1–4 字纯中文词条；挑选单条最常用释义；去除
    `CL:`、`variant of`、`see …` 等词典标记；输出为 `简体<TAB>英文` 的 TSV。
  - 因为 CC BY-SA 4.0 具有"相同方式共享"要求：**该 TSV 数据文件及其衍生版本需继续以 CC BY-SA 4.0 分发**
    （本仓库代码仍为 MIT）。再分发本应用/本数据时请保留本文件与上述署名。
- 其它引用到的同类数据（未打包）：`inputx-pinyin` 的词库为其自身许可（MIT OR Apache-2.0，见该 crate 说明）。
