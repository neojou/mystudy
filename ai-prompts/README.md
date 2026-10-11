# AI prompts

每個會打到模型的動作一個檔。程式在送出前讀檔，不把內容編譯進程式。改完存檔後，下一次 Ask、Extract 或 Draft 就用新內容。

| 檔案 | 動作 |
| --- | --- |
| `ask.md` | Wiki Ask |
| `ingest-extract.md` | 從一篇來源抽出 claims |
| `ingest-draft.md` | 把接受的 claims 起草成 wiki 小節 |

這個 README 不會送給模型。

## 檔案怎麼讀

第一個區段標記之前的文字是前言，給人讀，不送出。區段標記獨佔一行，名稱是小寫英數與連字號：

```text
--- system ---
--- user ---
```

區段收到下一個標記或檔尾。前後空白會去掉。

區段裡的 `<!-- 註解 -->` 會刪掉，可以跨行。用它說明某一句為什麼這樣下。註解不會送給模型。

`{{名稱}}` 由程式填入這次的資料，只替換骨架上的佔位。填進去的頁面正文、來源全文、schema 不會再掃描，所以正文裡的 `{{question}}` 會原樣送出。

Ask 不要求 JSON。Extract 與 Draft 的程式固定加上 `format: json`，改 prompt 文字不會關掉它。Extract 與 Draft 的 JSON 欄位名是程式解析用的契約，改掉名字會讓這一輪停下、不寫檔。
