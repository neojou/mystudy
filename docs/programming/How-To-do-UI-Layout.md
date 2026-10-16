# 程式如何做版面與閱讀

側欄寬度、閱讀／編輯、Live Preview、Markdown 渲染與 Properties 都在這一層。寫入契約仍在 `docs/programming/How-To-do-Note-Editor.md`：閱讀不寫檔，存檔仍只經 `assertNoteEdit`。

設計比較留在 `docs/programming/ui_ux_design_ref.md`。那份文件開頭的「1.2.0 已核准並實作」是這一版的決定。

## 配色與 tokens

`AppTheme` 使用明確的淺色 `colorScheme`（`studyLightColors`），並用 `LocalStudyTokens` 提供 `StudyTokens.Light`。主色仍是薰衣草 `#6750A4`，表面是 `#FEF7FF`。標題色、閱讀最大寬度 760 dp、內距 32 dp、字級與列高都在 `StudyTokens`。對比度由 `contrastRatio` 計算，測試鎖 H1–H6 與正文在 `#FEF7FF` 上至少 4.5。

只有 `notosanstc_regular`。粗體是合成的。沒有暗色方案；`colorScheme` 與 tokens 分開，之後可以加暗色而不改呼叫點。

## 側欄

`HomeScreen` 的 `fileBrowserOpen` 預設 true，只在記憶體。啟動時樹是開的。收合按鈕、左上角圖示、`Ctrl/Cmd+\` 都改這個旗標。`Ctrl/Cmd+\` 掛在 `StudyPane` 根上的 `onPreviewKeyEvent`，所以編輯欄位裡也吃得到。有 Shift 時不處理。

寬度：

| 動作 | 結果 |
| --- | --- |
| 拖曳中 | 只改畫面上的寬度，可以低於 180，方便拖過 120。不寫 Preferences。 |
| 放開且寬度 ≥ 120 | `finishSidebarDrag` 夾進目前視窗允許的範圍，再以整數寫入。儲存值一律落在 180..560。 |
| 放開且寬度 < 120 | 收合。不把那個窄寬度寫進偏好。下次打開用上次記下的寬度。 |
| 雙擊把手 | 寫回 260。 |
| 把手有焦點時 ← / → | 每次 16 dp，在按鍵放開時寫入。下限是儲存範圍 180，所以方向鍵不會收合。 |

視窗夠寬時，布局夾在 180 與 `min(560, 視窗寬 45%)`。更窄的視窗用單一寬度 `min(180, 視窗寬 - 280)`，且不低於 120。偏好鍵是 `ui.sidebarWidthDp`。壞掉的數字回到 260，再夾進 180..560。

把手的命中寬度是 6 dp，semantics 是「調整側欄寬度」。游標是東西向縮放。懸停或焦點時，1 dp 線變成 primary 2 dp。

## 檔案樹

區段標題是「Vault」。每一列含樹根只顯示路徑最後一段，列高 28 dp、字級 13 sp、縮排 16 dp，旁邊有 1 dp 導線。懸停 tooltip 是絕對路徑。沒有右鍵選單。

目前開啟的檔案：primary 12% 底色加左側 2 dp primary 直條。`wiki/` 資料夾文字後面有「 · wiki」。`raw/` 用較淡的 `onSurfaceVariant`。圖示是 `FileIcons.kt` 裡的小畫，不另加圖示套件。

麵包屑相對 vault，不含檔名，分隔是 ` › `。超過四段時保留第一段、省略號、最後兩段。省略號不能點。點其他段會展開祖先並把該列捲進畫面，不把目錄當成筆記打開。

## 筆記標頭與快捷鍵

開檔一律是閱讀，不記住。標頭是麵包屑、檔名（未存時加 `●`）、`閱讀 | 編輯`。編輯中才出現 `即時預覽 | Source`，以及「存檔」。複製是圖示按鈕，說明「複製全文」，複製的是草稿（LF、無 BOM），不是畫面上的渲染。Ingest 是最右側的 `FilledTonalButton`，提示在 tooltip 與底部狀態列，不壓過模式與存檔。

| 快捷鍵 | 作用 |
| --- | --- |
| `Ctrl/Cmd+E` | 可編輯時切換閱讀／編輯，並吃掉按鍵。 |
| `Ctrl/Cmd+Shift+E` | 只在編輯中切換即時預覽／Source。先看 Shift，避免被當成 `Ctrl/Cmd+E`。 |
| `Ctrl/Cmd+S` | 編輯中且有未存變更時存檔，並吃掉按鍵。 |
| `Ctrl/Cmd+\` | 開合側欄。 |
| `Ctrl/Cmd+Z` / `Y` | 不攔截。復原是 `TextFieldState` 內建的。 |
| macOS `⌘[`／`⌘]`，以及 `⌘⌥←`／`⌘⌥→` | 預覽區返回／前進。Home 且已開檔才動作。 |
| Windows／Linux `Alt+←`／`Alt+→` | 返回／前進。只在閱讀模式攔截。編輯中留給文字游標。 |
| Windows／Linux `Ctrl+Alt+←`／`Ctrl+Alt+→` | 返回／前進。閱讀與編輯都吃。 |

導覽歷史的按鈕、停用與捲動見 `docs/programming/How-To-do-Navigation-History.md`。不綁 `Ctrl+[`。

子模式在 `StudyController.ui`，鍵是 `ui.editorSubMode`（`live` 或 `source`）。不認得的值用即時預覽。切換子模式不增加 `editorEpoch`。`editorEpoch` 仍只在載入、捨棄、重載時增加。

狀態列高約 24 dp。左是 `noteStatus`（含「唯讀：」與原因、「未儲存變更」）。中間是維護警示、換行混用、Ingest 訊息；空白時若這則筆記可以 Ingest，顯示既有的 idle 提示。點中間可展開。右是屬性數、字數（code point）、模式文字：`閱讀`、`即時預覽 · 分割檢視` 或 `Source`。外部變更橫幅與「已將捨棄的內容複製到剪貼簿」仍留在內容上方。

## Live Preview（L0）

編輯且子模式是即時預覽時，左邊是同一個 `TextFieldState`（等寬 14 sp、行高 1.6），右邊渲染同一份草稿。中間 1 dp 分隔。右邊不可編輯。捲動只從左到右，按比例，不對行。

限制：不是游標所在區塊才顯示原始碼的 Live Preview。長文第一次可能先空白再補上（約 32_000 字以上改在 `Dispatchers.Default` 解析）。閱讀模式的清單可以逐段選取；「複製全文」仍是整份草稿。圖片只有 alt。`[!kind]-` / `+` 不折疊。

Source 模式是整頁原始文字，含 frontmatter。有開限寬時，編輯區與閱讀區最寬 760 dp 並置中。

## Markdown 與 Properties

`parseMarkdownDocument` 用 JetBrains Markdown 的 GFM 解析正文。`markdownBlocks` 仍給舊測試用。表格有表頭與格線，對齊讀自分隔列。引用、callout、有序清單、任務、水平線、連結都有。Callout 看來源列上的 `[!kind]`，因為 GFM 會把 `[!warning]` 解析成 reference link。

兩種樣式：

- `MarkdownStyle.Document`：筆記閱讀與 L0 右欄。可限寬、可顯示 Properties。
- `MarkdownStyle.Compact`：Chat 與 Ask 答案。有表格與標題色，不限 760，沒有 Properties。預設是 Compact，既有呼叫點不會突然變成筆記版面。

Frontmatter 重用 `readFrontmatter`。閉合且解析成功時，閱讀與 L0 右欄顯示唯讀 Properties：`tags` 是帶 `#` 的膠囊，其他清單是沒有 `#` 的膠囊，日期有日曆圖示，`http(s)` 可點，`[[wikilink]]` 經 `WikiIndex.resolve`（在 `wiki-index` dispatcher）只有唯一命中才打開。`true` / `false` 是不能改的核取方塊。折疊狀態只在這次畫面、預設展開。解析失敗顯示「YAML 無法解析」與等寬原文，屬性數是 0。沒有閉合圍欄（含未閉合）不顯示卡片。任務清單不能勾回去寫檔。

## 偏好

這三個鍵不進 `StudySettings.save`，也不進 vault：

| 鍵 | 意思 |
| --- | --- |
| `ui.sidebarWidthDp` | 180..560 的側欄寬度 |
| `ui.editorSubMode` | `live` 或 `source` |
| `ui.limitLineWidth` | 只有字串 `false` 是關閉，缺鍵是開 |

## 這一版不做

區塊級 Live Preview、暗色、大綱、分頁、字級 90–130、程式碼語言標籤與複製、callout 折疊、列號、新增屬性、Noto 的 Medium/Bold。

## 測試

不開 Compose 視窗：

- `MarkdownDocumentTest`：表格、callout、範圍鋪滿、`spliceBlock`。
- `PropertiesViewTest`：屬性、壞掉的 YAML、既有 `parseNote` 中文標題。
- `UiPrefsTest`：寬度夾限、拖曳結束才寫、子模式、`StudySettings.save` 不擦掉 `ui.*`、標題對比。
- `UiChromeTest`：麵包屑、模式文字、屬性數。
- `WikiBehaviorTest.markdownRendererDropsMarkerCharacters` 仍鎖舊的四塊結構與行內標記。

## 手動看

```bash
./gradlew :composeApp:run
```

- 同一則筆記的表格有表頭與格線。frontmatter 變成 Properties（tags 膠囊、日期、可點連結）。標題層級可分辨。內容限寬並置中。
- 拖完側欄再重開 app，寬度還在。長檔名有 tooltip。
- `Ctrl/Cmd+E` 切換。即時預覽與 Source 可選。存檔後位元組與存檔前的契約相同（BOM、換行）。
- Chat 與 Ask 的答案仍看得到標題、粗體與表格，版面沒有被 760 dp 限住。
