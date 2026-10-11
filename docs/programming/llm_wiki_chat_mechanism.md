# nashsu/llm_wiki：Chat 機制解析（以「請介紹植田和男」為例）

> 分析對象：`/workspace/llm_wiki`（nashsu/llm_wiki，`main`，commit `48fd970`）。
> 本文以實際原始碼為準；README 與程式碼不一致之處集中寫在第 7 節。
> 識別字（函式、欄位、工具名稱）保留 repo 原文。

---

## 1. 總覽

目前的 Chat **不是**前端 TypeScript 的 RAG 流程，而是走 **Rust 後端 Agent**（`src-tauri/src/agent/`）。前端 `chat-panel.tsx` 只負責送出請求、監聽 `agent-event` 事件，以及顯示工具進度、參考資料和最後的回答。

核心觀念：

1. **沒有 NER，也沒有獨立的「關鍵字抽取」模組。** 當設定的是可直接以 HTTP 呼叫的 LLM（OpenAI / Anthropic / Google / Azure / MiniMax / Ollama / custom）時，後端會跑一個 **JSON 工具迴圈**（`run_agent_loop`）。由 **LLM 自行決定**要呼叫 `wiki.search`，以及 `query` 要填什麼（例如 `"植田和男"`）。
2. `wiki.search` 會呼叫 `commands/search.rs::search_project_inner`，做的是 **三路混合檢索**：
   - **token**：CJK bigram 與單字 tokenizer，加上檔名、標題、片語加分
   - **vector**：選用，LanceDB chunk 向量，以 RRF 融合
   - **graph**：用 `[[wikilink]]` 與 frontmatter `related:` 做一跳鄰居擴展，保留 15–30% 名額
3. 檢索結果變成 `AgentReference`，包含 title、path、snippet、graph/backlink 資訊。每一輪迴圈都會重新組進 prompt 的「Retrieved project context」。LLM 可以再呼叫 `wiki.read_page` 讀 `wiki/entities/植田和男.md` 全文（最多 4,000 字元），最後回傳 `{"action":"final","answer":"..."}`。
4. 你看到的 `5 result(s), 5 new, mode=hybrid, tokenHits=11, vectorHits=0, graphHits=2` 是 **工具迴圈路徑** 中 `record_loop_tool_success` 產生的字串（`runtime.rs:2259`）。

---

## 2. 端到端流程（範例：「請介紹植田和男」）

假設：Standard 模式、retrieval mode = standard、LLM 為 HTTP provider、未啟用 embedding。

1. **UI 送出**
   `chat-input.tsx` 呼叫 `onSend` → `chat-panel.tsx::handleSend(text)`（約 L720），流程如下：
   - 先以 `addMessageToConversation` 寫入使用者訊息。
   - 判斷 `useBackendAgent = provider !== "claude-code" && provider !== "codex-cli"`。
   - 用 `listen("agent-event")` 監聽事件。
   - 呼叫 `invoke("agent_start_turn_stream", { request })`。

   request 的關鍵欄位：
   - `message: "請介紹植田和男"`
   - `mode: "standard"`
   - `retrievalMode`
   - `tools: { wiki: true, web, anytxt }`
   - **`topK: 5`**（deep 為 8）
   - `includeContent: false`（deep 為 true）
   - `history`：最近 `maxHistoryMessages` 則
   - `skills`、`contextFiles`、`images`

2. **Tauri command**
   `src-tauri/src/lib.rs::agent_start_turn_stream`（L153）的處理：
   - 從 app state 讀取 `embeddingConfig`、`llmConfig`、`searchApiConfig`（`load_agent_runtime_config`），並建立 `AgentRuntime`。
   - 以 `tauri::async_runtime::spawn` 在背景執行 `run_once_with_cancel_and_events`。
   - 事件透過 sink 以 `emit("agent-event", {sessionId, runId, event})` 推回前端。

