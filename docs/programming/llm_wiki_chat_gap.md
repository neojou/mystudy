# mystudy Ask vs llm-wiki Chat：落差分析（以「請介紹植田和男」為例）

> 這份落差分析已被 Chat Agent 取代。現況是 `ChatAgent`、`docs/programming/How-To-do-wiki-Chat.md` 與 `ChatAgentBehaviorTest`。
> 本文保留當歷史紀錄。不要依 §4 去改 Lexical Ask 的 `analyzeQuery` 或評分。



> 對象：`neojou/mystudy` `main` @ `d030dc1`（2026-10-11 18:49 +08:00）；`nashsu/llm_wiki` `main` @ `48fd970`。
> 以原始碼為準。識別字保留英文。llm-wiki Chat 完整解析見 `llm-wiki-chat-mechanism.md`（即 `/workspace/llm_wiki_chat.md`，可選擇一併放進 repo）。
> 本文未在 Neo 的真實 vault 上執行；切詞與候選池的推論以 Python 逐行重寫 `analyzeQuery` / `contentAnchors` / `lexicalPool` 驗證（見 §3.1）。

---

## 0. 先說結論

1. **根因（確定）**：`analyzeQuery` 把單字停用字 `和` 當成**切點**。`請介紹植田和男` → `請介紹植田` ＋ `男`（長度 1 被丟）。**完整人名 `植田和男` 從此不出現在任何 query term**：`phrase`、`pieces`、`bigrams`、`ftsTerms`、alias 查詢鍵都沒有它。所以 `pathsForAlias("植田和男")`（檔名 stem／title 完全相等）永遠不會被查。
2. **放大器（依 vault 內容而定）**：`請`、`介紹` 不在問句框架清單，`介紹` 變成與 `植田` 並列的 anchor；`lexicalPool` 依 anchor 出現順序（`介紹` 在前）塞路徑，再 `take(40)`。只要 vault 裡有 ≥40 頁正文含「介紹」，`wiki/entities/植田和男.md` 就被擠出評分池 → 不可能當種子。即使沒被擠出，`介紹` 頁也會佔種子名額與 6000 字 packet，模型容易回「沒有」。
3. **llm-wiki 為何成功**：不是它的 tokenizer 比較聰明，而是 **LLM 在工具迴圈中把查詢改寫成 `植田和男`**，接著 `score_file` 的 **檔名完全相等 +200** 讓實體頁穩居第 1。llm-wiki 若用整句當 query（非迴圈路徑），+200 一樣拿不到，只靠 bigram 撐。
4. **建議**：Phase 1 不必引入 LLM，也不違反 AGENTS.md「Ask 只用本地關鍵字」契約——在**未切斷的原始漢字串**上做「最長子字串 × alias/title/stem 完全相等」查表（等於本地版的 FILENAME_EXACT_BONUS），再修框架詞與 anchor 排序。LLM 查詢改寫、向量、工具迴圈列為之後可選階段，且需先改 AGENTS.md 契約。

---

## 1. mystudy Ask 目前流程

入口：`HomeScreen` 選單 `Wiki → Ask` → `StudyMode.Ask` → `StudyScreens.kt::AskPane` → `StudyController.ask()`（`wiki-index` dispatcher）→ `AskPipeline(index, client()).ask(question, numCtx)`。

> 注意：專案是 KMP Gradle 結構，但 `docs/Architecture.md` 規定**只建 desktop JVM**；整個 `wiki/`（含 Ask）在 `desktopMain`，用 JDBC SQLite 與 JVM 檔案 API。下文「KMP」指這個結構，不是要加新 target。

