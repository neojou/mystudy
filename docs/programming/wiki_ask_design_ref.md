# Wiki Ask 檢索設計參考（對齊 llm-wiki Chat 行為）

> 這份設計已被 Chat Agent 取代。現況是 `ChatAgent`、`docs/programming/How-To-do-wiki-Chat.md` 與 `ChatAgentBehaviorTest`。
> 本文保留當歷史紀錄。不要實作下面的 P1–P4 或 Phase 1–4；那些會改 Lexical Ask 的切詞與評分。



> 放置位置：`docs/programming/wiki-ask-design-ref.md`。背景與證據見同目錄 `llm-wiki-chat-gap.md`。
> 實作完成後，行為以 `WikiBehaviorTest` 與 `How-To-do-wiki-Ask.md` 為準；本文件是設計目標，不是現況說明。

## 1. 目標行為與驗收

問題 `請介紹植田和男`，在 vault 有 `wiki/entities/植田和男.md`（title、alias 或檔名 stem 為 `植田和男`）時：

1. 該頁是**第 1 個種子**，在 packet 裡是 `[1]`。
2. 即使 vault 裡有 ≥40 頁正文含「介紹」，結果不變。
3. 模型被呼叫一次，答案引用 `[1]`，內容來自該頁（不是「沒有」）。
4. 變體同樣通過：`植田和男`、`植田和男是誰`、`介紹一下植田和男`、`請問植田和男是誰？`、`日本央行總裁植田和男的政策`（最後一題實體頁要在種子內）。
5. title 為 `植田和男 (Kazuo Ueda)` 時也通過。
6. 既有 `WikiBehaviorTest` 全綠（特別是 `genericOnlyOverlapDoesNotCallTheModel`、`definitionQuestionFindsTwoCharacterConceptWithoutTheQuestionFrame`）。
7. 每次 Ask 有一行檢索 log（§5）。

## 2. 檢索管線（hybrid）

```text
question
  └─ analyzeQuery ──► runs（框架剝除、不以停用字切開）＋ 既有 phrase/pieces/bigrams/ftsTerms
        │
        ├─ [name]   exactNameHits(runs, index::pathsForAlias)      ← llm-wiki FILENAME_EXACT 的本地版
        ├─ [token]  contentAnchors(runs) → searchAskTerm / alias / FTS（每 anchor 配額）
        ├─ [vector] (P3, optional) Ollama /api/embed → RRF
        └─ score → seeds(≤5) ─► [graph] expandOneHop（既有）─► limitPacket(8 頁 / 6000 字)
```

### 2.1 Token 路徑（P1）

- **停用字不切詞**：`hanStopChars`（是的了在有和）只影響 bigram/單字層級，不能把 `植田和男` 切成 `植田`＋`男`。保留既有 `pieces` 給相容測試，新增 `runs` 給名字比對與 anchor。
- **請求框架只剝邊界**：開頭 `請`、`請問`、`(請)?(介紹|說明|解釋|簡介|描述|概述|談談|講講|告訴我)(一下)?`（含簡體）；結尾 `是誰`、`是什麼`、`嗎`、`呢`。中間出現的 `介紹`（如 `自我介紹`）不剝。
- **CJK bigram** 照舊（不放單字）。
- **anchor 排序與配額**：長度 desc → `countAskTerm` asc；每個 anchor 在池中最多 `max(10, 40 / anchors.size)` 頁；有 nameHit 時，df 比率 >0.20 的其他 anchor 不可 seed。
- **路徑也算標題**：`searchAskTerm` / `countAskTerm` 比對 `path`（對應 llm-wiki `title_text = title + file_name`）。

### 2.2 Exact name / title boost（P1，關鍵）

```kotlin
data class NameHit(val key: String, val paths: List<String>)

/** Longest-first substrings (len ≥ 2, run ≤ 32 chars) whose normalizeKey equals a title, alias, or filename stem. Maximal hits only. */
fun exactNameHits(runs: List<String>, lookup: (String) -> List<String>): List<NameHit>
```

- `lookup = index::pathsForAlias`（`byAlias` 已含 title / aliases / stem，記憶體查表，無 SQL）。
- `byAlias` 另放去括號 key（`植田和男 (Kazuo Ueda)` → `植田和男`），在 `reloadMaps()` 記憶體計算，不改 SQLite schema。
- 評分：`EXACT_NAME_BONUS = 200.0`，命中頁 `canSeed = true`、排在池最前。
- 只收 Ask 路徑（`includedAskPath`）。

### 2.3 Vector（P3，選用，需改 AGENTS.md 契約）

- Ollama loopback `/api/embed`（例如 `bge-m3`），chunk 向量存 vault 外 SQLite（同 `indexDatabasePath` 目錄）。
- 融合：`1/(60 + tokenRank) + 1/(60 + vectorRank)`；embedding 失敗靜默降級成 `vectorHits=0`。

### 2.4 Graph（既有，保留）

- `expandOneHop` 公式不變；鄰居接在種子後，超額先丟。
- 不採用 llm-wiki「graph 配額擠掉關鍵字第 4、5 名」。

## 3. 單發 vs 工具迴圈：建議

