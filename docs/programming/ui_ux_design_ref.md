# mystudy UI/UX 設計參考（閱讀體驗、編輯模式、版面）

> 目標路徑：`docs/programming/ui_ux_design_ref.md`
> 依據 commit：`16c127a`（Version 1.1.0 - edit）。設計與程式衝突時，以程式與測試為準。
> 本文只是設計參考，不含實作碼。

## 0. 1.2.0 已核准並實作

這一版照下面的決定做完。後文仍保留當時的現況與方案比較，方便對照；和程式不一致時，以程式與測試為準。操作步驟在 `docs/programming/How-To-do-UI-Layout.md`。

- Markdown 用方案 C：`org.jetbrains:markdown` 0.7.5，`GFMFlavourDescriptor`。Chat 與 Ask 共用這套渲染，樣式是 Compact。深度研究、Ask/Chat 的頁面閱讀、Browse 仍是純文字。
- Live Preview 只做 L0 分割檢視。按鈕寫「即時預覽」，狀態列寫「即時預覽 · 分割檢視」。與 Source 共用同一個 `TextFieldState`，切換子模式不增加 `editorEpoch`。不做區塊級編輯。
- 側欄寬度在拖曳結束才寫 Preferences（`ui.sidebarWidthDp`，180..560，預設 260）。拖到 120 dp 以下收合，不記下那個窄寬度。雙擊把手回到 260。開或合只活在這個工作階段，啟動時展開。
- 區段標題維持「Vault」。樹根仍是路徑最後一段。目前檔案用 primary 12% 底色加 2 dp 直條。`wiki/` 資料夾保留「 · wiki」。不再把每個 wiki 檔都塗成 primary。`raw/` 用 `onSurfaceVariant`。
- 閱讀寬度預設開，固定 760 dp。Settings 的「限制閱讀寬度」立刻寫 `ui.limitLineWidth`，不經 Settings 的 Save。沒有滑桿。
- Properties 與任務核取方塊唯讀。解析失敗只顯示原始 YAML。未閉合的 frontmatter 不變成屬性卡。圖片只顯示 alt，不讀檔、不連網。
- 這一版沒有暗色模式、大綱、分頁、字級縮放、Noto Sans TC Medium/Bold、新增屬性、程式碼列號、callout 折疊。`[!note]-` 仍畫成一般 callout。

## 1. 現況（讀程式所得）

