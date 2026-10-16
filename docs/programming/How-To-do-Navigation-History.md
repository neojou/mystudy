# 程式如何做預覽區導覽歷史

預覽區標題列、檔名左邊有 ‹ ›。它只記這次執行裡、主預覽區開過的筆記，行為與瀏覽器的上一頁／下一頁相同。設計比較在 `docs/programming/navigation_history_design_ref.md`。

不寫 Preferences，不寫 vault。關閉程式、換 vault、換 wiki root 就清空。不打模型，不連網。

## 什麼算一筆

| 來源 | 結果 |
| --- | --- |
| 檔案樹點檔 | 記入，並清空「前進」。 |
| 閱讀、即時預覽、Properties 的 `[[wikilink]]` | 記入，並清空「前進」。唯一命中才開。 |
| ‹ ›、快捷鍵、滑鼠側鍵 | 只移動指標，不清空「前進」。 |
| 麵包屑 | 只在樹裡展開，不切檔，不記入。 |
| 同檔再點 | 不重開，不記入。 |
| 第一次開檔 | 成為目前這一筆。‹ 仍停用。 |
| 索引剛就緒時對同一檔的重載 | 不記入。 |
| Chat、Ask、Ingest | 不開主預覽，不記入。 |

連續同檔只留一筆。A 再到 B 再回到 A，兩筆 A 都留。back 與 forward 各自最多 50 筆，超過從最舊丟，不提示。

新開的檔從閱讀、頂端開始。‹ › 還原離開時的閱讀或編輯；目標不可編就維持閱讀。不還原即時預覽／Source，那仍是全域偏好 `ui.editorSubMode`。閱讀模式還原捲動。編輯的游標與捲動不還原。

## 未存守門

每一次使用者切檔仍走 `requestOpen`。有未存變更時先問「存檔／捨棄／取消」，與換檔、換選單相同。取消或存檔失敗時，歷史指標不動，‹ 仍指向剛才那一筆。捨棄或存檔成功之後才移動指標。

目標檔已不在時跳過並從該方向移除。全部都不在時按鈕停用，狀態列中段顯示「上一則筆記已不存在」或「下一則筆記已不存在」。改名視為刪除，不追蹤。目前開著的檔被刪，仍走既有的衝突對話框，不從歷史抽走這一筆。

## 快捷鍵

掛在 `StudyPane`。只有 Home、而且已經開著檔才動作。

| 平台 | 返回 | 前進 |
| --- | --- | --- |
| macOS | `⌘[`、`⌘⌥←` | `⌘]`、`⌘⌥→` |
| Windows／Linux | `Alt+←`、`Ctrl+Alt+←` | `Alt+→`、`Ctrl+Alt+→` |

編輯中，不帶 Ctrl／Cmd 的 `Alt+←`／`Alt+→` 不攔截，留給文字游標。macOS 上只有 Option 的方向鍵也不攔截。不綁 `Ctrl+[`，避開 AltGr。帶 Shift 不攔截。

Tooltip 在 macOS 顯示 `⌘[`／`⌘]`，在 Windows／Linux 顯示 `Alt+←`／`Alt+→`。停用時是「沒有上一則」／「沒有下一則」。

滑鼠側鍵只在主視窗預覽區盡力支援，視驅動而定。macOS 上很多側鍵不會進到程式。沒有反應時，按鈕與快捷鍵仍可用。Chat／Ask 子視窗不接側鍵。

## 怎麼加一個會記入的入口

呼叫 `requestOpen(path, origin)`。樹用 `NavOrigin.Tree`，連結用 `NavOrigin.Link`。不要直接呼叫 `previewFile`，那條只負責載入，不記歷史。返回與前進不要呼叫 `visit`。

## 測試

`composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/NavigationHistoryTest.kt` 不開視窗、不建構 `StudyController`。它鎖住堆疊、去重、上限、peek／commit、刪檔跳過、dirty 取消不動、捨棄後才移動，以及快捷鍵表（含編輯中 `Alt+←` 不攔截）。

快捷鍵畫面上的焦點與側鍵沒有自動測試。手動看：

```bash
./gradlew :composeApp:run
```

- 樹點 A，捲到中段，點 wikilink 到 B。‹ 回到 A，捲動位置還在。› 回到 B。
- 在 A 點新檔 C 之後，› 停用。
- 未存時按 ‹，出現未存對話框。取消後仍停在原檔。
- 刪掉歷史裡的檔再按 ‹，會跳過它。
- 編輯中 `Alt+←` 仍移動游標。
