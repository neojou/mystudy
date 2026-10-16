# mystudy 導覽歷史設計參考（上一頁／下一頁 `<` `>`）

> 1.3.0 已核准並實作。操作與契約以 `docs/programming/How-To-do-Navigation-History.md` 與 `NavigationHistoryTest` 為準。
> 依據 commit：`14960b1`（feat: view/edit，版號 1.2.0）。設計與程式衝突時，以程式與測試為準。
> 本文保留當時的設計推理。視角：UI/UX 美感與互動一致性。

實作與本文初稿的差異：

- 決策抽成 `NavigationHistory` 與 `NoteNavigation`。測試不建構 `StudyController`。
- 快捷鍵只掛 `StudyPane`，Home 且已開檔才動作。Tooltip 在 macOS 顯示 `⌘[`／`⌘]`。
- 「檔案已不存在」放狀態列中段的 `navMessage`，不覆寫 `noteStatus`。
- 右鍵／長按歷史清單這次不做。檔案樹仍然沒有右鍵選單。
- 滑鼠側鍵用 `PointerButtons.isBackPressed`／`isForwardPressed`，盡力支援，不寫 AWT hook。
- 閱讀捲動用 hoist 的 `LazyListState`。Chat／Ask 的 Compact 呼叫不傳這個狀態。

## 0. 一句話

在右側預覽區標題列、檔名左邊放一對 chevron 圖示鈕 `‹ ›`，行為等同瀏覽器與 Obsidian 的「Navigate back / forward」：只記「在預覽區開過哪則筆記」，記憶體內、單一工作階段、最多 50 筆，切檔前一律經過既有的未存變更守門。

## 1. 現況（讀程式所得）