3. **Router（只做提示，不觸發檢索）**
   `agent/router.rs::route_query` 只做字串 `contains`，比對的是英文與**簡體**關鍵字，例如 `graph`、`relationship`、`知识图谱`、`最新`、`原始资料`。
   - 「請介紹植田和男」一個都不會命中，所以 `intent = Ambiguous`，`rationale = "Ambiguous request; let the tool planner decide whether retrieval is useful."`。
   - 程式碼明確寫死 `should_search_wiki = false`。

4. **進入工具迴圈**
   `runtime.rs` L244：只要 `llm_config.is_usable_for_backend_http()` 成立，就直接進 `run_agent_loop`。
   - Standard 模式的預算是 `max_iterations = 8`、`retrieval_budget = 4`（`agent_loop_iteration_budget` / `agent_loop_retrieval_budget`）。

5. **第 1 輪 LLM 呼叫：決定查詢詞**
   - system 由 `build_system_context` 與 `build_agent_loop_system` 組成，內容是工具政策、`overview.md`、`schema.md`，以及 JSON 協定。
   - user 由 `build_user_context` 與 `build_agent_loop_user` 組成，內容是歷史對話、「Retrieved project context: No matching wiki references were found.」、可用工具清單、迭代預算，以及「Latest user request: 請介紹植田和男」。
   - LLM 回傳例如 `{"action":"tool","tool":"wiki.search","query":"植田和男"}`。**「植田和男」這個關鍵字就是在這一步由 LLM 從問句中取出來的。**

6. **執行 `wiki.search`**
   呼叫鏈：`execute_agent_loop_tool` → `BuiltinToolRegistry::execute("wiki.search")` → `tools.rs::run_wiki_search` → `search::resolve_query_embedding`（沒設定 embedding 時回傳 `None`）→ `search::search_project_inner(query="植田和男", top_k=5)`。
   - 掃描 `wiki/**/*.md`，`score_file` 給 11 個檔案打分（**tokenHits=11**）。
   - `wiki/entities/植田和男.md` 因為「檔名完全相同」而拿到 +200，穩居第 1 名（計算見 §4.2）。
   - 沒有向量結果（**vectorHits=0**），因此不做 RRF。
   - `blend_graph_results` 以前 5 名為種子（seed），找出一跳鄰居。topK=5、vectorHits=0 時 graph 配額是 `ceil(5×0.30)=2`，所以最終 5 筆是「3 筆關鍵字結果 + **2 筆 graph 鄰居**」（**graphHits=2**）。
   - `search_mode(...)` 只要 `graph_hits>0` 就回傳 **`hybrid`**。

7. **轉成 reference**
   `run_wiki_search` 先呼叫 `build_knowledge_context_index` 掃一次 wiki 圖譜，替每筆結果附上 tags、outgoing links、backlinks、link_count、最新版本資訊。
   - 帶有 `graph_related_to` 的結果，`kind = "graph"`；其他的 `kind = "wiki"`。

8. **記錄觀察與 log**
   `record_loop_tool_success` 用 `push_unique_reference`（以 `kind+path` 去重）加入 reference，並對每一筆送出 `referenceAdded` 事件。接著組出：
   `"{count} result(s), {added} new, mode={}, tokenHits={}, vectorHits={}, graphHits={}"` → `5 result(s), 5 new, mode=hybrid, tokenHits=11, vectorHits=0, graphHits=2`。
   這段字串會同時成為 `toolEnd` 事件的 output（前端顯示的就是它），以及下一輪 prompt 的「Tool observations so far」。

9. **第 2 輪 LLM 呼叫**
   這一輪 user prompt 的「Retrieved project context」會列出 5 筆 reference，格式如下：
   ```
   1. [wiki] 植田和男 (wiki/entities/植田和男.md)
   Snippet: …植田和男…（關鍵字前後各約 80 字）
   Tags: …
   Links to: 日本銀行, …
   Backlinks: wiki/sources/xxx.md, …
   Related links: N
   ...
   4. [graph] 日本銀行 (wiki/entities/日本銀行.md)
   Snippet: Graph neighbor of 植田和男
   Graph neighbors of: 植田和男
   ```
   snippet 不夠寫介紹，所以 LLM 通常會接著回傳 `{"action":"tool","tool":"wiki.read_page","path":"wiki/entities/植田和男.md"}`。