| 區塊 | 檔案 | 現況 |
|---|---|---|
| 主題 | `commonMain/.../Theme.kt` | `MaterialTheme(typography = Typography().withFontFamily(Noto Sans TC))`，**沒有自訂 colorScheme**，薰衣草色來自 M3 預設 baseline 紫。沒有暗色模式。 |
| 頂部選單 | `commonMain/.../ui/menu/MyTopMenuBar.kt`、`MyTopMenuItem.kt`、`study/FileBrowserIcon.kt` | 資料夾圖示、About、Wiki、深度研究、Settings；`surfaceContainer` 底色。 |
| 版面 | `study/StudyPane.kt` | `FileTreePane(Modifier.width(260.dp))` 固定寬＋1dp `outlineVariant` 分隔線；右側 `when(mode)`。 |
| 檔案樹 | `study/FileTreePane.kt` | 縮排 `depth*12.dp`、`maxLines = 1` 截斷、無 tooltip、無圖示、標題「Vault」。 |
| 預覽／編輯 | `study/NotePreview.kt`、`NoteSession.kt` | 工具列：`Button("Ingest")`、標題（dirty 加 ●）、`TextButton` 閱讀／編輯（粗體表示目前模式）、複製全文、`Button("存檔")`；下一列 ingest 提示；再下一列 `noteStatus`（「已存 15:24」）、警告、換行混用、外部變更橫幅。`NoteViewMode { Reading, Editing }`。`Ctrl/Cmd+E`、`Ctrl/Cmd+S` 已有。frontmatter 由 `splitReading()` 切出，以 `surfaceVariant` 卡片＋等寬字原樣顯示。編輯為 `BasicTextField(TextFieldState)`。 |
| Markdown | `study/MarkdownText.kt` | 自寫小子集：Heading（H1=titleLarge、H2=titleMedium、H3+=titleSmall，全 Medium）、Bullet、Fenced Code、Paragraph；inline 只有 `**`、`*`、`` ` ``。**不支援表格、有序清單、引用、callout、水平線、連結、`[[wikilink]]`、圖片、任務清單**。表格行因非空白被併成同一段落 → 截圖中的 `| 項目 | 內容 | --- |`。 |
| frontmatter 解析 | `wiki/markdown/MarkdownDocs.kt`、`PageText.kt` | 已有 frontmatter 欄位解析（`fields`、`frontmatterUnparsed`），可直接重用給 Properties 面板。 |
| 偏好 | `wiki/settings/AppSettings.kt` | `PreferencesSettingsStore`（`com/neojou/mystudy/wiki`），可加 UI 偏好鍵。 |
| 依賴 | `gradle/libs.versions.toml` | Compose MP 1.10.0；無 Markdown 函式庫。 |

`MarkdownText` 也被 Chat/Ask/深度研究答案共用，修改要顧及這些畫面。

## 2. 參考原則（自行歸納，不抄 CSS）

**Blue Topaz（Obsidian 主題）**：
- 色彩是「語意層級」而不是裝飾：標題 H1–H6 各有一個色相或明度，讓掃讀時一眼看出結構。
- 柔和低彩度底色＋一個主色（accent），互動元件、連結、選取、目前檔案都用同一 accent 的不同透明度。
- 閱讀寬度有上限、留白充足、行高寬鬆；表格、程式碼、引用、callout 都是「卡片化」的柔和底色區塊，圓角一致。
- 側邊欄低對比、內容區高對比；狀態與中繼資料（屬性、字數）退到次要層。

**Obsidian 檢視模式**（help.obsidian.md「Views and editing mode」）：
- 兩大模式：**閱讀檢視**（完整渲染、不可編輯）與**編輯檢視**；編輯檢視內再分 **Live Preview**（游標所在的語法顯示原始碼，其餘就地渲染）與 **Source mode**（純原始 Markdown）。
- `Ctrl/Cmd+E` 在閱讀與編輯間切換；Live Preview／Source 是編輯檢視的子設定，可設預設值。
- Properties 在 Live Preview／閱讀中以表單呈現，在 Source mode 才露出 YAML。

## 3. 問題清單（依優先序）

### P0（直接傷害閱讀與信任）
1. **表格未渲染**：最常見的筆記結構變成雜訊；使用者會以為檔案壞了。
2. **frontmatter 以原始 YAML 呈現**：中繼資料搶了開頭最有價值的視覺位置，且不可點、不易掃讀。
3. **標題層級弱**：H1/H2 只差一號字、同色、同粗、間距 6dp 一律相同 → 無法建立結構感。
4. **內容全寬、行距密**：寬螢幕上一行可達 100+ 字，CJK 閱讀疲勞；應限寬並放大行高。
5. **側欄固定 260dp 且截斷長檔名**：中文長檔名幾乎全部被截掉，又無 tooltip，無法辨識檔案。（使用者明確要求可拖曳調寬）

### P1（效率與清晰度）
6. 右側工具列層級混亂：Ingest 是最大最紫的實心鈕，比「目前模式」與「存檔」更醒目；閱讀／編輯用 TextButton＋粗體，看不出是互斥狀態。
7. 沒有 Live Preview／Source 選擇（使用者明確要求）。
8. 狀態資訊散落三四行（ingest 提示、已存時間、警告、換行混用），推擠內容區。應收進底部狀態列。
9. 沒有麵包屑：不知道檔案在哪個資料夾。
10. 連結、`[[wikilink]]`、有序清單、引用、水平線、任務清單、callout 未渲染。
11. 檔案樹沒有目前檔案高亮、圖示、縮排導引線。

### P2（精緻度）
12. 大綱面板（標題目錄）。
13. 暗色模式與明確的 colorScheme（目前只靠 M3 預設）。
14. 字數／字元數、屬性數、反向連結數。
15. 程式碼區塊語言標籤與複製鈕。
16. 分頁（tabs）— 範圍大，建議不做或另案。

## 4. 設計 Tokens（保留薰衣草色）

集中在新檔 `commonMain/.../ui/theme/StudyTokens.kt`（或擴充 `Theme.kt`），以 `CompositionLocal` 提供；**明確寫出 lightColorScheme，色值取自目前 M3 baseline**，確保畫面色調不變，只是從「隱性預設」變「顯性 token」。

### 4.1 色彩（亮色）
| Token | 建議 | 用途 |
|---|---|---|
| primary | `#6750A4`（現行） | accent、連結、選取、目前模式 |
| surface / background | `#FEF7FF`（現行） | 內容區 |
| surfaceContainerLow | `#F7F2FA` | 側欄、狀態列 |
| surfaceContainer | `#F3EDF7` | 頂部選單、卡片（表格頭、程式碼、Properties） |
| outlineVariant | `#CAC4D0` | 分隔線、表格格線、縮排導引線 |
| H1 | `#4F378B`（primary 加深） | |
| H2 | `#6750A4` | |
| H3 | `#7D5260`（tertiary，玫瑰紫） | |
| H4 | `#625B71`（secondary） | |
| H5–H6 | onSurfaceVariant `#49454F` | |
| link | primary＋底線（hover 加深）；未解析 wikilink 用 primary 50% 透明度虛線 | |
| callout note/tip/warning/danger | 底色為對應色 10–12% 透明度、左邊條 3dp 實色（藍紫／綠／琥珀／紅） | |

