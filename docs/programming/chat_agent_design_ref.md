# Chat Agent 設計參考（對齊 llm-wiki Chat 的工具迴圈）

> 放置位置：`docs/programming/chat-agent-design-ref.md`。
> 背景與證據：`docs/programming/llm-wiki-chat-gap.md`（mystudy Ask 為何找不到植田和男）、`docs/programming/llm-wiki-chat-mechanism.md`（llm-wiki Chat 原始碼解析，可選）。
> 對象：`neojou/mystudy` `main` @ `d030dc1`。本文件是**設計目標**；實作完成後，行為以測試與 `docs/programming/How-To-do-wiki-Chat.md` 為準。
> 這份取代 `wiki-ask-design-ref.md` 的分階段計畫：不再分 P1–P4，Chat Agent 一次交付，並把「修 CJK 停用字切詞＋頁名完全相等加分」做在 Chat Agent 的 `wiki.search` 工具裡。

---

## 0. 一句話

新增一條 **Chat Agent** 路徑，成為 Wiki 的主要問答入口：本機 Ollama 在有界的 JSON 工具迴圈中自己決定 `wiki.search` 的查詢詞 → 本地 hybrid 檢索（token＋頁名＋一跳圖）→ 視需要 `wiki.read_page` 讀全文 → 回 `{"action":"final","answer":"..."}`。既有 Lexical Ask（`AskPipeline`）**保留不動**，改名為次要入口。

---

## 1. 驗收標準（必須全部成立）

1. vault 有 `wiki/entities/植田和男.md`（title、alias 或檔名 stem 為 `植田和男`），問 Chat：「請介紹植田和男」：
   - `wiki.search` 結果第 1 名是 `wiki/entities/植田和男.md`，`nameHits ≥ 1`。
   - 即使 vault 另有 ≥45 頁正文含「介紹」，結果不變。
   - 即使模型把**整句**「請介紹植田和男」當 query，第 1 名仍是該頁（搜尋工具內部自己剝請求框架、做頁名查表）。
   - 最終答案來自該頁內容並引用 `[n]`，不是「沒有」。
2. 變體同樣通過：`植田和男`、`植田和男是誰`、`介紹一下植田和男`、`請問植田和男是誰？`、`日本央行總裁植田和男的政策`（實體頁在前 5 名）。
3. title 為 `植田和男 (Kazuo Ueda)`、或 title 是英文而中文名只在檔名 stem 時，也通過。
4. 一次 Chat 回合最多 `MAX_LLM_CALLS = 7` 次模型呼叫（6 次迴圈＋1 次強制收尾），工具執行最多 `RETRIEVAL_BUDGET = 4` 次。
5. 一頁都沒檢索到時回 `NoMatch`，**不打最後回答那一次**模型。
6. 每次工具執行、每次收尾各有一行 `MyLog`（§8）。
7. 既有 `WikiBehaviorTest` 全綠（Lexical Ask 行為不變）；新增 Chat Agent 測試全綠：`./gradlew :composeApp:desktopTest`。
8. 只建 desktop JVM；不引入 Rust、Tauri、llm-wiki 程式碼；Ollama 仍只連 loopback `/api/chat`。

---

## 2. 與現有 AskPipeline 的關係

| 項目 | Lexical Ask（既有） | Chat Agent（新） |
| --- | --- | --- |
| 入口 | 選單 `Ask (keyword)`，`StudyMode.Ask`，`AskPane` | 選單 `Chat`（放第一個，Wiki 預設問答），`StudyMode.Chat`，`ChatPane` |
| 查詢理解 | 本地 `analyzeQuery`，不打模型 | **模型**產生 `wiki.search` 的 `query`；搜尋工具內再做本地切詞＋頁名查表 |
| 模型呼叫 | 1 次，`json = false` | 2–7 次，全部 `json = true`（答案放在 JSON 的 `answer` 字串裡） |
| 檢索 | `lexicalPool` / `scoreNote` / `expandOneHop` | 新 `WikiSearch`（§5）；**重用** `expandOneHop`、`includedAskPath`、`WikiIndex` 查詢 |
| 上下文 | 一次組好的 packet（8 頁 / 6000 字） | 每輪重組：references 清單＋snippet、工具觀察（含 `read_page` 全文） |
| 多輪 | 無 | 同一 Chat 視窗保留最近 3 輪問答（只在記憶體） |
| 引用 | `[n]` 對回 packet | `[n]` 對回本回合 references 清單（編號一旦配給不變） |
| 程式碼 | `wiki/ask/` | 新 `wiki/agent/`；`wiki/ask/` 只允許**新增** `internal`/public 可見度調整或抽出共用函式，行為不變 |
| prompt | `ai-prompts/ask.md` | 新 `ai-prompts/chat-agent.md` |

