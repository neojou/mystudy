# 程式如何做 Wiki Ask

Ask 用本地索引先找出相關 wiki 頁，組成一份有編號的 packet，再把 packet 送給本機 Ollama。模型只能根據 packet 裡的文字回答。沒有種子頁時不會呼叫模型。

這份說明對應 Desktop 實作。入口在畫面，核心在 `AskPipeline.ask`。

## 呼叫鏈

Top Menu 的 `Wiki → Ask` 把畫面切到 `StudyMode.Ask`。

- 選單：`composeApp/src/commonMain/kotlin/com/neojou/mystudy/HomeScreen.kt`
- 模式：`composeApp/src/commonMain/kotlin/com/neojou/mystudy/study/StudyMode.kt`
- 畫面：`composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/StudyScreens.kt` 的 `AskPane`
- 狀態與呼叫：`composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/StudyController.kt` 的 `ask()`

`AskPane` 的 Ask 按鈕呼叫 `StudyController.ask()`。Controller 清空上一次的答案與引用，在 `dbDispatcher` 上執行：

```kotlin
AskPipeline(current, client()).ask(asked, settings.ollamaNumCtx)
```

索引未開啟時回 `AskResult.NotCalled("Index is not open.")`，不會打 Ollama。

## 檔案地圖

| 職責 | 路徑 |
| --- | --- |
| 問答管線、system prompt、packet、存成 query 頁 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ask/AskPipeline.kt` |
| 查詢切詞 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/markdown/MarkdownDocs.kt` 的 `analyzeQuery` |
| 詞彙檢索、評分、泛用詞 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ask/LexicalScore.kt` |
| wikilink 圖、一跳擴展、hop 分數 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ask/LinkGraph.kt` |
| SQLite 索引、FTS、alias、ask 用頁面 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/index/WikiIndex.kt` |
| Ollama `/api/chat`、字元預算 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ollama/OllamaClient.kt` |
| 模型、URL、`num_ctx` 預設值 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/settings/AppSettings.kt` |
| 答案圖 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/AnswerGraph.kt` |
| 行為測試 | `composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/WikiBehaviorTest.kt` |

Browse 搜尋共用 `lexicalPool` 與 `scoreNote`，入口是 `LexicalScore.kt` 的 `browseHits`。Browse 允許 `raw/`。Ask 不會把 `raw/` 送進 prompt。

## `ask()` 步驟

`AskPipeline.ask(question, numCtx)` 依這個順序走。每一步對應上面地圖裡的函式。

### 1. Context 太小就停

`PromptBudget.tooSmall(numCtx)` 在 `num_ctx <= 4096` 時為真。回 `NotCalled`，訊息要求把 `num_ctx` 調到大於 `PromptBudget.RESERVE_TOKENS`。

### 2. 本地切詞

`analyzeQuery(question)` 在 `MarkdownDocs.kt`。這一步不呼叫模型，對齊 llm-wiki 的 `tokenize_query`。

對「什麼是卡片盒筆記法」會得到：

- `phrase`：`卡片盒筆記法`（去掉停用詞後最長的漢字片段）
- `bigrams`：`卡片`、`片盒`、`盒筆`、`筆記`、`記法`（重疊二字）
- `extras`：長度至少 3 的拉丁詞，例如 `gpu`
- `ftsTerms`：長度至少 3 的片段。FTS5 trigram 無法 MATCH 二字 token

漢字停用詞包含「什麼 / 什么 / 與否 / 與 / 与 / 對於 / 對 / 对 / 從 / 从」，以及單字「是、的、了、在、有、和」。拉丁停用詞是常見英文疑問詞與介系詞。單個漢字不會進 token。

`phrase`、`bigrams`、`extras` 全空時回 `NoMatch`，不打模型。

### 3. 詞彙候選池

`lexicalPool` 在 `LexicalScore.kt`。

1. 用 `phrase`、`bigrams`、`extras` 查 `WikiIndex.pathsForAlias`
2. 再用 `ftsTerms` 查 `WikiIndex.searchFts(..., 500)`
3. `includedAskPath` 只留 `wiki/` 底下的頁，排除 `wiki/index.md`、`wiki/log.md`、`wiki/schema.md` 與任何 `raw/`
4. 最多載入 40 頁

`tokenHits` 是通過路徑過濾後的候選數，畫面會顯示這個數字。

FTS 查詢由 `WikiIndex.ftsQuery` 組成：長度至少 3 的 term 用 `OR` 串成 `"卡片盒" OR "片盒筆"` 這類 phrase MATCH。

