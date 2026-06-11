---
name: query-wikipedia
description: Query summary from Wikipedia for a given topic.
---

# Query Wiki

## Instructions

Call the `run_js` tool using `index.html` and a JSON string for `data` with the following fields:
- **topic**: Required. Extract only the primary entity, person, or event (e.g., "2026 Oscars", "Albert Einstein"). Remove question details, action words, and conversational text such as "winner", "best picture", "who won", or "history of". Search for the broad subject so the tool can return the main article.
- **lang**: Required. The 2-letter language code. This code must match the language of the keywords you provided in the `topic` field. Use standard codes, e.g., "en" (English), "es" (Spanish), "zh" (Chinese), "fr" (French), "de" (German), "ja" (Japanese), "ko" (Korean), "it" (Italian), "pt" (Portuguese), "ru" (Russian), "ar" (Arabic), "hi" (Hindi).

**Constraints:**
- Provide a concise summary (1-3 complete sentences) to conserve context. Always end with a finished sentence, and write the response in the same language as the user's original prompt.
- For recurring events or time-sensitive facts, query the specific iteration (e.g., "2026 Oscars"). If the user omits the year, default to the current year.
- If the exact answer to the user's question is not found in the extract, briefly state this, then proactively offer a related piece of information that *was* found in the text.
