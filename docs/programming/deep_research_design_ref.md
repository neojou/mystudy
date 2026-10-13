# Deep Research 設計參考（mystudy）

> 實作之後以 `docs/programming/How-To-do-Deep-Research.md` 與 `DeepResearchBehaviorTest` 為準。下面是當時的設計參考，若與程式或測試衝突，改這份參考，不要改測試去遷就它。

> 依據：mystudy `5baf726`（v0.7 Ingest）、`/workspace/llm_wiki`（`src/lib/deep-research.ts`、`web-search.ts`、`review-batch-research.ts`、`components/review/review-view.tsx`）、`llm_wiki_ingest_to_deep_research.md`、`mystudy_review_vs_llm_wiki_dr.md`。
> 本文件是設計參考，不是實作。實作後以測試與程式為準，並補 `How-To-do-Deep-Research.md`。

## 1. 目標與範圍

- TopMenu 新增頂層項目「深度研究」→ `StudyMode.DeepResearch` → `DeepResearchPane`。
- 畫面讀 `wiki/review.md`，列出每筆 review；每筆有「深度研究」按鈕。
- 按鈕執行 llm-wiki 式流程：SearXNG 網路搜尋 → 顯示搜尋結果 → 串流顯示 Ollama 綜整輸出 → 寫入 `wiki/queries/research-*.md`、更新 `wiki/index.md`、`wiki/log.md`。
- 成功後把該筆從 `wiki/review.md` 移除（畫面同步消失）。
- Settings 新增 SearXNG URL（預設 `http://127.0.0.1:8080`）與「測試連線」；連不上時所有研究按鈕停用。
- 不做：批次研究（Research selected）、AnyTXT、本機檔案搜尋、研究結果自動 Ingest、embedding、sweep、研究任務持久化、並行佇列（一次一筆）。

## 2. 與既有契約的關係（AGENTS.md）

| 契約 | Deep Research 的做法 |
|---|---|
| 只建 desktop JVM | 全部放 `desktopMain`；HTTP 用既有 Ollama client 同款（`java.net.http`），不加新平台 |
| Ollama 只連 loopback、native `/api/chat` | 綜整呼叫走既有 Ollama client，`json = false`，串流 |
| Prompt 在 `ai-prompts/`，每次現讀 | 新增 `ai-prompts/deep-research.md`（system），並更新 `ai-prompts/README.md`；不把字串放回 Kotlin |
| 寫入不改 `schema.md`、不覆寫 `raw/` | 只寫 `wiki/queries/`（新檔）、`wiki/index.md`、`wiki/log.md`、`wiki/review.md`；經 `WriteGuard.assertWikiWrite` + `atomicWrite` |
| SQLite 只用 `wiki-index` dispatcher；模型呼叫不佔它 | 搜尋與模型呼叫在 IO；寫完後的重新索引才回 `wiki-index` |
| 偏好不寫 vault | SearXNG URL 存 `PreferencesSettingsStore`（新 key `searxngUrl`） |
| Chat Agent 無網路工具 | 不變。網路只在 Deep Research 通道，Chat/Ask/Ingest 不碰 SearXNG |
| Chat Agent 不讀 `review.md` | 不變。只有 Deep Research 讀寫 `review.md` |

**新契約（需寫進 AGENTS.md 與 Architecture.md）：** SearXNG 是本 app 唯一的網路出口，只送 SEARCH 查詢字串，不送 vault 內容。預設 loopback；若使用者填非 loopback URL，允許但在 Settings 顯示警告（待 Neo 決定是否強制 loopback，見 §11）。

## 3. review.md 解析

現行寫入格式（`markdown/Catalog.kt` `appendReviews`）：

```
# Review

## [2026-10-12] suggestion | 標題

<body：描述、OPTIONS:、SEARCH: 原文>

- source: raw/xxx.md
```

- 新增 `wiki/research/ReviewFile.kt`：`parseReviewFile(text): List<ReviewEntry>`，以 `^## \[(date)\] (type) \| (title)$` 切段，一段到下一個 `## ` 或 EOF。
- `ReviewEntry(id, date, type, title, description, options, searchQueries, source, startLine, endLine, rawBlock)`。
- `id` = 內容穩定雜湊（type + title + date + source），用於移除時比對；**移除時以 rawBlock 文字精確比對**，比對不到就中止並提示「review.md 已變更，請重新整理」（避免使用者在 Obsidian 同時編輯造成誤刪）。
- `SEARCH:` 取全部行（與 mystudy Ingest 一致），每行再以 `|` 分割、trim、去重、去空，最多 3 組。
- 標頭 `# Review`、無法辨識的段落原樣保留。

