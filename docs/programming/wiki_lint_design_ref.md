# Wiki Lint 設計參考（mystudy）

> 實作之後以 `docs/programming/How-To-do-Wiki-Lint.md` 與 `WikiLintBehaviorTest` 為準。這份是當時的設計參考；與程式或測試衝突時改這份，不要改測試去遷就它。
>
> 依據：mystudy `2c9e132`（v0.9，settings: ingest path）、`/workspace/llm_wiki` `48fd970`（`src/lib/lint.ts`、`lint-structural-core.ts`、`lint-config.ts`、`lint-fixes.ts`、`src/components/lint/lint-view.tsx`）。對照文件：`docs/programming/llm_wiki_lint_gap.md`。
>
> 建議放在 `docs/programming/wiki_lint_design_ref.md`（現有設計參考都用底線命名，如 `deep_research_design_ref.md`；若堅持連字號就用 `wiki-lint-design-ref.md`，見 §13）。

## 1. 目標與範圍

- 選單 **Wiki → Lint**（中文副標「自動健檢 wiki」），新 `StudyMode.Lint`。
- 第 1 階段：本地規則，不打模型。orphan、broken-link、missing-page（常被提到但沒頁）、no-outlinks。
- 第 2 階段（勾選才跑）：本機 Ollama 語意健檢，找 contradiction、stale、suggestion（也可回 missing-page，但要過濾）。
- 結果**不自動修內容**。使用者勾選後「寫入 review.md」，格式與 Ingest 的 `appendReviews` 完全一樣，深度研究頁就能一筆一筆處理（每筆有 `SEARCH:`、`- source: lint`）。
- 與既有 review 去重。
- 修掉 llm-wiki 的弱點：語意階段有自己的進度、分批適應小 context、取消保留結構結果、輸出強制繁體中文。

不做：自動修 broken link／建 stub、把 lint 設定寫進 vault、embedding、網路、改 `schema.md`、碰 `raw/`、背景排程。

## 2. 契約對照（AGENTS.md）

| 契約 | Lint 的做法 |
|---|---|
| desktop JVM only | 新程式在 `desktopMain`，只有 `StudyMode.Lint` 在 `commonMain` |
| Ollama loopback、native `/api/chat` | 語意階段走既有 `OllamaClient`（`complete` 或 `stream`），`json = false` |
| prompt 放 `ai-prompts/`、每次現讀 | 新檔 `ai-prompts/wiki-lint-semantic.md`；不在 Kotlin 放 system 字串 |
| 網路只有深度研究 | Lint 不碰 SearXNG，不送網路；只寫 SEARCH 字串給之後的深度研究用 |
| 寫入不改 schema、不覆寫 raw | 只寫 `wiki/review.md`（已在 `assertWikiWrite` 白名單），經 `atomicWrite` |
| SQLite 只用 `wiki-index` dispatcher | 頁清單與連結從 `WikiIndex`（`notesForAsk`、`linkRows`、`resolve`）在 `wiki-index` 讀成快照；規則計算與模型呼叫在 IO/Default |
| 偏好不寫 vault | 「語意健檢」勾選、忽略頁清單存 Preferences |
| Chat 不讀 review.md | 不變 |

## 3. 輸入：頁集合

- 來源：`WikiIndex.notesForAsk()`（已排除 `raw/`、`index.md`、`log.md`、`schema.md`、`review.md`；實作時確認 `includedAskPath` 的實際範圍，以程式為準）。
- 每頁快照 `LintPage(path, stem, title, aliases, type, body, outlinks: List<String>)`；`outlinks` 用 `parseWikilinks(body)`（已去掉 `#heading` 與 `|alias`）。
- `wiki/index.md` 的連結**不算** inbound（否則每頁都不是 orphan；llm-wiki 是把 index.md 排除在頁集合外，效果相同）。`wiki/log.md` 也不算。
- 解析目標：`WikiIndex.resolve(raw)` → `One` / `Ambiguous` / `None`。為了可測，規則函式吃一個純資料 `resolver: (String) -> Resolution`，測試可直接用暫存 vault + 真索引。
- 忽略清單（Preferences `lintIgnorePages`，逗號／全形逗號／換行分隔）：比對完整相對路徑或去 `.md` 的 slug，**不**用裸檔名比對巢狀頁（同 llm-wiki `lint-structural-core.ts` 的註解）。