決策：
- **不刪** Lexical Ask。它的確定性與測試是回歸基準；Chat 失敗時（Ollama 斷、JSON 一直壞）使用者仍有退路。
- Lexical Ask 本次**不修**植田和男問題（避免動到 7 個鎖住的 Ask 測試）。文件註明「已知限制：名字含 是/的/了/在/有/和 時找不到，請用 Chat」。
- `AskPipeline` 中 Chat 也要用的小工具（例如 `packetEdges`、`proposeQueryFile`、`schemaExcerpt`）如果是 private，改成 internal 或抽到共用檔，**不改邏輯**。

---

## 3. 模組與檔案（全部在 desktopMain，除了 `StudyMode`）

```
composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/agent/
  AgentProtocol.kt   // AgentAction 型別、parseAgentAction（純函式）
  WikiSearch.kt      // tokenizeSearchQuery、stripRequestFrame、exactNameHits、scoreSearchHit（純）＋ WikiSearch(index) 呼叫 SQL
  WikiTools.kt       // wiki.search / wiki.read_page 的執行與觀察字串、路徑驗證
  ChatAgent.kt       // 迴圈、預算、prompt 組裝、強制收尾、ChatAgentResult、AgentEvent
  ChatSession.kt     // 最近 3 輪歷史（記憶體）
composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/
  ChatScreens.kt     // ChatPane（或放進 StudyScreens.kt）
  StudyController.kt // chat()、cancelChat()、newChat()、狀態
composeApp/src/commonMain/kotlin/com/neojou/mystudy/study/StudyMode.kt   // 加 Chat
composeApp/src/commonMain/kotlin/com/neojou/mystudy/HomeScreen.kt         // 選單加 Chat，Ask 改標籤
ai-prompts/chat-agent.md
composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/ChatAgentBehaviorTest.kt
```

`WikiIndex.kt` 需要的新增（只加不改）：
- `reloadMaps()`：`byAlias` 另放**去括號 key**（`植田和男 (Kazuo Ueda)` → `植田和男`；全形 `（…）` 同理），在記憶體算，不改 SQLite schema（`refreshFile` 在 hash 未變時不重寫 aliases，所以不能只靠寫入時算）。Lexical Ask 也會因此多命中，需確認 `definitionQuestionFindsTwoCharacterConceptWithoutTheQuestionFrame` 仍綠；若有風險，改成另一張 `byLooseAlias` 只給 Chat 用。
- 若需要：`searchSearchTerm(term, limit)`＝`searchAskTerm` 加 **檔名 stem** 比對（只比最後一段檔名，不比 `wiki/entities/` 資料夾名），排序 stem 相等 → title → heading → body。

---

## 4. 工具 JSON 協定

### 4.1 模型輸出（每一輪只能一個物件）

```json
{"action":"tool","tool":"wiki.search","query":"植田和男"}
{"action":"tool","tool":"wiki.read_page","path":"wiki/entities/植田和男.md"}
{"action":"final","answer":"植田和男是日本銀行總裁 [1]。……"}
```

欄位名 `action`、`tool`、`query`、`path`、`answer` 與工具名 `wiki.search`、`wiki.read_page` 是**解析契約**，寫進 AGENTS.md 與 `ai-prompts/README.md`。

### 4.2 `parseAgentAction(raw: String): AgentAction`（純函式）

```kotlin
sealed class AgentAction {
    data class Search(val query: String) : AgentAction()
    data class ReadPage(val path: String) : AgentAction()
    data class Final(val answer: String) : AgentAction()
    data class Invalid(val reason: String, val raw: String) : AgentAction()
}
```

