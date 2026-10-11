動作：Draft。把已接受的 claims 起草成 wiki 小節前讀這個檔。這段前言與區段裡的 HTML 註解不送給模型。
改完存檔即可。從專案目錄執行時，下一次 Draft 生效。
程式固定要求 JSON（format: json）。改這裡的文字不會關掉那個設定。

為什麼目前這樣下：
- 只根據已接受的 claims 與既有頁摘錄起草，不另加想法。
- 每個 concept 或 entity claim 一則 page，標題用該 claim 的標題。路徑由 buildIngestProposal 決定，模型不指定路徑。
- 與既有摘錄矛盾時 conflict 為 true，不重寫既有頁。
- user 帶入 schema.md，起草才跟本地寫作規則走。既有頁用來判斷是否矛盾。沒有既有頁時填 none 區段的文字。
- JSON 欄位名是程式契約：sourceSummary、sourceBody、pages、title、type、summary、section、conflict、conflictNote、aliases、tags、logTitle。改掉名字會讓解析失敗，不寫檔。

請保留 {{schema}}、{{claims}}、{{existing}}，claim 區段的 {{title}}、{{type}}、{{statement}}，existing 區段的 {{path}}、{{title}}、{{body}}，ambiguous 區段的 {{title}}、{{paths}}。

這段前言不會送給模型。

--- system ---
You draft wiki sections from the accepted claims and the existing page excerpts. Do not add ideas that are not in the claims.
Return JSON: {"sourceSummary":"","sourceBody":"","pages":[{"title":"","type":"concept|entity","summary":"","section":"","conflict":false,"conflictNote":"","aliases":[],"tags":[]}],"logTitle":""} <!-- 欄位名是程式解析契約 -->
Include one pages item for each claim whose type is concept or entity. Use that claim's title. <!-- 路徑由程式決定 -->
Set conflict true when the claim contradicts an existing excerpt. Do not rewrite the existing page.
JSON only.

--- user ---
## schema.md
{{schema}}

## claims
{{claims}}

## existing pages
{{existing}}

--- claim ---
- title: {{title}} type: {{type}}
  {{statement}}

--- existing ---
## {{path}}
title: {{title}}
{{body}}

--- ambiguous ---
Ambiguous title {{title}}: {{paths}}

--- none ---
(none)
