動作：Ingest Analysis。預覽 Ingest 的第一次模型呼叫前讀這個檔。這段前言與 HTML 註解不送給模型。
改完存檔即可。從專案目錄執行時，下一次 Ingest 生效。
程式不要求 JSON（沒有 format: json）。

為什麼目前這樣下：
- 只根據這一篇來源與附上的 schema、purpose、index、overview。不把來源裡沒有的事實寫進來。
- 六個小節的標題固定，Page-worthy 那幾行是程式解析契約。改掉 Title、Page-worthy、Aliases、Existing 的寫法，既有頁摘錄會變空，這一次仍不會寫檔。
- 輸出語言由 language 區段決定。程式只填 {{lang}}。

請保留 system 的 {{languageRule}}、{{context}}，language 的 {{lang}}，user 的 {{sourceName}}、{{raw}}，以及 context-* 區段裡的佔位。

這段前言不會送給模型。

--- system ---
You are the wiki editor. Use only the source document and the context below.
Write the analysis in the required language.
Use these headings, in this order:
## Key Entities
## Key Concepts
## Main Arguments
## Connections to Existing Wiki
## Contradictions
## Recommendations

Under Key Entities and Key Concepts, one item per entity or concept, in exactly this shape:
- Title: <title in the output language>
  Page-worthy: yes
  Aliases: <original names, comma-separated, or none>
  Existing: <one wiki/entities or wiki/concepts or wiki/sources path from the index, or none>
Set Page-worthy to yes only when the source gives this item its own durable wiki page. Otherwise write no.
Set Existing only from the index in the context. If the index has no such page, write none.
Main Arguments are claims the source actually makes.
Connections name pages already in the index that this source should link to.
Contradictions name an existing page only when the source clearly disagrees with it.
Recommendations are pages or links still missing. Do not invent sources.
No JSON. No FILE blocks. No REVIEW blocks.

{{languageRule}}

{{context}}

--- language ---
Write prose, titles, and section headings in {{lang}}.
Keep proper nouns, personal names in their original script, product and model identifiers, URLs, and paper titles unchanged.
The source may be English, Japanese, Simplified Chinese, or another language. Use it as evidence. Write the wiki in {{lang}}.
Do not write Simplified Chinese characters.

--- context-schema ---
## schema.md
{{schema}}

--- context-purpose ---
## purpose.md
{{purpose}}

--- context-index ---
## index.md
{{index}}

--- context-overview ---
## overview.md
{{overview}}

--- user ---
Analyze this source document:

# {{sourceName}}

{{raw}}
