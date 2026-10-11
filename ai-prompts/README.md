# AI prompts

每個會打到模型的動作一個檔。程式在送出前讀檔，不把內容編譯進程式。改完存檔後，下一次 Ask、Chat、Extract 或 Draft 就用新內容。

| 檔案 | 動作 |
| --- | --- |
| `ask.md` | Wiki Ask（Lexical Ask，選單 Ask (keyword)） |
| `chat-agent.md` | Wiki Chat Agent |
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

Ask 不要求 JSON。Extract、Draft、Chat Agent 的程式固定加上 `format: json`，改 prompt 文字不會關掉它。旗標在程式。Extract 與 Draft 的 JSON 欄位名，以及 Chat Agent 的 `action`、`tool`、`query`、`path`、`answer` 與工具名 `wiki.search`、`wiki.read_page`，是程式解析用的契約，改掉名字會讓這一輪停下、不寫檔。

## chat-agent.md

五個區段：`system`、`user`、`reference`、`final-system`、`final-user`。

| 區段 | 佔位 |
| --- | --- |
| `system`、`final-system` | `{{schema}}` |
| `user` | `{{history}}`、`{{references}}`、`{{observations}}`、`{{question}}`、`{{step}}`、`{{max_steps}}`、`{{retrievals_left}}` |
| `reference` | `{{number}}`、`{{title}}`、`{{kind}}`、`{{path}}`、`{{snippet}}`、`{{links}}` |
| `final-user` | `{{history}}`、`{{references}}`、`{{observations}}`、`{{question}}` |

工具說明寫在區段正文，沒有 `{{tools}}`。空的歷史、references、觀察由程式填 `(none)`。`render` 會在佔位沒有值，或傳入的鍵沒出現在該區段時丟例外，所以每次只傳該區段自己的鍵。尋找 `ai-prompts/` 的條件仍是 `ask.md`、`ingest-extract.md`、`ingest-draft.md` 三個檔都在；`chat-agent.md` 不在這組必要檔名裡。缺區段時 Chat 回 `NotCalled`，不打模型。
