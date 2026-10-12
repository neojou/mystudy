# 程式如何做 Wiki Chat

Chat 是 wiki 的主要問答入口。本機模型在有界 JSON 工具迴圈裡自己決定查詢詞，再呼叫唯讀的 `wiki.search` 與 `wiki.read_page`。這對齊 llm-wiki Chat：查詢理解在 agent，不在 Lexical Ask 的切詞。

Lexical Ask 仍在，選單名稱是 Ask (keyword)，行為不變。見 `docs/programming/How-To-do-wiki-Ask.md`。

這份說明對應 Desktop 實作。入口在畫面，核心在 `ChatAgent.run`。

## 呼叫鏈

Top Menu 的 `Wiki → Chat` 把畫面切到 `StudyMode.Chat`。Ask (keyword) 排在它後面。

- 選單：`composeApp/src/commonMain/kotlin/com/neojou/mystudy/HomeScreen.kt`
- 模式：`composeApp/src/commonMain/kotlin/com/neojou/mystudy/study/StudyMode.kt`
- 畫面：`composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/ChatScreens.kt` 的 `ChatPane`
- 狀態與呼叫：`composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/StudyController.kt` 的 `chat()`

`chat()` 在主 coroutine 上跑 `ChatAgent.run`。工具裡所有 `WikiIndex` 呼叫用 `withContext(dbDispatcher)`。模型呼叫在 `ChatAgent` 裡用 `Dispatchers.IO`，不占 `wiki-index`。Stop 取消這個 job。進行中的 HTTP 會跑完再丟棄。

索引未開啟時畫面顯示 `Index is not open.`，不會打 Ollama。

## 檔案地圖

| 職責 | 路徑 |
| --- | --- |
| JSON 動作 | `wiki/agent/AgentProtocol.kt` 的 `parseAgentAction` |
| 工具 query 的切段、頁名相等、評分 | `wiki/agent/WikiSearch.kt` |
| `wiki.search` / `wiki.read_page` | `wiki/agent/WikiTools.kt` |
| 迴圈、預算、收尾 | `wiki/agent/ChatAgent.kt` |
| 最近 3 輪，只在記憶體 | `wiki/agent/ChatSession.kt` |
| 去括號頁名，只給 Chat | `WikiIndex.pathsForLooseAlias` |
| Prompt | `ai-prompts/chat-agent.md` |
| 行為測試 | `composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/ChatAgentBehaviorTest.kt` |

`pathsForAlias` 仍是完全相等，Lexical Ask 不讀 `byLooseAlias`。

## 協定

每一輪模型只回一個 JSON 物件。`json = true` 在程式裡。欄位名 `action`、`tool`、`query`、`path`、`answer`，以及工具名 `wiki.search`、`wiki.read_page`，是解析契約。

```json
{"action":"tool","tool":"wiki.search","query":"植田和男"}
{"action":"tool","tool":"wiki.read_page","path":"wiki/entities/植田和男.md"}
{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}
```

`parseAgentAction` 去掉 fence，取第一個平衡的 `{…}`。缺 `action` 時，有 `answer` 當 final，有 `tool` 當工具。工具別名接受 `search` 與 `read`。空白 answer 是無效。

無效動作記一則觀察，算迴圈步數，不算工具次數。連續兩次無效就強制收尾。收尾仍不是可解析的 final：原文不像 JSON 就把它當答案，否則 `NotCalled`。

## wiki.search

Agent 決定 query。工具不再用 Lexical Ask 的 `analyzeQuery`（那會把「和」切掉）。這裡只為這次 query 做三件事：

1. 以空白與標點切開，不以「是的了在有和」切開。只剝開頭或結尾的請求語，所以 `請介紹植田和男` 變成 run `植田和男`。句中的「介紹」「和」不剝。不產生單字 token。
2. `exactNameHits`：run 的子字串由長到短，向 `pathsForLooseAlias` 查完全相等（title、alias、檔名 stem、去括號 title）。被更長命中包住的短詞丟掉。`raw/`、`index.md`、`log.md`、`schema.md`、`review.md` 不要。nameHit 頁一定進候選池，分數 +200。
3. 其餘 term 用既有 `searchAskTerm` 與長度 ≥ 3 的 FTS。高頻詞（`countAskTerm / askNoteCount > 0.20`，且不是 nameHit key）那個 term 的分數 ×0.1。只命中高頻詞的頁丟掉。

排序是分數、`pathRank`、路徑。前 5 筆是關鍵字結果。再以前 3 名走既有 `expandOneHop`，最多 2 個鄰居接在後面，不插進前 5。圖鄰居的 snippet 是 `Graph neighbor of {標題}. ` 再接該頁正文節錄。`vectorHits` 固定 0。有圖鄰居時 `mode=hybrid`。

因此「請介紹植田和男」即使被整句送進 `wiki.search`，實體頁仍是第 1 名。45 頁正文裡的「介紹」在剝掉請求語之後不是 term，進不了前 5。