10. **`wiki.read_page`**
    `tools.rs::read_wiki_page` 只允許讀 `wiki/` 底下的路徑。觀察摘要是 `read <path>\n<空白壓縮後的正文，最多 4,000 字>`，再加上 `Knowledge context: {...}`（最多 2,000 字）。
    - 如有需要，LLM 還可以讀 `Backlinks` 裡的 `wiki/sources/*.md`，這就是「整合來源」的方式。
    - 也可以用 `graph.search` 查關係。

11. **最終回答**
    LLM 回傳 `{"action":"final","answer":"植田和男是…（提到 wiki/entities/植田和男.md 等路徑）"}`。
    - `parse_agent_loop_action` 解析出 `answer`，以**單一**一筆 `messageDelta` 送出，接著送 `done`。
    - 如果檢索次數已達 `retrieval_budget`，或出現重複查詢，系統會改用 `build_agent_final_system` 強制收尾，此時禁止再呼叫任何工具。

12. **前端收尾**
    - `messageDelta` 會累加到 `accumulated`。
    - `referenceAdded` 經 `backendReferenceToMessageReference` 轉成 `MessageReference`（`kind: "graph"` 會顯示為 source「Graph」）。
    - `done` 後呼叫 `finalizeStreamForConversation`，把回答、references、步驟一起存進對話（`.llm-wiki/chats/{id}.json`）。
    - `chat-message.tsx` 會把回答中的 `[[wikilink]]` 轉成可點擊連結，並在「References」區塊列出全部已收集的 reference，同時以 `ReferenceKnowledgeGraph` 畫出關係圖。

---

## 3. 關鍵字／查詢理解

| 層級 | 實作 | 對「請介紹植田和男」的作用 |
|---|---|---|
| Router | `router.rs::route_query`：英文與簡體子字串比對（`graph`、`知识图谱`、`最新`、`原始资料`、`写入`…） | 不命中，結果是 `Ambiguous`。只會變成 system prompt 裡的 `Router hint`，不會觸發檢索 |
| LLM 工具迴圈 | `build_agent_loop_system` 要求「Return only compact JSON… `{"action":"tool","tool":"wiki.search","query":"..."}`」 | **真正的「關鍵字抽取」在這裡**：由 LLM 改寫成 `query:"植田和男"`。graph.search 的工具說明有提示「use concise entity or concept names」 |
| Planner（非迴圈路徑） | `plan_tools_with_model`：system 是「You are an agent tool planner. Return only compact JSON.」，輸出 `{"toolCalls":[{"tool":"wiki.search","query":"short query"}]}` | 只在 **非** HTTP-LLM 路徑才會用到，而且該路徑沒有可用 LLM，所以實際上會 fallback（見 §6） |
| Tokenizer | `search.rs::tokenize_query` | 把 query 切成 token，細節見下方 |
| Wikilink 比對 | 檢索時**不會**把查詢詞當成 wikilink 去解析 | `[[...]]` 只用在 graph 擴展（頁面之間的連結），以及前端點擊連結時 |

`tokenize_query` 的步驟：

1. 全部轉小寫。
2. 以空白、ASCII 標點與中文標點（`，。！？、；：“”‘’（）·～…`）切開。
3. 丟掉長度 ≤1 的 token 與停用詞。停用詞包含 `的 是 了 什么 在 有 和 与 对 从` 與英文 stopwords。
4. 含 CJK（`\u3400–\u9fff`）且長度 >2 的 token，展開成：所有 **bigram** + 所有 **單字**（再過濾停用詞）+ **整串**。
5. 去重並排序。

實際切詞結果：

