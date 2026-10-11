# 架構

mystudy 是桌面 Compose 應用。它對本機 Obsidian vault 做 llm-wiki 式筆記，回答與起草都走本機 Ollama。

對齊的是 [llm-wiki](https://github.com/nashsu/llm_wiki) 的目錄與兩段式匯入。這裡沒有移植 FILE block、purpose.md、overview.md、embedding、多模態或持久佇列。

## 目標平台

`composeApp/build.gradle.kts` 只建 desktop JVM。Kotlin target 是 `jvm("desktop")`，toolchain 25。不要加 Android、iOS 或 Web。

`docs/programming/How-To-Change_Project_Name.md` 仍提到 `wasmJsMain`。那是 `configure.sh` 會改寫的路徑，那些檔不在目前的 source set。

## 原始碼怎麼分

| 位置 | 裡面有什麼 |
| --- | --- |
| `composeApp/src/commonMain/kotlin/com/neojou/mystudy/` | `App`、`HomeScreen`、`StudyMode`、主題、`AppVersion` |
| `composeApp/src/commonMain/kotlin/com/neojou/tools/` | 日誌、`SystemSettings`、上方選單。這不是產品套件，改名時不動 |
| `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/` | `Main`、`StudyController`、畫面、整個 `wiki/` |
| `composeApp/src/desktopTest/` | `WikiBehaviorTest`、`ChatAgentBehaviorTest`。檢索、prompt 形狀、寫入守衛、Chat 工具迴圈的契約在這裡 |
| `ai-prompts/` | 送給模型的 prompt。一個動作一個檔 |

`wiki/` 再分成 agent、ask、ingest、index、markdown、model、ollama、prompt、settings、vault。`wiki/agent/` 是 Chat Agent（協定、`wiki.search`、`wiki.read_page`、迴圈）。檔案系統、SQLite、Ollama 都在 desktopMain，因為它們用的是本機 JVM API。`StudyMode` 在 commonMain。

## 畫面與狀態

`StudyMode` 有 Home、Browse、Chat、Ingest、Ask、Settings。Wiki 選單順序是 Browse、Chat、Ask (keyword)。Settings 是上方獨立項目。預覽區的 Ingest 按鈕留在 Home，不在 Wiki 選單裡。`StudyMode.Ingest` 與 `IngestPane` 仍在，預覽流程不會切過去。

`HomeScreen` 左上角打開檔案樹，預設是開的。樹沒有右鍵選單：Compose 的 `ContextMenuArea` 會和列上的點擊搶同一下 pointer down。動作放在預覽區按鈕。樹根標籤用 vault 路徑的最後一段；Settings 顯示完整路徑。

`StudyController` 持有 vault、索引、Ollama 與各畫面的狀態。SQLite 只用名為 `wiki-index` 的那條 dispatcher。Chat 的工具呼叫在這條 dispatcher；模型呼叫在 IO，不佔住它。Chat 的問題、答案與最近 3 輪歷史只在記憶體，不寫 vault、不寫 Preferences。

呼叫關係：

```text
選單或預覽按鈕
  → StudyController
      → Chat：ChatAgent ⇄ WikiTools（WikiSearch / read_page）→ ai-prompts/chat-agent.md → OllamaClient
      → Ask：WikiIndex 本地檢索 → AskPipeline → ai-prompts/ask.md → OllamaClient
      → 預覽 Ingest：linkIntoRawSource（只建 symlink）
      → Extract / Draft：IngestPipeline → ai-prompts/ingest-*.md → OllamaClient
```

Chat 的工具迴圈、hybrid search 與預算在 `docs/programming/How-To-do-wiki-Chat.md`。Ask 的步驟、切詞與 packet 在 `docs/programming/How-To-do-wiki-Ask.md`。Ingest 的 symlink、Extract、Draft 在 `docs/programming/How-To-do-Ingest.md`。

## Vault、wiki root、來源

三個位置不要混在一起。

Vault 是 Obsidian 庫。預設路徑是 `AppSettings.DEFAULT_VAULT`，目前為 `/Users/neojou/Knowledge/第二大腦`。

Wiki root 是同時有 `raw/` 目錄與 `wiki/index.md` 的目錄。它可以是 vault 本身，也可以是裡面的 `llm-wiki/`。`discoverWikiRoot` 先採用仍在 vault 內的已存 root。否則收集候選：先看 `vault/llm-wiki` 與 vault 自己，再加上 `walkRoots` 找到的其他 root。`walkRoots` 從 vault 往下走，呼叫深度大於 4 就停，目錄 symlink 不跟著走，也跳過點開頭的目錄。零個就還沒有 wiki。一個就打開。多個就請使用者選，不自動挑。Scaffold 建立的是 `vault/llm-wiki/`。

Vault 裡與 `wiki/`、`raw/` 並列的筆記，例如 `Zettelkasten/`，不是 wiki 頁。`isWikiManagedPath` 只含 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/`。判斷用 lexical 路徑，所以 `raw/sources/` 裡指向庫外原文的 symlink 仍算受管。

Wiki 頁在 `{wikiRoot}/wiki/`（sources、concepts、entities、queries、index.md、log.md、schema.md）。來源 link 在 `{wikiRoot}/raw/sources/`。

## 寫入邊界

`assertWikiWrite` 只允許寫 wiki 頁、`wiki/index.md` 與 `wiki/log.md`。`wiki/schema.md` 不能從這條路改。`raw/` 不能覆寫。

新的來源 link 走 `assertNewRawFile`：只能新建 `raw/sources/` 底下的單一檔名，目的地已存在就拒絕。預覽 Ingest 只做這件事，不複製、不移動原文。Extract 與 Draft 不會被這個按鈕呼叫。

## 索引、偏好、Ollama

Ask 用的 SQLite 在 `~/Library/Application Support/mystudy/index/{sha256}.sqlite`。雜湊的是 wiki root canonical 路徑。這個路徑寫死在 macOS 的 Application Support，檔案不進 vault。

偏好在 Java Preferences 節點 `com/neojou/mystudy/wiki`，同樣不進 vault。內容是 vault 路徑、wiki root、Ollama URL、模型、`num_ctx`。預設 URL 是 `http://127.0.0.1:11434/v1`，模型 `gemma4-64k`，`num_ctx` 是 65536。Chat 歷史不在這裡。

Ollama 主機必須是 `127.0.0.1` 或 `localhost`。客戶端拿掉 URL 的 `/v1`，改打 native `/api/chat`，這樣才能設 `num_ctx`。`/v1/chat/completions` 設不了 context 長度。

## Prompt

送給模型的文字在 `ai-prompts/`，一個動作一個檔：`ask.md`、`chat-agent.md`、`ingest-extract.md`、`ingest-draft.md`。程式每次呼叫前讀檔。第一個區段標記之前的前言，以及區段裡的 `<!-- -->`，不送給模型。格式與佔位見 `ai-prompts/README.md`。

Lexical Ask 的 `json` 是 false。Extract、Draft、Chat Agent 的 `json` 是 true，這個旗標在程式裡，不在 prompt 檔。Extract / Draft 的 JSON 欄位名是 `parseClaims` 與 draft 解析讀的名字。Chat Agent 的欄位是 `action`、`tool`、`query`、`path`、`answer`，工具名是 `wiki.search`、`wiki.read_page`。改掉這些名字會讓該輪停下、不寫檔。