## 4. 哪些項目可研究

- 全部項目都列出（type、標題、描述、SEARCH、source）。
- 「深度研究」按鈕：所有類型皆顯示（產品需求為每項都有按鈕）。
- 查詢：有 SEARCH → 用 SEARCH；沒有 → 用 topic（標題去掉 `Create`/`Research`/`Save to Wiki` 前綴，空則用描述第一行）。對齊 llm_wiki `reviewResearchQueries`。
- 停用條件：SearXNG 測試未通過／未測、Ollama 未設定、已有研究在跑、vault/wiki root 未設定。停用時顯示原因 tooltip/文字。

## 5. SearXNG

- 新增 `wiki/research/SearxngClient.kt`：`GET {base}/search?q=<q>&format=json&categories=general&language=auto`，逾時 10s，`User-Agent: mystudy/<version>`。
- 回傳 `results[]` 取 `title`、`url`、`content`（snippet）、`engine`；每查詢最多 5 筆；全部查詢合併後以正規化 URL 去重，總上限 15 筆。
- 濾掉 `file://` 與非 http(s)。
- 注意：SearXNG 預設 `settings.yml` 的 `search.formats` 只有 `html`，需加 `json`，否則回 403。測試連線要把這個情況回報成可讀訊息。
- **連線測試** `testConnection()`：送 `q=test&format=json`；判定成功 = HTTP 200 且 JSON 含 `results` 陣列。狀態：`Unknown / Testing / Ok(n results, ms) / Failed(reason)`。
- 何時測：app 啟動載入設定後、Settings 儲存 URL 後、按「測試連線」、Deep Research 畫面開啟時。結果放 `StudyController`（記憶體，不持久化）。

## 6. 研究流程（一筆）

狀態機：`Idle → Searching → Synthesizing → Saving → Done | Failed`（記憶體，對齊 llm_wiki research-store 階段）。

1. **Searching**：依序跑查詢，UI 即時列出命中（`[n] 標題 — url — snippet`）。0 筆 → Failed「沒有搜尋結果」，不打模型。
2. **Synthesizing**：讀 `ai-prompts/deep-research.md` 當 system；user 訊息包含 topic、review 描述、編號來源清單 `[n] **title** (url)\nsnippet`、`wiki/index.md` 現有頁清單（供 `[[wikilink]]`，截斷以符合 `numCtx`）。Ollama 串流，UI 逐 token 顯示。1 次模型呼叫，`json = false`。
3. **驗證**（對齊 llm_wiki `validateResearchSynthesis`）：非空、長度 ≥ 門檻、至少引用一個 `[n]` 且 n 在範圍內。不通過 → Failed，可重試，**不寫任何檔**。
4. **Saving**（全部經 WriteGuard）：
   - 新檔 `wiki/queries/research-<slug>-<yyyy-MM-dd>.md`；同名存在則加 `-2`、`-3`（不覆寫既有頁）。slug：小寫、非字母數字轉 `-`，保留 CJK，截 60 字。
   - 內容：frontmatter（`type: query`、`title`、`created`、`origin: deep-research`、`review: <type> | <title>`、`queries:`）＋綜整內文＋`## Sources`（只列被引用的 `[n] [title](url)`）。
   - `wiki/index.md` append 一行（沿用 Ingest 的 index 更新函式，放 Queries 區段）。
   - `wiki/log.md` append `deep-research | <topic>`，詳列寫入檔。
   - `wiki/review.md` 移除該段（重新讀檔→以 rawBlock 精確比對→移除→atomicWrite）。
   - 順序同 `applyProposal`：頁 → index → log → review；任一步失敗即停，回報已落地的檔。review 最後移除，確保失敗時 review 仍在可重試。
5. **Done**：重新索引新頁（`wiki-index` dispatcher），清單重讀 review.md，顯示「已寫入 wiki/queries/…」連結（點了到 Browse 開該頁）。
6. 研究頁**不**再送 Ingest、不產生新 REVIEW。

