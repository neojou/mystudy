# 第二大腦方法比較與 mystudy 改進建議

> 對象：Neo Jou｜撰寫日：2026-10-13（台北時間）
> 依據：`neojou/mystudy` `main` @ `3208223`（2026-10-12 22:41 +08:00）的 `AGENTS.md`、`docs/Architecture.md`、`docs/programming/*`；`nashsu/llm_wiki` 與其中的 `llm-wiki.md`；以及下方列出的公開資料。

---

## 0. 結論先講

- mystudy 走的是 **Karpathy「LLM Wiki」路線**：LLM 把來源「編譯」成一份持續累積、互相連結的 markdown wiki，而不是每次提問才臨時做 RAG。這條路跟 Zettelkasten 和常青筆記的「知識要累積」精神一致，而且**全本機**（Ollama、loopback SearXNG、vault 外的 SQLite），在隱私上幾乎是同類工具裡最嚴格的。
- 它最缺的不是檢索，是**維護和回顧**：沒有 Lint（矛盾、孤兒頁、過期主張）、既有頁只能 append（會慢慢變成流水帳）、Chat 的好答案不會存回 wiki、也沒有把知識推回到「人」身上的機制（間隔重複、每日回顧）。
- 前五項建議：①Chat 答案「存成頁」（file-back）；②Wiki Lint 模式；③本機 embedding（Ollama `nomic-embed-text`／`bge-m3`）把 `vectorHits=0` 補起來；④頁面「重寫／合併」提案取代純 append；⑤每日回顧加上間隔重複卡片（FSRS）。

---

## 1. mystudy 目前狀態（依 repo 確認）

| 功能 | 現況 |
| --- | --- |
| 平台 | KMP 結構，但只建 desktop JVM（Compose）。vault 是 Obsidian 庫，wiki root 在 `vault/llm-wiki/`（`raw/` + `wiki/`）。 |
| Ingest | 預覽區按鈕 → Analyze（`ingest-analyze.md`）→ Generate（`ingest-generate.md`），最多 2 次模型呼叫。確認前不寫磁碟；確認後寫 `wiki/sources|concepts|entities/`、`index.md`、`log.md`，勾選的 REVIEW 寫進 `review.md`。**既有頁只 append**。 |
| Lexical Ask | 只用本地關鍵字＋一跳圖擴展，沒有種子頁就不打模型。 |
| Chat Agent | 有界 JSON 工具迴圈（最多 6+1 次模型呼叫、4 次工具），工具只有唯讀 `wiki.search`、`wiki.read_page`。所謂 `mode=hybrid` 指的是 **關鍵字＋圖鄰居**，`vectorHits` 固定為 0，**沒有 embedding**。歷史只放在記憶體。 |
| 深度研究 | 讀 `review.md` 的項目 → 只把查詢字串送到 loopback SearXNG → 串流綜整，寫 `wiki/queries/research-*.md`，並把該段從 review 移除。這是唯一的網路出口。（repo 裡已經有 `DeepResearchBehaviorTest`，所以它不只是「計畫中」，已經實作了。） |
| 沒有的 | embedding、多模態、持久佇列、分塊、Lint、答案存回 wiki、間隔重複、行動端擷取。 |

---

## 2. 主流第二大腦方法速覽

### 2.1 方法論

