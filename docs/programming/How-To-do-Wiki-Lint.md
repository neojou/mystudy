# 程式如何做 Wiki Lint

Wiki 選單「Lint（自動健檢 wiki）」對目前索引裡的 wiki 頁做健檢。第一段是本地規則，不打模型。勾了「語意健檢」才分批呼叫本機 Ollama。結果留在記憶體，使用者勾選後才 append 到 `wiki/review.md`。

Lint 不改任何頁的內容，不建 stub，不連網，不改 `wiki/schema.md`，不碰 `raw/`。深度研究仍是唯一的網路出口。Chat 仍不讀 `review.md`。

輸出語言是繁體中文（台灣），跟 Ingest 同一個常數 `IngestLanguage.NAME`。不是偏好，不做 OpenCC。

## 畫面

`StudyMode.Lint`。選單在 Ask (keyword) 後面，標籤是「Lint（自動健檢 wiki）」。`MyTopMenuItem` 只有一個 label。

沒有 wiki root 時，文案與「Create wiki root」跟深度研究相同，沒有開始按鈕。

有 root 時：

- 勾選「語意健檢（Ollama，較慢）」。預設不勾。鍵是 Preferences 的 `lintSemantic`，全域，不是每個 wiki root 一把。
- 「開始健檢」／進行中改成「取消」。
- 「忽略頁」可展開。完整相對路徑或去掉 `.md` 的 slug，用逗號、全形逗號或換行分隔。不比對裸檔名。跑的時候這欄與語意勾選都停用。文字在編輯時與開始時寫入 Preferences，鍵是 `lintIgnore.` 加上該 wiki root canonical 路徑的 SHA-256。`AppSettings.save(StudySettings)` 不碰這些鍵。
- 兩行進度。本地規則是「本地規則：done/total 頁」，跑完加 `✓`。沒有頁是「沒有可檢查的 wiki 頁。」中途取消是「本地規則：已取消（保留 done/total 頁的結果）」。
- 語意進度是「語意健檢：批次 i/n（第 from–to 頁）…」。跑完是「語意健檢：✓ n/n」。取消是「語意健檢：已取消（保留 k/n 批的結果）」。結構階段就取消、語意還沒開始，是「語意健檢：已取消」。跳過是「語意健檢跳過：…」。
- 結果依規則分組。可寫入的項目有勾選。orphan 與 no-outlinks 預設不勾，全選會勾上。ambiguous 沒有 review type，勾選停用，標「僅顯示」。已在 review.md 的標「已在 review.md」，不能再勾。
- 相關頁可點，切到 Browse 並打開該頁。超過 10 頁顯示「等 N 頁」。
- 「寫入 review.md（N 筆）」在進行中或沒有勾選時停用。成功訊息以「已加入」開頭時，出現「到深度研究」。
- 本地規則跑完（狀態含 `✓`）而且沒有任何發現，顯示「沒有發現問題。」「沒有可檢查的 wiki 頁。」不另顯示這句。

切換選單不取消、不清結果。換 wiki root、vault 不見、或還沒有 wiki 時清掉。同一時間只跑一個 Lint。不設全域 busy，也不取消進行中的 Ingest 或深度研究。

## 快照與執行緒

`WikiIndex.indexedNotes()` 讀 Ask 會看到的頁：`wiki/%`，排除 `wiki/index.md`、`wiki/log.md`、`wiki/schema.md`、`wiki/review.md`。`lintSnapshot` 在 `wiki-index` dispatcher 上把這些列做成 `LintPage`，並對每個 outlink 與 tag 呼叫一次 `resolve`。之後的規則不再碰 SQLite。

超過 `MAX_INDEX_BYTES`（2_000_000）的頁，frontmatter 有 `oversized: true`，索引裡的 body 是空的。這些頁不是 orphan、不是 no-outlinks，也不貢獻 outlink。

結構規則在 `Dispatchers.Default`。讀 `review.md` 與模型呼叫在 IO。每次語意呼叫新造一個 `OllamaClient`，走 `complete()`（`json = false`）。模型呼叫不佔 `wiki-index`。

取消用 `lintStop` 與 `Job.cancel()`。不在取消時增加 generation，這樣進行中的 job 還能把已完成的結果寫回畫面。新的一輪與清掉 root 才增加 generation。`complete()` 是阻塞的，進行中的那一批會跑完；若回來時已經取消，那一批丟掉。結構迴圈每頁看一次 `isActive`，停在已經掃過的頁。

## 本地規則

