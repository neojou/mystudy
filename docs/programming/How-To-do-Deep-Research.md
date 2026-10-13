# 程式如何做深度研究

上方選單「深度研究」讀 `wiki/review.md`，對一筆 review 做 SearXNG 搜尋，再請本機 Ollama 綜整，寫入 `wiki/queries/research-*.md`。成功後才從 `wiki/review.md` 刪掉那一段。

Ingest 與 Wiki Lint 都只負責把勾選的項目 append 進 `wiki/review.md`。它們不做搜尋，也不打這支 prompt。Lint 的來源行是 `- source: lint`。深度研究不看 source。有 SEARCH 就用 SEARCH。成功後仍只刪掉這一筆。

輸出語言是繁體中文（台灣），跟 Ingest 同一個常數 `IngestLanguage.NAME`。不是偏好，不做 OpenCC。四種類型共用 `ai-prompts/deep-research.md`。

沒有批次、佇列、embedding、本機檔案搜尋，也不把研究頁再送去 Ingest。一次只跑一筆。Chat、Ask、Ingest 沒有這條網路出口。

## 畫面

`StudyMode.DeepResearch`。選單在 Wiki 與 Settings 之間，標籤是「深度研究」。其餘畫面維持英文。

左欄列出每一筆 review（contradiction、duplicate、missing-page、suggestion），每筆一個「深度研究」按鈕。右欄顯示階段、搜尋命中、串流綜整。`review.md` 沒有項目時顯示「目前沒有待研究項目（Ingest 或 Lint 加入）」。

按鈕要同時成立才可按：SearXNG 連線狀態是 Ok、沒有另一筆在跑、wiki root 已開、Ollama 模型名稱不是空的。Unknown、Testing、Failed 都會停用，並在卡片下顯示原因。

沒有第二個確認對話框。按鈕就是動作。搜尋中與綜整中可以取消，取消不寫檔。進入寫入後會把該步做完。切換選單不取消。wiki root 在寫入前換掉就中止、不寫檔。

完成後的頁面路徑可點，會切到 Browse 並打開該頁。

## SearXNG

Settings 有 SearXNG URL，存在 Preferences（`searxngUrl`），不進 vault。空白存檔會回到預設 `http://127.0.0.1:8080`。只允許 `http` 或 `https` 的 `127.0.0.1` 或 `localhost`。別的主機在存檔與送請求時都會拒絕。

連線測試送 `q=test&format=json`。HTTP 200 且 JSON 有 `results` 陣列就是 Ok，空陣列也算 Ok，畫面寫 `Ok (n results, N ms)`。狀態只在記憶體：載入設定後、存檔後、按「測試連線」、打開深度研究畫面時會測。

真正研究若合併後 0 筆，失敗訊息是「沒有搜尋結果」，不打模型，不寫檔。

請求是 `GET {base}/search?q=&format=json&categories=general&language=auto`，逾時 10 秒，User-Agent 是 `mystudy/` 加產品版號。送給 SearXNG 的只有查詢字串。描述與 index 摘錄只進 loopback 的 Ollama。

每條查詢最多 5 筆，依序查，合併後以正規化 URL 去重（去掉 fragment、host 小寫、保留 query，先到的留下），總上限 15。`file://` 與其他非 http(s) 丟掉。

403 要能看出 JSON format 沒開（`Enable json in search.formats.`）。逾時是 `SearXNG timed out.`。連不上是 `SearXNG is not reachable.`。200 但不是帶 `results` 的 JSON 是 `SearXNG did not return JSON.`。其他 HTTP 是 `SearXNG HTTP {code}.`。

## 查詢詞與 prompt

有 `SEARCH:` 就全用。每行再以 `|` 切開、去掉空白、去重、最多 3 條。沒有 SEARCH 時，主題是標題去掉前綴 Create、Research、Save to Wiki；還是空的話，用描述裡第一個非空、且不是 OPTIONS、SEARCH、source 的行。

`num_ctx` 小於或等於 4096 時，搜尋前就停，0 次 HTTP、0 次模型、不寫檔。prompt 與必要區段也在搜尋前讀。缺檔的訊息是 `Prompt file ai-prompts/deep-research.md is missing. Nothing was written.`。`deep-research.md` 不在尋找 `ai-prompts/` 的必要檔名裡；缺它只讓深度研究失敗，Ask 與 Ingest 仍找得到目錄。

`{{lang}}` 填 `IngestLanguage.NAME`。前言（含「這段前言不會送給模型」）與 HTML 註解不送出。`json` 是 false。一次模型呼叫，走 `OllamaClient.stream`（`stream: true`）。`complete()` 仍是 `stream: false`，Ask、Chat、Ingest 不改成串流。

預算沿用 `PromptBudget`。超過時先丟掉 index 摘錄，再從最後一筆 snippet 往前縮。主題加來源仍放不下就停，不打模型，不分塊，也不打第二次。

模型若把整頁包進一個 code fence，或開頭是 frontmatter，只拆一次。仍不合法就失敗，不打第二次。

合法綜整：去掉空白後至少 80 字，而且每個 `[n]` 都落在 1 到結果筆數。有一個超出範圍，整頁失敗，不寫檔。

## 寫入

檔名是 `wiki/queries/research-<slug>-<yyyy-MM-dd>.md`。slug 把拉丁字母小寫，非字母數字換成 `-`，保留 CJK，摺疊連續的 `-`，最長 60，空的變成 `untitled`，並去掉 `\ / : * ? " < > |`。同名已在磁碟或索引裡就加 `-2`、`-3`，最多到 50，不覆寫。

Frontmatter 由程式寫：`type: query`、`origin: deep-research`、`review`、`queries`。正文後面接 `## Sources`，只列被引用的 `[n]`，編號維持搜尋結果的 n，不重新編號。

`wiki/queries/` 本來就在 `assertWikiWrite` 允許的目錄裡。這次不改白名單。`wiki/schema.md` 與 `raw/` 仍拒絕。

順序是頁、index、log、最後才刪 review。任一步失敗就停，不回滾。頁或 index 或 log 已經落地、review 還在，是接受的風險；重試可能產生 `-2`。失敗不寫 log。成功的 log 用 `appendLog`，operation 是 `deep-research`，title 是主題，details 含新頁路徑。

刪 review 時重讀檔案。只認標頭 `## [YYYY-MM-DD] type | title`，不認內文裡其他 `## `。`rawBlock` 必須剛好出現一次，否則拒絕，訊息是 `review.md changed. Refresh and try again.`，而且不執行這次刪除。其他段落的位元組保持不變。

寫完且 wiki root 沒換，才在 `wiki-index` dispatcher 上 `reconcileAll`。研究頁在 `wiki/queries/`，Ask 索引看得到。Chat 仍不讀 `review.md`。

搜尋與模型在 IO。`noteExists` 用 `runBlocking(wiki-index)`，而且只從 IO 呼叫。`wiki-index` 不呼叫 SearXNG 或 Ollama。SearXNG 進行時不持有 Ollama 的鎖。

## 測試

`DeepResearchBehaviorTest` 鎖了：`appendReviews` 能解析回來、刪一段不改其他位元組、rawBlock 不符或出現兩次就拒絕、SearXNG 的 200／空結果／403／逾時／非 JSON、去重與 5／15 上限、0 筆不打模型、引用不合法不寫檔、成功的寫入順序、`-2`、白名單、空白 URL 回預設、`canResearch`。

既有的 `WikiBehaviorTest`、`IngestBehaviorTest`、`ChatAgentBehaviorTest` 仍要綠。