暗色（選做）：沿用 M3 baseline dark（primary `#D0BCFF`、surface `#141218`），標題色改用 tone 80 系列。由 Settings「外觀：跟隨系統／亮／暗」控制。

### 4.2 字級（內容區，CJK 為主，Noto Sans TC）
| 元素 | 大小 / 行高 | 字重 | 上距 / 下距 |
|---|---|---|---|
| 文件標題（檔名） | 26sp / 34 | SemiBold（若只有 Regular 字檔則以大小＋顏色區分） | 0 / 12 |
| H1 | 24 / 32 | SemiBold | 28 / 12 |
| H2 | 20 / 28 | SemiBold，下方 1dp outlineVariant 線 | 24 / 10 |
| H3 | 17 / 26 | Medium | 20 / 8 |
| H4–H6 | 15 / 24 | Medium | 16 / 6 |
| 本文 | 15sp / 行高 1.7（≈26sp） | Regular | 段距 10dp |
| 次要（狀態列、屬性鍵） | 12 / 16 | Regular | |
| 程式碼 | 13sp Monospace / 20 | | |

注意：目前只打包 `notosanstc_regular`，粗體會是合成粗體。建議 Open Decision：是否加打包 Medium/Bold（檔案大小 vs 品質）。

### 4.3 間距與尺寸
- 間距階梯 4 / 8 / 12 / 16 / 24 / 32dp；圓角 6dp（小卡）、10dp（大卡）。
- **閱讀最大寬度 760dp**（約 40–45 個中文字）置中；可在設定開關「限制行寬」。編輯模式同寬度，保持切換時位置不跳。
- 內容區左右最小 padding 32dp。

