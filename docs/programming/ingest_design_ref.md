# Ingest 設計參考（對齊 llm-wiki 的 Analysis → Generation）

> 放置位置：`docs/programming/ingest-design-ref.md`。
> 背景：`docs/programming/llm_wiki_ingest_gap.md`（可選）。對象 `neojou/mystudy` `main` @ `ce66958`。
> 本文件是**設計目標**；實作後以測試與 `How-To-do-Ingest.md` 為準。
>
> 實作差異：預覽 Ingest 不先建 symlink。來源就是目前預覽的 vault 筆記，frontmatter `sources` 用 vault 相對路徑。確認前不寫磁碟。`linkIntoRawSource` 仍在，按鈕不呼叫它。以 `How-To-do-Ingest.md` 與 `IngestBehaviorTest` 為準。

## 0. 一句話

預覽區 `Ingest` 改成：symlink 到 `raw/sources/` → **Analysis**（Ollama，純文字分析）→ **Generation**（Ollama，輸出 `FILE`/`REVIEW` 區塊）→ 本地解析與守衛 → **提案對話框** → 使用者確認後寫入 wiki，程式更新 index/log。輸出一律繁體中文（台灣）。

## 1. 必須保留的契約

- 只建 desktop JVM；不引入 Rust/Tauri/llm-wiki 程式碼。Ollama 只連 loopback `/api/chat`，`think: false`。
- Prompt 在 `ai-prompts/`，每次呼叫現讀，system 字串不搬回 Kotlin。
- 不改 `wiki/schema.md`、不覆寫 `raw/`；所有寫入經 `assertWikiWrite`。偏好與索引不寫進 vault。
- symlink 規則不變（相對路徑、重用、` (2)` 後綴、不動原檔）。
- 寫入前**一定**經確認對話框（不自動寫）。模型呼叫不佔 `wiki-index` dispatcher。
- Lexical Ask、Chat Agent 行為與測試不變。

## 2. 目標流程

1. `ingestPreview`：`linkIntoRawSource`（同現在）。失敗就停。
2. 讀上下文：來源全文（跟 symlink）、`wiki/schema.md`（必需）、`wiki/purpose.md`（可選）、`wiki/index.md`（截斷）、`wiki/overview.md`（可選，只讀）。
3. 預算：system＋user > `MAX_INPUT_CHARS` 或 `num_ctx <= RESERVE_TOKENS` → 停，不打模型（先截 index，再不行才停）。
4. **Analysis**：`ai-prompts/ingest-analyze.md`，`json = false`，回 Markdown 分析（Key Entities/Concepts 含 `Page-worthy`、Main Arguments、Connections、Contradictions、Recommendations）。空回覆 → 停。
5. **Generation**：`ai-prompts/ingest-generate.md`，`json = false`，user 帶分析＋來源＋既有相關頁摘錄（沿用最多 8 則邏輯，改由分析中的實體/概念標題解析）。
6. `parseIngestBlocks`：
   - `---FILE: <path>---` … `---END FILE---`；code fence 內的結尾不算；未關閉 → 丟棄並列警告。
   - 路徑守衛：相對、無 `..`、只准 `wiki/sources/`、`wiki/concepts/`、`wiki/entities/`（schema 若定義其他目錄可列為 Phase 2）。`wiki/sources/*` 一律改成正式 `wiki/sources/{stem}.md`。`index.md`、`overview.md`、`log.md`、`schema.md`、`purpose.md` 的 FILE 忽略。
   - frontmatter：補 `title`、`type`、`created/updated`（今天）、`sources` 含本 raw（`sources/….md`，同現在去掉 `raw/` 前綴）。
   - `---REVIEW: <type> | <title>---` … `---END REVIEW---`；type ∈ contradiction|duplicate|missing-page|suggestion，取 `OPTIONS:`、`SEARCH:` 行。
7. `buildIngestProposal`（改寫）：新頁→create；既有頁（alias/標題唯一解析）→ **append 一節**（不覆寫）；多頁 ambiguous → 不可選；contradiction REVIEW 指到的頁預設不勾選。
8. 對話框：列出頁（可取消勾選）、REVIEW 清單、警告。確認 → `applyProposal`：寫頁、更新 `wiki/index.md` catalog、append `wiki/log.md` `## [YYYY-MM-DD] ingest | {title}`；勾選的 REVIEW append 到 `wiki/review.md`（不存在就建立）。
9. 若沒有任何合法 FILE 但有分析 → 程式產生後援 source 頁（分析摘要），仍需確認。

LLM 呼叫：固定 2 次。無佇列、無快取、無分塊。

