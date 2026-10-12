# 程式如何做 Wiki Ingest

預覽區的 Ingest 讀目前這則 vault 筆記，打兩次本機模型，組出可確認的 wiki 頁與 REVIEW。確認前不寫磁碟，也不在 `raw/sources/` 建 symlink。確認後才寫 wiki 頁、更新 `wiki/index.md`、append `wiki/log.md`，並把勾選的 REVIEW append 到 `wiki/review.md`。

輸出語言是繁體中文（台灣）。來源可以是英文、日文、簡體中文或其他語言。程式只把語言名填進 prompt，不做 OpenCC，也不濾簡體字。

沒有佇列、快取、分塊、多模態、LLM 合併或 Deep Research。一次 ingest 最多兩次模型呼叫。成功的深度研究會從 `wiki/review.md` 移除對應的那一段，見 `docs/programming/How-To-do-Deep-Research.md`。Ingest 本身仍不做深度研究。

左側檔案樹沒有右鍵選單。動作在預覽區按鈕。

## Vault 與 wiki

Vault 是 Obsidian 庫根（例：`第二大腦`）。Wiki root 是同時有 `raw/` 與 `wiki/index.md` 的目錄，可以是 vault 根，也可以是裡面的 `llm-wiki/`。Scaffold 仍建立 `vault/llm-wiki/`，並留下空的 `raw/sources/`。