### 4.4 區塊樣式
- **程式碼**：surfaceContainer 底、6dp 圓角、12dp padding、水平捲動不換行、右上角語言標籤＋複製鈕（hover 顯示）。
- **表格**：外框 1dp outlineVariant、6dp 圓角；表頭 surfaceContainer 底＋Medium；儲存格 padding 8×12dp；斑馬紋 surface/surfaceContainerLow（可選）；遵守 `:---:` 對齊；寬於內容時水平捲動，不壓縮欄位。
- **引用**：左邊條 3dp primary 40%、文字 onSurfaceVariant、左 padding 12dp。
- **Callout**（`> [!note] 標題`）：見 4.1；標題列含圖示＋粗體，可折疊 `[!note]-`（P2）。
- **水平線**：1dp outlineVariant，上下 16dp。
- **任務清單**：唯讀顯示勾選框（閱讀模式不寫回檔案，避免違反寫入契約；寫回列為 Open Decision）。

## 5. 版面

```
┌ TopMenu（不變）────────────────────────────────────────────┐
├ 側欄 ─────────┬┄┬ 右側 ──────────────────────────────────────┤
│ Vault  [⇤]    │║│ 麵包屑：mystudy / 隨筆 / 2026 / 10         │
│ ▾ 📁 隨筆      │║│ 標題（26sp, primary 深）   [閱讀|編輯] ⋯   │
│   ▾ 📁 2026   │║│ ── 編輯時：[Live Preview | Source] ──      │
│     📄 長檔名…│║│ ┌ Properties ┐                            │
│               │║│ 內容（max 760dp 置中）        │ 大綱(選) │
├───────────────┴┄┴────────────────────────────────────────────┤
│ 狀態列：● 未存 / 已存 15:24 · 屬性 5 · 1,234 字 · 唯讀原因 · Ingest 提示 │
└──────────────────────────────────────────────────────────────┘
```

### 5.1 可拖曳側欄（P0，使用者要求）
- 分隔線改為 6dp 透明「把手」區＋中間 1dp 可見線；hover 時線變 primary 2dp，游標 `PointerIcon(Cursor(E_RESIZE_CURSOR))`（Desktop `pointerHoverIcon`）。
- `Modifier.draggable(Orientation.Horizontal)` 或 `pointerInput { detectHorizontalDragGestures }` 調整 `sidebarWidth`。
- 範圍：min 180dp、max = min(560dp, 視窗寬 45%)；預設 260dp（與現況一致）。
- 雙擊把手 → 還原預設寬。拖到 < 120dp 時吸附為收合（等同關閉 file browser，沿用 `fileBrowserOpen`）。
- **只在拖曳結束時**寫入 Preferences（新鍵如 `ui.sidebarWidthDp`），經 `SettingsStore`，不寫 vault；拖曳中只改記憶體狀態，避免每幀 flush。
- 鍵盤無障礙：把手可聚焦，←/→ 每次 16dp。

### 5.2 麵包屑（P1）
- 相對於 vault 的路徑，資料夾段落可點 → 在樹中展開並捲到該資料夾；最後一段為檔名（不重複標題時可省略）。
- 12sp onSurfaceVariant，分隔符 `/` 或 `›`，過長時中間段落折疊為 `…`。

### 5.3 狀態列（P1）
- 高 24dp、surfaceContainerLow、12sp。把現在散落的 `noteStatus`、`noteWarning`、`noteMixed`、唯讀原因、ingest 提示/進度收進來；左：儲存狀態；中：警告（tertiary 色＋圖示，點擊展開完整說明）；右：屬性數、字數／字元數（CJK 以字元計）、目前模式。
- 外部變更橫幅（需要動作）保留在內容上方，不收進狀態列。

### 5.4 大綱面板（P2）
- 右側可收合 220dp 面板，列出 H1–H3，點擊捲動到該標題；以 `LazyColumn` 的 item index 或記錄各標題 `onGloballyPositioned` 座標實作。視窗 < 1200dp 時預設收合。