- `"植田和男"` → `植田、田和、和男、植、田、男、植田和男`。「和」是停用詞，所以單字「和」被移除。
- 如果 LLM 直接拿整句 `"請介紹植田和男"` 當 query，因為沒有標點可切，會得到 `請介、介紹、紹植、植田、田和、和男、請、介、紹、植、田、男、請介紹植田和男`。
  - 此時檔名無法完全相同，那 +200 也就拿不到。
  - 不過 `植田`、`和男` 等 bigram 與內文片語仍然會命中。

**沒有 NER、沒有 jieba / MeCab 斷詞，也沒有同義詞擴展。** 日文假名（`\u3040–\u30ff`）不在 CJK 判斷範圍內，純假名的詞不會被拆成 bigram。

---

## 4. 混合檢索（token / vector / graph）與 log 解碼

### 4.1 Log 的來源

| 字串 | 位置 | 何時出現 |
|---|---|---|
| `"{count} result(s), {added} new, mode={}, tokenHits={}, vectorHits={}, graphHits={}"` | `runtime.rs:2259`，`record_loop_tool_success` | **工具迴圈**（HTTP LLM，也就是一般 Chat）。這就是範例 log |
| `"{} result(s), mode={}, tokenHits={}, vectorHits={}, graphHits={}"`（沒有 `new`） | `runtime.rs:678`，非迴圈路徑 | 沒有 HTTP LLM 時，例如 claude-code / codex-cli 走 `agent_start_turn` |
| `"{count} result(s), {added} new"` | `runtime.rs:2273` | `source.search` / `graph.search` / `web.search` / `anytxt.search` |

### 4.2 各欄位的意義

| 欄位 | 程式來源 | 含義 | 範例值的解讀 |
|---|---|---|---|
| `5 result(s)` | `search.references.len()` | `search_project_inner` 截到 `limit = topK` 後的筆數 | Standard 模式的 `topK=5` |
| `5 new` | `push_unique_reference` 回傳 true 的數量 | 這次結果中，本回合 `references` 裡原本沒有的筆數（以 `kind+path` 判斷） | 第一次搜尋，5 筆全是新的。如果之後換詞再搜，可能出現 `5 result(s), 1 new` |
| `mode=hybrid` | `search_mode(token_rank_empty, vector_hits, graph_hits)` | `graph_hits>0` 就是 `hybrid`；否則 `vector_hits==0` 是 `keyword`；只有向量命中是 `vector`；其餘是 `hybrid` | 雖然沒有向量，**只要有 graph 鄰居就算 hybrid** |
| `tokenHits=11` | `token_rank.len()` | `score_file` 回傳 `Some` 的 **wiki 檔案數**（截斷前）。**不是 token 數量** | 有 11 個 `.md` 至少命中一項（含單字「田」「男」這類寬鬆命中） |
| `vectorHits=0` | `search_by_embedding` 回傳的頁數 | 向量檢索命中的頁數（chunk 依頁聚合後） | 0 代表以下其中一種情況：未啟用 embedding、`resolve_query_embedding` 失敗（只印 `[Search] embedding disabled for this request`）、LanceDB 沒有 `TABLE_V2`、或向量搜尋出錯 |
| `graphHits=2` | `blend_graph_results` 回傳的 `graph_count` | 最終結果中以 graph 鄰居身分放入的筆數 | `graph_result_quota(5, 0) = ceil(5×0.30)=2`，剛好用滿配額 |

### 4.3 Token 路徑：`score_file`

每個 `wiki/**/*.md` 都會計算以下分數（最多掃 10,000 個檔案）：

| 項目 | 加分 |
|---|---|
| `FILENAME_EXACT_BONUS` | 檔名（去掉 `.md`、轉小寫）完全等於 query 片語時 +200 |
| `PHRASE_IN_TITLE_BONUS` | 標題或檔名包含完整片語時 +50 |
| `PHRASE_IN_CONTENT_PER_OCC` | 內文每出現一次片語 +20，最多計 10 次 |
| `TITLE_TOKEN_WEIGHT` | 標題中每命中一個 token +5 |
| `CONTENT_TOKEN_WEIGHT` | 內文中每命中一個 token +1 |

