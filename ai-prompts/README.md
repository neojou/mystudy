# AI prompts

每個會打到模型的動作一個檔。程式在送出前讀檔，不把內容編譯進程式。改完存檔後，下一次 Ask、Chat、Ingest、深度研究或 Wiki Lint 就用新內容。

| 檔案 | 動作 |
| --- | --- |
| `ask.md` | Wiki Ask（Lexical Ask，選單 Ask (keyword)） |
| `chat-agent.md` | Wiki Chat Agent |
| `ingest-analyze.md` | Ingest 第一次呼叫：分析這則筆記 |
| `ingest-generate.md` | Ingest 第二次呼叫：輸出 FILE 與 REVIEW 區塊 |
| `deep-research.md` | 深度研究：把 SearXNG 命中綜整成一頁 |
| `wiki-lint-semantic.md` | Wiki Lint 語意健檢：一批頁面一次呼叫 |

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

Ask、Ingest、深度研究與 Wiki Lint 不要求 JSON。Chat Agent 的程式固定加上 `format: json`，改 prompt 文字不會關掉它。旗標在程式。Chat Agent 的 `action`、`tool`、`query`、`path`、`answer` 與工具名 `wiki.search`、`wiki.read_page`，是程式解析用的契約，改掉名字會讓這一輪停下、不寫檔。

深度研究的解析契約只有 `[n]`。n 是這次搜尋結果的編號，從 1 開始。程式要求每個 `[n]` 都落在範圍內，正文至少 80 字。改掉這個寫法，這一輪不會寫檔。

Ingest 的解析契約是整行標記，不是 JSON：

```text
---FILE: wiki/concepts/標題.md---
---END FILE---
---REVIEW: contradiction | 標題---
OPTIONS: Create Page | Skip
SEARCH: 查詢
---END REVIEW---
```

REVIEW 的 type 只准 `contradiction`、`duplicate`、`missing-page`、`suggestion`。`OPTIONS:` 與 `SEARCH:` 是 REVIEW 正文裡的行。改掉這些字，這一輪可能沒有合法頁。`{{lang}}` 由程式填 `Traditional Chinese (Taiwan, 繁體中文)`，不是偏好。

## chat-agent.md

五個區段：`system`、`user`、`reference`、`final-system`、`final-user`。

| 區段 | 佔位 |
| --- | --- |
| `system`、`final-system` | `{{schema}}` |
| `user` | `{{history}}`、`{{references}}`、`{{observations}}`、`{{question}}`、`{{step}}`、`{{max_steps}}`、`{{retrievals_left}}` |
| `reference` | `{{number}}`、`{{title}}`、`{{kind}}`、`{{path}}`、`{{snippet}}`、`{{links}}` |
| `final-user` | `{{history}}`、`{{references}}`、`{{observations}}`、`{{question}}` |

工具說明寫在區段正文，沒有 `{{tools}}`。空的歷史、references、觀察由程式填 `(none)`。`render` 會在佔位沒有值，或傳入的鍵沒出現在該區段時丟例外，所以每次只傳該區段自己的鍵。尋找 `ai-prompts/` 的條件是 `ask.md`、`ingest-analyze.md`、`ingest-generate.md` 三個檔都在；`chat-agent.md`、`deep-research.md` 與 `wiki-lint-semantic.md` 不在這組必要檔名裡。缺區段時 Chat 回 `NotCalled`，Ingest 回 `Stopped`，深度研究在搜尋前失敗，都不打模型。缺 Lint 的 prompt 只讓語意階段失敗。

## deep-research.md

三個區段：`system`、`language`、`user`。

| 區段 | 佔位 |
| --- | --- |
| `system` | `{{languageRule}}` |
| `language` | `{{lang}}` |
| `user` | `{{topic}}`、`{{reviewType}}`、`{{description}}`、`{{sources}}`、`{{index}}` |

`{{lang}}` 由程式填 `Traditional Chinese (Taiwan, 繁體中文)`。`{{sources}}` 是 `[n] **title** (url)` 加 snippet。模型不寫 frontmatter，標題從 `##` 開始。

## wiki-lint-semantic.md

三個區段：`system`、`language`、`user`。尋找 `ai-prompts/` 的必要檔名不加它。缺這個檔只讓語意健檢失敗，本地規則的結果仍留著。

| 區段 | 佔位 |
| --- | --- |
| `system` | `{{languageRule}}` |
| `language` | `{{lang}}` |
| `user` | `{{batch}}`、`{{batches}}`、`{{pages}}`、`{{titles}}` |

`{{lang}}` 跟 Ingest 一樣，由程式填 `Traditional Chinese (Taiwan, 繁體中文)`。

解析契約是整行標記，不是 JSON：

```text
---LINT: type | severity | short title---
PAGES: path1.md, path2.md
SEARCH: one web search query
---END LINT---
```

type 只准 `contradiction`、`stale`、`missing-page`、`suggestion`。severity 只准 `warning`、`info`。程式最多留 8 個區塊。改掉這些字，這一輪可能沒有合法發現。