## 6. 檔案樹改進
- 每列高 28dp、文字 13sp（現在 bodySmall 偏小）。
- 圖示：資料夾（開/關）、Markdown、圖片、其他；`raw/` 用淡色以示唯讀。
- 縮排 16dp/層＋1dp outlineVariant 縱向導引線。
- 目前檔案：primary 12% 底＋左側 2dp primary 條；hover：onSurface 6% 底。
- 長檔名：仍單行省略，但 **hover 0.5 秒顯示 `TooltipArea` 完整路徑**；側欄可調寬後問題大幅緩解。可選中段省略（保留副檔名與尾端日期）。
- 標題「Vault」改為 vault 資料夾名＋收合鈕 `⇤`。

## 7. 右側標頭重設計
- 第一列：標題（文件標題字級）＋ dirty「●」（primary）。
- 右側操作群：
  - **模式切換：Segmented control `閱讀 | 編輯`**（M3 `SingleChoiceSegmentedButtonRow`），Ctrl/Cmd+E 切換；tooltip 顯示快捷鍵。
  - 進入編輯時，第二層 **`Live Preview | Source`** 小型 segmented（或一個切換圖示），快捷鍵建議 `Ctrl/Cmd+Shift+E`（Open Decision）；選擇存 Preferences（`ui.editorMode`）。
  - 存檔：只在編輯模式顯示；dirty 時為 tonal 按鈕，否則隱藏或淡化（狀態列已有「已存」）。
  - 複製全文：圖示按鈕＋tooltip。
  - **Ingest**：降為 `FilledTonalButton` 或 `OutlinedButton`（保留紫色但不實心飽和），放在操作群最右；提示文字移到 tooltip 與狀態列。Ingest 是「重要但非每次」的動作，不應比模式／存檔更搶眼。Open Decision：使用者若習慣它最醒目可保留實心。
- 唯讀時「編輯」段落停用並 tooltip 顯示原因（沿用現有守門理由）。

## 8. Properties 面板（frontmatter）
- 重用 `wiki/markdown/MarkdownDocs.kt` 的 frontmatter 解析；`frontmatterUnparsed` 時退回現在的原樣等寬卡片並加一行「YAML 無法解析」。
- 卡片：surfaceContainer 底、10dp 圓角、可折疊（記住狀態），標題「屬性 (n)」。
- 每列：圖示＋鍵（12sp onSurfaceVariant，固定 120dp 寬）＋值：
  - `tags`/list → 膠囊（primary 12% 底、primary 字、`#` 前綴）
  - 日期（`YYYY-MM-DD`）→ 日曆圖示＋文字
  - URL → 可點連結（`LocalUriHandler`）；`[[x]]` → 內部連結（點擊在樹中開啟）
  - 布林 → 唯讀 checkbox；其他 → 文字
- **第一版唯讀**；「+ 新增屬性」與行內編輯屬於寫入功能，列為 Phase 後續／Open Decision。Source mode 顯示原始 YAML。

## 9. Markdown 渲染策略

### 9.1 選項
| 方案 | 優點 | 缺點 |
|---|---|---|
| A. 擴充自寫 `MarkdownText` | 零依賴、完全控制 CJK 字型與樣式、與 Chat/Ask 共用 | 要自己處理表格、巢狀清單、引用等邊界；長期維護成本 |
| B. `mikepenz/multiplatform-markdown-renderer`（Compose MP，支援表格、可自訂 typography/colors、底層 JetBrains `markdown` parser） | 成熟、表格/清單/引用/連結齊全 | 新依賴需確認與 Compose 1.10.0 相容；`[[wikilink]]`、callout、frontmatter 需自訂；樣式自由度受限於其 API |
| C. 只用 `org.jetbrains:markdown` parser（AST）＋自寫 Compose 渲染 | 解析交給成熟 parser（GFM 表格、任務清單），渲染完全自控；也提供 Live Preview 需要的 **block offset** | 渲染層仍要寫，但比 A 單純 |