標題由 `extract_title` 決定，依序取 frontmatter 的 `title:`、第一個 `# ` 標題、或檔名。

以 `wiki/entities/植田和男.md` 為例（query 為 `植田和男`，假設內文出現 ≥10 次）：

| 項目 | 分數 |
|---|---|
| 檔名完全相同 | 200 |
| 標題含片語 | 50 |
| 內文片語 10 次 × 20 | 200 |
| 標題 token 7 個 × 5 | 35 |
| 內文 token 7 個 × 1 | 7 |
| **合計** | **≈ 492** |

其他頁面通常只拿到片語或 token 分數，所以這一頁幾乎必定排第 1。

snippet 由 `build_snippet` 產生：以片語（沒有片語時取第一個命中的 token）為錨點，前後各取 80 字。

### 4.4 Vector 路徑（`vectorHits>0` 時）

1. `resolve_query_embedding` 用 `SearchEmbeddingConfig`，呼叫任何 OpenAI 相容、Google 或 Volcengine 的 embedding 端點取得查詢向量，逾時 8 秒，失敗會靜默降級。
2. `search_by_embedding` 呼叫 `vectorstore::vector_search_chunks`，在 `.llm-wiki/lancedb` 取前 `max(topK*3, 30)` 個 chunk。
   - 分數是 `1/(1+distance)`。
3. 依 `page_id` 聚合（`page_id` = wiki 相對路徑去掉 `.md`，例如 `entities/植田和男`）。
   - 頁面分數 = `top + min(0.3×其餘 chunk 分數總和, 1−top)`。
4. `materialize_vector_only_results`：只有向量命中、沒有關鍵字命中的頁也會補進結果。snippet 用 `heading_path: chunk_text`。
5. `apply_rrf_scores`：`score = 1/(60+token_rank) + 1/(60+vector_rank)`，同時保留 `vector_score`。
6. 頁面向量由 `commands/page_embedding.rs` 在 ingest 之後依 chunk 寫入。

### 4.5 Graph 路徑：`blend_graph_results`

1. **建圖**：邊來自每頁的 `[[target|alias]]` 與 frontmatter 的 `related:`（`extract_graph_links`）。
   - 別名表包含 path、wiki 相對路徑、檔名 stem、title，統一做小寫、空白轉 `-`、去掉 `.md` 與 `#anchor`。
   - 邊是雙向的。
2. **種子**：排序後前 `min(limit, 20)` 筆（topK=5 時就是前 5 名）。
3. **鄰居打分**：每個「不在種子內」的鄰居累加 `1/(seed_rank+1)`。
   - 第 1 名「植田和男」的鄰居每個 +1.0，所以 graph 名額幾乎都會被它的直接鄰居拿走，例如 `[[日本銀行]]`、它連到的 source 頁。
4. **配額**：`graph_result_quota(limit, vector_hits)` 在 30%（沒有向量時）到 15%（向量填滿時）之間，至少 1 筆、最多 `limit-1` 筆。
5. **合併**：先保留前 `limit − graph_count` 筆一般結果，再接上 graph 候選。
   - 如果候選原本就在關鍵字結果中（例如第 6–11 名），沿用原 snippet，只補上 `graph_related_to`。
   - 否則新建一筆，snippet 為 `"Graph neighbor of 植田和男"`，分數為 `graph_score/61`。
   - **副作用**：原本第 4、5 名的關鍵字結果會被擠掉。
6. 範例的 5 筆 = 關鍵字第 1–3 名 + 2 筆 graph 鄰居。在 `AgentReference` 中，後 2 筆的 `kind` 會變成 `"graph"`。

另外有一個**獨立的** `graph.search` 工具（`tools.rs::search_graph`）。它以子字串比對頁面作為「matched entity」（分數 10,000+link 數），再加上直接鄰居（5,000+link 數）。LLM 只有在問題涉及「關係」時才會主動呼叫它，「請介紹植田和男」通常不會用到。

