# 程式如何做 Wiki Ask

這是 Lexical Ask，選單名稱是 Ask (keyword)。主要問答入口是 Chat，見 `docs/programming/How-To-do-wiki-Chat.md`。

已知限制：`analyzeQuery` 會把「是的了在有和」當成切點。`請介紹植田和男` 的完整人名不會成為查詢詞；vault 裡大量含「介紹」的頁可能占滿 40 頁池。這條路徑的切詞與評分不改。要查這種名字，用 Chat。

Ask 用本地索引先找出相關 wiki 頁，組成一份有編號的 packet，再把 packet 送給本機 Ollama。模型只能根據 packet 裡的文字回答。沒有種子頁時不會呼叫模型。

這份說明對應 Desktop 實作。入口在畫面，核心在 `AskPipeline.ask`。

## 呼叫鏈

Top Menu 的 `Wiki → Ask (keyword)` 把畫面切到 `StudyMode.Ask`。

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
| 問答管線、packet、存成 query 頁 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ask/AskPipeline.kt` |
| Ask 送給模型的 prompt | `ai-prompts/ask.md` |
| 讀 prompt 檔、去掉註解、填 `{{名稱}}` | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/prompt/PromptFiles.kt` |
| 查詢切詞 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/markdown/MarkdownDocs.kt` 的 `analyzeQuery` |
| 詞彙檢索、anchor、評分 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ask/LexicalScore.kt` |
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

### 2. 路徑演算法

這一段決定哪些 wiki 路徑會進 packet，以及先後順序。全程不呼叫模型。函式在 `analyzeQuery`、`LexicalScore.kt`、`LinkGraph.kt`。

#### 切詞

`analyzeQuery` 在 `MarkdownDocs.kt`。llm-wiki 的 `tokenize_query` 也是本地切詞，而且會放出每一個漢字。這裡不放單字，因為 `現` 這類字會讓無關頁變成種子。

問句框架先從每段連續漢字裡拿掉，較長的優先。封閉類是 `什麼 / 什么 / 何謂 / 何為 / 何为 / 為何 / 为何 / 為什麼 / 为什么 / 如何 / 怎麼 / 怎么 / 怎樣 / 怎样 / 是否 / 請問`，以及 `與否 / 與 / 与 / 對於 / 對 / 对 / 從 / 从`。單字停用是「是、的、了、在、有、和」。內容名詞（`筆記`、`方法`、`原子`）不在這份清單裡。拉丁停用詞是常見英文疑問詞與介系詞。

切完得到：

- `pieces`：剩下的漢字片段（長度至少 2）加上長度至少 3 的拉丁詞。後面的 anchor 只用它
- `phrase`：最長的漢字片段。拉丁詞不進 `phrase`
- `bigrams`：每個漢字片段的重疊二字
- `extras`：通過停用詞的拉丁詞，例如 `gpu`
- `ftsTerms`：長度至少 3 的項。漢字片段長度在 3 到 12 時，整段也進去，再加每個重疊三字窗。二字片段沒有 FTS 項，因為 FTS5 trigram 無法 MATCH 二字 token

對「什麼是卡片盒筆記法」，`phrase` 與 `pieces` 都是 `卡片盒筆記法`，`bigrams` 是 `卡片`、`片盒`、`盒筆`、`筆記`、`記法`。FTS 會有整段 `卡片盒筆記法`，以及 `卡片盒`、`片盒筆` 這類三字窗。

對「何謂湧現?」，`何謂` 被拿掉，`phrase`、`pieces`、`bigrams` 都是 `湧現`，`ftsTerms` 是空的。FTS 不會去找 `何謂湧`、`謂湧現`。

`phrase`、`bigrams`、`extras` 全空時回 `NoMatch`，不打模型。

#### Anchor