| 步驟 | 函式（檔案） | 行為 |
| --- | --- | --- |
| 0 | `PromptBudget.tooSmall` | `num_ctx <= 4096` → `NotCalled` |
| 1 切詞 | `analyzeQuery`（`markdown/MarkdownDocs.kt`） | 抓每段連續漢字 → `stripHanStops`：先把 `hanStops`（什麼、何謂、如何、請問、與、對、從…）**任意位置**換成空白，再把 `hanStopChars`（是的了在有**和**）**任意位置**換成空白 → 依空白切，留長度 ≥2。產出 `phrase`（最長片段）、`pieces`、`bigrams`、`extras`（拉丁 ≥3）、`ftsTerms`（≥3 字窗＋整段 3–12 字） |
| 2 anchor | `contentAnchors`（`ask/LexicalScore.kt`） | 只看**最長**的 piece；由長到短列子字串，`countAskTerm > 0`（LIKE 標題/heading/正文，不含路徑）就收，已被更長命中包住的丟掉 |
| 3 候選池 | `lexicalPool` | `linkedSetOf`：每個 anchor `searchAskTerm(anchor, 40)` → `pathsForAlias(phrase/bigram/extra)` → `searchFts(ftsTerms, 500)`；`includedAskPath` 過濾；`tokenHits = 全部數`；**只載入前 40 頁** |
| 4 評分 | `scoreNote` | phrase in title +50；anchor in title +50；anchor in body +8；bigram/token in title +10、in body +1（df>20% 且非 anchor ×0.1）；concepts/entities/sources +2。`canSeed` = anchor 有出現 |
| 5 種子 | `AskPipeline.ask` | 排序後 `canSeed` 取前 5；0 個 → `NoMatch`，不打模型 |
| 6 一跳 | `expandOneHop`（`ask/LinkGraph.kt`） | 直接相鄰 3、共同 sources 4、Adamic–Adar ×1.5、同 type 1；接在種子後 |
| 7 packet | `excerptFor` / `limitPacket` | 每頁在第一個 anchor/phrase 命中處附近截 1500 字；最多 8 頁、合計 6000 字，超過從尾巴丟（先丟圖鄰居） |
| 8 prompt | `ai-prompts/ask.md` + `renderPacket` | system：只答 packet、繁中、`[n]` 引用、沒寫就答「沒有」；user：問題＋schema 節錄 800 字＋編號頁 |
| 9 生成 | `OllamaClient.complete(json=false)` | 單次 `/api/chat`，非串流 |
| 結果 | `AskResult.NoMatch / NotCalled / Answer` | 畫面顯示 `tokenHits=… graphHits=…`；**沒有 MyLog 檢索 log** |

特色：**單發（one-shot）、確定性、無 LLM 查詢改寫、無 embedding**——AGENTS.md 明文契約。

---

## 2. llm-wiki Chat 流程（摘要）

詳見 `llm-wiki-chat-mechanism.md`。重點概念：

| 概念 | llm-wiki 實作 | 對植田和男的作用 |
| --- | --- | --- |
| 查詢理解 | **LLM 工具迴圈** `run_agent_loop`：模型回 `{"action":"tool","tool":"wiki.search","query":"植田和男"}` | 問句框架「請介紹」由 LLM 去掉，這是真正的「關鍵字抽取」 |
| 切詞 | `tokenize_query`：依空白/標點切；CJK 長 token 展開成 bigram＋單字＋**整串**；停用字只濾**單字 token**，不切斷詞 | `植田和男` → 植田、田和、和男、植、田、男、植田和男（`和` 只在單字層被濾掉，整串保留） |
| 排名 | `score_file`：**檔名 stem 完全相等 +200**、標題(含檔名)含片語 +50、正文片語每次 +20（≤10 次）、標題 token ×5、正文 token ×1 | `wiki/entities/植田和男.md` ≈ 492 分，穩居第 1 |
| 向量 | 選用 LanceDB chunk 向量，RRF `1/(60+rank)` 融合 | 未設定時 `vectorHits=0` |
| 圖 | `blend_graph_results`：前 N 名為種子，一跳鄰居依 `1/(rank+1)` 累加，保留 15–30% 名額 | `graphHits=2`（日本銀行等） |
| 讀全文 | `wiki.read_page`（≤4000 字） | snippet 不夠時 LLM 自己去讀實體頁 |
| 收斂 | `retrieval_budget`（standard=4）、重複查詢強制收尾、`build_agent_final_system` | 2–3 次 LLM 呼叫完成 |
| log | `"{count} result(s), {added} new, mode={}, tokenHits={}, vectorHits={}, graphHits={}"` | `5 result(s), 5 new, mode=hybrid, tokenHits=11, vectorHits=0, graphHits=2` |

