# AGENTS.md

桌面 Compose 應用：對本機 Obsidian vault 做 llm-wiki 式筆記，模型是本機 Ollama。系統地圖在 `docs/Architecture.md`。

## 先讀

依要改的範圍讀，不必一次讀完。

1. `docs/Architecture.md`：分層、vault 與 wiki root、寫入邊界、索引與 Ollama。
2. `docs/Development.md`：怎麼跑、怎麼測、名稱與版號。
3. 改 Lexical Ask：`docs/programming/How-To-do-wiki-Ask.md`。
4. 改 Chat Agent：`docs/programming/How-To-do-wiki-Chat.md`、`docs/programming/chat_agent_design_ref.md`。
5. 改 Ingest：`docs/programming/How-To-do-Ingest.md`。
6. 改深度研究：`docs/programming/How-To-do-Deep-Research.md`。
7. 改 Wiki Lint：`docs/programming/How-To-do-Wiki-Lint.md`、`docs/programming/wiki_lint_design_ref.md`。
8. 改送給模型的用詞：`ai-prompts/README.md`，以及 `ask.md`、`chat-agent.md`、`ingest-analyze.md`、`ingest-generate.md`、`deep-research.md`、`wiki-lint-semantic.md`。
9. 改名稱：`docs/programming/How-To-Change_Project_Name.md`。
10. 改版號：`docs/programming/How-To-Change_Version_number.md`。

## 指令

```bash
./gradlew :composeApp:run
./gradlew :composeApp:desktopTest
```

`run` 的工作目錄是專案根，會讀到正在改的 `ai-prompts/`。

## 接手時保持的契約

- 只建 desktop JVM。不要加 Android、iOS 或 Web。見 `docs/Architecture.md`。
- Lexical Ask（選單 Ask (keyword)）只用本地關鍵字。沒有種子頁就不打模型，也不把 `raw/` 送進 prompt。不做 query rewrite、embedding 或 LLM rerank。見 `docs/programming/How-To-do-wiki-Ask.md`。
- Chat Agent（選單 Chat）由模型在有界 JSON 工具迴圈中決定查詢詞。工具只有唯讀的 `wiki.search` 與 `wiki.read_page`，只讀索引中的 `wiki/` 頁，不讀 `raw/`、`index.md`、`log.md`、`schema.md`、`review.md`。每回合最多 6 次迴圈呼叫＋1 次收尾、4 次工具執行。一頁都沒檢索到就不打收尾。不做 embedding、不做 LLM rerank、沒有寫入或網路工具。見 `docs/programming/How-To-do-wiki-Chat.md`。
- 預覽 Ingest 的預設是分析與產生，不建 symlink。它對目前這則筆記跑 Analyze（`ai-prompts/ingest-analyze.md`）再 Generate（`ai-prompts/ingest-generate.md`），最多 2 次模型呼叫，`json = false`。確認前不寫磁碟。確認後才寫 `wiki/sources|concepts|entities/`、`wiki/index.md`、`wiki/log.md`，勾選的 REVIEW append 到 `wiki/review.md`。既有頁只 append。Settings 可改成只建 `raw/sources/` 的相對 symlink，建完就停，不打模型。見 `docs/programming/How-To-do-Ingest.md`。
- 深度研究（選單「深度研究」）是唯一的網路出口。它只把查詢字串送到 loopback 的 SearXNG（預設 `http://127.0.0.1:8080`，只允許 127.0.0.1 或 localhost）。綜整讀 `ai-prompts/deep-research.md`，`json = false`，一次串流呼叫。成功才寫 `wiki/queries/research-*.md`、`wiki/index.md`、`wiki/log.md`，並從 `wiki/review.md` 移除該段。失敗不寫檔。Chat、Ask、Ingest、Wiki Lint 不使用這條出口。Lint 只把 SEARCH 字串寫進 review，不自己送出。Chat 仍不讀 `review.md`。見 `docs/programming/How-To-do-Deep-Research.md`。
- Wiki Lint（選單 Wiki → Lint）先跑本地規則（orphan、broken-link、missing-page、no-outlinks），不打模型。勾選語意健檢才分批呼叫本機 Ollama（`ai-prompts/wiki-lint-semantic.md`，`json = false`），解析契約是 `---LINT: type | severity | title---`、`PAGES:`、`SEARCH:`、`---END LINT---`。Lint 不改任何頁內容，不連網。只有使用者按「寫入 review.md」才用 `appendReviews` append 勾選項目（`- source: lint`），與既有 review 去重。取消保留已完成的結果。見 `docs/programming/How-To-do-Wiki-Lint.md`。
- 寫入通道不改 `wiki/schema.md`，也不覆寫 `raw/`。見 `docs/Architecture.md`。
- Prompt 放在 `ai-prompts/`（`ask.md`、`chat-agent.md`、`ingest-analyze.md`、`ingest-generate.md`、`deep-research.md`、`wiki-lint-semantic.md`），每次呼叫現讀。不要把 system 字串搬回 Kotlin。Ingest 的解析契約是 `---FILE:`、`---END FILE---`、`---REVIEW:`、`---END REVIEW---`、`OPTIONS:`、`SEARCH:`。深度研究的解析契約是 `[n]`。Wiki Lint 的解析契約是 `---LINT:`、`---END LINT---`、`PAGES:`、`SEARCH:`。Chat Agent 的 `action`、`tool`、`query`、`path`、`answer` 與工具名 `wiki.search`、`wiki.read_page` 是解析契約。`json = true` 只用在 Chat Agent，旗標在程式；Lexical Ask、Ingest、深度研究與 Wiki Lint 的 `json` 是 false。見 `ai-prompts/README.md`。
- Ollama 只連 loopback，走 native `/api/chat`。見 `docs/Architecture.md`。
- SQLite 只用 `StudyController` 的 `wiki-index` dispatcher。模型呼叫不要佔住 `wiki-index` dispatcher（Chat Agent 的工具在該 dispatcher，模型呼叫在 IO。Ingest 的模型呼叫也在 IO，索引查詢才回到這條 dispatcher。Wiki Lint 的快照在這條 dispatcher，模型呼叫在 IO）。
- 偏好與索引不寫進 vault。Chat 歷史只在記憶體，不寫 vault、不寫 Preferences。見 `docs/Architecture.md`。
- 檔案樹沒有右鍵選單，動作在預覽區按鈕。樹根標籤用路徑最後一段。見 `docs/Architecture.md`。
- 名稱與版號用 `./configure.sh`，不要只改一個檔。見 `docs/Development.md`。

改行為時，同時改對應的 How-To 與測試（Lexical Ask：`WikiBehaviorTest`；Ingest：`IngestBehaviorTest`；Chat Agent：`ChatAgentBehaviorTest`；深度研究：`DeepResearchBehaviorTest`；Wiki Lint：`WikiLintBehaviorTest`）。文件與測試衝突時，以測試與目前程式為準，再把文件改到一致。