`contentAnchors` 只看 `pieces` 裡長度並列最長的片段。在片段上由長到短找子字串，長度至少 2，而且 `countAskTerm > 0`。已被更長命中包住的較短子字串丟掉。最長片段在 Ask 頁裡沒有這種子字串時，anchors 為空，不改去用較短的兄弟片段。`countAskTerm` 只數 `wiki/` 頁，不含 `wiki/index.md`、`wiki/log.md`、`wiki/schema.md`，也不含 `raw/`。

拉丁詞若比漢字片段長，anchor 會落在那個拉丁詞上，因為它比 `phrase` 長，而且 `pieces` 含拉丁詞。

`什麼是卡片盒筆記法與原子` 的最長片段是 `卡片盒筆記法`，`原子` 不能單獨當 anchor。主體整段不在索引裡、較短子字串在時，用那個子字串。頁面只寫 `湧現` 時，問句裡的 `湧現現象` 仍找得到。

#### 候選路徑

`lexicalPool` 用 `linkedSetOf` 收集路徑，先放進去的排前面，所以二字 anchor 不會被較長的 FTS 命中擠出前 40 頁。

1. 每個 anchor 呼叫 `searchAskTerm(anchor, 40)`。SQL 已限定 Ask 頁。`LIKE` 比對標題、heading、正文，`%` 與 `_` 會跳脫。排序是標題命中、heading 命中、正文命中，再比路徑
2. 用 `phrase`、`bigrams`、`extras` 查 `pathsForAlias`。鍵經 `normalizeKey`：trim 後轉小寫，然後整鍵相等
3. 用 `ftsTerms` 查 `searchFts(..., 500)`。`ftsQuery` 把長度至少 3 的 term 用 `OR` 串成 `"卡片盒" OR "片盒筆"` 這類 phrase MATCH，tokenizer 維持 trigram
4. `includedAskPath` 只留 `wiki/`。拿掉 `wiki/index.md`、`wiki/log.md`、`wiki/schema.md` 與任何 `raw/`。`wiki/queries/` 和其他 `wiki/` 子路徑留得下來

`tokenHits` 是這份過濾後的全部路徑數，可以大於 40，也含後來當不成種子的 alias 命中。真正載入、拿去評分的只有前 40 頁。畫面顯示的是 `tokenHits`。

「何謂湧現?」的 anchor 是 `湧現`。`pathsForAlias("湧現")` 對不上標題 `湧現 (Emergence)` 或檔名 `emergence-湧現`，二字也沒有 FTS MATCH。`searchAskTerm` 的 `LIKE '%湧現%'` 會帶進標題含這個詞的概念頁、來源頁，以及只在正文寫到的頁。

#### 評分與種子

`scoreNote` 對載入的頁打分。phrase、anchor、token 都沒中的頁丟掉。

| 條件 | 分數 |
| --- | --- |
| 標題或 alias 含整段 `phrase` | +50 |
| 標題或 alias 含一個 anchor | +50 |
| heading 或正文含一個 anchor | +8 |
| 標題或 alias 含一個 bigram 或拉丁 token | +10 |
| heading 或正文含一個 bigram 或拉丁 token | +1 |
| 路徑在 `wiki/concepts/`、`wiki/entities/` 或 `wiki/sources/` | +2 |

`phrase` 只出現在 heading 或正文時，頁面留在排序裡，這一行不加分，也不能因此當種子。`wiki/queries/` 沒有資料夾加分。

`countAskTerm(token) / askNoteCount() > 0.20` 時，那個 token 若本身不是 anchor，它的分數再乘 0.1。這只影響排序。沒有寫死的內容詞清單。問「何謂方法」時，`方法` 就是 anchor，頻率再高也能當種子。測試 `definitionOfAWordOnTheOldGenericListStillSeeds`。

排序是分數由高到低。同分用 `pathRank`：concepts、entities、sources、queries、其他，再比路徑字串。

`canSeed` 為真，表示這是 Ask 路徑，而且標題、alias、heading 或正文含至少一個 anchor。排序後最多取 5 個當種子。沒有種子就回 `NoMatch`，即使 alias 或 FTS 有命中也不打模型。測試 `genericOnlyOverlapDoesNotCallTheModel` 鎖住「庫裡只有原子設計、問的是卡片盒筆記法與原子」：`tokenHits >= 1`，模型不被呼叫。標題就是 `原子設計` 時仍可檢索，測試 `phraseInTitleStillRetrievesAtomicDesign`。