教訓：**llm-wiki 成功的關鍵是「查詢詞 = 頁名」＋「頁名完全相等大加分」**。LLM 只是取得頁名的一種手段。

---

## 3. 為何 mystudy 找不到植田和男（以程式碼推導）

### 3.1 實測切詞（Python 逐行重寫 `analyzeQuery`）

| 問句 | `pieces` | `phrase` | `bigrams` | 完整人名有出現？ |
| --- | --- | --- | --- | --- |
| 請介紹植田和男 | `請介紹植田` | `請介紹植田` | 請介、介紹、紹植、植田 | **否** |
| 介紹一下植田和男 | `介紹一下植田` | 同左 | 介紹、紹一、一下、下植、植田 | 否 |
| 植田和男是誰 | `植田` | `植田` | 植田 | 否 |
| 植田和男 | `植田` | `植田` | 植田 | 否（`ftsTerms` 為空） |
| 日本央行總裁植田和男的政策 | `日本央行總裁植田`、`政策` | `日本央行總裁植田` | …、植田、政策 | 否 |

（對照：`請介紹黑田東彥` 不含停用字，整串保留——同類問題只有名字裡剛好有 是/的/了/在/有/和 才壞。）

### 3.2 假說與程式依據

| # | 假說 | 程式依據 | 嚴重度 |
| --- | --- | --- | --- |
| H1 | `和` 切斷人名，`植田和男` 永不成為 term | `stripHanStops` 對 `hanStopChars` 做**全字串**替換；`analyzeQuery` 之後只看切開的片段 | **根因**，必修 |
| H2 | 檔名/title 完全相等的捷徑查不到 | `insertAliases` 已把 title、aliases、**檔名 stem** 寫進 `aliases`／`byAlias`；但 `lexicalPool` 只用 `phrase`、`bigrams`、`extras` 查 `pathsForAlias`，`請介紹植田`／`植田` 都不等於 `植田和男` | 高。mystudy **已經有** llm-wiki +200 的資料，只是沒用上 |
| H3 | `請`、`介紹` 不是框架詞 → `介紹` 成 anchor | `hanStops` 只有 `請問`；`maximalHits("請介紹植田")` 先收 `介紹`（位置較前）再收 `植田` | 高 |
| H4 | 高頻 anchor 塞滿 40 頁池 | `lexicalPool` 依 anchor 順序加入，`searchAskTerm` 正文命中依路徑排序（`wiki/concepts/…` < `wiki/entities/…`），再 `askPaths.take(40)`。模擬：60 頁含「介紹」→ 池 42、實體頁不在前 40 → **NoMatch 或答非所問** | 依 vault，高機率 |
| H5 | anchor 不受頻率懲罰 | `scoreNote` 的 df>20% ×0.1 只對「非 anchor」token；`介紹` 是 anchor → +8 正文、標題含介紹 +50，與實體頁搶 5 個種子 | 中 |
| H6 | excerpt 對錯位置 | `excerptFor` 取 anchors＋phrase 中**最早出現**的位置；長頁可能以「介紹」為中心而非人名 | 低–中 |
| H7 | 頁名只在路徑 | `countAskTerm`／`searchAskTerm` 不比對 `path`；若頁面 title 是英文（Kazuo Ueda）而中文名只在檔名，LIKE 找不到 | 低（llm-wiki 的 `title_text` 會含檔名） |
| H8 | 標題帶括號 | `normalizeKey` 只 trim+lowercase；`植田和男 (Kazuo Ueda)` 不等於 `植田和男`（文件已記載 `湧現 (Emergence)` 同樣問題） | 低–中 |
| — | 非原因 | topK：mystudy 種子 5＋鄰居到 8 頁，不比 llm-wiki 的 5 少；language：prompt 已要求繁中；路徑過濾：`wiki/entities/` 屬 Ask 頁且 +2；graph：已有一跳 | — |

