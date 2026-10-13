# Note Editor 設計參考（Obsidian 式 檢視／複製／編輯／存檔）

> 目標位置：`docs/programming/note_editor_design_ref.md`
> 依據：mystudy `b7d2502`（Version 1.0.0 : Wiki Lint，2026-10-13）。本文件只做分析與設計，不含實作。
> 文件與程式衝突時，以程式與測試為準。實作契約見 `docs/programming/How-To-do-Note-Editor.md`。
> §15 的建議預設已核准採用。

## 1. 目標

在檔案樹點檔案後，右側預覽區（`HomePane`）從「唯讀純文字」變成像 Obsidian 的筆記檢視器：

- **閱讀模式**：渲染 Markdown、可選取複製、一鍵複製全文。
- **編輯模式**：原始碼（source mode）編輯，等寬字，undo/redo。
- `Ctrl+E` 切換、`Ctrl+S` 存檔、dirty 標示、離開前確認、外部修改偵測與衝突處理、原子寫入、存檔後重建索引。
- 寫入範圍受新守門函式管制，不碰 `raw/`、`wiki/schema.md`。

使用者偏好**一次交付**；§13 仍列出可分階段的切點，供需要時退守。

## 2. Obsidian 行為摘要（附出處）

標記：✅ 官方 help 已查證；⚠️ 僅第三方來源（論壇／外掛／部落格）；❓ 未查證。