### 4. 評分與種子

`scoreNote` 對池裡每一頁打分。完全沒有 phrase 或 token 命中的頁丟掉。

| 條件 | 分數 |
| --- | --- |
| 標題或 alias 含整段 `phrase` | +50 |
| 標題或 alias 含一個 token | +10 |
| heading 或 body 含一個 token | +1 |
| 路徑在 `wiki/concepts/`、`wiki/entities/` 或 `wiki/sources/` | +2 |

同分時 `pathRank` 決定順序：concepts → entities → sources → queries → 其他，再比路徑字串。

泛用詞會壓低分數，也會讓頁面當不成種子：

- 固定集合：`原子`、`筆記`、`方法`、`概念`、`系統`、`设计`、`設計`、`知識`、`知识`
- 文件頻率：`countAskTerm(token) / askNoteCount() > 0.20`

命中的 token 全是泛用、標題又沒有整段 phrase 時，分數乘 0.1，且 `canSeed = false`。排序後最多取 5 個 `canSeed` 當種子。沒有種子就回 `NoMatch`，即使 FTS 有命中也不打模型。測試 `genericOnlyOverlapDoesNotCallTheModel` 鎖住這個行為。標題含整段 phrase 時仍可當種子，測試 `phraseInTitleStillRetrievesAtomicDesign`。

### 5. 一跳圖擴展

`WikiIndex.wikiGraph()` 快取 `buildWikiGraph` 的結果。圖只含 Ask 用頁（與 `notesForAsk` 相同過濾）。`[[wikilink]]` 解析成功後建成無向邊。

`expandOneHop` 從種子走出一跳。鄰居分數 `hopScore`：

| 訊號 | 分數 |
| --- | --- |
| 直接相鄰 | 3 |
| 共同 `sources` 每一個 | 4 |
| 共同鄰居的 Adamic–Adar | × 1.5 |
| 與種子同 `type` | 1 |

Adamic–Adar 對每個共同鄰居加 `1 / ln(max(degree, 2))`。鄰居依分數、路徑排序，接在種子後面。種子頁的 `fromGraph = false`，純鄰居 `fromGraph = true`。`graphHits` 是 packet 裡 `fromGraph` 為真的頁數。

### 6. 節錄與 packet 上限

每頁正文用 `excerptAround`：在 `phrase` 與非泛用 bigram 第一次出現處附近截最多 1500 字（起點約在命中點往前 `cap/4`）。

`limitPacket` 再裁一次：

- 最多 `PACKET_PAGES = 8` 頁
- 單頁最多 1500 字
- 全部 excerpt 合計最多 `PACKET_CHARS = 6000` 字；超過就從最後一頁往前丟
- 只剩一頁且仍超過 6000 字，截到 6000

空 packet 回 `NoMatch`。

通過後編成 `PacketPage`，編號從 1。`packetEdges` 只保留 packet 內頁之間的 wikilink，邊的 `line` 是原文那一行，供畫面點選。

### 7. Schema 節錄

`schemaExcerpt` 讀 `wiki/schema.md`。從第一個 `## Frontmatter` 或 `## Links` 起取 `SCHEMA_EXCERPT = 800` 字。檔案開頭「This folder is an llm-wiki tree…」不進 prompt。沒有檔案時寫「（沒有 schema.md）」。

### 8. 組 prompt、檢查預算、呼叫模型

`renderPacket` 組成 user 訊息。system 加 user 的字元數超過 `PromptBudget.inputCharBudget(numCtx)` 時回 `NotCalled`，訊息是頁面放不進 budget，沒有送出。

過關後：

```kotlin
client.complete(ChatRequest(ASK_SYSTEM, user, json = false))
```

Ask 要的是純文字答案。Ingest 的 extract / draft 才設 `json = true`。

Ollama 失敗變成 `NotCalled`。成功則回 `AskResult.Answer`：答案原文、`sourcePaths`、`tokenHits`、`graphHits`、編號頁、邊。

## 送給模型的 prompt

常數與組裝都在 `AskPipeline.kt`。改 Ask 行為時先改這裡。

### System：`ASK_SYSTEM`

```
你只回答使用者這一個問題。用繁體中文。
一句話只放一個主張，句末用對應編號，例如 [1]。
不要複製頁面的標題或小標。不要用訓練記憶補內容。不要上網。
下面的頁面沒有寫的，只回答「沒有」。
只能使用給定的編號。不要自己寫檔名或路徑。
```