outlink 只算正文的 `[[wikilink]]`。`parseWikilinks` 去掉 `#heading` 與 `|alias`。frontmatter 的 sources、related 不是邊。`http://` 與 `https://`（不分大小寫）與空白目標略過。

排序：`LintRule` 順序 BrokenLink、Ambiguous、MissingPage、Orphan、NoOutlinks、Contradiction、Stale、Suggestion，然後第一個頁路徑，然後 title。

| 規則 | 判定 | review type | 預設勾選 |
| --- | --- | --- | --- |
| broken-link | 其他頁的 outlink 解析是 `None`。同一目標合併成一筆。1 頁與 ≥2 頁都留這條規則，不另出一筆 missing-page | missing-page | 勾 |
| ambiguous | 解析是 `Ambiguous`。同一目標合併。info | 無，不寫 | 不勾 |
| missing-page | tag 訊號，見下 | missing-page | 勾 |
| orphan | 沒有其他頁的 outlink 解析成這一頁。自連不算。路徑前綴 `wiki/sources/`、`wiki/queries/` 排除。不看 frontmatter type | suggestion | 不勾 |
| no-outlinks | `outlinks` 是空的。只有 http(s) 連結時 outlinks 不是空的，所以不是這條；那些連結也不算別人的 inbound | suggestion | 不勾 |

忽略頁不產生發現。它們的 outlink 仍算別人的 inbound。它們的 tag 不計入門檻。

tag 訊號預設開。依 `normalizeConcept` 分組。`normalizeConcept` 是 NFKC、trim、`Locale.ROOT` 小寫。顯示用出現最多的原始寫法，平手取較小的字串。任一寫法的 `resolve` 不是 `None`，或概念已在 `existingNames`，就丟掉。`existingNames` 是非 oversized 頁的 stem、title、aliases，做 `normalizeConcept` 後完全相等，不做子字串，不做 OpenCC。門檻是 ≥3 個不同頁。`LintConfig.missingLinkMin` 有存，不用來藏起只有 1 頁的斷連。

斷連與 tag 是同一個概念時，只留 broken-link。頁面取聯集。SEARCH 仍用斷連標題重算。內文仍寫連結頁數，不寫聯集頁數。

解析比對用索引的 `normalizeKey`（trim 與小寫，不做 NFKC）。Ask 的 `normalizeKey` 不改。

進度每 25 頁或最後一頁更新一次。

## 語意健檢

prompt 是 `ai-prompts/wiki-lint-semantic.md`。`locateAiPrompts` 的必要檔名仍是 `ask.md`、`ingest-analyze.md`、`ingest-generate.md`。缺這份 prompt 只讓語意階段失敗，0 次模型呼叫，結構結果留著。

`PromptBudget.tooSmall`（`num_ctx` ≤ 4096）在讀 prompt 之前就跳過，訊息是「context 太小」。

`inputCharBudget = min(40000, numCtx - 4096)`，至少 0。標題一行一個，超過預算 30% 就截斷並加上 `…（共 N 頁）`。固定成本是填好 `{{languageRule}}` 的 system，加上 pages 為空、標題已截斷的 user。固定成本大於預算就跳過，訊息是「context 不夠放下標題與說明」，0 次呼叫。

頁依目錄 concepts、entities、sources、queries、其他，再依 title、path 排序。貪婪裝箱，不因為換目錄就開新批。每批最多 20 頁。正文摘錄預設 500 字，放不進該批剩餘空間就用二分把摘錄縮短。連標題列都放不進空批的頁略過，警告是 `Skipped page that does not fit the context: path`。`pageFrom`／`pageTo` 是排序後全清單的 1-based 序號。

每批一次 `complete()`，不重試。解析出 0 個區塊算成功。HTTP、逾時、空答案、例外都記「第 i 批失敗：reason（其餘照常）」並繼續。合法區塊超過 8 個只留 8 個，警告 `Ignored extra LINT blocks`。組好的 prompt 仍超過預算就不呼叫，並記失敗。

送進模型的頁文用裝箱時算出的摘錄長度，避免計畫接受的一批在送出時超預算。

## `---LINT:` 解析