### 3.3 一句話

llm-wiki 的查詢詞是「植田和男」並且命中檔名；mystudy 的查詢詞是「請介紹植田」與「植田」，而且被「介紹」稀釋。

---

## 4. 建議移植到 mystudy（不搬 Rust/Tauri）

### 4.1 可移植 vs 不需移植

| llm-wiki 行為 | 移植方式 | 階段 |
| --- | --- | --- |
| 停用字只濾單字、保留整串 | 改 `analyzeQuery`：新增 `runs`（框架剝除後、**未以停用字切開**的漢字串），既有欄位語意不變 | P1 |
| LLM 抽出實體名 | **本地確定性替代**：`runs` 的子字串由長到短查 `byAlias`（title/alias/stem 完全相等）= `nameHits` | P1 |
| FILENAME_EXACT_BONUS +200 | `scoreNote` 新增 `exactName` 參數：命中 +200、必可當種子、排序最前 | P1 |
| 標題 text 含檔名 | `searchAskTerm`/`countAskTerm` 加 `OR path LIKE ?`（或 stem 欄） | P1 |
| 問句框架剝除 | 擴充框架詞；只剝**片段開頭/結尾**的請求語 | P1 |
| 檢索 log | `MyLog.add("Ask", "...result(s), mode=…, tokenHits=…, nameHits=…, vectorHits=0, graphHits=…")` | P1 |
| 標題括號別名 | `reloadMaps()` 在記憶體多放去掉 `(…)`／`（…）` 的 title key | P1b |
| 圖鄰居 | 已有 `expandOneHop`；保持「鄰居接在種子後、超額先丟」，不需 llm-wiki 的配額擠掉關鍵字結果 | 已具備 |
| LLM 查詢改寫（`wiki.search` query） | 新 prompt `ai-prompts/ask-query.md`（`json=true`，回 `{"queries":[...]}`），**只在 P1 NoMatch 時**當 fallback，Settings 開關 | P2（需改契約） |
| 向量 | Ollama `/api/embed`（loopback）→ 存在 vault 外的 SQLite；RRF 融合 | P3（需改契約） |
| 多輪工具迴圈（search→read_page→final） | 有界迴圈 `AskAgent`，budget 2–3 | P4（多半不需要） |
| 多輪 Chat 歷史 | `AskSession` 保存最近 N 輪 | 另案 |

### 4.2 具體模組/API 變更（P1）

全部在 `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/`。新邏輯盡量寫成**純函式**（不碰 JDBC），以便 `WikiBehaviorTest` 直接測，也保留日後移到 `commonMain` 的可能。

1. `markdown/MarkdownDocs.kt`
   - `QueryAnalysis` 加 `runs: List<String>`：每段漢字（以及拉丁詞）在**框架剝除**後、**不以 `hanStopChars` 切開**的原樣。
   - 新 `stripRequestFrame(run)`：只剝開頭 `請(問)?`、`(請)?(介紹|介绍|說明|说明|解釋|解释|簡介|简介|描述|概述|談談|谈谈|講講|讲讲|告訴我|告诉我)(一下)?`，結尾 `(是誰|是谁|是什麼|是什么|是甚麼|嗎|吗|呢|的介紹)`。既有 `hanStops` 行為保留以免動到現有測試。
   - 既有 `phrase/pieces/bigrams/ftsTerms` 可同時改成以剝除後的 run 計算，但 `queryAnalysisEmitsCjkBigramsNotSingleCharacters` 的期望值要守住（`什麼是卡片盒筆記法` → `卡片盒筆記法`）。
