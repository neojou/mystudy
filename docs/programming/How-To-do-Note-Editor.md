# 程式如何做筆記檢視／編輯

預覽區可以閱讀、複製、編輯、存檔 vault 裡的文字筆記。這是唯一的手動寫入通道，只經 `assertNoteEdit`。它與 `assertWikiWrite`（Ingest、Lint、深度研究）分開。

不打模型，不連網。不寫 `wiki/log.md`。不呼叫 `applyProposal`。不建新檔、不刪檔、不改名。刪除後經確認的「重新建立並寫入」是唯一會寫出尚不存在檔案的路。

## 畫面

開檔一律是閱讀。不記上次模式。標頭、側欄、Live Preview、Properties 與狀態列的排列在 `docs/programming/How-To-do-UI-Layout.md`。

未存時標題後面有 `●`，主視窗標題是 `mystudy ●`。Ask、Chat 那些子視窗不顯示這個標記。`EditorChrome` 的這個行為不變。

- 閱讀不寫檔。閉合的 frontmatter 以唯讀 Properties 顯示，正文用 Document 樣式渲染。「複製全文」複製編輯緩衝（LF、無 BOM），不是畫面上的渲染結果。
- 編輯：`BasicTextField` 加 `TextFieldState`。即時預覽與 Source 共用這一個狀態。等寬、軟換行。復原走欄位內建的 undo。`Ctrl/Cmd+Z` 與 `Ctrl/Cmd+Y` 不攔截。
- `Ctrl/Cmd+E` 在可編輯時切換閱讀／編輯。`Ctrl/Cmd+Shift+E` 在編輯中切換即時預覽／Source。`Ctrl/Cmd+S` 在編輯中且有未存變更時立刻存檔。這些快捷鍵都吃掉，避免字母進到欄位裡。
- 唯讀時編輯鈕停用，狀態列是「唯讀：」加上原因。非文字、過大、非 UTF-8 的內容區顯示「無法預覽此檔案類型」，不把截斷或錯誤字串放進可編輯緩衝。
- 狀態列另有「未儲存變更」「存檔中…」「已存 HH:mm」、失敗原因、衝突。`index.md`、`log.md`、`review.md` 的維護警示、換行混用與 Ingest 提示在狀態列中段。外部變更橫幅仍在內容上方。
- 檔案樹沒有右鍵選單。點檔案只開啟預覽。編輯、存檔、複製都是預覽區按鈕。Ingest 在標頭最右，不取代模式或存檔。

存檔不設全域 busy。

## 可編輯範圍

`noteEditPermission` 依序命中即停。`assertNoteEdit` 對唯讀路徑丟 `IllegalStateException`，訊息含 `Blocked path:`。

| 條件 | 結果 |
| --- | --- |
| 路徑不在 vault 內（不先解析 symlink） | 唯讀「不在 vault 內」 |
| 檔案或 vault 內的父目錄是 symlink | 唯讀「symlink 不可編輯」 |
| 相對路徑任一段以 `.` 開頭 | 唯讀「點開頭目錄不可編輯」 |
| 副檔名不是 `md`、`markdown`、`txt`（不分大小寫） | 唯讀「非文字檔」 |
| 詞彙上在 `{wikiRoot}/raw/` 底下 | 唯讀「raw/ 不可覆寫」 |
| `{wikiRoot}/wiki/schema.md` | 唯讀「schema.md 不可編輯」 |
| `wiki/index.md`、`log.md`、`review.md` | 可編輯，附維護警示 |
| 檔案不存在 | 唯讀「檔案不存在」 |
| 大於 1_000_000 bytes，或 UTF-8 `REPORT` 解不開 | 唯讀「檔案過大」或「非 UTF-8」 |

其餘既有文字檔可編輯，包含巢狀的 `wiki/**/*.md`（`assertWikiWrite` 會拒絕的那種），以及 wiki root 外面的筆記，例如 `Zettelkasten/b.md`。`.MD` 可編輯，但存檔後不重新索引。沒有副檔名的檔案當非文字。

`wikiRoot` 還沒有時，略過 raw、schema、index、log、review 這幾條。

## 讀取與存檔

讀寫在 IO。載入記下原始位元組的 SHA-256、mtime、size、BOM、換行。編輯緩衝是 LF，並拿掉 UTF-8 BOM；寫回時把 BOM 與換行還原。不加結尾換行。

文字與載入時相同就不寫，也不改 mtime。乾淨的混合換行檔因此不會被重寫。有改過才存：CRLF 與單獨 LF 哪種多就統一成哪種，一樣多則用 LF。

存檔前再跑守門。mtime 與 size 都和快照相同才略過重算雜湊；否則比對 SHA-256。磁碟不同且不是覆寫，回衝突，磁碟位元組保持外來版本。不自動合併。

寫入用 `atomicWriteBytes`：同目錄 `name.mystudy-tmp`，再 `ATOMIC_MOVE`。不建父目錄。`atomicWrite(String)` 仍會建目錄、寫無 BOM 的 UTF-8，筆記存檔不走那條。

檔案已刪除：衝突，選項是「重新建立並寫入」或「取消」。重新建立用同一個路徑、`mustExist = false`，父目錄必須已經在，而且不是 symlink。父目錄不在就不寫、也不建目錄。