容錯規則（用 kotlinx.serialization `Json { ignoreUnknownKeys = true; isLenient = true }` 解 `JsonObject`）：
1. 去掉 ```` ```json ```` 圍欄；取第一個平衡的 `{…}`。
2. `action` 缺漏時：有 `answer` → Final；有 `tool` → tool。
3. `tool` 接受 `wiki.search` / `wiki_search` / `search`，`wiki.read_page` / `read_page` / `read`。
4. `query` 去頭尾空白，長度 1–64；`path` 去頭尾空白與反引號。
5. `answer` 空白 → Invalid。其餘不合 → Invalid（帶原因）。

### 4.3 迴圈對 Invalid 的處理

- 記一則觀察 `invalid action: <reason>. Return one JSON object.`，算一次迴圈步數，不算工具次數。
- 連續 2 次 Invalid → 進強制收尾（§6.3）。
- 強制收尾仍 Invalid：若原始輸出非空且不像 JSON，直接把它當答案；否則回 `NotCalled("模型沒有回傳可解析的答案")`。

---

## 5. `wiki.search`：hybrid 檢索（工具內部，全部本地）

**這是修好植田和男的地方。** 不論模型給 `植田和男` 還是整句，都要得到同樣的第 1 名。

### 5.1 切詞 `tokenizeSearchQuery(query): SearchQuery`（純函式）

```kotlin
data class SearchQuery(
    val runs: List<String>,     // 去掉請求框架後、不以停用字切開的片段（漢字串、拉丁詞）
    val phrase: String,         // 最長的 run
    val terms: List<String>,    // runs ＋ 漢字 bigram ＋ 拉丁 token；不含單字
)
```

1. 轉小寫（拉丁），以空白、ASCII 標點、全形標點（`，。！？、；：「」『』“”‘’（）()·～…`）切段。**不以停用字切段**。
2. `stripRequestFrame(run)`：只剝**開頭／結尾**的請求語，由長到短：
   - 開頭：`請問`、`請`、`(請)?(介紹|介绍|說明|说明|解釋|解释|簡介|简介|描述|概述|談談|谈谈|講講|讲讲|告訴我|告诉我|什麼是|什么是|何謂|何谓)(一下)?`
   - 結尾：`是誰`、`是谁`、`是什麼`、`是什么`、`是甚麼`、`的介紹`、`嗎`、`吗`、`呢`、`？`
   - 中間的 `介紹`（`自我介紹`）、句中 `是`／`和` 一律不剝。
   - 例：`請介紹植田和男` → `植田和男`；`介紹一下植田和男` → `植田和男`；`植田和男是誰` → `植田和男`；`日本央行總裁植田和男的政策` → 原樣保留（由 §5.2 的子字串查表抓出 `植田和男`）。
3. `terms`：每個 run 本身（長度 ≥2）；漢字 run 長度 ≥3 時加所有重疊 bigram；拉丁 token 長度 ≥2 且非英文停用詞。
4. **不產生單字 token**（mystudy 既有理由：`現`、`田` 這類單字讓無關頁變種子）。單字停用字「是的了在有和」只用來**丟掉恰好等於封閉類問句詞的 bigram**（例如 `什麼`），絕不切斷 run。所以 `植田和男` 的 terms 是 `植田和男、植田、田和、和男`。
5. `runs` 全空 → 0 筆結果，觀察寫 `0 result(s), query had no searchable terms`。

### 5.2 頁名完全相等 `exactNameHits(runs, lookup)`（純函式，llm-wiki `FILENAME_EXACT_BONUS` 的本地版）

```kotlin
data class NameHit(val key: String, val paths: List<String>)
fun exactNameHits(runs: List<String>, lookup: (String) -> List<String>): List<NameHit>
```

- 每個 run 截到 32 字；列出長度 ≥2 的所有子字串，**由長到短**查 `lookup = index::pathsForAlias`（`byAlias` 已含 title、aliases、檔名 stem，再加 §3 的去括號 key）。
- 只留**極大**命中：被更長命中包住的子字串丟掉。
- 只留 `includedAskPath` 的路徑（`wiki/` 內、排除 `index.md`／`log.md`／`schema.md`、排除 `raw/`）。
- 對整句 `請介紹植田和男`（模型沒剝框架時）也有效：子字串 `植田和男` 會命中。

### 5.3 候選池

順序加入 `linkedSetOf`，先放的先載入：
1. `nameHits` 的路徑（**一定在池內**，不受任何上限影響）。
2. `terms` 依「長度 desc、`countAskTerm` asc」排序，每個 term `searchAskTerm`（或含 stem 的新版）取最多 `PER_TERM_POOL = 40` 頁。
3. 長度 ≥3 的 terms 走 `searchFts(…, 200)`。
4. `includedAskPath` 過濾；`tokenHits` = 過濾後總數；載入評分最多 `SEARCH_POOL = 200` 頁（nameHits 永遠在前）。

### 5.4 評分 `scoreSearchHit`（純函式，權重對齊 llm-wiki `score_file`）

| 條件 | 分數 |
| --- | --- |
| 路徑 ∈ nameHits（title / alias / stem / 去括號 title 完全相等） | **+200** |
| title 或檔名 stem 含 `phrase` | +50 |
| 正文每出現一次 `phrase`（最多 10 次） | +20 |
| title 或 stem 每命中一個 term | +5 |
| heading 或正文每命中一個 term（有出現就算一次） | +1 |
| 路徑在 `wiki/concepts/`、`wiki/entities/`、`wiki/sources/` | +2 |
| term 的 `countAskTerm / askNoteCount > 0.20`（高頻詞，例如「介紹」）且非 nameHit key | 該 term 分數 ×0.1 |

- 入選門檻：至少一個 nameHit，或 `phrase` 命中，或一個非高頻 term 在 title/正文命中。只靠高頻詞命中的頁丟掉（對應既有 `genericOnlyOverlapDoesNotCallTheModel` 的精神）。
- 排序：分數 desc → `pathRank`（concepts、entities、sources、queries、其他）→ 路徑。
- 取前 `TOP_K = 5`。

### 5.5 Graph（一跳，重用 `expandOneHop`）

- 以前 3 名為種子跑 `expandOneHop(index.wikiGraph(), …)`，取 `hopScore` 最高、尚未在結果中的最多 `GRAPH_QUOTA = 2` 頁，**接在 5 筆之後**（不擠掉關鍵字結果，跟 llm-wiki 不同）。
- `kind = "graph"`，snippet 寫 `Graph neighbor of <種子 title>`。

### 5.6 Vector

- 本次不做。`vectorHits` 固定 0，log 欄位保留以便日後擴充。不加 embedding、不加 LLM rerank。

### 5.7 回傳與 snippet

```kotlin
data class SearchHit(val path: String, val title: String, val type: String, val snippet: String,
                     val kind: String /* "wiki" | "graph" */, val score: Double, val nameHit: Boolean)