### 是否先預覽再寫？
llm_wiki 直接存檔。mystudy Ingest 是「確認前不寫磁碟」。建議：**直接寫**（使用者已明確按下按鈕、只新增檔且 review 可在 log 追溯），但留為 plan 問題（§11）。

## 7. Prompt：`ai-prompts/deep-research.md`

要點（改寫自 llm_wiki deep-research.ts 第 146 行起）：
- 你是研究助理，把收集到的來源綜整成一頁 wiki。
- 只能依據提供的來源；每個事實句以 `[n]` 標註；來源不足時明說。
- 用繁體中文（與 vault 一致；待確認），Markdown，標題階層從 `##` 開始，不輸出 frontmatter（由程式加）。
- 只能 `[[wikilink]]` 連到索引中存在的頁。
- 不輸出 `---FILE:`／`---REVIEW:` 區塊、不用 code fence 包整頁。
- 解析契約：只有 `[n]` 引用格式。寫進 `ai-prompts/README.md`。

## 8. UI

- `HomeScreen.kt` topMenus：在 Wiki 與 Settings 之間加 `MyTopMenuItem(id = "deep-research", label = "深度研究", onClick = { mode = StudyMode.DeepResearch })`。
- `DeepResearchPane`（新檔 `study/DeepResearchScreens.kt`）：
  - 頂部：SearXNG 狀態列（綠/紅＋URL＋「重新測試」＋「前往設定」）、「重新整理」（重讀 review.md）。
  - 左：review 清單卡片（type 標籤、標題、描述摘要、SEARCH chips、source、「深度研究」按鈕）。
  - 右：進行中任務面板（階段、搜尋命中清單、串流綜整、錯誤與「重試」、完成後頁面連結）。
  - 空狀態：review.md 不存在或無項目 → 「目前沒有待研究項目（Ingest 或 Lint 加入）」。
- `SettingsPane`：新增「SearXNG URL」欄位＋「測試連線」按鈕＋結果文字；空白存回預設值。

## 9. 程式落點（建議）

| 檔案 | 內容 |
|---|---|
| `wiki/settings/*.kt` | `StudySettings.searxngUrl`、`KEY_SEARXNG_URL`、`DEFAULT_SEARXNG_URL = "http://127.0.0.1:8080"` |
| `wiki/research/SearxngClient.kt` | search、testConnection、結果去重 |
| `wiki/research/ReviewFile.kt` | 解析、移除 entry |
| `wiki/research/ResearchRunner.kt` | 狀態機、prompt 組裝、驗證、寫入（可注入 search/chat 以便測試） |
| `wiki/research/ResearchPage.kt` | slug、檔名衝突、frontmatter、Sources |
| `wiki/prompt/*` | 載入 `deep-research.md` |
| `study/StudyController.kt` | searxng 狀態、research 狀態、`startResearch(entry)` |
| `study/DeepResearchScreens.kt`、`StudyPane.kt`、`HomeScreen.kt`、`StudyScreens.kt` | UI |
| `desktopTest/.../DeepResearchBehaviorTest.kt` | 見 §10 |
| docs | `How-To-do-Deep-Research.md`、`Architecture.md`、`AGENTS.md`、`ai-prompts/README.md` |

## 10. 測試（DeepResearchBehaviorTest）

- 解析：`appendReviews` 產出的文字能 round-trip 解析；多行 SEARCH、`|` 分割、無 SEARCH 回落 topic。
- 移除：只移除目標段，其餘位元組不變；rawBlock 不符時拒絕。
- SearXNG：用本機假 HTTP server（`com.sun.net.httpserver`）測 200/403/逾時/非 JSON；URL 去重。
- Runner：0 結果不打模型；無引用 → Failed 且不寫檔；成功 → 寫頁、index、log、移除 review，順序正確；檔名衝突加尾碼；WriteGuard 拒絕 `raw/`、`schema.md`。
- Settings：預設值、空白回預設。
- 按鈕停用：searxng 非 Ok 時 `canResearch == false`。

## 11. 待決問題

1. 寫入前是否要預覽確認（Ingest 風格）或直接寫？
2. SearXNG URL 是否強制 loopback？
3. 研究頁語言：固定繁中，或跟隨來源？
4. contradiction／duplicate 也給研究按鈕（需求如此）—是否要依類型調整 prompt？
5. 失敗是否寫 log？
6. 頁面目錄 `wiki/queries/` 是否已在 schema/WriteGuard 白名單？若無，需加入。