---

## 5. Context 組裝與回答生成

### 5.1 System prompt（`context.rs::build_system_context`，每輪都相同）

```
You are the LLM Wiki backend Agent.
Answer using the current project context, available tools, and cited references.
If evidence is insufficient, say what is missing instead of inventing facts.
When using references, mention the relevant page paths naturally.
Do not claim that internet or local-source search is unavailable when those tools are enabled; ...

Tool policy:
- wiki.search retrieves pages for factual or topical questions.
- graph.search retrieves relationships, neighbors, backlinks, ... use concise entity names ...
- (web.search / anytxt.search hints if enabled)
- Router hint: Ambiguous. Ambiguous request; let the tool planner decide whether retrieval is useful.

Generated file policy: ...（agent-workspace、Mermaid 規則等）

Project overview:   ← overview.md 或 wiki/overview.md（≤8,000 字）
Project schema:     ← schema.md 或 wiki/schema.md（≤6,000 字）
（skills 索引或已選 skill 內容）
```

工具迴圈會在後面再接上 `build_agent_loop_system`（`runtime.rs:2915`）的「Agent loop protocol」。重點如下：

- 只能回傳精簡 JSON。
- 每輪只能選一個動作：`wiki.search`、`user.ask`、`workspace.write_file`、`final` 等。
- 不可以輸出「Let me search…」這類自然語言計畫。
- 要盡快收斂。

`smart` 模式會再追加「bounded evidence loop」規則，`faithful` 模式會再追加「只能使用 raw source 摘錄」規則。

### 5.2 User prompt（`build_user_context` + `build_agent_loop_user`）

依序組成：

1. `Recent conversation history:`：最近 12 則，≤12,000 字。
2. `User-selected project files:`：使用者用 @ 附加的檔案，以 XML 跳脫，≤24,000 字。
3. `Retrieved project context:`：本回合累積的所有 reference，每筆包含 `N. [kind] title (path)`、`Snippet`、`Graph neighbors of`、`Tags`、`Links to`、`Backlinks`、`Related links`、`Latest version`。合計 ≤24,000 字。
4. `Retrieval summary:`：迴圈中固定是空字串。
5. `Latest user request: 請介紹植田和男`
6. 迴圈附加區塊：
   - `Available Agent tools for this turn:`
   - `Tool observations so far:`：每則 ≤8,000 字，例如 `1. wiki.search:\n5 result(s), 5 new, ...`、`2. wiki.read_page:\nread wiki/entities/植田和男.md\n…`
   - `Iteration budget: step k of 8`
   - `Return the next JSON action now…`

最後由 `fit_context_to_model` 依 `llmConfig.max_context_size` 截斷（以字元計，範圍 8k–400k；user 至少保留 4,000 字）。

> 重點：`wiki.search` 的 observation **只有計數那一行**。頁面實際的 snippet 與圖譜資訊放在「Retrieved project context」。要拿到全文，必須另外呼叫 `wiki.read_page`。

### 5.3 收斂與強制收尾

迴圈在以下情況結束：

- LLM 回傳 `final`。
- `retrieval_steps >= retrieval_budget`，或出現重複查詢（signature 相同），或 smart 模式連續 2 次沒有新證據。這三種情況都會改用：
  - `build_agent_final_system`：「The retrieval phase is complete. No tools are available now… Return only compact JSON in the form {"action":"final","answer":"..."}」
  - `build_agent_final_user`：「Provide the final answer now. No further tool action is permitted.」
- 跑完 8 輪仍沒有結論時，改由 `agent_iteration_limit_answer` 產生回覆。

### 5.4 引用與 wikilink