`previewOffersIngest` 只對 vault 內、單一 `.md`、且不在 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/` 的檔為真。資料夾、symlink、wiki 頁、raw 都不提供這個按鈕。vault 本身是 wiki root 時，`Zettelkasten/Inbox/卡片盒筆記法.md` 仍可 ingest。

`linkIntoRawSource` 仍可建相對 symlink，測試也還鎖這件事。預覽按鈕不呼叫它。

## 呼叫鏈

Wiki 選單沒有 Ingest。Browse、Chat、Ask (keyword) 在 Wiki 選單。

1. 左側點一則檔案：`FileTreePane` → `StudyController.previewFile`。換檔會取消進行中的 ingest。
2. 右側 Ingest 按鈕在 `previewOffersIngest` 為真時可按。提示是 `Ingest reads this note, then asks before writing wiki pages.`
3. 按 Ingest：`ingestPreview` 只處理目前這一檔。沒有 wiki root 時開 Create wiki，訊息是 `Create a wiki root before ingest.`
4. 讀檔與兩次模型呼叫在 `Dispatchers.IO`。索引的 `resolve`、`findSourceForRaw`、`noteExists` 用 `runBlocking(wiki-index)`，而且只從 IO 執行緒呼叫。模型呼叫不佔 `wiki-index`。
5. 結果留在記憶體的 `DraftProposal`。`ConfirmWriteDialog` 出現後，使用者按 Write 才 `applyProposal`。Cancel 不寫。
6. 忙碌時動作列先 `Analyzing…`，再 `Generating…`。提案出來後是 `Confirm the pages before anything is written.`

讀取的上下文：

| 檔 | 規則 |
| --- | --- |
| 目前這則筆記 | 全文。空白就停，不打模型 |
| `wiki/schema.md` | 必需。缺失或空白就停，不打模型，也不建立它 |
| `wiki/purpose.md` | 可選。不建立 |
| `wiki/index.md` | 可截斷。不建立 |
| `wiki/overview.md` | 可選，只讀。不建立 |

`sources` frontmatter 寫的是 vault 相對路徑，斜線分隔，例如 `Zettelkasten/Inbox/卡片盒筆記法.md`。拒絕絕對路徑、`..` 與含冒號的路徑。

## 檔案地圖

| 職責 | 路徑 |
| --- | --- |
| 預覽按鈕 | `StudyScreens.kt` 的 `HomePane` |
| 狀態與兩次呼叫 | `StudyController.kt` 的 `ingestPreview`、`runIngest`、`confirmWrites` |
| Analyze / Generate | `wiki/ingest/IngestPipeline.kt` |
| FILE / REVIEW 解析、路徑守衛 | `wiki/ingest/IngestBlocks.kt` |
| 語言常數 | `IngestLanguage.NAME` |
| Analyze prompt | `ai-prompts/ingest-analyze.md` |
| Generate prompt | `ai-prompts/ingest-generate.md` |
| 讀 prompt | `wiki/prompt/PromptFiles.kt` |
| 確認對話框 | `StudyDialogs.kt` 的 `ConfirmWriteDialog` |
| 寫頁、index、log、review | `wiki/vault/ApplyWrites.kt` |
| `wiki/review.md` 條目 | `wiki/markdown/Catalog.kt` 的 `appendReviews` |
| 寫入守衛 | `wiki/vault/WriteGuard.kt` 的 `assertWikiWrite` |
| 行為測試 | `IngestBehaviorTest.kt`；symlink 與 `previewOffersIngest` 仍在 `WikiBehaviorTest.kt` |

`StudyMode` 沒有 Ingest。沒有 `IngestPane`。

## 兩次模型呼叫

兩次都是 `ChatRequest(..., json = false)`，沒有 `format: json`。Prompt 每次現讀。前言與 `<!-- -->` 不送出。

1. Analyze：`ingest-analyze.md`。空回覆就停，不打 Generate。
2. 從分析裡 `Page-worthy: yes` 的標題取最多 8 則既有頁摘錄。
3. Generate：`ingest-generate.md`。這是第二次，也是最後一次。

兩份 prompt 都要在第一次呼叫前讀到，而且區段齊全。Analyze 需要 `system`、`language`、`context-schema`、`context-purpose`、`context-index`、`context-overview`、`user`。Generate 需要 `system`、`language`、`user`、`existing`、`ambiguous`、`none`。

停下時不寫檔。訊息：

| 情況 | 訊息 |
| --- | --- |
| `num_ctx <= 4096` | `Context setting is too small. Nothing was sent.` |
| 筆記空白 | `This note is empty. Nothing was sent.` |
| 沒有 schema | `wiki/schema.md is missing. Nothing was written.` |
| prompt 檔讀不到 | `Prompt file ai-prompts/{name} ... Nothing was sent.` |
| 區段或佔位缺失 | `PromptException` 的句子 |
| Analyze 塞不進預算 | `This note is too big for one pass. Split it. Nothing was sent.` |
| 分析是空的 | `The analysis was empty. Nothing was written.` |
| Generate 塞不進預算 | `The generate prompt does not fit. Nothing was written.` |

超預算只縮小可選上下文，不切來源，也不打第三次。預算是 `min(40000, num_ctx - 4096)`。

Analyze：schema、purpose 各最多 8000 字；overview 最多 1500 字。index 從尾端縮到放得下，然後丢掉 overview，再丢掉 purpose。還是放不下就停，零次呼叫。

Generate：從後面丢掉既有頁摘錄。分析加來源加 schema 仍然超過就停。這時 Analyze 那一次已經打過，分析不存下來。

## 解析與路徑

Generate 的解析契約是整行標記：

```text
---FILE: wiki/concepts/標題.md---
---END FILE---
---REVIEW: contradiction | 標題---
---END REVIEW---
```

`OPTIONS:` 與 `SEARCH:` 是 REVIEW 正文裡的行。type 只准 `contradiction`、`duplicate`、`missing-page`、`suggestion`。

只有區塊裡面的 ` ``` ` 會切換 code fence。fence 裡的結尾標記留在正文。未關閉的區塊丟掉，警告 `Unclosed FILE discarded:` 或 `Unclosed REVIEW discarded:`。空白 FILE 是 `Skipped empty FILE`。區塊外的非空白文字只警告一次：`Ignored text outside FILE/REVIEW.`

路徑必須是相對路徑，沒有 `..`、反斜線、冒號，也不是絕對路徑。只准 `wiki/sources/`、`wiki/concepts/`、`wiki/entities/` 底下的 `名稱.md`。`index.md`、`overview.md`、`log.md`、`schema.md`、`purpose.md`、`review.md` 即使放在這三個目錄也拒絕。`raw/` 與 `wiki/queries/` 拒絕。