模型被限制在 packet 內。沒寫到的內容只能答「沒有」。引用用 `[1]` 這種編號，不可自己寫檔名或路徑。

### User：`renderPacket`

```
問題：{question}

規則節錄（不是答案，不要引用）：
{schema excerpt}

[1] {path}
type: {type}
{excerpt}

[2] {path}
type: {type}
{excerpt}
```

規則節錄是寫作規範，不是答案來源。測試 `cardQuestionOutranksAGenericFalseFriendAndAddsOneGraphHop` 檢查 prompt 含 `規則節錄（不是答案，不要引用）：` 與 `## Frontmatter`，不含 catalog，也不含 schema 開頭那段 llm-wiki 說明。

## Ollama 連線與 budget

`StudyController.client()` 用目前 Settings 建 `OllamaClient`：

| 設定 | 預設 | 寫在哪 |
| --- | --- | --- |
| Base URL | `http://127.0.0.1:11434/v1` | `AppSettings.DEFAULT_OLLAMA_URL` |
| 模型 | `gemma4-64k` | `AppSettings.DEFAULT_OLLAMA_MODEL` |
| `num_ctx` | `65536` | `AppSettings.DEFAULT_NUM_CTX` |

使用者在 Settings 畫面改這三項。Preferences 存在 `com/neojou/mystudy/wiki`，不進 vault。

`OllamaClient` 會把 URL 的 `/v1` 去掉，改打 native `/api/chat`。註解說明 `/v1/chat/completions` 設不了 context 長度。主機必須是 `127.0.0.1` 或 `localhost`。

請求本體：

- `model`、`stream: false`
- `think: false`；若伺服器抱怨 think，同一請求再送一次且不加該欄
- `options.num_ctx`
- Ask 不設 `format: json`
- messages：一則 `system`、一則 `user`

逾時 5 分鐘。回傳讀 `message.content`。

`PromptBudget`（同一個檔案）：

| 常數 | 值 | 用途 |
| --- | --- | --- |
| `MAX_INPUT_CHARS` | 40000 | 輸入字元上限 |
| `RESERVE_TOKENS` | 4096 | 預留給生成；`num_ctx` 必須大於這個值 |
| 字元預算 | `min(40000, num_ctx - 4096)` | system + user 不可超過 |

Ask packet 另外用 `PACKET_PAGES`、`PACKET_CHARS`、`SCHEMA_EXCERPT` 先把內容裁小，再跟這個字元預算比。

## 三種結果

`AskResult` 在 `AskPipeline.kt`。

| 型別 | 何時 | 畫面 |
| --- | --- | --- |
| `NoMatch` | 切詞空、沒有種子、packet 空 | `Nothing in the wiki matches.`，仍顯示 `tokenHits` / `graphHits` |
| `NotCalled` | context 太小、prompt 超預算、Ollama 失敗、索引未開 | 顯示該訊息，沒有答案 |
| `Answer` | 模型回了文字 | Markdown 答案、引用列表、答案圖 |

`AskPane` 在回報後顯示 `tokenHits=…  graphHits=…`。引用列是 `[n] title — path`，點了呼叫 `openIndexed`。`AnswerGraph` 把 packet 裡最多 8 頁排成圓，邊是 packet 內 wikilink。這張圖不是整個 vault 的圖。

## 把答案存成 query 頁

`File this answer` 呼叫 `StudyController.fileAnswer()`，再用 `proposeQueryFile`（`AskPipeline.kt`）。

- 路徑：`wiki/queries/{stem}.md`，檔名來自問題前 40 字；撞名則加 ` (2)`、` (3)`
- frontmatter：`type: query`，`sources` 是這次 packet 的路徑
- 正文：`## Question` 與 `## Answer`
- 會加 catalog 列，等使用者在寫入對話框勾選後，經 `applyProposal` 寫進 vault

## 行為測試鎖住的契約

`WikiBehaviorTest.kt` 裡與 Ask 直接相關的案例：

- `queryAnalysisEmitsCjkBigramsNotSingleCharacters`：切詞、FTS 長度
- `cardQuestionOutranksAGenericFalseFriendAndAddsOneGraphHop`：評分順序、一跳鄰居、prompt 形狀、schema 節錄、不送 raw / index
- `genericOnlyOverlapDoesNotCallTheModel`：沒有種子就不打模型
- `phraseInTitleStillRetrievesAtomicDesign`：標題整句仍可檢索
- `oneHopScoreUsesDirectSourcesAdamicAndSameType`：`hopScore` 公式