2. `ask/LexicalScore.kt`
   - 新 `fun exactNameHits(runs: List<String>, lookup: (String) -> List<String>): List<NameHit>`：每個 run 長度上限 32；子字串由長到短（≥2）查 `lookup`（= `index.pathsForAlias`），保留**極大**命中（被更長命中包住者丟棄）。`NameHit(key, paths)`。
   - `lexicalPool(index, analysis, anchors, nameHits)`：**nameHits 路徑先放**；anchors 依「長度 desc、df asc」排序再查；每個 anchor 配額 `40 / anchors.size`（至少 10），不讓單一高頻 anchor 吃滿。
   - `contentAnchors` 也在 `runs` 上跑（取代或聯集 `pieces`），使 `植田和男` 成為 anchor；當存在 nameHit 時，df 比率 >0.20 的其他 anchor 降為一般 token（不可 seed）。
   - `scoreNote(..., exactNames: Set<String>)`：路徑 ∈ nameHits → +200（`EXACT_NAME_BONUS`）並 `canSeed = true`。
3. `index/WikiIndex.kt`
   - `searchAskTerm` / `countAskTerm`：WHERE 與 ORDER BY 加 path 比對（排序：exact stem → title → heading → body）。
   - 標題括號別名：title 去括號後若不同，也要能被 `pathsForAlias` 查到。（注意：`refreshFile` 在 content hash 未變時直接 `return`，**不會重寫 aliases**。所以要用目前沒在用的 `meta` 表記 `index_version`，版本不符時清空重建；或在 `reloadMaps()` 建 `byAlias` 時，於**記憶體**多放一個去括號的 key（每次開索引都重算，不動 SQLite schema）。P1 建議後者。）
4. `ask/AskPipeline.kt`
   - 串接 `exactNameHits`；`excerptFor` 以 **nameHit key → 最長 anchor → phrase** 的優先序找中心點。
   - `AskResult.NoMatch/Answer` 加 `nameHits: Int`（可選 `mode: String`）；畫面列 `tokenHits / nameHits / graphHits`。
   - 檢索完成後 `MyLog.add("Ask", ...)`（見 design ref 的格式）。
5. `WikiBehaviorTest.kt` 新測試（見 design ref §6）；`docs/programming/How-To-do-wiki-Ask.md` 同步更新。

### 4.3 為何 P1 選確定性而不是 LLM 改寫

- 根因是**切詞把名字切壞**，不是「沒有理解能力」。名字已存在於 `byAlias`，查表就能命中，零額外延遲、可測、可重現。
- llm-wiki 的教訓本身也是：LLM 給的 query 若不等於頁名（例如整句），+200 就失效。真正的槓桿在「頁名完全相等」。
- 本機 Ollama（預設 `gemma4-64k`）多一次往返要數秒到十幾秒，且 JSON 輸出偶爾不穩；Ask 目前一次呼叫，P1 維持一次。
- LLM 改寫的獨有價值是**同義/跨語**（`Ueda` → `植田和男`、`日銀總裁` → `植田和男`）。這留給 P2，作為 NoMatch fallback，並需要 Neo 同意修改 AGENTS.md 契約。

---

## 5. Out of scope / 不要照搬

- Tauri commands、`agent-event` 事件串流、React 前端、`chat-store` 持久化格式（`.llm-wiki/chats/*.json`）。
- LanceDB、雲端 embedding 供應商、Volcengine/Google 端點；Ollama 只能 loopback（AGENTS.md）。
- `router.rs` 的簡體/英文字串路由（只是 hint，無效益）。
- llm-wiki 的**單字 token**（`田`、`男`）：mystudy 刻意不放單字，理由正確（文件：`現` 會讓無關頁變種子），保持。
- graph 配額擠掉關鍵字第 4、5 名的作法（llm-wiki 已知副作用）；mystudy「鄰居接在後面、超額先丟」較好。
- `includeContent`、`deep`/`local_first` 模式、Deep Research、web/anytxt 工具、skills、`workspace.write_file`。
- References 顯示「檢索到」而非「被引用」：mystudy 用 `[n]` 編號對回 packet，比 llm-wiki 精準，保留。
- 不新增 Android/iOS/Web target；不把 prompt 字串搬回 Kotlin；不把索引寫進 vault；不送 `raw/` 進 Ask prompt。
