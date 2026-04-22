---
name: qlik-research
description: Answer Qlik-internal questions by searching the Qlik Knowledge Base MCP (engineering docs, QAC design reviews and ADRs, user research, personas), downloading supporting sources, and citing them with the KB's prescribed URL templates.
---

# Qlik internal research

Given a question about Qlik-internal engineering, architecture, user research, or product design, use the `qlik-kb` MCP to find grounded answers and cite the source documents. Do not guess from general knowledge - Qlik-internal behavior is not covered by public training data.

## Arguments

`$ARGUMENTS` - the question or topic to research. If empty, ask the user to state it.

## Step 1: Pick the right KB

Route to the narrowest KB first; widen only if no relevant chunks come back.

| Topic | KB alias |
|---|---|
| SDE setup, dev portal howtos, coding guidelines, API standards (JSDoc, lifecycle), Go Service Kit, JWT/authz, feature flags, telemetry, SRE practices, onboarding | `search-engineering-docs` |
| Architecture design reviews across Analytics / Data / SaaSOps / AI / QPaaS / Central, ADRs by domain (AI, API 2.0, Data, QAC, TLT), allowed API-namespace registry | `architecture-design-docs` |
| User-research reports, usability studies, benchmarks, strategy reports, discovery research | `Condens_Knowledge` |
| Personas, journey maps, mental models, pain-point/opportunity synthesis | `Qlik_user_personas` |

When a question spans two KBs (e.g. "how did we implement X and what research backed the design"), run both searches in parallel.

## Step 2: Search and filter

- Call the KB's search tool with a focused prompt. Prefer concrete terms (feature name, component, ADR number) over generic phrasing.
- The tool returns a prose preamble followed by a JSON payload; rely on the JSON for `chunks`, `score`, `doc.path`, `kbId`, `datasourceId`.
- Evaluate each chunk for scope, product area, study type, and recency. Discard low-score or off-topic chunks before downloading.

## Step 3: Download supported sources

- For each relevant chunk whose `doc.path` ends in `.md`, `.txt`, `.html`, `.htm`, `.pdf`, or `.json`: call `download_kb_document` (or the per-KB `download_kb_{alias}` when present) with `kbId`, `datasourceId`, `path`.
- Skip binary or office formats (`.xlsx`, `.pptx`, `.docx`, `.png`, `.jpg`, `.zip`, etc.) - rely on the chunk text. The server cannot extract them cleanly.
- PDFs come back as extracted text; text/markdown as utf-8. Quote only what is needed.

## Step 4: Cite using the KB's downloadPreamble

Each search result's preamble carries a mandatory citation format specific to that KB. Honor it verbatim. Known templates:

- `search-engineering-docs`: rewrite `github/qlik-trial/internal-qlik-dev/src/content/articles/{path}.md` as `[Title](https://internal.qlik.dev/{path})`.
- `architecture-design-docs`: rewrite `kb-tests/qac/{path}` as `[Title](https://github.com/qlik-trial/qac/blob/main/{path})`.
- `Condens_Knowledge` / `Qlik_user_personas`: include clickable links back to the originating report as the preamble directs.

If the preamble in a future response changes, follow the new instruction - it is authoritative.

## Step 5: Detect failures and degrade gracefully

Recognize failures in any of these forms and do not loop:

- Auth or connectivity error - the MCP subprocess's pre-flight should have killed startup on 401/403. If tools are missing or ECA reports the server failed to start, it is almost certainly the token; refresh at `https://qcs.us.qlikcloud.com/settings/api-keys` and run `bb setup.bb`.
- Success-wrapped server error. The upstream server returns malformed-request failures inside a normal `Tool Result: Success` envelope with a top-level `{"error": "..."}` in the JSON body (not inside `chunks`). Empty queries, bad params, and similar Qlik Cloud 4xx responses all surface this way. Treat it as a failure, not as content.
- Empty `chunks` array. The KB has nothing matching the query; widen or rephrase, or try a different KB before giving up.

On any failure:

- Surface the exact error text to the user with the likely fix.
- Fall back to `web_search` against public Qlik docs (`qlik.dev`, `help.qlik.com`) only for the cases those cover, and state explicitly that the answer is from public sources, not the internal KB.
- If the binary itself is stale after a recent upstream release, suggest `bb tools/qlik-kb/update.bb`.

## Step 6: Summarize

Provide a concise answer with:

1. One-paragraph answer grounded in the retrieved chunks.
2. Clickable citations for every claim, formatted per Step 4.
3. Confidence signal: how many distinct documents back the claim, and their recency when visible in the chunks.
4. Any KB whose search failed or returned nothing relevant - state it so the user knows the search surface was incomplete.