### 9.2 建議
**採 C**（或 Plan 階段驗證後選 B）：現有 `MarkdownBlock` sealed class 結構可保留，改由 AST 產生並擴充 `Table`、`Quote`、`Callout`、`OrderedList`、`Task`、`Rule`、`Image`（P2）；每個 block 帶 `sourceRange`，正好供 Live Preview 使用。wikilink、callout 以後處理。
**Fallback**：若 Plan 判定新依賴不可行，走 A，**最少補表格**（連續以 `|` 開頭的行＋第二行為分隔列 → `Table`）、有序清單、引用、水平線、連結。

Chat/Ask 使用同一渲染器，需回歸測試這些畫面。表格以 `Row`/`Column` ＋ 每欄量測最大寬度（`SubcomposeLayout` 或簡化：固定欄寬加權）實作；超寬時 `horizontalScroll`。

### 9.3 渲染效能
長筆記用 `LazyColumn` 逐 block 呈現（現在 `Column`＋`verticalScroll` 對大檔不利）；解析放 `remember(text)` 並在 `Dispatchers.Default` 做。

## 10. 編輯模式：Live Preview 與 Source

### 10.1 Source mode（已有，精修）
- 等寬字 14sp、行高 1.6、最大寬 760dp 置中、可選行號（P2）；輕量語法著色（標題行 primary、`**`、`` ` ``、連結以淡色）用 `OutputTransformation` 或 `VisualTransformation`，只改樣式不改文字。

### 10.2 Live Preview（務實範圍）
Compose `BasicTextField` 無法像 CodeMirror 那樣以 decoration 把部分文字換成元件。建議 **block 級 Live Preview**：
- 文件依 9.2 的 block（含 `sourceRange`）切成 `LazyColumn`；**游標所在的 block** 顯示為 `BasicTextField`（原始碼），其他 block 顯示渲染結果。
- 點擊渲染中的 block → 把該 block 切成編輯狀態並將游標放在點擊位置附近（以 block 起點近似即可）。上下鍵越過 block 邊界時切換到相鄰 block。
- 每次編輯只替換該 block 的子字串並回寫 `noteDraft`（單一真實來源仍是整份文字；undo 以整份 `TextFieldState` 或 block 層 undo 疊加，需在 Plan 定案）。
- Frontmatter 在 Live Preview 顯示為 Properties 卡片，點擊則展開為 YAML 編輯。
- 跨 block 選取／貼上多段：在 Live Preview 中受限，提示使用 Source mode。

**Fallback 階梯**：
1. Phase L0：Live Preview 選項先做成「**分割檢視**」— 左 Source、右即時渲染（同步捲動以 block 對應）。實作最穩。
2. Phase L1：block 級 Live Preview（上述）。
3. 行內級（游標所在 `**粗體**` 才露標記）：不建議，Compose 目前代價過高。

## 11. 互動與快捷鍵
- `Ctrl/Cmd+E` 閱讀↔編輯（已有）；`Ctrl/Cmd+Shift+E`（建議）Live Preview↔Source；`Ctrl/Cmd+S` 存檔（已有）；`Ctrl/Cmd+\` 收合側欄（建議）。
- 模式切換保持捲動位置（以目前頂端 block index 對應）。
- 閱讀模式維持 `SelectionContainer` 可選取複製。

## 12. 無障礙
- 文字對比 ≥ 4.5:1（標題色在 `#FEF7FF` 上皆符合；callout 底色上需驗證）。
- 所有圖示按鈕有 `contentDescription` 與 tooltip；把手、segmented control、樹節點可 Tab 聚焦並有可見焦點框（primary 2dp）。
- 不以顏色為唯一資訊：dirty 用 ●＋文字「未存」、唯讀用鎖頭圖示＋原因。
- 支援字級縮放：Settings「內容字級」90–130%，存 Preferences。
- 點擊目標 ≥ 28dp（桌面）。