衝突的另外兩個選項是「重新載入磁碟版本」與「用我的版本覆寫」。重新載入會把捨棄的草稿放進剪貼簿，並在狀態列說明。沒有 diff。

詞彙上在 `{wikiRoot}/wiki/` 底下、而且相對路徑剛好以 `.md` 結尾（區分大小寫）的檔，存完後在 `wiki-index` dispatcher 呼叫 `reindexRelative`。`.txt`、`.markdown`、`.MD`、wiki 外的筆記不呼叫它，否則非 `.md` 會被 `deletePath`。手動編輯不寫 log。

Watcher 仍只看 wiki root。自己存檔之後的事件若雜湊等於新的基準，不當成外部變更。`.mystudy-tmp` 不是 `.md`，既有 watcher 會略過。wiki root 外的筆記沒有第二個 WatchService；視窗取得焦點、切換閱讀／編輯、以及每次存檔前會再檢查。乾淨時外部變更會自動重載。有未存變更時顯示「檔案已在外部變更」，按鈕是「重新載入（捨棄我的編輯）」。

自動存檔預設關。Settings 的「停止編輯 2 秒後自動存檔」寫 Preferences 鍵 `noteAutosave`，不進 vault。`AppSettings.save` 會寫這個鍵，不碰 `lintIgnore`。開啟時，草稿變更後 2 秒呼叫 `save(force = false)`。衝突或失敗會停到使用者處理。`Ctrl/Cmd+S` 不等這 2 秒。

慢讀用 generation。較舊的結果不能蓋掉較新的檔。vault 或 wiki root 清掉時 generation 增加，並清掉這次開啟。同一檔再點一次不會重開，也不取消進行中的 Ingest。

## 離開

未存時，換檔、換選單、按 Ingest、關主視窗，都先問「存檔／捨棄／取消」。取消則檔案、Ingest、模式都不動，也不取消進行中的 Ingest。存檔失敗或進入衝突時不繼續導覽。衝突處理完且筆記已乾淨，才走原本要去的地方。

`HomeScreen` 仍擁有 `StudyMode`。它把想去的模式交給 `StudyPane`，由 controller 決定放不放行。關窗走 `EditorChrome`。`StudyController` 留在 desktop 的 `StudyPane` 裡。

Browse 的 `openIndexed` 仍讀索引正文，不進這個編輯器。

## 這一版不做

區塊級 Live Preview（游標所在區塊才露原始碼）、`[[` 自動完成、多分頁、檔案歷史、自動合併、暗色、大綱、字級縮放。分割檢視的即時預覽與唯讀 Properties 已在 `docs/programming/How-To-do-UI-Layout.md`。

## 測試

`composeApp/src/desktopTest/kotlin/com/neojou/mystudy/wiki/NoteEditorBehaviorTest.kt` 不開 Compose、不打 Ollama。它鎖住：

1. 權限表，以及 `assertNoteEdit` 對唯讀路徑丟例外。symlink 的存檔不改到目標。
2. 沒改內容就存，mtime 與位元組都不變。
3. 有改就寫入，`.mystudy-tmp` 不留下。
4. CRLF 與 BOM 保留，不補結尾換行。混合換行在乾淨時不重寫；有改才依多數統一，平手用 LF。
5. 外部改過的檔，存檔得到衝突且磁碟仍是外來版本；`force` 之後才是自己的版本。
6. 檔案被刪是衝突。重新建立只在父目錄已存在時寫入。
7. 乾淨的外部變更要重載；有未存變更則留下草稿並標記。
8. 自己存檔後的雜湊不算外部變更。
9. `wiki/*.md` 存完可被索引讀到新正文與 `linksFrom`。wiki 外與 `.txt` 不進索引。log 位元組不變。
10. 超過 1_000_000 bytes 或非 UTF-8 是唯讀，存檔不寫。
11. 未存時離開需要確認。存檔失敗不清除待離開的導覽。
12. `noteAutosave` 預設關，可經 `MemorySettingsStore` 往返，且不擦掉 `lintIgnore`。

快捷鍵、對話框與視窗標題沒有 Compose UI 測試。手動看：

```bash
./gradlew :composeApp:run
```

- 開 `.md` 先是閱讀。frontmatter 是 Properties，其餘是 Markdown。表格有表頭與格線。
- 「複製全文」是原始 Markdown。
- `Ctrl/Cmd+E` 進入編輯。打字後標題與主視窗標題出現 `●`。`Ctrl/Cmd+Z` 可復原。
- `Ctrl/Cmd+S` 存檔後 `●` 消失。用另一個程式改同一個檔，再存會出現衝突，取消不覆寫。
- 未存時換檔、換選單、按 Ingest、關視窗都會問。取消後仍停在原檔。
- `raw/`、`schema.md`、`.obsidian/`、圖片是唯讀，編輯鈕不能按。
- Settings 的自動存檔預設沒勾。

## 常見問題

`raw/` 不能在這裡改，是為了不覆寫 staged 來源，也不跟著 `raw/sources/` 的 symlink 寫到 vault 外面。要改原文，在檔案總管或 Obsidian 改那個目標；預覽這裡只讀。

`schema.md` 仍只由人在 vault 裡維護，手動編輯也不寫它。`index.md`、`log.md`、`review.md` 可以改，但 Ingest、Lint、深度研究也會寫它們，所以存檔前若磁碟已變，會進衝突而不是靜默覆蓋。
