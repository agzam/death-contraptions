---
name: metrics
description: Search and query Grafana metrics (metrics.qlikcloud.io) to investigate a ticket - find the relevant dashboard, read its PromQL, run and adjust queries, and correlate with splunk/k8s. Backed by the metrics MCP tools, which borrow the Brave session cookie and auto-open login when stale. Use when asked to look into metrics, dashboards, or PromQL for a problem or ticket.
---

# Metrics investigation (metrics)

Query the Grafana metrics backend to investigate a problem (often a Jira
ticket). The `metrics` MCP tools borrow your Brave session cookie; if the
session is stale the first call auto-opens the Grafana login - finish SSO/MFA
once if prompted, then it proceeds.

## 1. Make sure metrics is configured

`:metrics {:host "metrics.qlikcloud.io"}` under `:servers` in
`local-config.edn.gpg`, then `bb setup.bb`, then reload the MCP. macOS + Brave
only.

## 2. Find the datasource and dashboard

- `metrics-list-datasources` to see the backends (note the Prometheus uid).
- `metrics-search-dashboards` with terms from the ticket (service, symptom,
  team) to find an existing dashboard.

## 3. Read what the dashboard already asks

- `metrics-get-dashboard` with the uid dumps each panel's title and PromQL.
  Reuse or adapt those expressions instead of guessing.

## 4. Query and narrow

- `metrics-query` runs a PromQL expression. Default range is the last hour; pass
  `start`/`end` (e.g. `-6h`, `now`, epoch, or RFC3339), `step`, or
  `instant=true`. Use `metrics-metric-names` (optionally with a `filter`
  substring) to discover metric names.

## 5. Correlate

Pull the time window and labels from the metrics, then line them up with logs
(`splunk`) and, on an SDE, pod state (`k8s`) for the same service + window. See
the `monitor` skill for live tailing during a repro.

## Notes

- Read-only: every tool is an HTTP GET. No mutation.
- If a call reports "not authenticated", a Brave window was opened - finish
  login there and retry.