data class SearchOutcome(val hits: List<SearchHit>, val tokenHits: Int, val nameHits: Int,
                         val vectorHits: Int, val graphHits: Int, val mode: String)
```

- snippet：以 nameHit key → `phrase` → 第一個非高頻 term 的第一次出現為中心，前後各 120 字，空白壓縮，最多 `SNIPPET_CHARS = 240`。
- `mode`：`graphHits > 0` 或 `vectorHits > 0` → `hybrid`；否則 `keyword`。

---

## 6. 迴圈、預算、收尾

### 6.1 常數（放 `ChatAgent.kt` companion，寫進 How-To）

| 常數 | 值 | 說明 |
| --- | --- | --- |
| `MAX_STEPS` | 6 | 迴圈內模型呼叫次數 |
| `RETRIEVAL_BUDGET` | 4 | `wiki.search` ＋ `wiki.read_page` 執行次數 |
| `MAX_SEARCHES` | 3 | 其中 search 上限 |
| `TOP_K` / `GRAPH_QUOTA` | 5 / 2 | 每次 search 最多 7 筆 |
| `SNIPPET_CHARS` | 240 | references 區每筆 snippet |
| `READ_PAGE_CHARS` | 4000 | `read_page` 正文上限（從頁首取；超過標 `…(truncated)`） |
| `OBSERVATION_CHARS` | 12000 | 所有觀察合計；超過時把較早的 read 全文縮成 1500 字 |
| `MAX_REFERENCES_IN_PROMPT` | 14 | references 清單上限 |
| `HISTORY_TURNS` / `HISTORY_CHARS` | 3 / 1200 | 歷史問答輪數與每則上限 |
| 總輸入 | `PromptBudget.inputCharBudget(numCtx)` | 超過時依序裁：歷史 → 舊觀察 → 尾端 references；仍超過回 `NotCalled` |
| `PromptBudget.tooSmall(numCtx)` | — | 真就 `NotCalled`，不打模型 |

### 6.2 每一輪

1. 組 system（`chat-agent.md` 的 `system`，填 `{{tools}}` 由檔內文字提供、`{{schema}}` 用既有 `schemaExcerpt`）。
2. 組 user（`user` 區段）：`{{history}}`、`{{references}}`（`reference` 區段逐筆渲染）、`{{observations}}`、`{{step}}`、`{{max_steps}}`、`{{retrievals_left}}`、`{{question}}`。
3. `client.complete(ChatRequest(system, user, json = true))`。Ollama 例外 → `NotCalled`。
4. `parseAgentAction`：
   - **Search**：正規化 query（trim、小寫拉丁、去框架）與先前相同 → 不執行，直接強制收尾（llm-wiki 的重複查詢規則）。否則執行、記 log、加 references（以 path 去重，新頁配下一個編號）、加觀察：
     ```
     wiki.search "植田和男": 7 result(s), 7 new, mode=hybrid, tokenHits=11, nameHits=1, vectorHits=0, graphHits=2 → [1] [2] [3] [4] [5] [6] [7]
     ```
   - **ReadPage**：驗證路徑（§7.2）；成功則加觀察 `wiki.read_page [n] wiki/entities/植田和男.md\n<正文 ≤4000>`，該頁若不在 references 就新增。
   - **Final**：若本回合 references 為空且尚未 search 過 → **不接受**，由程式以使用者原句跑一次 `wiki.search`（記 `auto=true`），繼續迴圈。否則結束。
   - **Invalid**：§4.3。
5. 工具次數達 `RETRIEVAL_BUDGET`、search 達 `MAX_SEARCHES`、重複查詢、連續 2 次 Invalid、或步數達 `MAX_STEPS` → 強制收尾。

### 6.3 強制收尾

- references 為空 → `ChatAgentResult.NoMatch`，**不打模型**。
- 否則用 `final-system` ＋ `final-user` 區段（明說「不能再用工具，只回 `{"action":"final","answer":"..."}`」），`json = true`，1 次。

### 6.4 答案後處理

- 掃描 `[n]`：只保留 1..references.size 的編號；`cited` = 出現過的編號。
- 畫面 references 列：有 cited 就只列 cited；沒有 cited 就列本回合 read 過的頁，再沒有就列前 5 筆。
- 不在答案裡出現的 `[n]`（越界）原樣保留但不連結，並記 log `invalidCitations=[…]`。

### 6.5 Thread 與取消

- 工具執行（所有 `WikiIndex` 呼叫）在 `wiki-index` dispatcher（`withContext(dbDispatcher)`）。
- **模型呼叫不得佔住 `wiki-index` dispatcher**（現行 Lexical Ask 整條在 dbDispatcher 上跑；Chat 一輪可能數十秒，會卡住 Browse 與索引更新）。模型呼叫走 `Dispatchers.IO`。
- `ChatAgent.run` 是 `suspend`，工具以注入的 `suspend (AgentAction) -> ToolResult` 執行；測試用 `runBlocking` ＋ 假 `ChatClient`。
- Stop 按鈕取消 coroutine；在步與步之間檢查 `ensureActive()`。進行中的 HTTP 請求可等它回來再丟棄（`OllamaClient` 目前不可中斷，不必改）。
- 進度事件 `AgentEvent`（`ToolStart(tool, arg)`、`ToolEnd(summary)`、`ReferenceAdded(n, path)`、`Thinking(step)`）用 callback 推給 Controller，畫面即時顯示步驟。

---

## 7. `wiki.read_page` 與安全邊界

### 7.1 行為

- 從**索引**讀（`index.load(path)`），不直接讀檔案系統；回 `title`、`type`、正文前 4000 字。
- 頁面不存在 → 觀察 `wiki.read_page error: not found <path>`，算一次工具次數。

### 7.2 路徑驗證（純函式 `normalizeReadPath`）

- 接受 `wiki/…/*.md`；也接受 references 編號（`"[1]"`、`"1"`）或不帶 `.md` 的路徑、或只給 title（用 `pathsForAlias` 唯一解析）。
- 拒絕：絕對路徑、含 `..`、`raw/` 開頭、`wiki/index.md`、`wiki/log.md`、`wiki/schema.md`、非 `includedAskPath`。拒絕時觀察寫原因，不丟例外。
- Chat Agent **沒有寫入工具**。`File this answer` 仍走既有 `proposeQueryFile` ＋ 寫入對話框，由使用者勾選。

---

## 8. Log

模組名 `ChatAgent`，每行一事件：

```text
MyLog.add("ChatAgent", "step=1 action=tool tool=wiki.search query=植田和男")
MyLog.add("ChatAgent", "wiki.search: 7 result(s), 7 new, mode=hybrid, tokenHits=11, nameHits=1, vectorHits=0, graphHits=2, top=[wiki/entities/植田和男.md, …]")
MyLog.add("ChatAgent", "step=2 action=tool tool=wiki.read_page path=wiki/entities/植田和男.md chars=3120")
MyLog.add("ChatAgent", "final: steps=3 llmCalls=3 searches=1 reads=1 references=7 cited=[1] forced=false elapsedMs=…")
```

| 欄位 | 定義 |
| --- | --- |
| `result(s)` | 本次回傳筆數（≤ TOP_K＋GRAPH_QUOTA） |
| `new` | 本回合 references 新增數（path 去重） |
| `mode` | `hybrid`（graphHits>0 或 vectorHits>0）／`keyword` |
| `tokenHits` | 候選池過濾後路徑數（截斷前），**是頁數不是 token 數** |
| `nameHits` | `exactNameHits` 的不重複路徑數 |
| `vectorHits` | 固定 0 |
| `graphHits` | 本次結果中 `kind=graph` 筆數 |

格式由純函式 `formatSearchLog(outcome, added, top)` 產生，測試直接斷言字串。

---

## 9. Prompt：`ai-prompts/chat-agent.md`

沿用 `PromptFiles` 規則（前言不送、`<!-- -->` 刪除、`{{name}}` 只換骨架一次、每次呼叫現讀）。區段：

| 區段 | 用途 | 佔位 |
| --- | --- | --- |
| `system` | 迴圈 system：角色、工具說明、JSON 協定、引用規則、繁中 | `{{schema}}` |
| `user` | 迴圈 user | `{{history}}` `{{references}}` `{{observations}}` `{{question}}` `{{step}}` `{{max_steps}}` `{{retrievals_left}}` |
| `reference` | 每筆 reference 的格式 | `{{number}}` `{{kind}}` `{{title}}` `{{path}}` `{{snippet}}` |
| `final-system` | 強制收尾 system | `{{schema}}` |
| `final-user` | 強制收尾 user | 同 `user` 但沒有 `{{step}}` 類 |

system 必須講清楚（文字放檔案，不放 Kotlin）：
- 每次只回**一個** JSON 物件，不要其他文字。三種形狀照 §4.1。
- 回答前至少用一次 `wiki.search`。`query` 用**頁名或實體／概念名**（例如「植田和男」），不要整句、不要「請介紹」。
- snippet 不夠就 `wiki.read_page` 讀最相關的那頁再回答。
- 答案只用 references 與觀察裡的文字，繁體中文，一句一個主張，句末 `[n]`，`n` 只能用 references 清單的編號；不寫路徑；沒寫到的回「wiki 裡沒有」。不用訓練記憶，不上網。
- 「規則節錄」是 schema 寫作規範，不是答案來源。

`ai-prompts/README.md` 表格加一列 `chat-agent.md | Wiki Chat Agent`，並寫明：Chat Agent 的 `json = true` 在程式裡；`action/tool/query/path/answer` 與工具名是解析契約。

---

## 10. UI

- `StudyMode` 加 `Chat`；`HomeScreen` 選單順序：`Browse`、`Chat`、`Ask (keyword)`、`Settings`。
- `ChatPane`：
  - 對話串（使用者問題、步驟摘要、答案）；答案用既有 `MarkdownText`、`SelectionContainer`、`VerticalScrollbar`。
  - 每則答案下的步驟可收合：`🔍 wiki.search "植田和男" — 7 result(s)…`、`📄 wiki.read_page …`。
  - references 列 `[n] title — path`，點擊重用 `openAskPage` 開頁面視窗。
  - `Knowledge graph` 按鈕重用 `AnswerGraph`（節點＝cited／read 頁，最多 8；邊用既有 packet 內 wikilink 計算）。
  - `File this answer` 重用 `proposeQueryFile`（sources＝cited 頁）。
  - 按鈕：Send、Stop、New chat（清歷史）。
- 歷史只在記憶體，關 app 即失，不寫 vault、不寫 Preferences。

---

## 11. AGENTS.md 與文件需要的修改（必做，否則契約自相矛盾）

### 11.1 AGENTS.md

1. 「先讀」加：`改 Chat Agent：docs/programming/How-To-do-wiki-Chat.md、docs/programming/chat-agent-design-ref.md`；第 3 項改成「改 Lexical Ask」。
2. 改寫契約 `Ask 只用本地關鍵字…`，拆成兩條：
   - `Lexical Ask（選單 Ask (keyword)）只用本地關鍵字。沒有種子頁就不打模型，也不把 raw/ 送進 prompt。不做 query rewrite、embedding 或 LLM rerank。`
   - `Chat Agent（選單 Chat）由模型在有界 JSON 工具迴圈中決定查詢詞。工具只有唯讀的 wiki.search 與 wiki.read_page，只讀索引中的 wiki/ 頁，不讀 raw/、index.md、log.md、schema.md。每回合最多 6 次迴圈呼叫＋1 次收尾、4 次工具執行。一頁都沒檢索到就不打收尾。不做 embedding、不做 LLM rerank、沒有寫入或網路工具。見 How-To-do-wiki-Chat.md。`
3. Prompt 契約：`ask.md、chat-agent.md、ingest-*.md`；`json = true` 用在 Extract、Draft、Chat Agent，旗標在程式；Chat Agent 的 `action/tool/query/path/answer` 與工具名是解析契約。
4. SQLite 契約補一句：`模型呼叫不要佔住 wiki-index dispatcher（Chat Agent 的工具在該 dispatcher，模型呼叫在 IO）。`
5. 偏好與索引不寫進 vault：補「Chat 歷史只在記憶體」。
6. 測試句改成：`改行為時，同時改對應的 How-To 與測試（Lexical Ask/Ingest：WikiBehaviorTest；Chat Agent：ChatAgentBehaviorTest）。`

### 11.2 其他文件

- 新增 `docs/programming/How-To-do-wiki-Chat.md`：現況說明（呼叫鏈、檔案地圖、協定、切詞、評分表、預算表、log、測試清單），格式比照 `How-To-do-wiki-Ask.md`。
- `How-To-do-wiki-Ask.md`：開頭加一段「這是 Lexical Ask；主要問答改用 Chat，見 How-To-do-wiki-Chat.md」與已知限制（植田和男）。
- `docs/Architecture.md`：`StudyMode` 列表、上方選單、資料流加 `→ Chat：ChatAgent ⇄ WikiTools(WikiSearch / read_page) → ai-prompts/chat-agent.md → OllamaClient`、`wiki/` 子套件加 `agent`、`json` 段落。
- `ai-prompts/README.md`：§9。
- 本文件放 `docs/programming/chat-agent-design-ref.md`；`llm-wiki-chat-gap.md` 與（可選）`llm-wiki-chat-mechanism.md` 放同目錄。

---

## 12. 測試清單（`ChatAgentBehaviorTest.kt`，沿用 `WikiBehaviorTest` 的暫存 vault 寫法）

測試 vault：`wiki/entities/植田和男.md`（title `植田和男`，正文含「日本銀行總裁」並 `[[日本銀行]]`）、`wiki/entities/日本銀行.md`、`wiki/sources/boj-2023.md`（連到兩者）、45 頁 `wiki/concepts/cNN.md` 正文含「介紹」、`wiki/schema.md`、`raw/sources/x.md`。

| # | 測試 | 斷言 |
| --- | --- | --- |
| 1 | `searchTokenizerKeepsNamesWithStopChars` | `請介紹植田和男` → runs=[植田和男]，terms 無單字；`自我介紹的方法` 不剝中間；`什麼是卡片盒筆記法` → 卡片盒筆記法；`植田和男是誰`、`請問植田和男是誰？`、`介紹一下植田和男` → 植田和男 |
| 2 | `exactNameHitsFindsLongestSubstring` | `日本央行總裁植田和男的政策` → NameHit(植田和男)；被包住的短命中丟掉；raw/ 路徑濾掉 |
| 3 | `searchRanksEntityFirstDespiteFrequentFrameWord` | query `植田和男` 與 `請介紹植田和男`：hits[0]=植田和男.md、nameHits=1、mode=hybrid、graphHits≥1（日本銀行）、介紹頁不在前 5 |
| 4 | `searchMatchesParenthesizedTitleAndStemOnly` | title `植田和男 (Kazuo Ueda)`；title `Kazuo Ueda` 但檔名 `植田和男.md` |
| 5 | `parseAgentActionToleratesFencesAndMissingAction` | 圍欄、前後雜字、缺 action、別名工具名、空 answer → Invalid |
| 6 | `agentSearchesReadsAndAnswersUeda` | 假 ChatClient 依序回 search(植田和男) → read_page → final「…[1]」：結果 Answer、references[0]=植田和男.md、cited=[1]、模型呼叫 3 次、每次 `json=true`、第 3 次 user 含該頁正文、不含 raw/ 內容與 schema 前言 |
| 7 | `agentRecoversWhenModelUsesWholeSentence` | 模型 query=`請介紹植田和男` → 仍第 1 名 |
| 8 | `agentRejectsFinalBeforeAnyRetrieval` | 第 1 輪就 final → 程式自動 search，第 2 輪才接受 final |
| 9 | `agentForcesFinalOnDuplicateQueryAndBudget` | 重複 query → 下一次請求的 system 是 `final-system`；永遠回 search 的模型 → 總呼叫 ≤7、工具 ≤4 |
| 10 | `readPageRejectsUnsafePaths` | `raw/sources/x.md`、`../x.md`、`/etc/passwd`、`wiki/schema.md`、不存在頁 → 觀察含錯誤、不丟例外；`[1]` 與 title 可解析 |
| 11 | `agentReturnsNoMatchWithoutFinalCall` | 問 vault 沒有的名字：模型搜兩次都 0 筆 → NoMatch，沒有 `final-system` 呼叫 |
| 12 | `agentNotCalledOnMissingPromptOrTinyContext` | `chat-agent.md` 缺區段 / `numCtx=4096` → NotCalled，0 次呼叫 |
| 13 | `searchLogLineFormat` | `formatSearchLog` 輸出與 §8 完全一致 |
| 14 | `invalidJsonTwiceForcesFinal` | 連 2 次非 JSON → 強制收尾 |
| — | 既有 `WikiBehaviorTest` | 全綠，Lexical Ask 行為不變 |

---

## 13. 不做（out of scope）

- Rust、Tauri、React、llm-wiki 原始碼；`agent-event` 串流格式；`.llm-wiki/chats/*.json` 持久化。
- embedding／LanceDB／RRF、LLM rerank、web search、`source.search`（raw）、`graph.search` 工具、skills、deep/fast 模式切換。
- 逐 token 串流答案（Ollama 仍 `stream:false`）。
- Android、iOS、Web target；把 prompt 字串寫回 Kotlin；把索引或對話寫進 vault。
- 修改 Lexical Ask 的切詞與評分（只允許 §3 的 `byAlias` 去括號 key，且需既有測試全綠）。