| 選項 | 適合度 | 說明 |
| --- | --- | --- |
| A. 確定性抽取（本地 `exactNameHits` + 修切詞），單次生成 | **P1 採用** | 根因是切詞，不是理解力；名字已在 `byAlias`。零額外延遲、可測、符合現有契約 |
| B. LLM 產生 `wiki.search` query（一次 JSON 呼叫），再走 A 的檢索 | P2，fallback | 只在 A 回 `NoMatch` 時呼叫；prompt 放 `ai-prompts/ask-query.md`，`json = true`，輸出 `{"queries":["植田和男"]}`（≤3 個）。處理同義/跨語（Ueda、日銀總裁）。Settings 開關，預設關。需改 AGENTS.md「不做 query rewrite」 |
| C. 有界工具迴圈（search → read_page → final） | P4，多半不需要 | llm-wiki 的 snippet 太短才需要 `read_page`；mystudy packet 已帶 1500 字 excerpt。本機模型多輪 JSON 不穩、延遲高 |

llm-wiki 的教訓：成功來自「query = 頁名」＋「頁名完全相等 +200」。LLM 只是拿到頁名的手段；能在本地拿到就不必多打一次模型。

## 4. Prompt / context 組裝

維持 `ai-prompts/ask.md` 結構（system / user / page，`{{question}}` `{{schema}}` `{{pages}}`）。調整：

- `excerptFor` 中心點優先序：nameHit key → 最長 anchor → phrase（不要被「介紹」帶走）。
- 種子頁若是 nameHit 且全文 ≤ 3000 字，可給它 3000 字上限（其他頁仍 1500；總量 `PACKET_CHARS` 視需要提高到 8000 並檢查 `PromptBudget`）。此為 P1 可選項。
- 每頁 `page` 區段可加 `title: {{title}}`（改 `ask.md` 與 `renderPacket`，同步測試 `askPromptDropsCommentsAndLeavesPlaceholdersInsidePageText`）。可選。
- system 規則不變：只用 packet、繁中、`[n]` 引用、沒寫答「沒有」。

## 5. Log 指標（對應 llm-wiki）

每次 Ask 檢索完成後一行：

```text
MyLog.add("Ask", "wiki.search: {results} result(s), mode={mode}, tokenHits={tokenHits}, nameHits={nameHits}, vectorHits={vectorHits}, graphHits={graphHits}, anchors=[…], seeds=[…]")
```

| 欄位 | 定義 |
| --- | --- |
| `results` | 最終 packet 頁數 |
| `mode` | `keyword`（無 graph、無 vector）/ `hybrid`（graphHits>0 或 vectorHits>0）/ `vector`（只有向量）；P2 觸發時加 `+rewrite` |
| `tokenHits` | 既有：過濾後候選路徑總數（截斷前） |
| `nameHits` | `exactNameHits` 命中的不重複路徑數 |
| `vectorHits` | P3 前固定 0 |
| `graphHits` | 既有：packet 中 `fromGraph` 頁數 |

植田和男預期：`… mode=hybrid, tokenHits≥1, nameHits=1, vectorHits=0, graphHits≥0, anchors=[植田和男, …], seeds=[wiki/entities/植田和男.md, …]`。

`AskResult.NoMatch` / `Answer` 加 `nameHits`（可選 `mode`），`AskPane` 一併顯示。

## 6. 分階段計畫（plan mode 用）

### Phase 1：修 CJK 名字找不到（必做，不改契約）

1. 先寫**會失敗**的測試 `introduceQuestionFindsEntityWhoseNameContainsAStopChar`：
   - vault：`wiki/entities/植田和男.md`（title `植田和男`，正文含「日本銀行總裁」並 `[[日本銀行]]`）、`wiki/entities/日本銀行.md`、45 頁 `wiki/concepts/cNN.md` 正文含「介紹」。
   - 斷言：第一個送出的 packet 頁是 `wiki/entities/植田和男.md`；`ChatClient { calls += 1; … }` 被呼叫一次；`nameHits == 1`。
   - 參數化變體（§1-4）與括號 title（§1-5）。
2. `analyzeQuery`：加 `runs`、邊界框架剝除；守住 `queryAnalysisEmitsCjkBigramsNotSingleCharacters`。
3. `exactNameHits` + `EXACT_NAME_BONUS`；`lexicalPool` nameHits 先放、anchor 排序與配額。
4. `searchAskTerm` / `countAskTerm` 比對 path；`byAlias` 去括號 key。
5. `excerptFor` 中心點優先序。
6. `MyLog` 一行 + `AskResult.nameHits` + `AskPane` 顯示。
7. 更新 `How-To-do-wiki-Ask.md`（切詞、name 路徑、評分表、log）；`./gradlew :composeApp:desktopTest` 全綠。

### Phase 2：LLM 查詢改寫 fallback（選用，需 Neo 同意改 AGENTS.md）

`ai-prompts/ask-query.md`、`AskPipeline` 在 NoMatch 時呼叫一次 `json = true`、Settings 開關、測試用 `ChatClient { … }` lambda 回 `{"queries":["植田和男"]}`。

### Phase 3：向量（選用，需改契約）

`/api/embed`、chunk 表、RRF、`vectorHits`。

### Phase 4：多輪 Chat / 工具迴圈（另案）

`AskSession` 歷史、`wiki.read_page` 類工具、budget；先確認 P1–P2 後仍有需求。
