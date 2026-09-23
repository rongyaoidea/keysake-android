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

## Tatoeba（双语例句数据，生成 sentbank.bin）

- 文件：`app/src/main/assets/sentbank.bin`（由 `tools/gen_sentbank.py` 从 Tatoeba 中英句对生成；CI 可选产物，缺失时句库候选为空、功能回退）
- 上游：<https://tatoeba.org/>
- 数据集：OPUS-Tatoeba `en-zh_cn`（v2023-04-12）<https://opus.nlpl.eu/production/corpus.php?corpus=Tatoeba>
- 许可：**Creative Commons Attribution 2.0 France（CC BY 2.0 FR）** <https://creativecommons.org/licenses/by/2.0/fr/>
- 署名：Sentences from **Tatoeba**（tatoeba.org）。再分发本应用或衍生数据时请保留本文件与该署名。
- 说明：仅保留对齐的中英句对并转为二进制句库，未对其它内容做修改。

## jieba 词表（生成 lex.bin）

- 文件：`app/src/main/assets/lex.bin`（由 `rust-core/src/bin/lexgen.rs` 从 jieba 词表生成；CI 可选产物，缺失时退回内置 16.5 万词库）
- 上游：<https://github.com/fxsjy/jieba>（`jieba/dict.txt`）
- 许可：随上游仓库以 **MIT** 分发 <https://github.com/fxsjy/jieba/blob/master/LICENSE>
- 署名：jieba（"结巴"中文分词）词表，作者 fxsjy 及贡献者。