## 4. 本地規則（精確演算法）

所有規則對同一快照執行，結果排序：type 順序（broken-link、missing-page、orphan、no-outlinks）→ path → target，確保 deterministic。

### 4.1 broken-link（severity: warning）

```
for page in pages (not ignored):
  for raw in distinct(page.outlinks):
    if raw 以 http(s): 開頭 或 是空字串 -> skip
    r = resolver(raw)
    if r is None -> finding(page.path, target = raw)
    # Ambiguous 不算 broken；另記為 info 不寫 review（UI 只顯示）
```
- 同一 target 被多頁斷連時**合併成一筆**：`target`、`pages = [來源頁...]`（最多列 10，其餘「等 N 頁」）。
- 寫入 review 時 type = `missing-page`（目標頁不存在，深度研究能「建頁」），title = target 原字串。

### 4.2 missing-page（常被提到但沒頁，severity: warning/info）

兩個訊號，合併後以 `normalizeConcept`（NFKC、trim、小寫拉丁）去重：

1. **斷連目標頻率**：4.1 合併後 `pages.size >= MISSING_LINK_MIN`（預設 2）的 target。這些在 review 只出一筆 missing-page（不和 4.1 重複；4.1 中只被 1 頁引用的仍是 broken-link 單筆）。
2. **未連結的高頻詞**（可關，預設開）：只用已有的結構，不做中文斷詞：
   - 候選 = 在 ≥ `MISSING_TERM_MIN_PAGES`（預設 3）個不同頁的 frontmatter `tags` 中出現的 tag 值（不掃自由文字、粗體或 code）。
   - 若 `resolver(tag)` 是 `None` → missing-page 候選，`pages` = 帶這個 tag 的頁。
   - 理由：中文沒斷詞，自由文字詞頻會大量誤報；tags 是 Ingest 產出、語意乾淨。

過濾（等同 llm-wiki #537）：候選若在 `existingNames`（所有頁的 stem、title、aliases 經 `normalizeConcept`）中就丟掉。**只做完全相等**，不做子字串。

### 4.3 orphan（severity: info）

```
inbound[target] = 來自「其他頁」的 resolver(raw) is One 的連結數（自連不算）
orphan = pages 中 inbound == 0 且 不在 ignore 且 type != "source"?（見 §13 決定）
```
- 建議預設排除 `wiki/sources/`：source 摘要頁通常沒人連，會淹沒結果（llm-wiki 用 `ignoreOrphan` 全關，太粗）。

### 4.4 no-outlinks（severity: info）

`page.outlinks.isEmpty()` 且不在 ignore。合理，保留，但預設不寫進 review（見 §5）。

### 4.5 進度

結構階段：`Structural(done, total)`，total = 頁數，每 25 頁或最後一頁更新一次。純記憶體計算，千頁級應 < 1 秒，取消檢查在每頁迴圈。

## 5. 寫進 review.md 的格式

沿用 `appendReviews(existing, date, reviews: List<ReviewProposal>, source = "lint")`，**不另寫 serializer**，保證 `parseReviewFile`／`removeReviewBlock` 能 round-trip。

規則 → review 對應：

| lint 結果 | review type | title | 預設勾選寫入 |
|---|---|---|---|
| broken-link / 高頻斷連 / 未連 tag | `missing-page` | 概念名（target 或 tag） | 是 |
| semantic contradiction | `contradiction` | 模型標題 | 是 |
| semantic stale | `suggestion`（正文首行標 `Lint：stale`） | 模型標題 | 是 |
| semantic suggestion | `suggestion` | 模型標題 | 是 |
| orphan | `suggestion`，title `補連結：<頁名>` | | 否 |
| no-outlinks | `suggestion`，title `補外連：<頁名>` | | 否 |

不新增 review type，深度研究與 Ingest 的四型契約不變（`[a-z-]+` 標頭 regex 雖然接受別的字，但 How-To 說四型）。