| 區塊 | 位置 | 現況與對本功能的意義 |
|---|---|---|
| 開檔入口 | `StudyController.requestOpen(path)` | 同檔直接 return（`sameNotePath`）；dirty 時 `noteSession.requestLeave(PendingNav.OpenFile)` 擋下並跳對話框；否則 `previewFile(path)`。**這是唯一的「使用者導向切檔」閘門，歷史要掛在這裡。** |
| 實際載入 | `StudyController.previewFile(path)` | 重設 `NoteSession`，`mode = Reading`、`editorEpoch++`、`loadToken` 防競態，IO 讀檔。也被 `performNav`（守門後放行）與 wiki root 變更（行 ~1645）直接呼叫。 |
| 守門放行 | `proceedPending()` → `performNav(PendingNav.OpenFile)` → `previewFile` | 存檔或捨棄後才真正切檔；取消則 `pendingNav = null`。**歷史只能在「真的切成功」時推入，不能在 requestOpen 一開始就推。** |
| 檔案樹 | `FileTreePane.kt:112` `onOpen = controller::requestOpen`；`selected = controller.previewPath` | 樹的高亮跟著 `previewPath`，Back/Forward 後自動同步；需要時用 `revealInTree` 展開祖先。 |
| Wikilink | `MarkdownText` 的 `LinkAnnotation.Clickable` → `controller.openWikilink(token)` → `index.resolve` 在 `wiki-index` dispatcher → `requestOpen` | 閱讀模式、Live Preview 右欄、Properties 面板都走這條。未解析／多義連結不動作。 |
| Markdown 連結 | `inlineMarkdown` 的 `[label](url)` | 只有 `http(s)` 變成 `LinkAnnotation.Url`（交給系統瀏覽器）；**相對路徑 `.md` 連結目前只顯示文字、不可點**。本功能不改這點（見 §11 決策 D3）。 |
| 麵包屑 | `BreadcrumbBar(crumbs, onOpen = controller::revealInTree)` | 只列資料夾，點了是「在樹中展開」，**不切檔**，所以不算導覽。 |
| 標題列 | `NotePreview.kt`：第一列 `BreadcrumbBar`；第二列 `Row{ Text(title + " ●", weight 1f) ; ModeSegments }` | `‹ ›` 放在第二列最左、檔名之前。 |
| 快捷鍵 | `NotePreview` 的 `onPreviewKeyEvent`：Ctrl/Cmd+E、Ctrl/Cmd+Shift+E、Ctrl/Cmd+S；`StudyPane` 的 Ctrl/Cmd+`\`（側欄）；`SidebarHandle` 方向鍵 | 新快捷鍵加在 `NotePreview` 同一個 handler，或上提到 `StudyPane`（決策 D6）。 |
| Chat / Ask | `openAskPage` 開在 Ask/Chat 自己的閱讀器（`askReaderPath`），不是主預覽區 | 不會呼叫 `requestOpen`，目前不產生歷史。 |
| Ingest | 結果寫入後不會自動開新頁 | 不產生歷史。 |
| 外部刪檔 | `checkExternal` → `markConflict(deleted = true)` | 刪除只對「目前開著的檔」有處理；歷史裡的其他檔要在 Back/Forward 當下檢查是否存在。 |
| 切換 vault / wiki root | `resetNote()` | 必須一併清空歷史。 |
| 捲動位置 | `MarkdownText(lazy = true)` 內部 `LazyColumn` 狀態；`editorEpoch` 變就重建 | 目前**沒有**可外部存取的捲動狀態，要還原捲動需把 `LazyListState` 提升（hoist）到 `NotePreview`／controller。 |

## 2. 參考：Obsidian 的行為（摘要，部分未驗證）

- 標題列左側有 `←` `→`（新版為 chevron），在「同一個分頁」內前後切換，等同瀏覽器歷史。
- 長按或右鍵按鈕會列出歷史清單（**未驗證是哪一個手勢**；社群描述為右鍵／長按，以實機為準）。
- 預設快捷鍵：Navigate back `Ctrl/Cmd+Alt+←`、Navigate forward `Ctrl/Cmd+Alt+→`（**未在官方文件逐字確認**，標為未驗證）。滑鼠側鍵在 Obsidian 也能用。
- 歷史是「每個分頁各自一條」，關掉分頁就沒了；新開一則筆記會清掉 forward。

mystudy 沒有分頁，只有一個預覽區，所以就是「一條」歷史。

## 3. 歷史模型

### 3.1 結構（瀏覽器語意）

```
backStack: ArrayDeque<HistoryEntry>   // 最舊 … 最新
current:   HistoryEntry?
forwardStack: ArrayDeque<HistoryEntry> // 最近離開的在頂端
```

`HistoryEntry(path: Path, mode: NoteViewMode, scroll: ScrollAnchor?)`，`ScrollAnchor(firstVisibleIndex: Int, offset: Int)`。path 一律 `toAbsolutePath().normalize()`，比較用既有 `sameNotePath` 的邏輯。

### 3.2 轉移

| 事件 | 動作 |
|---|---|
| `visit(entry)`（新導覽成功） | 若與 `current` 同檔 → 只更新 current（去重，不推）。否則 `current` 推進 back、`current = entry`、**清空 forward**。back 超過上限從最舊端丟。 |
| `back()` | back 空 → 無動作。否則 `current` 推入 forward 頂、pop back 成為新 current。回傳目標。 |
| `forward()` | 對稱。 |
| `updateCurrent(mode, scroll)` | 離開前把 current 的捲動與模式寫回（切檔前呼叫）。 |
| `remove(path)` / `prune(exists)` | 刪掉指向已不存在檔案的項目，並合併因此相鄰的同檔項目。 |
| `clear()` | 換 vault、換 wiki root、`resetNote()`。 |

上限 **50**（back＋forward 合計各自 ≤ 50 也可；建議 back ≤ 50、forward 天然 ≤ 50）。超過丟最舊，不提示。

### 3.3 兩階段提交（關鍵）

因為守門可能擋下或使用者按「取消」，Back/Forward **不能**在按下時就移動指標：

1. 按 `‹`：`target = history.peekBack()`；若 null 不動作。
2. 經 `requestOpen(target.path, origin = NavOrigin.Back)` → 守門。
3. 守門放行、`previewFile` 開始載入時，才 `history.commitBack()`；取消則什麼都不變。
4. 若一般連結點擊被守門擋住後使用者選「存檔」或「捨棄」，`performNav` 帶著 origin 再呼叫 `history.visit`。

做法：`PendingNav.OpenFile` 加欄位 `origin: NavOrigin = NavOrigin.Link`（`Link | Tree | Back | Forward | System`），`performNav` 依 origin 決定 `visit / commitBack / commitForward / 不記錄`。

### 3.4 什麼算導覽（決策與理由）

| 來源 | 記入歷史？ | 理由 |
|---|---|---|
| 閱讀模式／Live Preview／Properties 的 `[[wikilink]]` | **是** | 這是需求核心；跟 Obsidian 一致。 |
| 檔案樹點檔 | **是** | Obsidian 也記；使用者心智是「我剛剛在看 A」，不在乎怎麼過去的。不記會讓 `‹` 跳到更早的地方，令人困惑。 |
| 麵包屑 | 否 | 只在樹中展開資料夾，不切檔。 |
| Back / Forward 本身 | 只移動指標 | 不得 `visit`，否則 forward 會被清掉。 |
| 同一檔再點一次 | 否 | `requestOpen` 本來就 return；去重。 |
| Chat / Ask 結果 | 目前否（不經主預覽區） | 若未來加「在主視窗開啟」按鈕，走 `requestOpen` 就自動記入（origin = Link）。 |
| Ingest 結果 | 否 | 不自動開頁。若未來加「開啟產生的頁」，同上。 |
| 啟動、換 wiki root 時程式自己 `previewFile` | 否（origin = System） | 不是使用者意圖；且換 root 本身要清歷史。 |
| 外部變更後重新載入同檔 | 否 | 同檔。 |
| 第一次開檔（current 為 null） | 設為 current，不推 back | `‹` 仍為停用，避免回到「空白畫面」。 |

### 3.5 每筆還原什麼

- **模式**：記離開時的 `NoteViewMode`。還原規則：目標檔仍 `Editable` 才還原成 Editing，否則 Reading。但 `previewFile` 目前總是 Reading，需在載入完成（`noteSession.open` 之後）套用 `entry.mode`。**建議預設：只還原 Reading/Editing，不還原 Live Preview/Source 子模式**（那是全域偏好 `ui.editorSubMode`）。
- **捲動**：只還原閱讀模式的 `LazyListState(firstVisibleItemIndex, offset)`。編輯模式的游標與捲動不還原（`TextFieldState` 每次 `editorEpoch` 重建，還原游標風險高、收益低）。檔案內容在離開後若被改，index 可能超界 → clamp。
- **新導覽（連結、樹）**：一律從頂端、Reading 開始，與現況一致。

### 3.6 已刪除／改名／移動的檔

- 按 `‹`/`›` 時先在 IO 檢查 `Files.isRegularFile(target)`。不存在 → 從歷史移除該項，繼續往同方向找下一筆存在的；全部都不在 → 按鈕變停用，狀態列顯示「上一則筆記已不存在」。
- 不做「停用但仍列出」：主按鈕停用會讓使用者以為沒有歷史。P1 的歷史清單裡可以把不存在的項目灰掉並加刪除線，tooltip「檔案已不存在」。
- 改名／移動：沒有可靠的 rename 事件（`WikiWatcher` 只看 wiki root，且 rename = delete+create），**不追蹤**，當作刪除處理。
- Tooltip 文字在 hover 時用快取的存在狀態即可，不必每次 hover 都打 IO。

### 3.7 持久化

**不持久化**，只在記憶體、這個工作階段。理由：AGENTS.md 已把「Chat 歷史只在記憶體」「側欄開合只在這個工作階段」定為慣例；歷史含 vault 內路徑，不寫 Preferences 可避免隱私與換 vault 後失效的問題。關 app、換 vault、換 wiki root 就清空。

## 4. UI 設計

### 4.1 位置與草圖

```
Vault › 隨筆 › 2026
[‹][›]  我的讀書筆記.md ●                    [閱讀 | 編輯]
       ─ 即時預覽 | Source ─         …  存檔  ⧉  Ingest