模型寫的 `wiki/sources/其他名字.md` 收成 `wiki/sources/{stem}.md`。stem 來自原檔名，經 `safeFileStem`。第二個 source FILE 警告 `Ignored extra source FILE`，只留第一個。

沒有合法 FILE 但分析不是空白：用分析當正文，做一頁可勾選的 source，仍要確認。有其他合法 FILE 但模型沒給 source 時，同樣補一頁，警告 `Added a source page from the analysis because the model did not emit one.`

## 提案

`buildIngestProposal` 在記憶體組頁，不寫檔。

| 情況 | 結果 |
| --- | --- |
| 這個 vault 路徑已有 source 頁（`findSourceForRaw`） | append 到那一頁 |
| `wiki/sources/{stem}.md` 不存在 | 新建 |
| 該路徑存在，而且 `sources` 引用這則筆記，或 `sources` 是空的且標題 stem 相符、type 是空或 source | append |
| 該路徑存在，但引用的是別則筆記 | `wiki/sources/{stem} (2).md` |
| concept / entity 標題唯一解析到一頁 | append 一節，不整頁覆寫 |
| 解析到多頁 | `selectable = false`，`newText` 保持舊文 |
| 解析不到 | 新建。檔名用標題，不用模型檔名。撞名加 ` (2)` |
| frontmatter `type` 是 entity 或 concept | 用那個目錄；否則用守衛後的目錄 |

既有頁先去掉模型 FILE 的 frontmatter，再 `appendSection`。`updated` 改成今天。不替換舊正文，也不合併舊 frontmatter。

新頁由程式重寫 frontmatter：`title`、目錄決定的 `type`、`created` 與 `updated` 為今天、`sources` 含這則 vault 相對路徑。保留模型的 aliases 與 tags。不加 `related` 欄。concept 與 entity 的正文 `ensureWikilink` 回 source 頁（`[[sources/{stem}]]`）。source 頁不自動連回 Inbox 筆記的檔名。

contradiction REVIEW 的標題若與某頁標題正規化後相同，或 `resolve` 唯一指到該頁，該頁 `conflict = true`，預設不勾。既有頁的註記是 `Conflict. Checking this appends a section and does not replace the page.` 新頁是 `Conflict. Checking this writes a new page.` 重複的 REVIEW 不會再取消勾選。

同一提案裡重複的相對路徑略過，警告 `Skipped duplicate page`。

Catalog 摘要是正文第一段非標題，壓成一行，最多 200 字。log 標題用 source 頁標題，否則用 stem。

## 確認與寫入

對話框是既有的 `Dialog` + `Surface`，不是 `AlertDialog`，也不是新視窗。Ask 與 Chat 的存檔仍用同一個對話框；它們的 reviews 是空的，所以不會寫 `wiki/review.md`。

畫面文字維持英文：

- 標題 `Confirm write`
- 勾選的頁會更新 index 並 append log
- 有 REVIEW 時說明會 append 到 `wiki/review.md`
- schema 警告、截斷說明、parser 警告
- Pages：核取方塊、路徑、註記、diff
- Reviews：核取方塊、`type | title`、OPTIONS、SEARCH、正文約 12 行（預覽去掉 OPTIONS / SEARCH 行）
- Write / Cancel

頁面預設勾選是 `selectable && !conflict`。四種 REVIEW 預設全勾。Write 在至少有一頁可寫或一則 REVIEW 被勾時才可按。

`applyProposal` 順序：勾選且可選的頁，然後 catalog 有變才寫 index，然後只要有頁或 REVIEW 就 append log，最後 append 勾選的 REVIEW。失敗就停，不回滾。`written` 列出已經落地的路徑。頁與 REVIEW 都是空的就什麼都不寫。

`wiki/review.md` 不存在時以 `# Review` 開頭。每一則：

```text
## [YYYY-MM-DD] type | title

模型正文（含 OPTIONS / SEARCH）

- source: vault/relative.md
```

只勾 REVIEW 時仍寫 review 與 log，不寫概念頁，index 也不會因為沒有頁而更新。

log 行是 `## [YYYY-MM-DD] ingest | {title}`。有 REVIEW 時細節多一行 `wiki/review.md`。