body 構成（程式組，不是模型）：

```
Lint：<rule>（<severity>）
<說明一到三行，繁中>
相關頁：[[頁A]]、[[頁B]]
OPTIONS: Create Page | Skip        ← missing-page；contradiction 用 Research | Skip
SEARCH: <查詢1>
SEARCH: <查詢2>
```

SEARCH 產生：
- missing-page：`<概念>`；若有任一相關頁 title 不同，再加 `<概念> <最常見相關頁 title>`；最多 3。
- 語意結果：用模型給的 `SEARCH:` 行（§7），沒有就用 title。
- orphan/no-outlinks：`<頁 title>`。

範例（寫入後 review.md 的一段）：

```markdown
## [2026-10-13] missing-page | 盧曼

Lint：broken-link（warning）
3 頁用 [[盧曼]] 連結，但 wiki 裡沒有這一頁。
相關頁：[[卡片盒筆記法]]、[[Zettelkasten]]、[[第二大腦]]
OPTIONS: Create Page | Skip
SEARCH: 盧曼
SEARCH: 盧曼 卡片盒筆記法

- source: lint

## [2026-10-13] contradiction | 植田和男就任日期不一致

Lint：contradiction（warning）
[[植田和男]] 寫 2023-04-09 就任，[[日本銀行]] 寫 2023-04-01。
相關頁：[[植田和男]]、[[日本銀行]]
OPTIONS: Research | Skip
SEARCH: 植田和男 日銀總裁 就任日

- source: lint
```

`parseReviewFile` 的 `description` 會含 Lint/相關頁行，深度研究 prompt 的 `{{description}}` 因此帶到上下文，不需改深度研究。

### 5.1 去重

鍵 `dedupKey = type + "|" + normalizeConcept(title)`。
- 寫入前重讀 `wiki/review.md`，`parseReviewFile` 取所有既有鍵（不論 source 是 ingest 或 lint）。
- 既有鍵相同 → 不寫，UI 標「已在 review.md」。
- missing-page 另外比對：既有任何 type 的 title 去掉 `Create`/`Research` 前綴後 `normalizeConcept` 相等也算重複（Ingest 常產 `missing-page | 盧曼`）。
- 同一次 lint 內也去重（結構 missing-page 與語意 missing-page 撞名時留結構那筆）。
- 寫入：`appendReviews` → `assertWikiWrite(root, "wiki/review.md")` → `atomicWrite`。寫前比對 review.md 讀取時的內容雜湊，若被 Obsidian 改過就重讀再去重一次（不 merge 衝突、不覆蓋使用者編輯）。
- 不寫 `wiki/log.md`？建議寫一行 `lint | N 筆 review`（見 §13）。

## 6. UI

`study/LintScreens.kt`，`LintPane(controller)`：

- 頂部：`[ ] 語意健檢（Ollama，較慢）` 勾選、「開始健檢」/「取消」按鈕、忽略頁欄位（折疊）。
- 狀態列，兩段各自的進度：
  - `本地規則：120/120 頁 ✓`
  - `語意健檢：批次 3/7（第 41–60 頁）…`；完成 `✓ 7/7`、取消 `已取消（保留 2/7 批的結果）`、失敗 `第 4 批失敗：<原因>（其餘照常）`。
- 結果清單，依 type 分組，每列：勾選框、type 標籤、severity、標題、說明、相關頁（可點，切到 Browse 開該頁）、SEARCH chips、「已在 review.md」灰標。
- 底部：「全選／全不選」「寫入 review.md（N 筆）」→ 完成顯示「已加入 N 筆，略過重複 M 筆。到『深度研究』處理」與切換按鈕。
- 沒有自動修復按鈕。
- 結果只在記憶體（`StudyController`），切換選單不清除，換 wiki root 清除。

## 7. 語意 prompt：`ai-prompts/wiki-lint-semantic.md`

建議內容（區段 `system`、`language`、`user`；`json = false`）：

