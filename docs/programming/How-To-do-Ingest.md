# 程式如何做 Wiki Ingest

目前預覽區的 Ingest 只做一件事：把 vault 裡的一則 Markdown 相對路徑 symbolic link 到 `{wikiRoot}/raw/sources/`。原始檔留在原處。目錄名對齊 [llm-wiki](https://github.com/nashsu/llm_wiki) 的 `raw/sources/`（複數）。這與之後會寫入的 wiki 頁 `{wikiRoot}/wiki/sources/` 是不同資料夾。

Extract claims 與 Draft wiki 頁的程式（`IngestPipeline`、`extractClaims`、`draftClaims`、確認對話框）仍保留，預覽 Ingest **不會**自動跑它們。沒有移植 llm-wiki 的 FILE block、purpose.md、overview.md、多模態或持久佇列。

左側檔案樹沒有右鍵選單。Compose Desktop 的 `ContextMenuArea` 與列上的 `clickable` 會搶同一下 pointer down，選單不會出現，因此改成預覽區按鈕。

## Vault 與 wiki

Vault 是 Obsidian 庫根（例：`第二大腦`）。Wiki root 是同時有 `raw/` 與 `wiki/index.md` 的目錄，可以是 vault 根，也可以是裡面的 `llm-wiki/`。發現順序是 `vault/llm-wiki`，再 `vault`。Scaffold 仍建立 `vault/llm-wiki/`。

Wiki **頁面**在 `{wikiRoot}/wiki/`（concepts、entities、sources、queries、index.md）。**來源**在 `{wikiRoot}/raw/`。`Zettelkasten/` 這類筆記與這兩個資料夾並列，屬於 vault，不是 wiki 頁面樹。

`isWikiManagedPath`（`WikiPaths.kt`）只把 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/`（含自身）當成 wiki 管理區。Ingest 排除這兩條路徑，**不是**排除整個 wiki root。vault 本身是 wiki root 時，`Zettelkasten/Inbox/卡片盒筆記法.md` 仍可 ingest。

檔案樹的 ` · wiki` 標在 `wiki/` 那一列，vault 根「第二大腦」不帶這個標。`wiki/` 與 `raw/` 底下的列著色。

## 呼叫鏈

Wiki 選單沒有 Ingest。Browse 與 Ask 仍在 `HomeScreen.kt` 的 `Wiki` 子選單。

1. 左側點一則檔案：`FileTreePane` → `StudyController.previewFile`。畫面切到 Home 預覽。
2. 右側第一行：`Ingest` 按鈕與檔名。`previewOffersIngest` 為真（vault 內、且不在 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/` 的 `.md`）才啟用。
3. 右側第二行：動作列，顯示 `ingestMessage` 與 busy。
4. 按 Ingest：`ingestPreview` 對**目前這一檔**呼叫 `linkIntoRawSource`，然後停。沒有 extract、沒有 Draft、沒有資料夾批次。
5. 沒有 wiki root 時開 Create wiki。檔案不能 ingest 時第二行說明原因。
6. 已有指向同一檔的 link 時重用，動作列寫 `Already linked at raw/sources/…`。

## 檔案地圖

| 職責 | 路徑 |
| --- | --- |
| 檔案樹選檔、`wiki/` 與 `raw/` 著色 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/FileTreePane.kt` |
| wiki root 判定、`isWikiManagedPath` | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/WikiPaths.kt` |
| 預覽第一行 Ingest、第二行動作列 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/StudyScreens.kt` 的 `HomePane` |
| 收集目標、列出 raw | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/VaultListing.kt` |
| 相對路徑 symlink | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/RawStaging.kt` 的 `linkIntoRawSource` |
| 新 link 路徑守衛 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/WriteGuard.kt` 的 `assertNewRawFile` |
| 建立 `raw/sources/` | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/WikiScaffolder.kt` |
| 狀態、預覽 Ingest（只 link） | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/study/StudyController.kt` |
| Extract / Draft prompt 與提案 | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ingest/IngestPipeline.kt` |
| 確認後寫入 index / log | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/vault/ApplyWrites.kt` |
| Ollama、`json = true`、budget | `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/wiki/ollama/OllamaClient.kt` |
| 畫面 | `HomePane` 預覽；`IngestPane` 仍在但預覽流程不會切過去 |
| 行為測試 | `composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/WikiBehaviorTest.kt` |

## 預覽按鈕到 symbolic link

`ingestTargets` 只收 vault 內、且不在 `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/` 的 `.md`。預覽 Ingest 只處理目前打開的那一檔。目標本身是 symlink 時略過。

`linkIntoRawSource(wikiRoot, source)`：

- 目標：`{wikiRoot}/raw/sources/{檔名}.md`
- wiki root 是 vault 裡的 `llm-wiki/` 時：

```
llm-wiki/raw/sources/卡片盒筆記法.md
  -> ../../../Zettelkasten/Inbox/卡片盒筆記法.md
```

- wiki root 就是 vault（根目錄已有 `raw/` 與 `wiki/index.md`）時：

```
raw/sources/卡片盒筆記法.md
  -> ../../Zettelkasten/Inbox/卡片盒筆記法.md
```

- 相對路徑 link，vault 整夾搬移後仍指向同一則筆記
- 不複製、不移動、不改原始檔
- 已有指向同一 `toRealPath()` 的 link 就重用
- 不同來源同檔名則加 ` (2)`、` (3)`
- 禁止覆寫既有一般檔
- `{wikiRoot}/wiki/` 與 `{wikiRoot}/raw/` 裡的檔不可 ingest

`raw/sources/` 在 scaffold 時建立。既有 vault 第一次 link 時 `createDirectories`。不搬移舊的 `raw/source/`。

檔案樹用 `isWikiManagedPath`（lexical）判斷一列是否在 `wiki/` 或 `raw/`，所以 `raw/sources/` 底下的 link 會當成 wiki 列（有色、Ingest 關閉），即使真實檔在 Inbox。`listVaultChildren` 會顯示目標落在 vault 內的檔案 symlink；目錄 symlink 仍跳過。

`listMarkdownUnder` 與 `WikiIndex.walkMarkdown` 收入這些 link，相對路徑是 link 自己的路徑（`raw/sources/….md`）。讀檔走 `Files.newInputStream`，跟著 link 讀原文。Ask 仍不把 `raw/` 送進 prompt。

## 兩步演算法（程式保留，預覽 Ingest 不呼叫）

對齊 llm-wiki：先分析來源，再生成 wiki 頁。輸出是 JSON claims / JSON draft，本地程式負責路徑、append、catalog、log。`IngestPane` 的 Extract / Draft 仍可呼叫這些函式。預覽 Ingest 目前停在 symlink。

### 1. Extract

`IngestPipeline.extract(rawText, numCtx)`。`num_ctx <= 4096` 或原文加 system 超過字元預算則停下，不打模型。

`ChatRequest(EXTRACT_SYSTEM, rawText, json = true)`。

`keepVerbatim` 丢掉 excerpt 沒有完整出現在原文裡的項。沒有任何 verbatim claim 則停下，不寫檔。

Controller 把結果列成可取消勾選的 claims。這一步由 `extractClaims` 觸發，預覽 Ingest 不會自動跑。

### 2. Draft

`IngestPipeline.draft(index, rawRelative, claims, numCtx, today)`。沒勾選、context 太小、或沒有 `wiki/schema.md` 則停下。

User 訊息 `buildDraftUser`：

```
## schema.md
{schema 前 8000 字}

## claims
- title: {title} type: {type}
  {statement}

## existing pages
## {path}
title: {title}
{body 前 1500 字}
```

既有頁最多 8 則，只含 title 解析到唯一 concept/entity 的 claim。超預算就從後面丢掉既有頁。仍超則 `Stopped`，不打模型。

`ChatRequest(DRAFT_SYSTEM, user, json = true)`。回傳去掉 code fence 後當 JSON。`buildIngestProposal` 在本地組提案：

| 情況 | 寫到哪 |
| --- | --- |
| 這個 raw 還沒有 source 頁 | 新建 `wiki/sources/{stem}.md` |
| 已有 source 頁 | append 一節 |
| 標題解析到一頁 | append 一節（conflict 只加註，不整頁覆寫） |
| 標題找不到 | 新建 `wiki/concepts/` 或 `wiki/entities/` |
| 標題對到多頁 | `selectable = false`，不寫 |

新頁的 `sources` frontmatter 指向這則 raw（`raw/sources/….md` 去掉 `raw/` 前綴）。正文用 `ensureWikilink` 連回 source 頁。衝突頁預設不勾選。

### 3. 確認寫入

`applyProposal` 只寫勾選且 `selectable` 的頁，然後更新 `wiki/index.md` catalog，最後 append `wiki/log.md`：`## [YYYY-MM-DD] ingest | {title}`。失敗就停，已寫的列在結果裡。`wiki/schema.md` 與 `raw/` 不能經這條路徑覆寫。

## 送給模型的 prompt

常數在 `IngestPipeline.kt`。Ask 用純文字（`json = false`）；Ingest 兩步都是 `json = true`。

### Extract system：`EXTRACT_SYSTEM`

```
You extract claims from one source note. Use only that note.
Return JSON: {"claims":[{"statement":"","excerpt":"","suggestedType":"concept|entity|","suggestedTitle":"","aliases":[]}]}
The excerpt must be copied verbatim from the note.
Set suggestedType to concept or entity only when that idea is actually in the note. Otherwise use an empty string.
Do not invent claims. Keep titles in the note's language.
JSON only.
```

User 就是 raw 全文（已通過字元預算）。

### Draft system：`DRAFT_SYSTEM`

```
You draft wiki sections from the accepted claims and the existing page excerpts. Do not add ideas that are not in the claims.
Return JSON: {"sourceSummary":"","sourceBody":"","pages":[{"title":"","type":"concept|entity","summary":"","section":"","conflict":false,"conflictNote":"","aliases":[],"tags":[]}],"logTitle":""}
Include one pages item for each claim whose type is concept or entity. Use that claim's title.
Set conflict true when the claim contradicts an existing excerpt. Do not rewrite the existing page.
JSON only.
```

模型不可自己指定檔案路徑。路徑由 `buildIngestProposal` 用 alias 解析與 `allocatePath` 決定。

## Ollama 與 budget

與 Ask 共用 `StudyController.client()` → `OllamaClient`（loopback `/api/chat`、`num_ctx`、`think: false`）。Ingest 多設 `format: json`。

| 常數 | 值 | 用途 |
| --- | --- | --- |
| `PromptBudget.MAX_INPUT_CHARS` | 40000 | 輸入字元上限 |
| `PromptBudget.RESERVE_TOKENS` | 4096 | `num_ctx` 必須大於這個值 |
| `PromptBudget.SCHEMA_CAP` | 8000 | draft 裡 schema 節錄 |
| `PromptBudget.EXISTING_EXCERPT` | 1500 | 每則既有頁 |
| 既有頁數量 | 8 | `existingExcerpts` |

## 與 llm-wiki 的對照

| llm-wiki | 這裡 |
| --- | --- |
| 匯入到 `raw/sources/` | `{wikiRoot}/raw/sources/` 的相對路徑 symlink |
| Step 1 Analysis | `extract`：claims JSON |
| Step 2 Generation（FILE block 寫 wiki） | `draft`：JSON 小節 + 本地 `buildIngestProposal` |
| 佇列自動寫入 | 確認對話框之後才寫 |
| purpose.md / overview.md | 沒有；schema.md 進 draft user |

## 行為測試鎖住的契約

`WikiBehaviorTest.kt`：

- `previewOffersIngestOutsideTheWikiRoot`：nested `llm-wiki` 時 Inbox `.md` 可 ingest；wiki / raw 與資料夾不行
- `vaultAsWikiRootLeavesInboxIngestible`：wiki root 等於 vault 時，`Zettelkasten/Inbox/*.md` 可 ingest、建相對 symlink；`wiki/` 與 `raw/` 下不可
- `stagingLinksWithoutCopyingOrOverwritingTheOriginal`：建的是 symlink、原文不變、同名不同內容加後綴、同一來源重用、索引看得到 `raw/sources/`
- `ingestTargetsSkipTheWikiTree`：`wiki/` 內的檔不會進 ingest
- `writeGuardRejectsSchemaRawAndEscape`：不能經寫入通道改 schema 或覆寫 raw
- `badExcerptsAreDroppedAndConflictsStayOutOfTheWrite`：excerpt 必須原文出現；conflict 預設不寫
- `ambiguousTitlesAreNotWritable`：標題對到多頁則不寫