#### 一跳與 packet

`wikiGraph()` 快取 `buildWikiGraph`。圖的節點是 `notesForAsk()` 的全部 Ask 頁，不是上面那 40 頁。`[[wikilink]]` 解析到唯一 Ask 頁後建成無向邊。

`expandOneHop` 從種子走出一跳。鄰居已經是種子就跳過。同一個鄰居被多個種子連到時，留較高的 `hopScore`：

| 訊號 | 分數 |
| --- | --- |
| 直接相鄰 | 3 |
| 共同 `sources` 每一個 | 4 |
| 共同鄰居的 Adamic–Adar | × 1.5 |
| 與種子同 `type` | 1 |

Adamic–Adar 對每個共同鄰居加 `1 / ln(max(degree, 2))`。鄰居依分數、路徑排序，接在種子後面。種子的 `fromGraph = false`，這些鄰居是 `true`。鄰居不在那 40 頁裡時，仍從 `notesForAsk()` 載入。

每頁正文用 `excerptFor`：在 anchor 與 `phrase` 第一次出現處附近截最多 1500 字，起點約在命中點往前 `cap/4`。

`limitPacket` 再裁一次：

- 最多 `PACKET_PAGES = 8` 頁
- 單頁最多 1500 字
- 全部 excerpt 合計最多 `PACKET_CHARS = 6000` 字；超過就從最後一頁往前丟。鄰居排在種子後面，所以超限時先丟掉圖擴展頁
- 只剩一頁且仍超過 6000 字，截到 6000

空 packet 回 `NoMatch`。通過後編成 `PacketPage`，編號從 1。`graphHits` 是最後這個 packet 裡 `fromGraph` 為真的頁數。`packetEdges` 只保留 packet 內頁之間的 wikilink，邊的 `line` 是原文那一行，供畫面點選。

### 3. Schema 節錄

`schemaExcerpt` 讀 `wiki/schema.md`。從第一個 `## Frontmatter` 或 `## Links` 起取 `SCHEMA_EXCERPT = 800` 字。檔案開頭「This folder is an llm-wiki tree…」不進 prompt。沒有檔案時寫「（沒有 schema.md）」。

### 4. 組 prompt、檢查預算、呼叫模型

每次呼叫前讀 `ai-prompts/ask.md`。前言與 `<!-- -->` 不送出。`renderPacket` 把問題、schema 節錄、編號頁填進檔案裡的 `{{question}}`、`{{schema}}`、`{{pages}}`。每一頁用 `page` 區段，頁與頁以一個空白行相接。

檔案讀不到、缺少 `system` / `user` / `page`，或缺少必要佔位時回 `NotCalled`，沒有送出。system 加 user 的字元數超過 `PromptBudget.inputCharBudget(numCtx)` 時也回 `NotCalled`，訊息是頁面放不進 budget。

過關後：

```kotlin
client.complete(ChatRequest(system, user, json = false))
```

Ask 要的是純文字答案。Ingest 的 Analyze / Generate 也是 `json = false`。Chat Agent 才設 `json = true`。改 `ask.md` 不會打開 JSON。

Ollama 失敗變成 `NotCalled`。成功則回 `AskResult.Answer`：答案原文、`sourcePaths`、`tokenHits`、`graphHits`、編號頁、邊。

## 送給模型的 prompt

文字在 `ai-prompts/ask.md`。改用詞時改這個檔，存檔後下一次 Ask 生效，不用重新編譯。packet 怎麼選、schema 怎麼裁、預算怎麼算，仍在 `AskPipeline.kt`。

檔案第一個 `--- system ---` 之前是前言，不送出。`system`、`user`、`page` 三個區段才是 prompt。區段裡的 `<!-- 註解 -->` 會刪掉。

### System

