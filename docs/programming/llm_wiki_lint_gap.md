# llm-wiki Lint 與 mystudy 的差異

> 依據：llm-wiki `48fd970`、mystudy `2c9e132`（v0.9）。設計見 `docs/programming/wiki_lint_design_ref.md`。

## 1. llm-wiki 怎麼做

### 結構 lint
- `src/lib/lint.ts` `runStructuralLint(projectPath, {signal, onProgress, config})`（約 157 行）：列 `wiki/` 全部 `.md`，排除 `index.md`、`log.md`；每頁取 `shortName`、`slug`（去 `.md`）、`extractTitle`（frontmatter title → 第一個 `#` → 檔名）、`extractWikilinks`、`tokenizeForSuggestion`（NFKC、小寫、CJK 拆單字，用來推薦相關頁）。
- 計算丟到 `lint-structural.worker.ts` → `lint-structural-core.ts` `computeStructuralLint`（102 行起）：
  - `slugMap`：`normalizeTarget`（去 `wiki/`、去 `.md`、小寫）後的 slug 與 basename → 頁索引。
  - inbound 計數：所有 outlink 能對到 slugMap 的目標 +1（**沒排除自連**）。
  - orphan：inbound 0（info），附 `suggestedSource`。
  - no-outlinks：outlinks 空（info），附 `suggestedTarget`。
  - broken-link：outlink 與 basename 都不在 slugMap（warning），附 Levenshtein/片段相似度的 `suggestedTarget`。
  - 進度：讀檔階段 `(i+1, n*2)`，worker 每 25 頁回報 `(n + done, n + total)`。
- `lint-config.ts`：`ignoreOrphan`、`ignoreNoOutlinks`、`ignorePages`，存 `<project>/.llm-wiki/lint-config.json`；ignore 只比 slug/路徑，不比裸檔名。

### 語意 lint
- `lint.ts` `runSemanticLint`（205 行起）：所有頁（除 `log.md`，**含 index.md**）取 `content.slice(0, 500)`（含 frontmatter）組成 `### path\npreview`，**一次**塞進單一 user prompt，沒有 system、沒有分批、沒有預算。
- 語言：`buildLanguageDirective(summaries 前 2000 字)`（`src/lib/output-language`），auto 模式依樣本猜語言。
- 輸出格式 `---LINT: type | severity | Short title---` … `PAGES:` … `---END LINT---`；types contradiction / stale / missing-page / suggestion；severity warning / info。
- 解析 regex `LINT_BLOCK_REGEX`：title 用 `[^\n-]+?`，含 `-` 的標題會失配。
- #537：`missingPageAlreadyExists` 以 `normalizeReviewTitle` + NFKC + 小寫，與檔名、frontmatter title 做完全相等比對，丟掉「已有頁」的 missing-page；明確不做子字串比對。
- 進度只寫 activity store 的文字（"Running LLM semantic analysis..."），沒有數字；UI 的 `lintProgress` 停在結構的 N/N。

### UI 與修復
- `src/components/lint/lint-view.tsx`：「semantic」勾選、Run/Cancel（`AbortController`，按鈕顯示 `cancelProgress {completed}/{total}`）、ignore 設定、結果清單、單筆 Fix、批次 Fix、Send to Review、Ignore。
- `handleRunLint`（約 150–190 行）：先結構再語意，`all = [...structural, ...semantic]`；任一階段 abort 丟 `AbortError`，**整次結果都不顯示**。
- `addLintItemToReview`：broken-link／orphan／no-outlinks／semantic 各轉成 `useReviewStore.addItem`（記憶體 store，非檔案）；semantic 一律送 Review 人工處理。
- `lint-fixes.ts`：`appendWikilink`、`rewriteWikilinkTarget`、`ensureBrokenLinkStub` 會**直接改頁或建 stub**。

## 2. mystudy 的不同與採用

| 面向 | llm-wiki | mystudy |
|---|---|---|
| 頁來源 | 直接列目錄、自己解析 | `WikiIndex` 快照（`notesForAsk`、`linkRows`、`resolve`），與 Ask/Chat 同一解析，含 aliases、Ambiguous |
| 自連 | 算 inbound | 不算 |
| index.md | 結構排除；語意卻送進去 | 兩階段都不送，也不算 inbound |
| broken-link | 每連結一筆，附相似頁建議 | 同目標合併；Ambiguous 不算 broken；不做 Levenshtein 建議（只讀，不修） |
| missing-page | 只靠 LLM | 本地規則：斷連頻率 ≥2 頁＋（可選）tag ≥3 頁無頁；語意的 missing-page 仍過 #537 等價過濾 |
| orphan 噪音 | 全有或全無（ignoreOrphan） | 預設排除 `wiki/sources/`，ignore 清單同語意 |
| 設定位置 | vault 內 `.llm-wiki/lint-config.json` | Preferences（偏好不寫 vault） |
| 語意 prompt | Kotlin/TS 字串、單一 user 訊息 | `ai-prompts/wiki-lint-semantic.md`，system/language/user，每次現讀 |
| 分批 | 無，全部頁一次 | 依 `PromptBudget` 貪婪裝箱，同目錄相鄰，`numCtx` 太小就跳過 |
| 進度 | 語意無數字，卡在 N/N | `Structural(i/n)` 與 `Semantic(batch/batches, 頁範圍)` 分開 |
| 取消 | 全丟 | 保留結構與已完成批 |
| 語言 | auto 偵測 | 固定 `IngestLanguage.NAME` 繁中，prompt 明禁簡體 |
| 解析 | 跨行 regex，標題不能含 `-` | 整行標記解析（Ingest 風格），未收尾／未知 type 記 warning |
| 結果去向 | 記憶體 review store | 使用者勾選後 `appendReviews` 寫 `wiki/review.md`（`- source: lint`、含 `SEARCH:`），去重，深度研究可逐筆處理 |
| 自動修復 | 有（改頁、建 stub） | 不做；修正由深度研究產生新頁或使用者手改 |

## 3. 直接採用

- 四種結構類型的定義與 severity（orphan/no-outlinks=info、broken-link=warning）。
- `---LINT: type | severity | title---` + `PAGES:` 格式（多加 `SEARCH:`）。
- 語意四型與 missing-page 標題只寫概念名的指令。
- #537 的完全相等過濾，不做子字串。
- ignore 只比路徑/slug。
- 每頁 500 字摘要（但受預算縮減）。