- 後端 prompt **沒有**要求 `[1]` 編號引用，只要求「mention the relevant page paths naturally」。回答通常會帶出 `wiki/entities/植田和男.md` 這類路徑，或 `[[植田和男]]`。
- 前端的 References 區塊直接使用 `referenceAdded` 收到的**全部**檢索結果（`savedReferences`），不是 LLM 實際引用的子集。
- `extractCitedPages` 的 `<!-- cited: 1,3 -->` / `[n]` / `[[wikilink]]` 解析，只在訊息沒有 saved references 時才作為後備。
- 回答中的 `[[...]]` 由 `chat-message.tsx` 轉成 `wikilink:` 連結，點擊後會嘗試 `wiki/{entities,concepts,sources,queries,...}/<name>.md`。

### 5.5 串流

- 工具迴圈中的每次 LLM 呼叫都用 `generate_with_cancellation`，也就是**非串流**的 `generate_text`。
- 最終答案是從 JSON 解析出來後，**一次**以 `messageDelta` 送出。所以使用者看到的「串流」只是工具事件即時更新（`toolStart`/`toolEnd`/`referenceAdded`），正文不是逐 token 顯示。
- 可以中途取消：`agent_cancel_turn` → `AgentCancellationToken`，透過 `tokio::select!` 中斷等待。

---

## 6. 模式差異（簡述）

| 選項 | 值 | 對 Chat 的影響 |
|---|---|---|
| `agentMode` | `fast` / `standard` / `deep` / `local_first` | **iterations**：4 / 8 / 12 / 8（有 skill 時是 8/16/20/16）<br>**retrieval budget**（standard 檢索模式）：2 / 4 / 8 / 4<br>**前端參數**：deep 送 `topK=8`、`includeContent=true`；其他模式送 `topK=5`<br>**`local_first`**：在迴圈中與 standard 完全相同 |
| `retrievalMode` | `standard` / `smart` / `faithful` | **smart**：檢索預算 3/4/6，查詢正規化後去重，連續 2 次沒有新證據就強制收尾<br>**faithful**：先強制跑一次 `source.search`，只允許 raw source 作為證據，並移除 overview/schema |
| 非 HTTP provider | `claude-code` / `codex-cli` | 前端改呼叫 `agent_start_turn`（非串流）→ `run_once_with_cancel_and_events` 的**非迴圈路徑**：<br>• planner 因為沒有 HTTP LLM 而無法執行 → `should_fallback_wiki_search` → 用**整句原文**當 query 跑 `wiki.search`（log 沒有 `new`）<br>• 再由前端用 `streamChat` 透過 CLI provider 逐 token 串流最終答案（system：「Use retrieved LLM Wiki context when available…」） |

**Deep mode ≠ Deep Research。** Chat 的 `deep` 只是放寬工具迴圈的預算與 topK。**Deep Research** 是另一套功能（`src/lib/deep-research.ts` / `research-store`）：由 LLM 產生搜尋主題 → 多查詢 web search → 結果 auto-ingest 回 wiki。兩者不共用 runtime。

---

## 7. 關鍵檔案

| 檔案 | 角色 |
|---|---|
| `src/components/chat/chat-panel.tsx` | `handleSend`：決定走後端 Agent、`invoke("agent_start_turn_stream")`、處理 `agent-event`、整理 references 與步驟 |
| `src/components/chat/chat-input.tsx` | 輸入框、模式選單（`AGENT_MODE_OPTIONS`、`RETRIEVAL_MODE_OPTIONS`）、`/skill`、@ 檔案 |
| `src/components/chat/chat-message.tsx` | 回答渲染、`[[wikilink]]` 轉換、References 區塊、`extractCitedPages`、Save to Wiki |
| `src/stores/chat-store.ts` | `agentMode`（預設 standard）、`retrievalMode`、對話與訊息持久化 |
| `src-tauri/src/lib.rs` | Tauri commands：`agent_start_turn(_stream)`、`agent_cancel_turn`、`load_agent_runtime_config` |
| `src-tauri/src/agent/runtime.rs` | `run_once_with_cancel_and_events`、`run_agent_loop`、`record_loop_tool_success`（log 字串）、各種 prompt builder、預算 |
| `src-tauri/src/agent/router.rs` | `route_query`（只做提示） |
| `src-tauri/src/agent/context.rs` | `build_system_context` / `build_user_context`、各段字數上限 |
| `src-tauri/src/agent/tools.rs` | `run_wiki_search`、`read_wiki_page`、`search_graph`、`search_sources`、`build_knowledge_graph_snapshot` |
| `src-tauri/src/commands/search.rs` | `search_project_inner`、`tokenize_query`、`score_file`、`apply_rrf_scores`、`blend_graph_results`、`search_mode` |
| `src-tauri/src/commands/vectorstore.rs` / `page_embedding.rs` | LanceDB chunk 向量的寫入與查詢 |
| `src-tauri/src/agent/provider.rs` | `LlmClient`、`is_usable_for_backend_http` |
| `src/lib/search.ts` | Search 頁面用的 `searchWiki`（同一個 `search_project`，topK=20）；檔內的 TS `tokenizeQuery` 在 Chat 中沒有使用 |