```text
動作：Wiki Lint 語意健檢。每一批頁面一次呼叫。這段前言與 HTML 註解不送給模型。
解析契約：---LINT: type | severity | title---、PAGES:、SEARCH:、---END LINT---。
type 只准 contradiction、stale、missing-page、suggestion；severity 只准 warning、info。
請保留 system 的 {{languageRule}}，language 的 {{lang}}，user 的 {{batch}}、{{batches}}、{{pages}}、{{titles}}。

--- system ---
You are a wiki quality reviewer. You get summaries of some wiki pages and the list of all page titles.
Report only real problems you can point to in the given text. Do not invent problems. Zero findings is a valid answer.

Output only blocks in this exact format, nothing else:

---LINT: type | severity | short title---
One to three sentences explaining the problem. Quote the conflicting claims.
PAGES: path1.md, path2.md
SEARCH: one web search query
---END LINT---

Types:
- contradiction: two pages in this batch state conflicting facts. PAGES must list both.
- stale: a claim looks outdated or superseded by another page.
- missing-page: an important concept named in several pages has no page. The title is only the concept name. Check the title list first; never report a title that is in the list.
- suggestion: a question or source worth adding.
Severity: warning = should fix, info = nice to have.
At most 8 blocks. Use page paths exactly as given.

{{languageRule}}

--- language ---
Write titles and explanations in {{lang}}. Do not write Simplified Chinese characters.
Keep proper nouns, personal names, product identifiers, and paths unchanged.
SEARCH may be in the language most likely to find sources.

--- user ---
## Batch {{batch}} of {{batches}}

## All page titles
{{titles}}

## Pages
{{pages}}
```

- `{{lang}}` = `IngestLanguage.NAME`（固定繁中，不是偏好、不做自動偵測；修 llm-wiki `buildLanguageDirective` 依樣本猜語言的問題）。
- `{{pages}}` 每頁：`### <path>\ntitle: …\ntype: …\ntags: …\n<正文前 N 字>`。N 預設 500，與 llm-wiki 相同，但受預算裁切（§8）。
- `{{titles}}`：全部頁 title（一行一個；超過預算時只列 title，不列 alias），給 missing-page 判斷用。
- 解析：regex `^---LINT:\s*([a-z-]+)\s*\|\s*(warning|info)\s*\|\s*(.+?)\s*---$` 整行標記（同 Ingest 風格，不是 llm-wiki 那種跨行 regex，避免 title 含 `-` 時被 `[^\n-]` 截斷）；`---END LINT---` 收尾；未知 type、未收尾區塊丟掉並記 warning。
- 後處理：missing-page 經 §4.2 existingNames 過濾；PAGES 不在快照中的路徑去掉；contradiction 的 PAGES 少於 2 頁降為 suggestion；繁中檢查只做「title 非空」，不做 OpenCC。
- `ai-prompts/README.md` 加一列與一節。`locateAiPrompts` 必要檔名不加它（缺檔只讓語意階段失敗，結構結果照常）。

## 8. 分批、進度、取消

- 預算：`PromptBudget.inputCharBudget(numCtx)`；`tooSmall(numCtx)` → 語意階段直接跳過並顯示原因，結構結果照常。
- 固定成本 = system + titles 區段。titles 超過預算 30% 時截斷並加 `…（共 N 頁）`。
- 分批：頁依目錄（`concepts`、`entities`、`sources`、`queries`）再依 title 排序後**貪婪裝箱**：每頁片段長度 `min(500, 剩餘/頁)`；單批頁數上限 `LINT_BATCH_MAX_PAGES = 20`；預算夠就裝滿。同目錄相鄰的頁放同批，提高跨頁矛盾被看到的機會。
- 已知限制：矛盾只在同批內找得到。可選改善（open decision）：第二輪把「共享 tag 或互相連結」的頁對組成批次。
- 每批一次 `complete()`（`json=false`），不重試；某批失敗（HTTP、逾時、解析 0 區塊不算失敗）記錯、繼續下一批。
- 進度：`Semantic(batch, batches, pageFrom, pageTo)`，每批開始與結束更新；串流時可另顯示「已收到 N 字」（若用 `stream`）。不再有 llm-wiki 那種結構 N/N 之後卡住的情況。
- 取消：`Job.cancel()`；結構結果一律保留；語意已完成批的結果保留，進行中那批丟棄。模型呼叫在 IO；OllamaClient 的鎖要能被中斷（確認 `stream` 的取消行為，必要時關閉連線）。
- 同一時間只能跑一個 lint；深度研究或 Ingest 正在用模型時，語意階段排隊等 `OllamaClient` 鎖（不另做佇列）。