`assertWikiWrite` 允許 wiki 頁、`wiki/index.md`、`wiki/log.md`、`wiki/review.md`。仍拒絕 `wiki/schema.md`、`raw/` 與逃出 root 的路徑。

Ask 與 Chat 不讀 `wiki/review.md`，與 index、log、schema 同一組排除。索引仍會收錄這個檔，檢索 SQL 與 `includedAskPath` 把它濾掉。`wiki.read_page` 拒絕這條路徑。

## Prompt

改 `ai-prompts/ingest-analyze.md` 或 `ingest-generate.md` 後存檔，下一次 Ingest 就用新內容。`{{lang}}` 由 `IngestLanguage.NAME` 填入，值是 `Traditional Chinese (Taiwan, 繁體中文)`。這不是偏好，也不寫進 vault。

Analyze 的 system 佔位是 `{{languageRule}}`、`{{context}}`。context 依序接 schema、有內容才接 purpose、index 節錄非空才接 index、有內容才接 overview。

Generate 的 system 佔位是 `{{languageRule}}`、`{{schema}}`、`{{sourceStem}}`。user 是 `{{analysis}}`、`{{sourceName}}`、`{{sourcePath}}`、`{{raw}}`、`{{existing}}`。沒有既有頁時 `{{existing}}` 是 `(none)`。

標記字串改掉之後，這一輪可能沒有合法頁，只剩後援 source，而且仍要等人確認。

## Ollama

與 Ask、Chat 共用 `OllamaClient`：loopback、`/api/chat`、`think: false`。Ingest 不設 `format: json`。

| 常數 | 值 |
| --- | --- |
| `MAX_INPUT_CHARS` | 40000 |
| `RESERVE_TOKENS` | 4096 |
| `SCHEMA_CAP` | 8000 |
| `EXISTING_EXCERPT` | 1500 |
| 既有頁數量 | 8 |

## 與設計參考的差異

`docs/programming/ingest_design_ref.md` 第 1 步仍寫先建 symlink。實作不採用那一步。來源就是目前預覽的筆記。`sources` 是 vault 相對路徑，不是 `sources/{stem}.md`。舊頁若仍寫 `sources/x.md`，`findSourceForRaw` 對不上，可能另開 ` (2)`。

Scaffold 的 `wiki/schema.md` 種子仍描述 raw/sources symlink。Ingest 不改這個檔。

## 行為測試鎖住的契約

`IngestBehaviorTest.kt`：

- 兩次呼叫、`json = false`、確認前 wiki root 與 `raw/sources/` 與原文不變；確認後寫出繁中流程需要的 source、concept、entity、index、log、review
- context 太小、schema 缺失、prompt 檔或 system 區段缺失、空白筆記、來源超過預算：零次呼叫。分析空白：一次呼叫。Generate 仍超預算：一次呼叫，不寫檔
- parser：正常 FILE、未關閉區塊、fence 內的假結尾、空 FILE、不安全路徑、未知 REVIEW、四種 REVIEW 與多行 SEARCH
- source 路徑收成 `wiki/sources/{stem}.md`；新頁 `sources` 含 vault 相對路徑；concept / entity 連回 `[[sources/{stem}]]`
- 既有頁只 append；ambiguous 不可寫；contradiction 預設不勾
- 只寫 REVIEW 時有 review 與 log，沒有概念頁；既有 `# Review` 開頭還在；沒呼叫 apply 就沒有 review.md
- 送出的 system 含 `Traditional Chinese (Taiwan, 繁體中文)`，不含前言
- 沒有合法 FILE 時仍有一頁可勾選的後援 source，確認前不寫
- `wiki/review.md` 不進 Ask 檢索、`wiki.read_page` 拒絕它、Ask/Chat 存檔不新建 review 條目

`WikiBehaviorTest.kt` 仍鎖 symlink、`previewOffersIngest`、寫入守衛（`wiki/review.md` 允許，schema 與 raw 拒絕）與 Lexical Ask。`ChatAgentBehaviorTest.readPageRejectsUnsafePaths` 含 `wiki/review.md`。