```

- 放在**第二列、檔名正左方**，不放第一列麵包屑旁：麵包屑是「位置」（空間），`‹ ›` 是「時間」；與檔名同列才符合 Obsidian 與瀏覽器把歷史放在標題左側的心智模型，也讓按鈕與標題同一條視覺基線。
- 兩鈕間距 0–2 dp，視為一組；與檔名間距 8 dp（沿用 Row 的 `spacedBy(8.dp)`）。
- 沒有開任何檔時整組不顯示（與標題一致）。

### 4.2 外觀（沿用 lavender tokens）

| 狀態 | 圖示色 | 底 |
|---|---|---|
| 可用 | `onSurfaceVariant` | 透明 |
| hover | `onSurface` | `onSurface` 8%（M3 state layer） |
| pressed | `onSurface` | `onSurface` 12% |
| focus（鍵盤） | `onSurface` | 2 dp `primary` 外框（ui_ux_design_ref §無障礙） |
| 停用 | `onSurface` 38%（M3 disabled） | 透明，無 hover |

- 圖示：自畫 chevron（2 dp 圓頭線段、18 dp 視覺尺寸），風格比照現有 `CopyIcon`/`FileIcons`；**不用 primary 塗色**——它們是低頻 chrome，不該比「閱讀|編輯」或檔名搶眼。
- 尺寸：`IconButton` 點擊區 **32×32 dp**（≥ 32dp 要求），圓形 state layer。檔名字級 26sp，按鈕垂直置中對齊檔名第一行。

### 4.3 Tooltip

沿用 `NotePreview` 的 `Hint` 元件（M3 PlainTooltip）：

- 可用：「返回：上一則檔名.md（Ctrl+Alt+←）」／「前進：下一則檔名.md（Ctrl+Alt+→）」；macOS 顯示 `⌘⌥←`。
- 停用：「沒有上一則」／「沒有下一則」。
- 檔名過長時中段省略（保留副檔名），最多約 40 字。

### 4.4 歷史清單（P1，可選）

- 右鍵（`onPointerEvent(Press)` 檢查 `isSecondaryPressed`）或長按 400 ms 開 `DropdownMenu`，列出該方向最多 15 筆，最近的在最上，每列：檔案圖示＋檔名＋淡色上層資料夾。點某列＝連跳多步（一次經守門，成功後一次移動指標）。
- 注意 AGENTS.md「檔案樹沒有右鍵選單」只限檔案樹；此處是按鈕，不衝突，但要在計畫中說明。

### 4.5 無障礙

- `semantics { contentDescription = "返回上一則筆記"; role = Role.Button }`，停用時 `disabled()`；`stateDescription` 帶目標檔名。
- 可 Tab 聚焦，Enter/Space 觸發。
- 停用色只用於圖示；tooltip 補足文字資訊，不只靠顏色。

## 5. 快捷鍵

| 平台 | 返回 | 前進 | 備註 |
|---|---|---|---|
| macOS | `⌘[` | `⌘]` | Finder／Safari／Xcode 慣例，建議主推。 |
| macOS | `⌘⌥←` | `⌘⌥→` | 對齊 Obsidian 預設（未驗證）。 |
| Windows/Linux | `Alt+←` | `Alt+→` | 瀏覽器與檔案總管慣例。 |
| Windows/Linux | `Ctrl+Alt+←` | `Ctrl+Alt+→` | 對齊 Obsidian 預設（未驗證）。 |
| 全平台 | 滑鼠側鍵 Back | 滑鼠側鍵 Forward | 見 §5.2。 |

### 5.1 與編輯衝突

- 編輯模式下 `Alt+←/→`（Windows/Linux）與 `⌥←/→`（macOS 逐字移動）是文字游標操作；`⌘←/→` 是行首行尾。規則：
  - **不帶 Ctrl/Cmd 的 `Alt+←/→` 只在閱讀模式生效**；Editing 時不攔截（交給 `BasicTextField`）。
  - `⌘[` / `⌘]`、`Ctrl/Cmd+Alt+←/→` 在兩種模式都生效（文字編輯沒有標準語意）。注意 `⌘[`/`⌘]` 在部分編輯器是縮排，mystudy 目前沒有，若未來加縮排要重新評估。
- 實作用 `onPreviewKeyEvent`，KeyDown、比對 `Key.LeftBracket`/`Key.RightBracket`/`Key.DirectionLeft`/`Key.DirectionRight` 與修飾鍵；Windows 上 AltGr = Ctrl+Alt，`[`/`]` 在某些鍵盤配置需要 AltGr —— 因此 Windows/Linux **不綁** `Ctrl+[`。
- 平台判斷沿用既有方式（`isCtrlPressed || isMetaPressed` 已是專案慣例）；文字標示用 `System.getProperty("os.name")`。

### 5.2 滑鼠側鍵（可行性）

- Compose Desktop 的 `PointerButtons` 有 `isBackPressed`／`isForwardPressed`（`androidx.compose.ui.input.pointer`），可在 `NotePreview` 根 `Modifier.onPointerEvent(PointerEventType.Press)` 讀取。**未在本 repo 的 Compose 1.10.0 實測**；AWT 在 macOS 對第 4/5 鍵的回報依滑鼠驅動而異，Linux 某些環境會映成水平捲動。結論：P0 做，但列為「盡力支援」，實測失敗就從需求移除，不寫 AWT 低階 hook。
- 側鍵事件只在指標位於主視窗預覽區時生效；Chat/Ask 子視窗不接。

## 6. 狀態放哪裡

- `wiki/` 層不碰。新增純 Kotlin `study/NavigationHistory.kt`（無 Compose 依賴，泛型或直接用 `Path`），controller 持有一個實例。
- `StudyController` 對 UI 暴露 Compose state：`canGoBack`、`canGoForward`、`backLabel`、`forwardLabel`（每次歷史變動後 `publishNavigation()` 更新，與既有 `publishNote()` 同模式）。
- 新函式：`goBack()`、`goForward()`、`goToHistory(direction, steps)`（P1）、`rememberScroll(anchor)`。
- `requestOpen(path, origin = NavOrigin.Link)`；樹呼叫帶 `Tree`。`openWikilink` 帶 `Link`。
- `previewFile` 不改語意；記錄歷史在 `requestOpen` 直通分支與 `performNav` 兩處，或集中在一個 `navigateTo(path, origin)` 私有函式裡（建議後者，避免漏記）。
- `resetNote()`、換 vault 時 `history.clear()`。

## 7. 邊界情境

| 情境 | 期望 |
|---|---|
| dirty 時按 `‹`，選「取消」 | 不切檔、歷史不動、`‹` 仍可用。 |
| dirty 時按 `‹`，選「存檔」但存檔失敗 | 依既有契約不離開（`Failed` 保留 pendingNav）；歷史不動。 |
| dirty 時按 `‹`，選「捨棄」 | 切檔、指標移動；捨棄內容照既有規則複製到剪貼簿。 |
| Conflict 狀態按 `‹` | 走同一守門（目前 `dirty` 判斷）；不新增規則。 |
| 載入中又按 `‹` | `loadToken` 已處理競態；歷史指標以最後一次成功提交為準。連按三次 = 退三步。 |
| A→B→A | back = [A, B]，不去重非相鄰項（瀏覽器語意）。 |
| 目標在歷史中但已被刪 | 跳過並移除，見 §3.6。 |
| 換 vault / wiki root | 清空。 |
| 唯讀檔（raw/、schema.md） | 照常記入；模式還原強制 Reading。 |
| 非 Markdown（圖片、無法預覽） | 照常記入（使用者確實「看過」它）。 |
| 切到 Chat/Lint 等其他 StudyMode 再回 Home | 歷史保留。 |

## 8. 測試

### 8.1 `NavigationHistoryTest`（純單元，`desktopTest/.../study/` 或 `wiki/` 同層，與現有測試同 package 慣例）

- 初始兩鈕不可用；第一次 visit 不可 back。
- visit A,B,C → back 得 B → back 得 A → forward 得 B。
- back 後 visit D 清空 forward。
- 連續同檔去重；A→B→A 不去重。
- 上限 50：visit 60 次，back 最多 50 步，最舊被丟。
- peek 不改狀態；commit 才移動。
- prune：刪掉中間項後相鄰同檔合併；全部刪光時兩鈕不可用。
- updateCurrent 保存 mode/scroll，back 回來取得同值。
- clear。

### 8.2 `NavigationHistoryBehaviorTest`（或併入 `NoteEditorBehaviorTest`）

沿用既有暫存 vault 寫法（`Files.createTempDirectory`），以 controller 層或「controller 的純邏輯抽出函式」驗證：

- 樹點 A、點 B、wikilink 到 C → `goBack` 兩次回 A，`goForward` 回 B。
- dirty 時 `goBack` → 設定 `pendingNav`，歷史未變；`cancelLeave` 後仍未變；`discardAndProceed` 後指標移動。
- 歷史中的檔被刪 → `goBack` 跳過。
- 換 wiki root 清空。
- 快捷鍵判斷抽成純函式 `navShortcut(key, ctrl, meta, alt, shift, editing): NavCommand?` 以表格驅動測試，涵蓋「編輯中 Alt+← 不攔截」。

若 controller 難以在測試中建構（Compose state、scope），優先把決策邏輯抽成純函式測，不為測試引入 UI 測試框架。

## 9. 文件與契約

- 新增 `docs/programming/How-To-do-Navigation-History.md`：行為、快捷鍵表、什麼算導覽、如何新增一個會記入歷史的入口（呼叫 `requestOpen(path, origin)`）、測試位置。
- 本文放 `docs/programming/navigation_history_design_ref.md`。
- `AGENTS.md`：「先讀」加一行；「契約」加一條：「導覽歷史只在記憶體、這個工作階段，不寫 Preferences 或 vault；所有使用者切檔都經 `requestOpen` 與未存守門；Back/Forward 不清 forward」。測試清單加 `NavigationHistoryTest`。
- `docs/Architecture.md` 的 study 層一句話提到 `NavigationHistory`。
- `How-To-do-UI-Layout.md`、`How-To-do-Note-Editor.md` 的快捷鍵表補上。
- 版號：`./configure.sh` 升 **1.3.0**（新使用者功能，minor）。

## 10. 實作順序（一次交付，可退守）

1. `NavigationHistory` + 單元測試（可獨立合併）。
2. controller 整合：`NavOrigin`、`navigateTo`、`goBack/goForward`、守門兩階段提交、clear、刪檔跳過 + 行為測試。
3. UI：兩顆 chevron 鈕、tooltip、停用樣式、semantics。
4. 快捷鍵（含編輯模式衝突規則）。
5. 捲動與模式還原（需要 hoist `LazyListState`；風險最高，可退守為「只還原模式」）。
6. 滑鼠側鍵（實測不行就移除）。
7. P1：右鍵／長按歷史清單。
8. 文件、AGENTS.md、版號。

退守切點：做完 1–4 即可交付核心價值。

## 11. 待決策

- D1 樹點檔是否記入歷史（建議：是）。
- D2 上限 50（或 100）。
- D3 相對路徑 Markdown 連結 `[x](note.md)` 是否這次一併變成可點並記入歷史（建議：另案；目前只顯示文字）。
- D4 是否還原捲動（建議：閱讀模式還原；編輯不還原）；是否還原 Reading/Editing（建議：是，且受可編輯權限限制）。
- D5 刪除檔：跳過並移除（建議）或停用顯示。
- D6 快捷鍵掛在 `NotePreview`（只有焦點在預覽區時有效）或 `StudyPane`／視窗層（全域）。建議 `StudyPane`，但 Home 模式外不動作。
- D7 macOS 主推 `⌘[`/`⌘]` 還是 `⌘⌥←/→`；兩者都綁？（建議都綁）
- D8 Windows/Linux 是否也綁 `Ctrl+Alt+←/→`（AltGr 衝突風險低，因為是方向鍵）。
- D9 滑鼠側鍵列 P0（盡力）或 P1。
- D10 歷史清單（右鍵／長按）做不做、手勢選哪個。
- D11 是否持久化（建議：不）。
- D12 未來 Chat/Ask「在主視窗開啟」要不要記入（建議：要，走 `requestOpen`）。
- D13 版號 1.3.0。

## 12. 未驗證事項

- Obsidian 預設快捷鍵 `Ctrl/Cmd+Alt+←/→` 與歷史清單手勢（右鍵或長按），未查官方文件逐字確認。
- Compose 1.10.0 Desktop 的 `PointerButtons.isBackPressed/isForwardPressed` 在 macOS/Windows/Linux 的實際回報。
- `MarkdownText(lazy = true)` 的 `LazyListState` 能否乾淨地 hoist 而不影響 Chat/Ask 的 Compact 用法。
- `⌘[` 在 Compose `Key.LeftBracket` 於非美式鍵盤配置（如日文 JIS）的對應。