## 9. 程式落點（建議）

| 檔案 | 內容 |
|---|---|
| `commonMain/.../study/StudyMode.kt` | 加 `Lint` |
| `commonMain/.../HomeScreen.kt` | Wiki 子選單加 `MyTopMenuItem(id = "lint", label = "Lint", …)`（放 Ask (keyword) 之後） |
| `wiki/lint/LintModels.kt` | `LintPage`、`LintFinding(rule, severity, title, detail, pages, searches, reviewType)`、`LintPhase` |
| `wiki/lint/StructuralLint.kt` | `runStructuralLint(pages, resolver, config, onProgress, isActive): List<LintFinding>` |
| `wiki/lint/SemanticLint.kt` | 分批、prompt 組裝、`parseLintBlocks`、過濾 |
| `wiki/lint/LintReview.kt` | `toReviewProposals`、`dedupAgainst(existing)`、`appendLintReviews` |
| `wiki/settings/AppSettings.kt` | `lintSemantic`、`lintIgnorePages` |
| `study/StudyController.kt` | lint 狀態、`startLint()`、`cancelLint()`、`writeLintReviews(selected)` |
| `study/LintScreens.kt`、`StudyPane.kt` | UI |
| `ai-prompts/wiki-lint-semantic.md`、`ai-prompts/README.md` | prompt |

## 10. AGENTS.md 修改

- 「先讀」加：改 Wiki Lint：`docs/programming/How-To-do-Wiki-Lint.md`、`docs/programming/wiki_lint_design_ref.md`。
- prompt 清單加 `wiki-lint-semantic.md`。
- 新契約一條：
  > Wiki Lint（選單 Wiki → Lint）先跑本地規則（orphan、broken-link、missing-page、no-outlinks），不打模型。勾選語意健檢才分批呼叫本機 Ollama（`ai-prompts/wiki-lint-semantic.md`，`json = false`），解析契約是 `---LINT: type | severity | title---`、`PAGES:`、`SEARCH:`、`---END LINT---`。Lint 不改任何頁內容，不連網。只有使用者按「寫入 review.md」才用 `appendReviews` append 勾選項目（`- source: lint`），與既有 review 去重。取消保留已完成的結果。見 `docs/programming/How-To-do-Wiki-Lint.md`。
- json 那句加上 Lint：「Lexical Ask、Ingest、深度研究與 Wiki Lint 的 `json` 是 false」。
- 測試清單加 `Wiki Lint：WikiLintBehaviorTest`。

## 11. 測試：`WikiLintBehaviorTest`（仿 `DeepResearchBehaviorTest`：暫存 vault + `scaffoldWiki`、假 `ChatClient`）