---

## 8. 落差／README 與程式碼不一致之處

1. **README §7「Optimized Query Retrieval Pipeline」已過時。** 對照現行程式碼：

   | README 說法 | 現行程式碼 |
   |---|---|
   | 「4-signal relevance model」「2-hop traversal with decay」 | Chat 只做**一跳**鄰居、`1/(rank+1)` 加權。`graph-relevance.ts` 只被 `wiki-graph.ts` 使用，Chat 沒用到 |
   | 「Budget Control 60/20/5/15」 | `context-budget.ts` 只被 ingest / llm-providers 使用。後端改用固定字數上限加上 `fit_context_to_model` |
   | 「Numbered pages with full content」「System prompt includes purpose.md, language rules, citation format, index.md」「cite pages by number: [1], [2]」 | 後端只放 snippet。全文要靠 LLM 呼叫 `wiki.read_page`，而且截到 4,000 字。system 只含 overview.md 與 schema.md，**沒有** purpose.md、index.md、語言規則或編號引用格式 |
   | 「Title match bonus (+10)」 | 實際權重是 +200（檔名）、+50（標題片語）、×5（標題 token） |
   | 「Searches both wiki/ and raw/sources/」 | `search_project_inner` 只掃 `wiki/`。raw source 要另外呼叫 `source.search` |

2. **`includeContent` 在 Chat 中沒有作用。** deep 模式送 `includeContent=true`，`search_project_inner` 也會讀入全文，但 `AgentReference` 沒有 content 欄位，`run_wiki_search` 只保留 snippet，全文被丟掉。
3. **Router 只認簡體與英文關鍵字**（例如 `知识图谱`、`最新`）。繁體的「知識圖譜」「最新消息」部分不會命中（「最新」相同所以例外）。影響不大，因為 router 現在只提供提示。
4. **工具迴圈的回答不是逐 token 串流**（見 §5.5）。非迴圈路徑雖然有 `generate_with_cancellation_stream`，但能走到那條分支的前提是「有 HTTP LLM」，而有 HTTP LLM 時早已進入迴圈，所以在 App 中那段實際上執行不到。
5. **`tokenHits` 容易被誤讀**：它是「有命中的檔案數」，不是 token 數。CJK 單字 token（如「田」「男」）會讓命中範圍變很寬。
6. **graph 配額會擠掉關鍵字結果**：topK=5 時，關鍵字第 4、5 名會被 2 筆 graph 鄰居取代，即使鄰居和問題無直接關係。
7. **References 面板顯示的是「檢索到的」頁面，不是「被引用的」頁面。**
8. `local_first` 在後端沒有任何差異化邏輯。
9. 回答語言沒有明確規則。後端沒有注入 output-language 設定，中文回答完全依賴 LLM 跟隨使用者語言。

---

*以上為靜態閱讀原始碼的結果，未實際執行 App。範例中「哪 2 頁是 graph 鄰居」「LLM 是否呼叫 `wiki.read_page`」取決於實際 wiki 內容與模型行為；流程與數字推導（topK=5、配額 2、mode 判定）則由程式碼決定。*
