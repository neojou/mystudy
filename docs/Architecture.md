# 架構

mystudy 是桌面 Compose 應用。它對本機 Obsidian vault 做 llm-wiki 式筆記，回答與起草都走本機 Ollama。

對齊的是 [llm-wiki](https://github.com/nashsu/llm_wiki) 的目錄與兩段式匯入。預覽 Ingest 走 Analysis 再 Generation，模型輸出 FILE / REVIEW 區塊，本地解析後才寫。Wiki Lint 先對索引快照跑本地規則，勾選後才分批請 Ollama 做語意健檢，使用者確認後只 append `wiki/review.md`。深度研究讀 `wiki/review.md`，只把查詢字串送到 loopback SearXNG，再串流綜整成 `wiki/queries/` 的一頁。沒有移植 embedding、多模態、持久佇列或分塊。預覽 Ingest 預設不建 `raw/sources` symlink。Settings 可以改成只建那條相對 link，建完就停。

## 目標平台

`composeApp/build.gradle.kts` 只建 desktop JVM。Kotlin target 是 `jvm("desktop")`，toolchain 25。不要加 Android、iOS 或 Web。

`docs/programming/How-To-Change_Project_Name.md` 仍提到 `wasmJsMain`。那是 `configure.sh` 會改寫的路徑，那些檔不在目前的 source set。

## 原始碼怎麼分

| 位置 | 裡面有什麼 |
| --- | --- |
| `composeApp/src/commonMain/kotlin/com/neojou/mystudy/` | `App`、`HomeScreen`、`StudyMode`、主題、`AppVersion` |
| `composeApp/src/commonMain/kotlin/com/neojou/tools/` | 日誌、`SystemSettings`、上方選單。這不是產品套件，改名時不動 |
| `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/` | `Main`、`StudyController`、畫面、整個 `wiki/` |
| `composeApp/src/desktopTest/` | `WikiBehaviorTest`、`IngestBehaviorTest`、`ChatAgentBehaviorTest`、`DeepResearchBehaviorTest`、`WikiLintBehaviorTest`。檢索、prompt 形狀、寫入守衛、Ingest、Chat 工具迴圈、深度研究、Wiki Lint 的契約在這裡 |
| `ai-prompts/` | 送給模型的 prompt。一個動作一個檔 |

`wiki/` 再分成 agent、ask、ingest、lint、research、index、markdown、model、ollama、prompt、settings、vault。`wiki/agent/` 是 Chat Agent（協定、`wiki.search`、`wiki.read_page`、迴圈）。`wiki/lint/` 是 Wiki Lint（本地規則、語意分批、寫 review）。`wiki/research/` 是深度研究（review 解析、SearXNG、寫頁）。檔案系統、SQLite、Ollama、SearXNG 都在 desktopMain，因為它們用的是本機 JVM API。`StudyMode` 在 commonMain。

## 畫面與狀態

`StudyMode` 有 Home、Browse、Chat、Ask、Lint、DeepResearch、Settings。Wiki 選單順序是 Browse、Chat、Ask (keyword)、Lint（自動健檢 wiki）。「深度研究」是 Wiki 與 Settings 之間的頂層項目。Settings 是上方獨立項目。預覽區的 Ingest 按鈕留在 Home，不在 Wiki 選單裡。沒有 Ingest 模式，也沒有 `IngestPane`。

`HomeScreen` 左上角打開檔案樹，預設是開的。樹沒有右鍵選單：Compose 的 `ContextMenuArea` 會和列上的點擊搶同一下 pointer down。動作放在預覽區按鈕。樹根標籤用 vault 路徑的最後一段；Settings 顯示完整路徑。

系統選單列在應用程式名稱右邊有 Windows。它列出目前開著的 Compose `Window`：主視窗、Ask 與 Chat 的知識圖、頁面視窗。About、寫入確認這類對話框不在清單裡。點一項會還原該視窗並把它帶到前面。每一扇視窗都掛這個選單，焦點在子視窗時它還在。

`StudyController` 持有 vault、索引、Ollama 與各畫面的狀態。SQLite 只用名為 `wiki-index` 的那條 dispatcher。Chat 的工具呼叫在這條 dispatcher；模型呼叫在 IO，不佔住它。Chat 的問題、答案與最近 3 輪歷史只在記憶體，不寫 vault、不寫 Preferences。

呼叫關係：

```text
選單或預覽按鈕
  → StudyController
      → Chat：ChatAgent ⇄ WikiTools（WikiSearch / read_page）→ ai-prompts/chat-agent.md → OllamaClient
      → Ask：WikiIndex 本地檢索 → AskPipeline → ai-prompts/ask.md → OllamaClient
      → 預覽 Ingest：IngestPipeline（Analyze → Generate）→ 確認對話框 → applyProposal
      → Lint：索引快照 → 本地規則 → 可選 ai-prompts/wiki-lint-semantic.md → 使用者確認 → 只 append review
      → 深度研究：ReviewFile → SearxngClient → ai-prompts/deep-research.md → OllamaClient.stream → 頁、index、log、移除 review
```

Chat 的工具迴圈、hybrid search 與預算在 `docs/programming/How-To-do-wiki-Chat.md`。Ask 的步驟、切詞與 packet 在 `docs/programming/How-To-do-wiki-Ask.md`。Ingest 的兩次呼叫、路徑守衛與確認寫入在 `docs/programming/How-To-do-Ingest.md`。Wiki Lint 的規則、分批與寫入在 `docs/programming/How-To-do-Wiki-Lint.md`。深度研究的 SearXNG、引用檢查與寫入順序在 `docs/programming/How-To-do-Deep-Research.md`。模型呼叫在 IO。Ingest 讀索引時才回到 `wiki-index`。Lint 的快照在 `wiki-index`，規則與模型不在這條 dispatcher。深度研究的 `noteExists` 也只在 IO 上短暫回到這條 dispatcher。

## Vault、wiki root、來源

三個位置不要混在一起。

Vault 是 Obsidian 庫。預設路徑是 `AppSettings.DEFAULT_VAULT`，目前為 `/Users/neojou/Knowledge/第二大腦`。

Wiki root 是同時有 `raw/` 目錄與 `wiki/index.md` 的目錄。它可以是 vault 本身，也可以是裡面的 `llm-wiki/`。`discoverWikiRoot` 先採用仍在 vault 內的已存 root。否則收集候選：先看 `vault/llm-wiki` 與 vault 自己，再加上 `walkRoots` 找到的其他 root。`walkRoots` 從 vault 往下走，呼叫深度大於 4 就停，目錄 symlink 不跟著走，也跳過點開頭的目錄。零個就還沒有 wiki。一個就打開。多個就請使用者選，不自動挑。Scaffold 建立的是 `vault/llm-wiki/`。

Vault 裡與 `wiki/`、`raw/` 並列的筆記，例如 `Zettelkasten/`，不是 wiki 頁。`isWikiManagedPath` 只含 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/`。判斷用 lexical 路徑，所以 `raw/sources/` 裡指向庫外原文的 symlink 仍算受管。

Wiki 頁在 `{wikiRoot}/wiki/`（sources、concepts、entities、queries、index.md、log.md、schema.md、review.md）。`raw/sources/` 仍是 scaffold 留下的目錄。預覽 Ingest 預設不在那裡建 link。Settings 選 Symbolic link only 時，按鈕只在那裡建相對 symlink。

## 寫入邊界

`assertWikiWrite` 只允許寫 wiki 頁（含 `wiki/queries/`）、`wiki/index.md`、`wiki/log.md` 與 `wiki/review.md`。`wiki/schema.md` 不能從這條路改。`raw/` 不能覆寫。深度研究的新頁走同一條允許清單，不另外加目錄。

`assertNewRawFile` 仍只允許新建 `raw/sources/` 底下的單一檔名，目的地已存在就拒絕。`linkIntoRawSource` 還在。預設的預覽 Ingest 不呼叫它，確認前不寫磁碟。Settings 選 Symbolic link only 時，按鈕只呼叫它，立刻寫那條 link，不打模型。

手動編輯走另一條 `assertNoteEdit`（`wiki/vault/WriteGuard.kt` 與 `NoteFiles.kt`）。它只放行 vault 內既有的 `.md`、`.markdown`、`.txt`，含 `assertWikiWrite` 會拒絕的巢狀 `wiki/*.md`，以及 wiki root 外的筆記。`raw/`、`wiki/schema.md`、點開頭目錄、symlink 仍拒絕。`wiki/index.md`、`log.md`、`review.md` 可編，但畫面上有維護警示。讀寫在 IO，不佔 `wiki-index`；只有詞彙上位於 `wiki/` 且檔名剛好以 `.md` 結尾的存檔，才回到那條 dispatcher 呼叫 `reindexRelative`。不打模型，不連網，不寫 `wiki/log.md`。細節在 `docs/programming/How-To-do-Note-Editor.md`。

## 索引、偏好、Ollama

Ask 用的 SQLite 在 `~/Library/Application Support/mystudy/index/{sha256}.sqlite`。雜湊的是 wiki root canonical 路徑。這個路徑寫死在 macOS 的 Application Support，檔案不進 vault。

偏好在 Java Preferences 節點 `com/neojou/mystudy/wiki`，同樣不進 vault。內容是 vault 路徑、wiki root、Ollama URL、模型、`num_ctx`、SearXNG URL、Ingest 模式、`lintSemantic`、`noteAutosave`。預設 URL 是 `http://127.0.0.1:11434/v1`，模型 `gemma4-64k`，`num_ctx` 是 65536，SearXNG 是 `http://127.0.0.1:8080`。Ingest 模式預設是 `analyze`（分析與產生，不建 link）；`link` 是只建 `raw/sources/` 的相對 symlink。缺鍵或不認得的值都當成 `analyze`。`lintSemantic` 與 `noteAutosave` 只有字串 `true` 算勾選，預設不勾。忽略頁是另一把鍵 `lintIgnore.` 加上 wiki root canonical 路徑的 SHA-256，不跟 `StudySettings` 一起存。`save(StudySettings)` 會寫 `noteAutosave`，不碰 `lintIgnore`。Chat 歷史不在這裡。Lint 結果只在記憶體。SearXNG 連線狀態也不在這裡。筆記編輯的草稿與衝突狀態只在記憶體。

Ollama 主機必須是 `127.0.0.1` 或 `localhost`。客戶端拿掉 URL 的 `/v1`，改打 native `/api/chat`，這樣才能設 `num_ctx`。`/v1/chat/completions` 設不了 context 長度。深度研究的綜整用同一條 loopback，但是 `stream: true`。Wiki Lint 的語意健檢用 `complete()`，`stream: false`。`complete()` 仍是 `stream: false`。

SearXNG 是這個 app 唯一的網路出口，而且只送查詢字串。主機同樣必須是 `127.0.0.1` 或 `localhost`。Chat、Ask、Ingest、Wiki Lint、筆記編輯不呼叫它。Lint 只把 SEARCH 字串寫進 review。

## Prompt

送給模型的文字在 `ai-prompts/`，一個動作一個檔：`ask.md`、`chat-agent.md`、`ingest-analyze.md`、`ingest-generate.md`、`deep-research.md`、`wiki-lint-semantic.md`。程式每次呼叫前讀檔。第一個區段標記之前的前言，以及區段裡的 `<!-- -->`，不送給模型。格式與佔位見 `ai-prompts/README.md`。

Lexical Ask、Ingest、深度研究與 Wiki Lint 的 `json` 是 false。Chat Agent 的 `json` 是 true，這個旗標在程式裡，不在 prompt 檔。Ingest 的解析契約是 FILE / REVIEW 標記，以及 `OPTIONS:`、`SEARCH:`。深度研究的解析契約是 `[n]`。Wiki Lint 的解析契約是 `---LINT:`、`---END LINT---`、`PAGES:`、`SEARCH:`。Chat Agent 的欄位是 `action`、`tool`、`query`、`path`、`answer`，工具名是 `wiki.search`、`wiki.read_page`。改掉這些名字會讓該輪停下、不寫檔。