結構：
- orphan：A→B，C 無人連 → C 是 orphan，B 不是；自連不算；`index.md` 的連結不算 inbound；`wiki/sources/` 預設排除。
- broken-link：`[[不存在]]`、`[[頁#段落]]`、`[[頁|別名]]` 正確解析；Ambiguous 不算 broken；兩頁斷連同一目標合併成一筆 missing-page，pages=2。
- missing-page by tag：3 頁 tag `盧曼` 且無頁 → 一筆；有 alias `盧曼` 的頁 → 不報（#537 等價）。
- no-outlinks、ignore 清單（路徑比對、不比裸檔名）。
- 排序 deterministic。
語意：
- 假模型回兩個合法區塊＋一個未知 type＋一個未收尾 → 只留兩個，warnings 兩條。
- missing-page 標題等於既有 title → 被濾掉。
- 小 `numCtx` → 多批，每批 prompt 長度 ≤ 預算；`tooSmall` → 0 次模型呼叫，結構結果仍在。
- 進度事件序列：Structural… → Semantic(1/3)…(3/3)。
- 取消在第 2 批 → 結構結果與第 1 批保留，未寫任何檔。
- 某批丟例外 → 其他批照常。
- prompt 送出內容含 `IngestLanguage.NAME`、不含前言與 HTML 註解；缺 prompt 檔 → 語意失敗訊息，結構照常。
寫入：
- `appendLintReviews` 結果能被 `parseReviewFile` 讀回，type/title/SEARCH/source=lint 正確；深度研究 `researchQueries(entry)` 取到 SEARCH。
- 與既有 ingest review 同鍵 → 略過；`Create 盧曼` 也視為重複。
- 既有 review 段落位元組不變（只 append）。
- 未按寫入 → review.md 不存在／不變；`schema.md`、`raw/` 不被碰。
既有 `WikiBehaviorTest`、`IngestBehaviorTest`、`ChatAgentBehaviorTest`、`DeepResearchBehaviorTest` 仍綠。

## 12. 文件與版號

- 新增 `docs/programming/How-To-do-Wiki-Lint.md`（仿 `How-To-do-Deep-Research.md` 結構：畫面、規則、語意、寫入、測試）。
- 更新 `docs/Architecture.md`（Lint 讀索引快照、只寫 review.md）、`docs/programming/How-To-do-Deep-Research.md`（review 來源多了 `- source: lint`；空狀態文字改為「Ingest 或 Lint 加入」）、`ai-prompts/README.md`、`AGENTS.md`。
- 版號：目前 `app.version=0.9`，建議 `./configure.sh version 0.10`（或 1.0，見 §13）。不要只改一個檔。

## 13. 待決問題

1. orphan / no-outlinks 預設要不要寫進 review？（建議只顯示、預設不勾）深度研究對「補連結」幫助有限。
   核准採用：只顯示，預設不勾。全選可以勾上。
2. orphan 是否排除 `wiki/sources/`？`wiki/queries/research-*` 也常是 orphan，要不要一起排除？
   核准採用：依路徑前綴排除 `wiki/sources/` 與 `wiki/queries/`。不看 frontmatter type。
3. missing-page 第二訊號用 tags 還是乾脆只用斷連頻率？門檻（2 頁／3 頁）？
   核准採用：tags 訊號開。斷連不論 1 頁或更多都留 broken-link，review type 是 missing-page，不另出一筆。tag 門檻是至少 3 個不同頁。不掃自由文字。與斷連同一概念時併進那筆，內文仍寫連結頁數。
4. stale 要不要新增 review type `stale`（需同步改 Ingest 四型契約與深度研究文件），還是併入 suggestion？
   核准採用：不新增 review type。併入 suggestion，正文第一行 `Lint：stale`。
5. 語意批次要不要加「相關頁配對」第二輪以提高找矛盾率（多一倍模型呼叫）？
   核准採用：不加第二輪。矛盾只在同一批內。
6. 寫入 review.md 時要不要也寫 `wiki/log.md`？
   核准採用：至少 append 一筆才寫一條。operation 是 `lint`，title 是 `N 筆 review`，detail 是 `wiki/review.md`。全重複或 0 筆不寫。先 review.md，再 log。log 失敗不回滾 review。
7. 語意階段用 `stream`（可顯示字數）還是 `complete`？
   核准採用：`complete()`，`json = false`。不改 `OllamaClient`。
8. 忽略頁清單存 Preferences（per wiki root key）還是全域？
   核准採用：忽略清單每個 wiki root 一把，鍵是 `lintIgnore.` 加 canonical 路徑的 SHA-256。語意勾選是全域 `lintSemantic`，預設 false。`save(StudySettings)` 不碰忽略鍵。
9. 文件檔名用底線（與現有一致）還是需求寫的連字號 `wiki-lint-design-ref.md`？
   核准採用：這份維持 `wiki_lint_design_ref.md`。How-To 是 `docs/programming/How-To-do-Wiki-Lint.md`。
10. 版號 0.10 或 1.0。
    核准採用：0.10。用 `./configure.sh version 0.10`。