| 項目 | Obsidian 行為 | 狀態 | 出處 |
|---|---|---|---|
| 兩種 view | Reading view（不顯示 Markdown 語法）與 Editing view | ✅ | [Views and editing mode](https://help.obsidian.md/edit-and-read) |
| 編輯模式 | Editing view 內分 Live Preview（行內格式化、游標進入時顯示語法）與 Source mode；預設 Live Preview，可在 Settings → Editor → Default editing mode 改 | ✅ | 同上 |
| 切換快捷鍵 | `Ctrl+E`（macOS `Cmd+E`），或狀態列圖示，或命令「Toggle Reading view」 | ✅ | 同上 |
| 同時開兩種 view | 按住 Ctrl 點 view switcher | ✅ | 同上 |
| 編輯快捷鍵 | Copy/Cut/Paste、`Ctrl+Z` undo、`Ctrl+Shift+Z` 或 `Ctrl+Y` redo、無選取時 `Ctrl+C` 複製段落、`Ctrl+A` 全選 | ✅ | [Editing shortcuts](https://help.obsidian.md/editing-shortcuts) |
| 閱讀模式選取複製 | Reading view 是渲染後的 HTML，可用滑鼠選取並複製 | ❓（官方頁未明寫，屬常識） | — |
| 外部修改 | 「notes are plain text files… Obsidian automatically refreshes your vault to keep up with any external changes」 | ✅ | [How Obsidian stores data](https://help.obsidian.md/data-storage) |
| 外部修改 vs 未存編輯 | 自 0.11.6 起自動 merge 並顯示通知；有論壇回報 merge 掉行或重複 | ⚠️ | [Jotura guide](https://jotura.io/guides/obsidian-vault-with-claude-code/) |
| 自動存檔 | 開始輸入約 2 秒後寫入，之後每 2 秒存一次直到沒有變更；沒有必須的手動存檔，`Ctrl+S`／Save file 命令會立即存 | ⚠️（官方 help 未找到明文） | [Autosave Control 論壇帖](https://forum.obsidian.md/t/plugin-autosave-control-delay-automatic-saves-keep-manual-saves-immediate/104651)、[外掛 README](https://github.com/mihasm/obsidian-autosave-control/blob/main/README.md) |
| 關閉未存 | 原生自動存檔所以通常無「未存」概念；外掛停用自動存檔時才跳警告 | ⚠️ | 同上 |
| 版本快照 | File recovery 核心外掛：預設每 ≥5 分鐘一份快照、保留 7 天，存在 vault 外的全域設定資料夾 | ✅ | [File recovery](https://help.obsidian.md/plugins/file-recovery) |
| Properties | frontmatter 以 Properties UI 編輯，型別 Text/List/Number/Checkbox/Date/Date & time/Tags；`Ctrl+;` 新增；巢狀屬性建議用 source mode；顯示可設 Visible/Hidden/Source | ✅ | [Properties](https://help.obsidian.md/properties) |
| `[[` 自動完成 | 編輯器輸入 `[[` 出現檔案建議；`[[#` 標題、`#^` 區塊 | ✅ | [Internal links](https://help.obsidian.md/links) |
| 分頁 | 多分頁、`Ctrl+T` 新分頁、Ctrl+點連結開新分頁、釘選 | ✅ | [Tabs](https://help.obsidian.md/tabs) |
| 分頁的未存標示 | 因自動存檔，原生無 dirty 圓點 | ❓ | — |

**對 mystudy 的取捨**：Obsidian 靠「無感自動存檔＋自動 merge」。mystudy 的 vault 同時被 Obsidian、Ingest、深度研究、Lint 寫入，自動 merge 有已知掉行風險（⚠️），所以本設計採**明確存檔＋存前比對＋衝突對話框**，自動存檔列為選項（§12 決策 D1）。

## 3. mystudy 現況（`b7d2502`）

路徑前綴 `composeApp/src/desktopMain/kotlin/com/neojou/mystudy/`。

- **檔案樹**：`study/FileTreePane.kt` `FileTreePane` 以 vault 為根列出全部檔案，點檔呼叫 `controller::previewFile`；`previewOffersIngest` 判斷 Ingest 鈕。契約：樹沒有右鍵選單，動作放預覽區按鈕（AGENTS.md）。
- **右側預覽**：`study/StudyScreens.kt` `HomePane`（約 L55–126）：上排 `Ingest` 按鈕＋標題，下排狀態列，內容是
  `Text(controller.previewBody, …verticalScroll(…))` —— **純文字、未渲染 Markdown、沒有 SelectionContainer，所以目前連複製都不行**。
- **狀態**：`study/StudyController.kt` `previewPath / previewTitle / previewBody`（L136–141）；`previewFile(path)`（L449）取消 Ingest、在 IO 讀 `readPreview`；`readPreview`（L1427）用 `readCapped(path, 100_000)`，超長附加 `"\n… truncated"`，讀失敗回 `"Cannot read this file as UTF-8."`（錯誤字串和內容混在同一欄位）。
- **可重用元件**：`study/MarkdownText.kt` `MarkdownText(markdown)`（簡易區塊＋inline 渲染，Ask 已用）；`StudyScreens.kt` `CopyableScroll`（SelectionContainer＋捲軸）。
- **寫入工具**：`wiki/vault/AtomicWrite.kt` `atomicWrite(target, text)`：同目錄 `*.mystudy-tmp` → `ATOMIC_MOVE+REPLACE_EXISTING`，UTF-8。`wiki/vault/TextFiles.kt` `readCapped`。
- **WriteGuard**：`wiki/vault/WriteGuard.kt` `assertWikiWrite(root, relative)` 只允許 `wiki/{sources,concepts,entities,queries}/*.md`（恰三段）、`wiki/index.md`、`wiki/log.md`、`wiki/review.md`；拒 `wiki/schema.md`；`assertNewRawFile` 只建新 symlink。**vault 中 wiki root 外的使用者筆記（如 `Zettelkasten/`）目前沒有任何寫入通道。**
- **索引**：`wiki/index/WikiIndex.kt` `reindexRelative(relative)`、`reconcileAll()`、`contentHash(relative)`；`wiki/index/WikiWatcher.kt` 監看 wiki root，`StudyController.startWatcher` 300 ms debounce 後在 `dbDispatcher` reindex 並更新 `summaries`。
- **契約**（AGENTS.md）：寫入通道不改 `wiki/schema.md`、不覆寫 `raw/`；SQLite 只在 `wiki-index` dispatcher；偏好不寫 vault；名稱版號用 `./configure.sh`；改行為同時改 How-To 與 `*BehaviorTest`。
- **版號**：`gradle.properties` `app.version=1.0.0`。
- **測試樣式**：`composeApp/src/desktopTest/.../wiki/*BehaviorTest.kt`，暫存 vault（`scaffoldWiki`）、`MemorySettingsStore`、`kotlin.test`。

### llm-wiki 參考（簡述）
`/workspace/llm_wiki` 有編輯器：`src/components/editor/wiki-editor.tsx`（Milkdown WYSIWYG）、`frontmatter-panel.tsx`、`file-history-panel.tsx`；`src/components/layout/preview-panel.tsx` 以 **1000 ms debounce 自動存檔**、`immediate` 選項立即寫、並以 `lastLoadedRef` 跳過「內容等於上次載入」的 no-op 存檔（避免把 placeholder 寫回）。未見存前外部修改比對。

## 4. 範圍

**做**：閱讀模式（渲染＋複製）、編輯模式（source）、切換、存檔、dirty、離開確認、外部修改偵測、衝突對話框、原子寫入、編碼／換行保留、守門、存後 reindex、undo/redo、大檔／非文字唯讀。
**不做**（本輪）：Live Preview、Properties UI、`[[` 自動完成、多分頁、新建／改名／刪除檔案、快照歷史、自動 merge、模型呼叫、網路。

## 5. 可編輯範圍（新守門 `assertNoteEdit`）

新增於 `wiki/vault/WriteGuard.kt`（不改 `assertWikiWrite` 語意）：

```kotlin
sealed interface EditPermission { data object Editable : EditPermission; data class ReadOnly(val reason: String) : EditPermission }
fun noteEditPermission(vault: Path, wikiRoot: Path?, file: Path): EditPermission
fun assertNoteEdit(vault: Path, wikiRoot: Path?, file: Path): Path   // 不通過就 error("Blocked path: …")
```

判定（依序，任一命中即 ReadOnly）：
1. 不在 vault 內（canonical 比對）、或本身是 symlink、或任一父層 canonical 離開 vault → 唯讀。
2. 副檔名不在 `md, markdown, txt`（D3）→ 唯讀「非文字檔」。
3. 位於 `{wikiRoot}/raw/` 之下（lexical，含 `raw/sources/` symlink）→ 唯讀「raw/ 不可覆寫」。
4. `{wikiRoot}/wiki/schema.md` → 唯讀。
5. 任一路徑段以 `.` 開頭（`.obsidian/`、`.git/` 等）→ 唯讀。
6. `{wikiRoot}/wiki/index.md|log.md|review.md` → 依 D2（建議：可編輯但顯示「此檔由 Ingest/Lint/深度研究維護」警示）。
7. 檔案不存在（只編輯既有檔，不建新檔）→ 唯讀。
8. 讀取被截斷（>100 000 字元）或含無法解碼的位元組 → 唯讀「檔案過大／非 UTF-8」。

其餘：`wiki/` 頁與 vault 中 wiki root 外的筆記 → 可編輯。
AGENTS.md 要明寫：這是**使用者手動編輯**的寫入通道，與 `assertWikiWrite`（自動流程）分開。

## 6. UI 設計（`HomePane` 預覽區）

```
┌ [Ingest] 標題.md ●(未存)                [閱讀|編輯] [複製全文] [存檔] ┐
│ 狀態列：已存 19:20 ／ 未儲存變更 ／ 唯讀：raw/ 不可覆寫 ／ ⚠ 檔案已在外部變更 [重新載入] │
├──────────────────────────────────────────────────────────────┤
│ 閱讀：CopyableScroll { MarkdownText(body) }                       │
│ 編輯：BasicTextField(TextFieldState, FontFamily.Monospace) + 捲軸   │
└──────────────────────────────────────────────────────────────┘
```

- **閱讀模式**（預設，D4）：`CopyableScroll { MarkdownText(text) }`；frontmatter 以等寬灰框原樣顯示（不做 Properties UI）。「複製全文」用 `LocalClipboardManager.setText(AnnotatedString(rawText))`，複製的是**原始 Markdown**。
- **編輯模式**：Compose Foundation `BasicTextField(state = TextFieldState)`（新 API 內建 `UndoState`，支援 `Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y`；若專案 Compose 版本不支援則退用 `TextFieldValue` 並自做 undo 堆疊，計畫須確認版本）。等寬字、不換行選項（D7，建議換行）。
- **快捷鍵**：在預覽區根 `Modifier.onPreviewKeyEvent`：`Ctrl/Cmd+E` 切換、`Ctrl/Cmd+S` 存檔（只在編輯模式且 dirty）。用 `isCtrlPressed || isMetaPressed`。
- **dirty 標示**：標題後「●」＋狀態列「未儲存變更」；視窗標題可選（D8）。
- **唯讀**：編輯鈕 disabled，tooltip／狀態列顯示 `ReadOnly.reason`。非文字檔顯示「無法預覽此檔案類型」，不把錯誤字串塞進內容欄。
- 樹選取高亮仍跟 `previewPath`。

## 7. 狀態模型（`study/NoteEditorState.kt` 或放在 controller 的獨立類別）

```kotlin
enum class NoteViewMode { Reading, Editing }
data class DiskSnapshot(val sha256: String, val mtime: FileTime, val size: Long, val lineEnding: LineEnding, val bom: Boolean)
enum class LineEnding { LF, CRLF }
sealed interface SaveState { Clean; Dirty; Saving; Saved(at); Failed(msg); Conflict(disk: String, diskSnap: DiskSnapshot) }
sealed interface PendingNav { data class OpenFile(val path: Path); data class SwitchMode(val mode: StudyMode); data object CloseWindow }

class NoteEditor(…) {
  var path: Path?; var permission: EditPermission; var mode: NoteViewMode
  var loadedText: String          // 已正規化為 LF、去 BOM
  val editState: TextFieldState
  var base: DiskSnapshot?
  var saveState: SaveState
  var externalChange: Boolean     // 乾淨時自動重載；dirty 時只顯示橫幅
  var pendingNav: PendingNav?     // 非 null 時顯示「未儲存」對話框
  val dirty: Boolean get() = editState.text.toString() != loadedText
}
```

狀態轉移：

```
(none) --open(path)--> Reading[Clean]
Reading --Ctrl+E/編輯鈕 (Editable)--> Editing[Clean]
Editing[Clean] --輸入--> Editing[Dirty] --輸入回原文--> Editing[Clean]
Editing[Dirty] --Ctrl+S--> Saving --ok--> Editing[Clean, Saved]
                           Saving --磁碟已變--> Conflict --(重新載入|覆寫|取消)-->
                           Saving --IO錯誤--> Failed（保留文字，仍 Dirty）
Editing --Ctrl+E--> Reading（dirty 仍保留；閱讀模式渲染編輯中的文字並標「未儲存」）
任何狀態[Dirty] --點別的檔/切 StudyMode/關窗--> pendingNav 對話框：[存檔] [捨棄] [取消]
外部變更事件: Clean → 自動重載；Dirty → externalChange=true 橫幅 [重新載入(捨棄我的)] [比較…]
```

## 8. 讀取演算法

`loadNote(path)`（IO dispatcher）：
1. `permission = noteEditPermission(...)`；非文字檔直接回 `Unsupported`。
2. `bytes = Files.readAllBytes`（大小 > 1 MB 先只讀 `readCapped` 並唯讀，D6）。
3. 以 `CharsetDecoder`（`REPORT` 模式）解碼 UTF-8；失敗 → 唯讀「非 UTF-8」、閱讀模式顯示訊息。
4. 記 `bom = 開頭 EF BB BF`，去 BOM。
5. `lineEnding = if 含 "\r\n" 且不含孤立 "\n" then CRLF else LF`；混合視為 LF 並在狀態列提示（存檔會統一，D5）。
6. 編輯器內文字一律 LF。
7. `base = DiskSnapshot(sha256(bytes), mtime, size, …)`。
8. 以 `generation` 計數防止慢讀回來覆蓋已切換的檔（沿用 `ingestGeneration` 的作法）。

## 9. 存檔與衝突演算法

`saveNote(force = false)`（IO dispatcher；同一時間只有一個存檔 job，`Mutex`）：
1. `target = assertNoteEdit(vault, wikiRoot, path)`（每次存都重檢，路徑可能已變 symlink）。
2. 文字未變（`text == loadedText`）→ 不寫，直接 Clean（同 llm-wiki 的 no-op 防護）。
3. 重讀磁碟：檔案不存在 → `Conflict(deleted)`；`sha256(現在) != base.sha256` 且 `!force` → `Conflict(diskText)`，**不寫**。（以內容 hash 為準，mtime 只作快速路徑：mtime 與 size 都相同時可略過 hash，D9。）
4. 還原格式：`out = if CRLF then text.replace("\n","\r\n") else text`；`bytes = (if bom) EF BB BF + out.utf8`。
5. `atomicWrite(target, …)`（擴充為接受 `ByteArray`，或新增 `atomicWriteBytes`；保留現有 `.mystudy-tmp` 慣例，失敗刪 temp）。
6. `base = snapshot(新 bytes, 新 mtime)`；`loadedText = text`；`SaveState.Saved(now)`。
7. 若 target 在 `{wikiRoot}/wiki/` 下：在 `dbDispatcher` 呼叫 `index.reindexRelative(relative)` 並更新 `noteCount/summaries`（不等 watcher；watcher 之後再觸發也冪等）。wiki root 外的筆記不在索引，不 reindex。
8. 自己寫入造成的 watcher／外部偵測事件：比對 hash == `base.sha256` 即忽略（不跳「外部變更」）。

**衝突對話框**（`StudyDialogs.kt` 新 `NoteConflictDialog`）：
- 「重新載入磁碟版本」：捨棄我的修改（先把我的文字放剪貼簿，並提示）。
- 「用我的版本覆寫」：`saveNote(force = true)`。
- 「取消」：保持 Dirty，什麼都不寫。
- 檔案已被刪除：選項改為「重新建立並寫入」（仍經 `assertNoteEdit` 的父目錄檢查）/「取消」。
不做自動 merge（Obsidian 的 merge 有掉行回報）。

**外部修改偵測**：
- wiki root 內：沿用 `WikiWatcher` 事件，在 debounce callback 後若 `relative` 等於開啟中檔案 → `checkExternal()`。
- wiki root 外的 vault 筆記：Watcher 不涵蓋 → 視窗取得焦點時、切回閱讀/編輯時、存檔前比對 hash（D10：是否另開一個只看單檔目錄的 WatchService）。
- `checkExternal()`：hash 不同 → Clean 則自動重載（保留捲動位置盡量）、Dirty 則顯示橫幅。

**離開保護**：`previewFile`、`StudyPane` 的 `onMode`、Ingest 按鈕、視窗關閉（`AppWindow` 的 `onCloseRequest`）先問 `editor.requestLeave(nav)`；dirty 時設 `pendingNav` 顯示對話框「存檔／捨棄／取消」。存檔失敗或進入 Conflict 時不繼續導覽。

**自動存檔（選項，D1）**：若開啟，輸入停止 2 s（debounce，對齊 Obsidian 約 2 秒）後呼叫 `saveNote(force=false)`；遇 Conflict 停止自動存檔直到使用者處理。偏好存 `Preferences` 鍵 `noteAutosave`（不寫 vault）。

## 10. 與既有功能的互動

- **Ingest**：Ingest 讀的是磁碟內容。dirty 時按 Ingest → 先問存檔（同離開保護）。Ingest 寫入的 `wiki/` 頁若正開著且 Clean → 自動重載。
- **Lint／深度研究** 寫 `review.md`／`index.md`／`log.md` 時，若使用者正在編輯同檔 → 存檔時進入 Conflict，不會靜默覆蓋。
- **Chat／Ask** 讀索引；存後 reindex 讓它們看到新內容。
- `openIndexed`（Browse 用 `openNote`）不在本輪改；計畫可評估是否也導到 NoteEditor（D11）。

## 11. AGENTS.md / 文件變更

AGENTS.md「先讀」加：`改筆記檢視／編輯：docs/programming/How-To-do-Note-Editor.md、docs/programming/note_editor_design_ref.md`。
契約新增一條（草稿）：

> - 預覽區的筆記編輯（閱讀／編輯切換 `Ctrl+E`、`Ctrl+S` 存檔）是唯一的**手動**寫入通道，只經 `assertNoteEdit`：只改 vault 內既有的 `.md/.markdown/.txt`，不改 `raw/`、`wiki/schema.md`、點開頭目錄、symlink，不建新檔、不刪檔。存檔前比對載入時的 SHA-256，磁碟已變就跳衝突對話框，不自動 merge、不靜默覆寫。寫入走 `atomicWrite`，保留 UTF-8 BOM 與 CRLF/LF。`wiki/` 下的檔存後在 `wiki-index` dispatcher 呼叫 `reindexRelative`。不打模型、不連網。自動存檔預設關閉，偏好存 Preferences。見 `docs/programming/How-To-do-Note-Editor.md`。

並修改：「寫入通道不改 `wiki/schema.md`…」一句補「（含手動編輯）」；測試列表加 `Note Editor：NoteEditorBehaviorTest`；`docs/Architecture.md` 寫入邊界段加 `assertNoteEdit`、`wiki/vault/` 職責；`FileTreePane.kt` 檔頭註解。
新增 `docs/programming/How-To-do-Note-Editor.md`：使用方式、可編輯範圍表、快捷鍵、衝突處理、存檔格式保留、測試方式、常見問題（為何不能編輯 raw/）。

## 12. 測試：`NoteEditorBehaviorTest`

放 `composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/`，邏輯抽成可測的純函式／非 UI 類別（`loadNote`、`saveNote`、`noteEditPermission`、`NoteEditor` 狀態）。

1. 權限：`wiki/concepts/a.md`、vault 內 `Zettelkasten/b.md` 可編輯；`raw/sources/x.md`、`raw/` symlink、`wiki/schema.md`、`.obsidian/app.json`、`.png`、vault 外、symlink 檔、`../` 逃逸 → 唯讀；`assertNoteEdit` 對唯讀路徑丟例外。
2. 往返：載入→不改→存 → 不寫（mtime 不變）。
3. 修改存檔：位元組＝預期，`.mystudy-tmp` 不殘留。
4. CRLF 檔改一行後存 → 仍全 CRLF；BOM 保留；無結尾換行的檔不被加換行。
5. 衝突：載入後外部改檔 → `save()` 回 Conflict 且磁碟仍是外部版本；`force` 後是我的版本。
6. 外部刪檔 → Conflict(deleted)。
7. 外部變更：Clean 自動重載；Dirty 保留文字並標記 externalChange。
8. 自己存檔後的 watcher 事件不視為外部變更。
9. `wiki/` 下存檔後 `WikiIndex.load(relative).body` 為新內容、`linksFrom` 更新；wiki root 外不觸 index。
10. 超過大小上限／非 UTF-8 → 唯讀且不可存。
11. dirty 時 `requestLeave` 回需要確認；存檔失敗不導覽。
12. 既有 `WikiBehaviorTest / IngestBehaviorTest / ChatAgentBehaviorTest / DeepResearchBehaviorTest / WikiLintBehaviorTest` 全過。

UI（`Ctrl+E/S`、對話框）以手動驗收清單補充，寫在 How-To。

## 13. 實作順序（一次交付，但內部可切）

1. `WriteGuard.kt` `noteEditPermission/assertNoteEdit`＋測試 1。
2. `wiki/vault/NoteFiles.kt`：`loadNote / saveNote / DiskSnapshot`、`atomicWrite` bytes 版＋測試 2–6、10。
3. `study/NoteEditor.kt` 狀態類別＋測試 7、8、11。
4. `StudyController` 接線（取代 `previewBody` 的讀取；reindex）＋測試 9。
5. `HomePane` UI、快捷鍵、對話框（`StudyDialogs.kt`）、離開保護（`StudyPane`、`AppWindow`）。
6. 文件、AGENTS.md、`./configure.sh version 1.1.0`。

若需分階段：Phase A＝閱讀模式渲染＋複製（第 5 步的一部分，零寫入風險）；Phase B＝編輯＋存檔＋衝突；Phase C＝自動存檔／Live Preview／`[[` 自動完成／Properties。

## 14. 版號

新功能 → `./configure.sh version 1.1.0`（同步 `gradle.properties` 的 `app.version` 與 `AppVersion.NAME`，見 `How-To-Change_Version_number.md`）。Commit 訊息沿用慣例：`Version 1.1.0 : Note Editor`。

## 15. 待決策

以下建議預設已核准採用。

- **D1** 自動存檔：預設關閉＋`Ctrl+S`（建議）／預設開啟 2 s debounce（Obsidian 式）／不提供。核准採用：預設關閉。Settings 可開，停止編輯 2 秒後存。`Ctrl/Cmd+S` 一律立刻存。
- **D2** `wiki/index.md`、`log.md`、`review.md` 是否允許手動編輯（建議可編，顯示警示）。核准採用：可編，警示「此檔由 Ingest、Lint 或深度研究維護」。`schema.md` 維持唯讀。
- **D3** 可編輯副檔名：`md, markdown, txt`？是否加 `canvas`/`json`（建議不加）。核准採用：只有這三個，不分大小寫。不加 `canvas` 或 `json`。
- **D4** 開檔預設模式：閱讀（建議）／記住上次模式（存 Preferences）。核准採用：一律先閱讀，不記上次模式。
- **D5** 混合換行的檔：存檔統一成 LF 或 CRLF 多數者，或混合檔唯讀。核准採用：有修改才依多數統一，平手用 LF。沒改不重寫。
- **D6** 大檔門檻：沿用 100 000 字元唯讀，或提高到 1 MB 並可編輯。核准採用：1_000_000 bytes 以下且為 UTF-8 才可編。
- **D7** 編輯器自動換行預設。核准採用：軟換行。沒有橫向捲動模式。
- **D8** 未存標示是否也放視窗標題。核准採用：主視窗標題 `mystudy ●`。子視窗不加。
- **D9** 衝突判定只用 SHA-256，或 mtime+size 快速路徑。核准採用：mtime 與 size 都相同才略過雜湊，否則比 SHA-256。
- **D10** wiki root 外的筆記外部變更：焦點檢查即可，或另開單檔 WatchService。核准採用：不開第二個 WatchService。焦點、閱讀／編輯切換、存檔前檢查。
- **D11** Browse 的 `openIndexed` 是否也改用 NoteEditor。核准採用：不改。Browse 仍讀索引正文。
- **D12** 衝突時是否提供簡易 diff 檢視（建議本輪只提供「複製我的版本」）。核准採用：沒有 diff。重新載入時把捨棄的草稿放進剪貼簿。
- **D13** Live Preview、`[[` 自動完成（可用 `WikiIndex.resolve/pathsForAlias`）、Properties UI 是否排入後續版本。核准採用：1.1.0 不做。多分頁、檔案歷史、自動合併也不做。
- **D14** 文件檔名：`How-To-do-Note-Editor.md`、`note_editor_design_ref.md`。核准採用：沿用這兩個檔名，不另開一份設計稿。
- **D15** 版號。核准採用：`./configure.sh version 1.1.0`。