## 3. Prompt 大綱

`ai-prompts/ingest-analyze.md`
- system：你是 wiki 編輯；只根據來源；列出六節；每個實體/概念標 `Page-worthy: yes/no`；對照 index 標出已存在頁；`{{languageRule}}`；`{{schema}}`、`{{purpose}}`、`{{index}}` 區段（空就省略）。
- user：`Analyze this source document:` + `{{sourceName}}` + `{{raw}}`。

`ai-prompts/ingest-generate.md`
- system：只輸出 FILE/REVIEW 區塊，不要其他文字；必含一個 `wiki/sources/{{sourceStem}}.md`；每個 Page-worthy 實體/概念一頁（`wiki/entities/`、`wiki/concepts/`），檔名用頁面標題；frontmatter 規則（`type/title/aliases/tags/sources/related`）；用 `[[wikilink]]`；不要寫 index/log/overview；REVIEW 四型與 `OPTIONS: Create Page | Skip`、suggestion/missing-page 附 2–3 行 `SEARCH:`；`{{languageRule}}`；`{{schema}}`。
- user：`## Analysis` `{{analysis}}`、`## Source` `{{raw}}`、`## Existing pages` `{{existing}}`（區段 `existing`、`none` 沿用現在格式）。

標記字串 `---FILE:`、`---END FILE---`、`---REVIEW:`、`---END REVIEW---`、`OPTIONS:`、`SEARCH:` 是解析契約，寫進 `ai-prompts/README.md`。

## 4. 語言

- `{{languageRule}}` 由程式填；預設語言名 `Traditional Chinese (Taiwan, 繁體中文)`，常數或偏好（不寫 vault）。
- 規則文字（放 prompt 檔的 `language` 區段，程式只填 `{{lang}}`）：散文、標題、小節標題用 {{lang}}；專有名詞、人名原文、產品/模型/程式識別字、URL、論文標題保留原形；來源可為英文/日文/簡中等，作為證據但輸出用 {{lang}}；不要輸出簡體字。
- 檔名：用頁面標題（中文可），經安全檔名清理；aliases 收原文名稱（例：`Kazuo Ueda`）。

## 5. 測試（`WikiBehaviorTest` 或新 `IngestBehaviorTest`，假 `ChatClient`）

1. 預覽 Ingest 先建 symlink，再依序呼叫 analyze、generate 各一次，`json = false`。
2. 預算超出 / schema 缺 / prompt 檔缺 → 不打模型、不寫檔。
3. parser：正常 FILE、未關閉 FILE 丟棄、fence 內假結尾、`../`、絕對路徑、`raw/`、`wiki/schema.md`、`wiki/index.md` 的 FILE 被拒或忽略。
4. source 路徑正規化成 `wiki/sources/{stem}.md`；frontmatter `sources` 含本 raw。
5. 既有頁只 append，不覆寫；ambiguous 不可選；contradiction 預設不勾。
6. REVIEW 解析四型與 SEARCH；確認後 append `wiki/review.md`。
7. 確認前磁碟不變；確認後 index catalog 與 log 更新。
8. language 區段填入 `Traditional Chinese`（檢查送出的 system 字串）。
9. 既有 symlink、WriteGuard、Ask、Chat 測試全綠。

## 6. AGENTS.md 要改的地方

- 「預覽 Ingest 只建 symlink、不呼叫 Extract/Draft」→ 改為「預覽 Ingest 建 symlink 後跑 Analyze＋Generate，確認後才寫入」。
- Prompt 清單改為 `ingest-analyze.md`、`ingest-generate.md`；解析契約改為 FILE/REVIEW 標記；`json` 旗標：Ingest 兩步為 false。
- 加：Ingest 每次最多 2 次模型呼叫；只寫 `wiki/sources|concepts|entities/`、`index.md`、`log.md`、`review.md`。
- 同步 `How-To-do-Ingest.md` 與 `ai-prompts/README.md`。

## 7. 舊 Extract/Draft

建議一併移除 `extract`/`draft`/`keepVerbatim`/`IngestPane` 與兩個舊 prompt 檔與其測試（`badExcerptsAreDropped…` 改寫成 parser 測試），避免兩套契約。若 Neo 想保留，標為 legacy、不接 UI。

## 8. 交付方式建議

**一次交付（one-shot）**，範圍限於 §2 的 2 次呼叫流程；分塊、快取、佇列、LLM 合併、Deep Research 留給之後。理由：各部件（prompt、parser、proposal、對話框、測試）互相依賴，拆開會有半成品契約；mystudy 的 Chat Agent 也是一次交付成功的前例。必須守住 §1。
