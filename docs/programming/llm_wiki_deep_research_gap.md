# mystudy vs llm_wiki：Deep Research 差距（簡要）

| 項目 | llm_wiki | mystudy（v0.7） | 計畫（v0.8） |
|---|---|---|---|
| Review 儲存 | `.llm-wiki/review.json`，有 resolved 狀態 | `wiki/review.md` 只 append | 仍用 review.md；研究成功即**移除**該段（不保留 resolved） |
| Review 畫面 | 獨立 Review 頁 | 無（只在 Ingest 確認框勾選） | TopMenu「深度研究」頁 |
| 研究按鈕 | 只 suggestion／missing-page；另有批次 | 無 | 每筆都有；不做批次 |
| 搜尋來源 | 多 provider（Tavily、SearXNG…）＋AnyTXT | 無網路 | 只 SearXNG，預設 `http://127.0.0.1:8080` |
| 來源檢查 | `hasConfiguredDeepResearchSources` 跳提示 | — | Settings 連線測試；失敗則按鈕停用 |
| 佇列 | 記憶體，最多 3 並行 | — | 一次一筆，記憶體 |
| 綜整 | 串流 LLM，`validateResearchSynthesis` 檢查引用 | — | Ollama `/api/chat` 串流，同樣驗證 `[n]` |
| 輸出 | `wiki/queries/research-*.md` | — | 同路徑，加 frontmatter 與 Sources，更新 index/log |
| 後處理 | embedding；不 ingest | — | 重新索引（SQLite）；不 ingest、無 embedding |
| Sweep | 佇列清空時自動關閉 review | 無 | 不做 |
| Prompt 位置 | TS 程式內 | `ai-prompts/` | 新增 `ai-prompts/deep-research.md` |
| 平台 | Tauri（Rust + React） | Compose desktop JVM | 維持 JVM，不加 Rust/Tauri |

刻意不追：批次研究、AnyTXT、embedding、sweep、研究任務持久化、Review 專用 LLM 階段。
