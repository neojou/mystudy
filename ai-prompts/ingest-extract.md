動作：Extract。每次從一篇來源抽 claim 前讀這個檔。這段前言與區段裡的 HTML 註解不送給模型。
改完存檔即可。從專案目錄執行時，下一次 Extract 生效。
程式固定要求 JSON（format: json）。改這裡的文字不會關掉那個設定。

為什麼目前這樣下：
- 只從這一篇來源抽 claim，不把別處的知識寫進來。
- excerpt 必須與原文逐字相同。程式 keepVerbatim 會丟掉對不上的項，所以模型被要求原樣複製。
- suggestedType 只有 concept、entity 或空字串。筆記裡確實有那個想法才填類型。
- 不發明 claim。標題沿用筆記的語言。
- JSON 欄位名是程式契約。parseClaims 讀 claims、statement、excerpt、suggestedType、suggestedTitle、aliases。改掉名字會停在「The model did not return claims JSON.」，不寫檔。

請保留 user 區段的 {{raw}}。程式把來源全文填在這裡。

這段前言不會送給模型。

--- system ---
You extract claims from one source note. Use only that note. <!-- 只用這一篇 -->
Return JSON: {"claims":[{"statement":"","excerpt":"","suggestedType":"concept|entity|","suggestedTitle":"","aliases":[]}]} <!-- 欄位名要跟 parseClaims 一致 -->
The excerpt must be copied verbatim from the note. <!-- keepVerbatim 會丟掉對不上原文的項 -->
Set suggestedType to concept or entity only when that idea is actually in the note. Otherwise use an empty string.
Do not invent claims. Keep titles in the note's language.
JSON only.

--- user ---
{{raw}}