- **Building a Second Brain（Tiago Forte）**：CODE（Capture、Organize、Distill、Express）流程，加上依「可行動性」分類的 PARA（Projects／Areas／Resources／Archives），還有漸進式摘要（Progressive Summarization）。重點是**產出**，不是收藏。[Forte Labs – BASB](https://www.buildingasecondbrain.com/)、[PARA Method](https://fortelabs.com/blog/para/)、[Progressive Summarization](https://fortelabs.com/blog/progressive-summarization-a-practical-technique-for-designing-discoverable-notes/)
- **Zettelkasten（Luhmann）**：原子化卡片、唯一 ID、卡片之間直接連結，再用索引卡（結構筆記）當入口。重點是在**連結**中長出新想法。[zettelkasten.de – Introduction](https://zettelkasten.de/introduction/)、[Luhmann Archive](https://niklas-luhmann-archiv.de/)
- **常青筆記（Andy Matuschak）**：筆記要原子化、以概念為主、緊密連結、標題是一個主張，而且會**持續被改寫**。[Evergreen notes](https://notes.andymatuschak.org/Evergreen_notes)
- **間隔重複**：用演算法（SM-2、FSRS）排定複習時間來對抗遺忘曲線。Matuschak 也主張「把提示寫進筆記裡」。[FSRS（open-spaced-repetition）](https://github.com/open-spaced-repetition/fsrs4anki)、[Obsidian Spaced Repetition plugin](https://github.com/st3v3nmw/obsidian-spaced-repetition)、[Matuschak – Spaced repetition memory system](https://notes.andymatuschak.org/Spaced_repetition_memory_systems_make_memory_a_choice)

### 2.2 工具

- **Obsidian**：本機 markdown vault，有雙向連結和圖譜。外掛方面：[Smart Connections](https://github.com/brianpetro/obsidian-smart-connections)（本機 embedding 找相關筆記、跟筆記對話）、[Copilot for Obsidian](https://github.com/logancyang/obsidian-copilot)（Vault QA，可接 Ollama）、[Dataview](https://blacksmithgu.github.io/obsidian-dataview/)（把 frontmatter 當資料庫查詢）。
- **Logseq**：以大綱和 block 為單位、每日日誌優先、本機檔案，有 block reference 和查詢。[logseq.com](https://logseq.com/)
- **Notion AI**：雲端 workspace，AI 可以跨頁、跨連接器問答和撰寫。[Notion AI](https://www.notion.com/product/ai)
- **Tana**：supertag 把節點變成有欄位的物件，AI 命令可以自動補欄位，也有語音擷取。[tana.inc](https://tana.inc/)
- **Mem**：AI 優先，自動整理和找相關筆記，主打「不用分類」。[mem.ai](https://get.mem.ai/)
- **Reflect**：端對端加密、每日筆記加雙向連結、內建 AI。[reflect.app](https://reflect.app/)
- **NotebookLM**：以上傳的來源為基礎做 RAG，有引用的回答、Audio Overview、學習指南。屬於雲端、**每次提問重新推導**的典型代表。[NotebookLM](https://notebooklm.google/)
- **Karpathy「LLM Wiki」**：raw（不可變）→ LLM 擁有的 wiki → schema 三層，操作是 Ingest、Query、Lint。「好的答案要存回 wiki」，「Lint 定期健康檢查」。到中等規模（約 100 個來源）只靠 index.md 就夠用，再往上建議用 [qmd](https://github.com/tobi/qmd)（本機 BM25＋向量＋LLM rerank）。[gist](https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f)；實作參考 [nashsu/llm_wiki](https://github.com/nashsu/llm_wiki)
- **RAG／GraphRAG**：向量檢索；GraphRAG 先抽出實體和關係，再做社群摘要，適合回答「全域」問題。[Microsoft GraphRAG](https://microsoft.github.io/graphrag/)、[LightRAG](https://github.com/HKUDS/LightRAG)
- **Khoj**：可以自架的「AI second brain」，索引 markdown／org／PDF，提供對話、排程自動化和研究模式，支援本機模型。[khoj-ai/khoj](https://github.com/khoj-ai/khoj)
- **AnythingLLM**：桌面版加 workspace 的 RAG、agent，支援 Ollama，全本機。[Mintplex-Labs/anything-llm](https://github.com/Mintplex-Labs/anything-llm)

---

## 3. 比較表

圖例：●強 ◐中 ○弱

| 方法／工具 | 擷取 | 組織 | 連結 | 檢索 | AI 綜整 | 回顧／重現 | 隱私／本機 | 維護成本（低＝好） | 產出 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| BASB（CODE/PARA） | ● 鼓勵大量擷取 | ● PARA 依行動性分類 | ◐ | ◐ 靠資料夾 | ○ 方法本身沒有 | ◐ 漸進式摘要、週回顧 | 看工具 | ◐ 要定期整理 | ● Express 是核心 |
| Zettelkasten | ◐ 文獻卡 | ◐ 結構筆記 | ● 手動、有意義的連結 | ◐ 索引卡、連結 | ○ | ◐ 靠走連結偶遇 | ● 紙或本機 | ○ 很花人力 | ● 寫作導向 |
| 常青筆記 | ◐ | ◐ | ● | ◐ | ○ | ◐ 持續改寫 | ● | ○ 很花人力 | ● |
| Obsidian＋Smart Connections／Copilot／Dataview | ● 外掛、Web Clipper | ● 資料夾、標籤、屬性 | ● 雙鏈、圖譜、語意相似 | ● 全文＋向量＋Dataview | ◐ 對話、QA，但不會累積 | ◐ SR 外掛 | ● 本機（模型可接 Ollama） | ◐ 外掛很多 | ◐ |
| Logseq | ● 日誌 | ◐ 大綱、屬性 | ● block ref | ◐ 查詢 | ○/◐ | ● 內建 flashcards | ● | ◐ | ◐ |
| Notion AI | ● | ● 資料庫 | ◐ | ● AI 搜尋＋連接器 | ● | ○ | ○ 雲端 | ● 低 | ● |
| Tana | ● 語音、supertag | ● 結構化物件 | ● | ● | ● 自動補欄位 | ◐ | ○ 雲端 | ◐ 要設計 schema | ◐ |
| Mem／Reflect | ● | ◐ 自動 | ◐ 相關筆記 | ● | ● | ◐ | ○／◐（Reflect E2EE） | ● 低 | ◐ |
| NotebookLM | ◐ 上傳來源 | ○ 以 notebook 為單位 | ○ | ● 有引用的 RAG | ● 摘要、Audio | ○ | ○ 雲端 | ● 低 | ● 指南、Podcast |
| Khoj／AnythingLLM | ● 多格式 | ◐ workspace | ○ | ● 向量 RAG | ● 對話、研究、agent | ◐ Khoj 排程 | ● 可全本機 | ● 低 | ◐ |
| GraphRAG／LightRAG | ◐ | ◐ 自動社群 | ● 自動實體圖 | ● 全域＋局部 | ● | ○ | ● 可本機（索引成本高） | ○ 建索引貴 | ◐ |
| Karpathy LLM Wiki／llm_wiki | ● Ingest | ● LLM 維護 index、類型 | ● LLM 寫 wikilink | ◐→● index、搜尋 | ● **累積式**綜整 | ◐ Lint、log | ● 可本機 | ◐ 要定期 Lint | ● 答案存回 |
| **mystudy（現況）** | ◐ 一次一則、手動預覽 | ● sources/concepts/entities＋index | ◐ LLM 寫的 wikilink、一跳圖 | ◐ 關鍵字＋圖，無向量 | ● Ingest 綜整＋Chat 工具迴圈＋深度研究 | ◐ review.md 只是「待研究」，不是記憶回顧 | ● **最嚴格**：loopback-only、只讀工具 | ◐ 只 append 會漂移，沒有 Lint | ◐ 只有 research 頁；Chat 答案會消失 |

---

## 4. 評估 Neo 的做法

### 4.1 優點

1. **路線對**：選的是「編譯式累積 wiki」而不是一次性 RAG。這正好是 Karpathy 點出 NotebookLM 一類工具的弱點：知識不會累積。
2. **隱私和可控性很強**：Ollama、SearXNG 都只連 loopback；索引和偏好不進 vault；Chat 工具是唯讀的。這些都寫成契約，也有對應的測試。比 Notion、Mem、Tana、NotebookLM 都更適合放私人或工作資料。
3. **人在迴圈裡**：Ingest 預覽、確認才寫，路徑守衛（`assertWikiWrite`），不改 schema、不覆寫 raw。可以避免「LLM 把筆記寫壞」這個最常見的失敗模式。
4. **工程紀律好**：prompt 外部化（`ai-prompts/`），每個行為有 How-To 文件和 BehaviorTest，預算有上限（num_ctx、迴圈次數），這對小型本機模型很重要。
5. **跟 Obsidian 共存**：產出是純 markdown，可以用 Obsidian 的圖譜、Dataview 瀏覽，沒有鎖定。
6. **review.md → 深度研究**的閉環：Ingest 時發現的缺口會變成研究題，研究結果再回到 wiki。這和 Lint 裡的「data gaps 用 web search 補」是同一個精神。

### 4.2 弱點

1. **只 append 會造成漂移**：llm-wiki 的社群經驗是「drift 是主要的失敗模式，Lint 不是可選的」。只 append 的頁會越來越長、重複、前後矛盾，常青筆記「持續改寫」的精神做不到。
2. **沒有 Lint／健康檢查**：沒有孤兒頁、斷鏈、矛盾、過期主張、缺頁概念的偵測。
3. **檢索沒有語意層**：`vectorHits=0`。同義詞、跨語言（中、英、日名詞）、改寫的問題都只能靠模型猜關鍵字。之前「植田和男」那類切詞問題就是症狀。超過幾百頁以後會更明顯。
4. **Chat 是一次性的**：歷史只在記憶體，好的答案不會存回 wiki，違反 LLM Wiki 的核心主張「探索也要累積」。
5. **擷取摩擦大**：一次 Ingest 一則筆記、要手動預覽，沒有批次或佇列，也沒有網頁、PDF 擷取（llm_wiki 有瀏覽器擴充和 MCP）。
6. **沒有「回到人」的機制**：review.md 是給機器研究的待辦，不是給人的複習。沒有每日、每週重現，也沒有間隔重複，第二大腦只「記得」，人卻沒有記住。
7. **缺少產出管道**：BASB 強調 Express，但目前只有 research 頁，沒有「把幾頁組成文章、簡報、比較表」的流程。
8. **小模型的品質風險**：本機 Ollama 寫實體、概念頁容易出現幻覺或連結錯誤，目前只靠人工預覽把關，沒有自動的來源引用驗證（深度研究有 `[n]` 檢查，Ingest 沒有）。
9. **平台限制**：索引路徑寫死 macOS Application Support，也沒有行動端擷取。（這是刻意的取捨，但會影響「隨手擷取」。）

---

## 5. 建議（依優先順序）

所有建議都維持 local-first：模型只連 loopback Ollama、不新增雲端依賴；寫入一律走 `assertWikiWrite` 加上確認對話框。如果某項會改到 AGENTS.md 的契約，會特別標出來。

| # | 項目 | 為什麼 | 粗略設計 | 工作量 |
| --- | --- | --- | --- | --- |
| 1 | **Chat 答案存成頁（File-back）** | LLM Wiki 的核心：探索也要累積。目前好的答案會消失。 | Chat 回答下方加「存成頁」按鈕 → 預覽對話框（可改標題）→ 寫 `wiki/queries/chat-YYYYMMDD-slug.md`，frontmatter 放 question、引用頁、日期，正文保留 `[[wikilink]]` 引用 → 更新 `index.md`、`log.md`。重用深度研究的寫頁和 index 程式。Chat 仍維持唯讀，寫入只由 UI 觸發。 | 小（1–2 天） |
| 2 | **Wiki Lint 模式** | 防止 drift，是 Karpathy 和社群都強調的必要步驟。 | 分兩層。**本地規則（不用模型）**：孤兒頁（沒有入鏈）、斷掉的 `[[link]]`、被提到 ≥N 次但沒有頁的名詞、index 缺頁、超長頁、很久沒更新的頁。**模型層（可選）**：對同一實體或概念的頁和它的來源頁，用 `ai-prompts/lint.md` 找矛盾或過期主張，輸出沿用 `---REVIEW:` 格式，append 到 `review.md`，接上現有的深度研究。報告寫到 `wiki/lint-report.md`（要把它加進寫入允許清單；Chat 不讀它）。新增 `LintBehaviorTest`。 | 中（本地規則 2–3 天＋模型層 2–3 天） |
| 3 | **本機向量檢索（補上 `vectorHits`）** | 解決同義、跨語言、改寫的召回問題，讓 Chat 的 hybrid 名副其實。Smart Connections、qmd、Khoj 都證明本機 embedding 可行。 | Ollama `/api/embed` 搭配 `bge-m3`（多語、中文表現好）或 `nomic-embed-text`；以標題或段落為 chunk，向量存同一個 SQLite（BLOB，暴力 cosine 在幾千頁內就夠快，或用 sqlite-vec）；依 mtime 和 hash 做增量更新；在 `wiki.search` 用 RRF 融合關鍵字、向量、圖。Lexical Ask 維持純關鍵字（依契約）。**要改 AGENTS.md 中 Chat「不做 embedding」的契約**和 How-To。 | 中（3–5 天） |
| 4 | **頁面重寫／合併提案（取代純 append）** | 實現常青筆記「持續改寫」，降低重複和矛盾。 | Ingest Generate 對既有頁可以輸出 `---REWRITE:` 區塊（完整新版）；預覽時顯示 diff，使用者逐頁接受才覆寫；舊版存到 `wiki/.history/` 或依賴 git（建議 vault 用 git 自動 commit 當安全網）。預設仍是 append，重寫要明確勾選。要改 Ingest 契約和測試。 | 中（3–4 天） |
| 5 | **每日回顧＋間隔重複（FSRS）** | 讓知識回到人身上；BASB 的 resurfacing 和 Logseq、Obsidian SR 的做法。 | (a) Home 加「今日回顧」：隨機或依「最近 Ingest／很久沒看」挑 3–5 頁。(b) Ingest Generate 選擇性輸出 Q/A 卡，用 Obsidian SR 相容格式（`問題::答案` 或 `?` 分隔）寫在頁尾，這樣可以和 Obsidian 外掛互通。(c) 排程狀態用 FSRS（有 Kotlin/JVM 可移植的演算法）存在 vault 外的 SQLite。要另外加 `review` 模式，避免跟 `review.md`（研究佇列）搞混，建議改名為「複習」。 | 中（4–6 天） |
| 6 | **批次和佇列擷取** | 降低擷取摩擦，大量匯入既有筆記或 PDF。 | 資料夾多選 → 本機持久佇列（SQLite）→ 逐則跑 Analyze/Generate，結果先放「待確認」清單，之後一次審。PDF 用 PDFBox 抽文字。之後可以考慮 loopback HTTP 端點接瀏覽器剪藏（仍只綁 127.0.0.1）。 | 中到大 |
| 7 | **Ingest 引用驗證** | 降低小模型的幻覺。 | 要求 Generate 在每段主張附上來源頁的 `[[sources/...]]` 和引文片段；本地檢查引文是否真的出現在原筆記，不符合的在預覽中標紅。概念上沿用深度研究的 `[n]` 檢查。 | 小到中 |
| 8 | **產出模式（Express）** | BASB 的 E；讓 wiki 變成寫作素材。 | 從 index 或搜尋選幾頁 → 選模板（文章大綱、比較表、Marp 簡報）→ `ai-prompts/compose.md` → 預覽 → 存到 `wiki/queries/` 或 vault 的草稿區。 | 小到中 |
| 9 | **PARA 和狀態 frontmatter** | 讓 Dataview 能做專案視角，讓 Lint 能判斷過期。 | Ingest 時加上 `para: project/area/resource/archive`、`status`、`updated`、`sources_count`。純 frontmatter，不改目錄結構。 | 小 |
| 10 | **局部 GraphRAG 摘要（以後再說）** | 回答「我整體對 X 領域知道什麼」這類全域問題。 | 以現有 wikilink 圖做社群偵測（Louvain），每個社群由 LLM 寫一頁 `wiki/overviews/`，Lint 時更新。成本高，等頁數上千再做。 | 大 |

### 建議路線

- **短期（1–2 週）**：#1、#9、#2 的本地規則。不碰檢索契約，馬上讓 wiki 能累積、能自我檢查。
- **中期**：#3、#4、#2 的模型層、#7。
- **長期**：#5、#6、#8、#10。

---

## 6. 參考來源

- Karpathy, *LLM Wiki*（gist, 2026-04）：https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f
- nashsu/llm_wiki：https://github.com/nashsu/llm_wiki
- neojou/mystudy：https://github.com/neojou/mystudy
- Tiago Forte, Building a Second Brain：https://www.buildingasecondbrain.com/ ；PARA：https://fortelabs.com/blog/para/
- Zettelkasten 入門：https://zettelkasten.de/introduction/
- Andy Matuschak, Evergreen notes：https://notes.andymatuschak.org/Evergreen_notes
- Obsidian Smart Connections：https://github.com/brianpetro/obsidian-smart-connections ；Copilot：https://github.com/logancyang/obsidian-copilot ；Dataview：https://blacksmithgu.github.io/obsidian-dataview/
- Logseq：https://logseq.com/ ；Tana：https://tana.inc/ ；Mem：https://get.mem.ai/ ；Reflect：https://reflect.app/ ；Notion AI：https://www.notion.com/product/ai ；NotebookLM：https://notebooklm.google/
- Microsoft GraphRAG：https://microsoft.github.io/graphrag/ ；LightRAG：https://github.com/HKUDS/LightRAG ；qmd：https://github.com/tobi/qmd
- Khoj：https://github.com/khoj-ai/khoj ；AnythingLLM：https://github.com/Mintplex-Labs/anything-llm
- FSRS：https://github.com/open-spaced-repetition/fsrs4anki ；Obsidian Spaced Repetition：https://github.com/st3v3nmw/obsidian-spaced-repetition

> 附註：產品功能描述以 2026-10 前的公開資料為準，雲端產品的功能變動很快。mystudy 的現況以 commit `3208223` 的文件為準，沒有在 Neo 的真實 vault 上執行驗證。