逐行，不跨行。標記在 \`\`\` fence 裡時留在正文，跟 Ingest 一樣。

```text
^---LINT:\s*([a-z-]+)\s*\|\s*(warning|info)\s*\|\s*(.+?)\s*---$
```

標題可以含 `-` 與 `|`。type 只准 `contradiction`、`stale`、`missing-page`、`suggestion`。未知 type 丟掉，警告 `Ignored LINT type: …`。未收尾丟掉，警告 `Unclosed LINT discarded: title`。以 `---LINT:` 開頭但對不上格式：`Ignored LINT header`，不開區塊。

正文去掉 `PAGES:`、`SEARCH:`、`OPTIONS:`。`PAGES` 以逗號與 `，` 切開，不在快照裡的路徑丟掉。contradiction 已知頁少於 2 就改成 suggestion。語意 missing-page 在標題空白、`normalizeConcept(title)` 已在 `existingNames`、或 `resolve(title)` 是 `One` 或 `Ambiguous` 時丟掉。不做子字串。

同一輪去重鍵是 `reviewType + "|" + normalizeConcept(title)`。結構結果贏過同鍵的語意結果。ambiguous 沒有鍵，留在畫面上。

## 寫入 review.md

只寫勾選、有 review type、而且還沒在 review.md 的項目。正文由程式放進 `ReviewProposal.body`。`appendReviews` 自己不加 OPTIONS 或 SEARCH，只在後面加 `- source: lint`。

```text
Lint：<rule>（warning|info）
<detail>
相關頁：[[title]]、…（超過 10 頁加「、等 N 頁」）
OPTIONS: Create Page | Skip
SEARCH: ...
```

missing-page（含 broken-link）的 OPTIONS 是 `Create Page | Skip`。其餘可寫項目是 `Research | Skip`。

SEARCH 最多 3 條，空白丟掉，以 `normalizeConcept` 去重：

- missing-page／broken-link：概念本身；再加「概念 + 空格 + 最常見的相關頁標題」（平手取路徑較小的）；還可再加另一個標題，同樣規則。
- 語意：模型的 SEARCH 行以 `|` 切開、trim、去重、最多 3。一條都沒有就用標題。
- orphan／no-outlinks：頁標題。

跟既有 review 去重，不論 source。鍵是 `type|normalizeConcept(title)`。新的 missing-page 還要看既有標題去掉 Create、Research、Save to Wiki 前綴之後，`normalizeConcept` 是否等於這個概念。

寫入前重讀 review 位元組。檔案變了就再去重一次。再變就什麼都不寫，訊息是「review.md 已被修改。請再按一次寫入。」沒有新項目是「沒有新的項目。」成功是「已加入 N 筆，略過重複 M 筆。」沒有 `review.md` 時由 `appendReviews` 從 `# Review` 開始。既有位元組只接在後面。CRLF 檔維持 CRLF。

至少 append 一筆才寫一條 `wiki/log.md`：operation `lint`，title `N 筆 review`，detail `wiki/review.md`。全是重複或 0 筆時 review 與 log 都不寫。先寫 review，再寫 log。log 失敗不回滾 review，訊息是「已加入 N 筆，但 log 沒寫成：…」。`schema.md` 的種子說明本來就有 lint，這次不改 `schema.md`。

寫入成功且 root 沒換，才重讀 review、標已存在的項目、清掉勾選、`reloadReviews()`，並在 `wiki-index` 上 `reconcileAll()`。不在 IO 上 reconcile。不呼叫 `applyProposal`。

stale 不新增 review type。它寫成 suggestion，正文第一行是 `Lint：stale`。深度研究看得到，並用 SEARCH 逐筆處理。成功後仍只刪那一段。

## 測試

`WikiLintBehaviorTest` 鎖了：自連與 index 連結不算 inbound、sources／queries 不是 orphan、斷連合併、ambiguous 不寫、http 略過、`#` 與 `|` 解析、tag 的完全相等與別名、tag 與斷連合併、忽略清單、排序、oversized、`---LINT:`、既有標題過濾、薄 contradiction 降級、小 context 分批、`num_ctx` 4096 零次呼叫、取消留第 1 批、單批失敗留其他批、繁中與前言／註解不送出、缺 prompt 不呼叫、append 後 `parseReviewFile` 與 `researchQueries`、同鍵與 Save to Wiki 前綴略過、既有位元組是前綴、CRLF、未勾選不出現、schema 與 raw 不變、`lintSemantic` 與每 root 的忽略清單。

既有的 `WikiBehaviorTest`、`IngestBehaviorTest`、`ChatAgentBehaviorTest`、`DeepResearchBehaviorTest` 仍要綠。

## 限制

矛盾只在同一批裡找。沒有「相關頁配對」的第二輪。frontmatter 的 sources 不是連結。取消不會中止已經送出的 HTTP；那一批回來後丟掉。
