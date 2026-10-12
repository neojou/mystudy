# mystudy vs llm-wiki：Ingest 落差分析

> 放置建議：`docs/programming/llm_wiki_ingest_gap.md`（可選；設計以 `ingest-design-ref.md` 為準）。
> 對象：`neojou/mystudy` `main` @ `ce66958`（2026-10-11 22:45 UTC+8）；`nashsu/llm_wiki` @ `48fd970`。
> 證據來源：mystudy `docs/programming/How-To-do-Ingest.md`、`AGENTS.md`、`IngestPipeline.kt`、`ai-prompts/ingest-*.md`；llm-wiki 原始碼與先前三份整理（`llm_wiki_ingest_deepsearch.md`、`ingest_analysis_prompt.md`、`ingest_generation_step2.md`）。

## 1. mystudy 目前的流程

1. 左側檔案樹選檔 → `StudyController.previewFile` → 預覽區第一行 `Ingest` 按鈕（`previewOffersIngest`：vault 內、不在 `{wikiRoot}/wiki/`、`{wikiRoot}/raw/` 的 `.md`）。
2. 按 Ingest → `ingestPreview` → `linkIntoRawSource`：在 `{wikiRoot}/raw/sources/` 建**相對 symlink**，然後**停**。不打模型。
3. 兩步程式仍在但預覽流程不呼叫：
   - `IngestPipeline.extract`：`ai-prompts/ingest-extract.md`、`json = true`，回 claims JSON；`keepVerbatim` 丟掉 excerpt 不在原文者。
   - `IngestPipeline.draft`：`ai-prompts/ingest-draft.md`、`json = true`，user 帶 schema.md＋勾選 claims＋最多 8 則既有頁摘錄；回 sections JSON。
   - `buildIngestProposal`：**路徑由程式決定**（alias 解析＋`allocatePath`），已存在頁只 append 一節，conflict/ambiguous 預設不寫。
   - 確認對話框 → `applyProposal`：寫勾選頁、更新 `wiki/index.md` catalog、append `wiki/log.md`。
4. 預算：`MAX_INPUT_CHARS = 40000`、`num_ctx > 4096`，超過就停，不分塊。
5. 語言：Extract 只說「Keep titles in the note's language」，沒有輸出語言設定。
6. 沒有：purpose.md、overview.md、REVIEW、佇列、快取、checkpoint、分塊、多模態、頁面合併 LLM、Deep Research。

## 2. llm-wiki 流程（摘要）

- 入口全部進持久佇列（`.llm-wiki/ingest-queue.json`，序列、重試 3 次）→ `autoIngest`。
- SHA256 增量快取：內容未變就跳過 LLM。
- 長文件：依 context 預算語意分塊＋Global Digest＋checkpoint。
- **Step 1 Analysis**（純文字 Markdown，temperature 0.1）：Key Entities（`Page-worthy: yes/no`）、Key Concepts、Main Arguments、Connections to Existing Wiki、Contradictions、Recommendations；system 帶 schema、purpose、index 與 **MANDATORY OUTPUT LANGUAGE**。
- **Step 2 Generation**（純文字）：只輸出 `---FILE: wiki/...md--- … ---END FILE---`（source 摘要頁、entity/concept 頁、log 條目），加選用 `---REVIEW: type | Title--- … ---END REVIEW---`（contradiction / duplicate / missing-page / suggestion，`OPTIONS: Create Page | Skip`，`SEARCH:` 查詢串）。
- 條件式第三次呼叫：產出大時跑專門 REVIEW prompt；截斷 FILE 修復；既有頁 LLM 合併。
- 寫檔：`isSafeIngestPath`、source 路徑強制正規化、忽略模型寫的 index/overview、frontmatter 修正、日期戳、`sources` 正規化、語言守門、CJK 檔名；index/log 由程式確定性更新。**自動寫入，不經確認**。
- REVIEW 存 `.llm-wiki/review.json`，佇列清空時 sweep。

## 3. 落差

| # | 項目 | llm-wiki | mystudy | 嚴重度 |
|---|---|---|---|---|
| G1 | 預覽 Ingest 是否跑模型 | 匯入即分析＋生成 | 只 symlink | 高（主要缺口） |
| G2 | 分析步驟 | 結構化 Markdown 分析，看 index 判斷關聯/矛盾 | claims JSON，只看單篇，不看 index | 高 |
| G3 | 生成格式 | FILE blocks（整頁 Markdown＋frontmatter） | JSON sections，程式組頁 | 中（設計選擇） |
| G4 | REVIEW | 有，四型＋SEARCH | 無（只有 conflict 旗標） | 高 |
| G5 | 輸出語言 | 設定/偵測，強制語言段 | 無；Neo 要 ZH-TW | 高 |
| G6 | index / purpose / overview 進 prompt | 有 | 只有 schema.md＋8 則既有頁 | 中 |
| G7 | 長文件分塊 | 有 | 超 40000 字就停 | 中 |
| G8 | 增量快取 / 佇列 / checkpoint | 有 | 無 | 低（單檔手動） |
| G9 | 既有頁合併 | LLM 合併＋備份 | append 一節 | 低（mystudy 較安全） |
| G10 | 寫入確認 | 自動 | 確認對話框 | mystudy 較好，保留 |
| G11 | 多模態、MinerU、embedding、Deep Research | 有 | 無 | 不移植 |

## 4. 建議移植（符合 desktop JVM / Ollama / ai-prompts 契約）

- 預覽 Ingest = symlink → Analysis → Generation → 提案對話框 → 確認寫入。單檔、手動觸發、無佇列。
- 新 prompt 檔 `ai-prompts/ingest-analyze.md`、`ai-prompts/ingest-generate.md`（現讀、區段格式、`{{}}` 佔位）。兩步都 `json = false`（純文字），對齊 llm-wiki；FILE/REVIEW 區塊的標記字串成為新的解析契約。
- 本地 parser（Kotlin）：FILE/REVIEW 區塊、`isSafeIngestPath` 等價守衛（只准 `wiki/sources|concepts|entities/`）、source 路徑強制、忽略 index/overview/schema/log 的 FILE、frontmatter `sources` 正規化、日期戳。
- 寫入走既有 `assertWikiWrite`/`ApplyWrites`/`AtomicWrite`；已存在頁預設 **append 一節**（不做 LLM 合併）；index/log 由程式確定性更新。
- 輸出語言：程式常數/偏好預設「Traditional Chinese（台灣）」，填入 `{{language}}`；來源可為任何語言，專有名詞保留原文。
- REVIEW：解析後顯示在提案對話框，可選擇寫入 `wiki/review.md`（append）或僅記憶體；不寫 `.llm-wiki/`（偏好不寫 vault 契約）。
- 長文件：先保留「超預算就停」；分塊列為 Phase 2。

## 5. 不在範圍

佇列與重試、SHA256 快取、checkpoint/resume、分塊分析、多模態/圖片 caption、MinerU、PDF/Office 解析、頁面 LLM 合併、截斷修復呼叫、專門 REVIEW 第三次呼叫、review sweep、Deep Research、embedding、自動寫入（無確認）、Rust/Tauri 程式碼、Android/iOS/Web。