模型被限制在 packet 內。沒寫到的內容只能答「沒有」。引用用 `[1]` 這種編號，不可自己寫檔名或路徑。預設句子見 `ask.md` 的 `system` 區段。

### User

```
問題：{{question}}

規則節錄（不是答案，不要引用）：
{{schema}}

{{pages}}
```

`page` 區段預設是：

```
[{{number}}] {{path}}
type: {{type}}
{{excerpt}}
```

規則節錄是寫作規範，不是答案來源。測試 `cardQuestionOutranksAGenericFalseFriendAndAddsOneGraphHop` 檢查送出的 prompt 含 `規則節錄（不是答案，不要引用）：` 與 `## Frontmatter`，不含前言、不含 catalog，也不含 schema 開頭那段 llm-wiki 說明。`{{名稱}}` 只在骨架上替換一次，頁面正文裡的佔位字樣會原樣送出。

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

`AskPane` 在回報後顯示 `tokenHits=…  graphHits=…`。

答案在自己的區塊裡，用 `SelectionContainer` 包住 `MarkdownText`。選取後用系統複製快捷鍵，文字進作業系統剪貼簿，可以貼到其他 app。複製的是畫面上的字，不含 `**` 這類標記。這個區塊右邊有 `VerticalScrollbar`，只捲答案，不連引用一起捲。

引用列是 `[n] title — path`。點了呼叫 `openAskPage`，用一扇新視窗顯示該頁索引裡的 body。再點另一頁會換成同一扇視窗的標題與正文，不疊多扇。正文同樣可以選取複製，右邊有捲軸。這不走 `openIndexed`，所以不會改到 Home 或 Browse 的預覽。

答案帶有頁面時自動打開標題為 `Knowledge graph` 的視窗。關掉只關這扇視窗。`Knowledge graph` 按鈕可再打開。沒有頁面時視窗關掉。視窗裡的 `AnswerGraph` 把 packet 裡最多 8 頁排成圓，邊是 packet 內 wikilink。點節點打開頁面視窗，點邊在圖視窗裡顯示那一行 wikilink。這張圖不是整個 vault 的圖。新問一次會先關掉這兩扇視窗；新答案仍有頁面時，圖視窗再打開。

## 把答案存成 query 頁

`File this answer` 呼叫 `StudyController.fileAnswer()`，再用 `proposeQueryFile`（`AskPipeline.kt`）。

- 路徑：`wiki/queries/{stem}.md`，檔名來自問題前 40 字；撞名則加 ` (2)`、` (3)`
- frontmatter：`type: query`，`sources` 是這次 packet 的路徑
- 正文：`## Question` 與 `## Answer`
- 會加 catalog 列，等使用者在寫入對話框勾選後，經 `applyProposal` 寫進 vault

## 行為測試鎖住的契約

`WikiBehaviorTest.kt` 裡與 Ask 直接相關的案例：

- `queryAnalysisEmitsCjkBigramsNotSingleCharacters`：切詞、FTS 長度，以及「何謂湧現?」去掉問句框架後只剩 `湧現`
- `cardQuestionOutranksAGenericFalseFriendAndAddsOneGraphHop`：anchor 只有 `卡片盒筆記法`、`原子設計` 不可當種子、一跳鄰居、prompt 形狀、schema 節錄、不送 raw / index
- `genericOnlyOverlapDoesNotCallTheModel`：主體不在索引裡時，不因為較短的 `原子` 去打模型
- `phraseInTitleStillRetrievesAtomicDesign`：標題整句仍可檢索
- `definitionQuestionFindsTwoCharacterConceptWithoutTheQuestionFrame`：「何謂湧現?」找到標題 `湧現 (Emergence)`、來源頁，以及只在正文提到的實體；`原子設計` 不進 packet
- `definitionOfAWordOnTheOldGenericListStillSeeds`：「何謂方法」仍以正文裡的 `方法` 當種子
- `oneHopScoreUsesDirectSourcesAdamicAndSameType`：`hopScore` 公式