檔名 stem 只看路徑最後一段去掉 `.md`。資料夾名不會變成 key。

| 條件 | 分數 |
| --- | --- |
| 路徑是 nameHit | +200 |
| title 或 stem 含 phrase | +50 |
| 正文每出現一次 phrase，最多 10 次 | +20 |
| title 或 stem 每命中一個 term | +5 |
| heading 或正文每命中一個 term | +1 |
| `wiki/concepts/`、`wiki/entities/`、`wiki/sources/` | +2 |
| 高頻且不是 nameHit key 的 term | 該 term ×0.1 |

## wiki.read_page

從索引 `load`，不直接讀檔。正文從頁首取最多 4000 字，後面加一行 `連結：`（鄰居標題與 frontmatter `sources`）。接受 `wiki/…​.md`、引用編號 `[1]`、唯一的 title。拒絕絕對路徑、`..`、`raw/`、`wiki/index.md`、`wiki/log.md`、`wiki/schema.md`、`wiki/review.md`。拒絕寫在觀察裡，不丟例外。

沒有寫入工具。File this answer 仍走 `proposeQueryFile` 與既有寫入對話框。存檔來源是答案裡的 `[n]`；沒有編號時改用這一回合讀過的頁。不會把沒引用也沒讀的搜尋命中寫進去。

## 預算

| 常數 | 值 |
| --- | --- |
| `MAX_STEPS` | 6 次迴圈內的模型呼叫 |
| 強制收尾 | 另外最多 1 次 |
| `RETRIEVAL_BUDGET` | 4 次工具 |
| `MAX_SEARCHES` | 其中 search 最多 3 次 |
| 每次 search | 關鍵字 5 筆，圖鄰居最多 2 筆 |
| `READ_PAGE_CHARS` | 4000 |
| `OBSERVATION_CHARS` | 12000，較早的讀頁先縮到 1500 |
| `HISTORY_TURNS` / `HISTORY_CHARS` | 3 輪，每則 1200 字 |
| 總輸入 | `PromptBudget.inputCharBudget` |

`num_ctx <= 4096` 為 `NotCalled`，不打模型。超過字元預算時依序裁歷史、舊觀察、尾端 references；仍超過則 `NotCalled`。

重複 query（trim、拉丁小寫、再剝請求語之後相同）不執行，直接收尾。第一輪就回 final 且還沒 search 過，程式用使用者原句自動 search 一次再繼續。一頁都沒有就 `NoMatch`，不打收尾。

若已經 search 過，模型卻在工具還有餘額時就 `final`，而且 references 裡還有未讀的 `wiki/sources/`，程式拒絕這一次。觀察與步驟寫 `仍有未讀來源：[n] path。先讀再回答，不要只留一句。` 這不算工具次數。模型再回 `final` 就接受。強制收尾不走這段。

## Prompt

`ai-prompts/chat-agent.md` 的區段是 `system`、`user`、`reference`、`final-system`、`final-user`。每次呼叫現讀。空的歷史、references、觀察、連結填 `(none)`。`system` 與 `final-system` 只有 `{{schema}}`。`reference` 還有 `{{links}}`：圖上鄰居標題最多 8 個，再加 frontmatter `sources`。工具說明寫在檔案正文，沒有 `{{tools}}`。`final-user` 沒有 `{{schema}}`，也沒有 `{{step}}`。

答案要留下已讀頁裡與問題有關的段落，可以多段，每一段句末 `[n]`。不要只留第一句。介紹題若還有未讀來源頁，先 `wiki.read_page`。

## Log

模組名 `ChatAgent`。search 那一行由 `formatSearchLog` 產生：

```text
wiki.search: N result(s), M new, mode=hybrid, tokenHits=…, nameHits=…, vectorHits=0, graphHits=…, top=[…]
```

自動補的那次 search 在同一行後面加 `auto=true`。收尾另有一行 `final:`。

## 畫面

對話串顯示問題、可收合的步驟、用 `MarkdownText` 畫的答案。References 列出這次回合檢索到的全部頁，`[n] title — path` 點開頁面，不只顯示答案裡出現的編號。Knowledge graph 用同一份清單，最多 8 頁，重用 `AnswerGraph`。歷史只在記憶體，不寫 vault，不寫 Preferences。

## 測試

`ChatAgentBehaviorTest` 覆蓋切詞、頁名相等、植田和男排第 1、括號 title 與檔名 stem、JSON 解析、search → read → 未讀來源拒絕一次 → final、整句 query、過早的 final、重複 query 與預算、工具預算用完時直接收 final、圖 snippet 與連結、不安全路徑、沒有命中時不打收尾、缺 prompt 與過小的 `num_ctx`、log 格式、連續兩次無效 JSON。答案只引用 `[1]` 時，References 仍列出其餘檢索頁。

`WikiBehaviorTest` 仍鎖 Lexical Ask，不改期望。