## 13. 需觸及的程式（預估）
- 新增：`ui/theme/StudyTokens.kt`（colors/typography/spacing）、`study/markdown/MarkdownBlocks.kt`（AST→blocks＋sourceRange）、`study/markdown/MarkdownTable.kt`、`study/PropertiesPanel.kt`、`study/ResizableSplit.kt`、`study/Breadcrumb.kt`、`study/StatusBar.kt`、`study/LivePreviewEditor.kt`、（P2）`study/OutlinePane.kt`。
- 修改：`Theme.kt`、`MarkdownText.kt`、`NotePreview.kt`、`NoteSession.kt`（`EditorMode { LivePreview, Source }`）、`StudyPane.kt`、`FileTreePane.kt`、`StudyController.kt`（側欄寬、編輯子模式、目前檔案）、`AppSettings.kt`（UI 偏好鍵）、`StudyScreens.kt`（Settings 外觀選項）、`gradle/libs.versions.toml`（若採 B/C）。

## 14. 測試
- `MarkdownBlocksTest`：表格（對齊、跳脫 `\|`、CJK、欄數不齊）、frontmatter 分離、引用／callout／有序清單／任務清單、fenced code 內的 `|` 不當表格、`sourceRange` 正確且覆蓋全文。
- `PropertiesTest`：tags 列表／字串兩種寫法、日期、URL、wikilink、無法解析的 YAML 回退。
- `UiPrefsTest`：側欄寬 clamp、只在拖曳結束時寫入（`MemorySettingsStore` 計數）、編輯子模式記憶。
- `LivePreviewEditTest`：替換單一 block 後全文正確、BOM/CRLF 不變（沿用既有存檔測試）。
- 既有 `./gradlew :composeApp:desktopTest` 全過；Chat/Ask/深度研究答案的渲染回歸。
- 手動：`./gradlew :composeApp:run` 以截圖中的同一則筆記比對前後。

## 15. 文件與版號
- 新增 `docs/programming/ui_ux_design_ref.md`（本文）、`docs/programming/How-To-do-UI-Layout.md`（使用者操作：拖曳側欄、模式切換、快捷鍵、設定）。
- 更新 `AGENTS.md`（UI 偏好只存 Preferences、閱讀模式不寫檔）、`docs/Architecture.md`（Markdown 渲染管線）、`README` 截圖/功能列。
- 版號：`./configure.sh version 1.2.0`（新增功能為 minor），勿手改單一檔。

## 16. 交付階段（一次交付，標出退守點）
1. **Phase 1（P0）**：tokens＋明確 colorScheme；Markdown 渲染（表格、標題層級、行高、限寬）；Properties 唯讀；可拖曳側欄＋持久化；樹 tooltip。← 最低可交付點
2. **Phase 2（P1）**：標頭 segmented control、Ingest 降級、狀態列、麵包屑、樹圖示/高亮/導引線、連結/引用/callout/清單。
3. **Phase 3**：編輯子模式 Source 精修＋Live Preview（L0 分割檢視；L1 block 級若可行）。
4. **Phase 4（P2）**：大綱、暗色模式、字數、字級縮放。

## 17. Open Decisions
1. Markdown：方案 B（mikepenz 渲染器）／C（JetBrains parser＋自渲染）／A（擴充自寫）。
2. Live Preview 第一版做 L0 分割檢視或直接 L1 block 級。
3. Live Preview↔Source 快捷鍵（`Ctrl/Cmd+Shift+E`？）與編輯預設子模式。
4. Ingest 按鈕是否降為 tonal/outlined。
5. 閱讀最大寬度 760dp 是否可調、是否預設開啟。
6. 是否打包 Noto Sans TC Medium/Bold。
7. 側欄 min/max/預設值與拖小時是否自動收合。
8. Properties 是否允許編輯／「+ 新增屬性」（涉及寫入契約）。
9. 任務清單勾選是否寫回檔案。
10. 暗色模式是否本版做；是否跟隨系統。
11. 大綱面板、tabs 是否納入。
12. Chat/Ask 是否同步採用新渲染樣式（或保留緊湊版）。
13. 文件檔名與版號（1.2.0）。
