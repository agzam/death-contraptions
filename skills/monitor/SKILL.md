---
name: monitor
description: Spawn a conditional stream watcher as an ECA background task so the LLM sees matches only. Use instead of repeated MCP polling whenever the user asks to watch, monitor, or be alerted to a condition on a cluster, log tail, or any long-running producer.
---

# Conditional Monitor via ECA Background Tasks

Use this skill when the user asks for long-running observation: "monitor", "watch for", "let me know if/when", "keep an eye on", "alert me if". The monitor runs in a background shell job; the filter runs in the child process, so non-matching lines never reach the conversation context.

## When to reach for this instead of MCP polling

- Watching k8s events/pods/deployments for a condition (CrashLoopBackOff, rollout stall, Warning events).
- Tailing logs for an error pattern (stern, kubectl logs, journalctl, plain files).
- Following any shell-producible stream (splunk CLI stream, redis monitor, tcpdump, etc.) for a specific trigger.

Do not use for one-shot queries. If the user wants a single snapshot, call the relevant MCP tool directly (`k8s-events`, `k8s-resources-list`, `splunk-search`).

## Tool

`tools/monitor/monitor.bb` in this repo. Invoke via its absolute path. No MCP registration - it is a plain Babashka script driven by ECA background jobs.

Flags:

- `--source "<shell cmd>"` - the long-running producer. Runs under `sh -c <cmd>`; on kill the monitor destroys the whole descendant tree.
- Exactly one filter: `--jq <expr>` | `--regex <pat>` | `--grep <substring>`.
- `--live` - push each match into the active ECA chat via `emacsclient --eval '(eca-chat-send-prompt "monitor: ...")'`. Wakes the agent on every match without user input. Requires Emacs server on PATH. Implies `--max-matches` defaults to unlimited.
- `--max-matches <n>` - exit 0 after N matches. Default: 1 without `--live`, unlimited with `--live`.
- `--max-runtime <sec>` - hard ceiling on total runtime. Recommended for any ad-hoc watch so a forgotten monitor cannot linger.

Stdout is match-only. Stderr carries the startup banner and source errors.

## Choosing a mode

| User phrasing | Mode |
|---|---|
| "let me know when X happens" + they will be active in the chat anyway | Default (no `--live`). Monitor exits on first match; the hit surfaces on the user's next turn via ECA's queued completion message. |
| "alert me the moment X happens, I may not be looking" | `--live`. Monitor stays running and pushes each match into the chat immediately, waking the agent. |
| "watch this continuously and react to everything" | `--live`. |
| "wait for X once in the background while I do other things" | `--live --max-matches 1 --max-runtime <sec>`. Fires once, pushes, exits. |
| "give me a periodic pulse of what the cluster/log is doing, but not the firehose" | `--digest-interval <sec> --live`. Every N seconds a dedup+top-N histogram pushes into the chat. Filter optional. |
| "report every 2 minutes on what's happening, only the relevant bits" | `--digest-interval 120 --live` (with or without filter). |

Rules of thumb:

- `--live` without `--digest-interval`: per-match push. Good for rare events. Bad for chatty sources (one agent turn per match = token burn).
- `--digest-interval N --live`: time-windowed push. Good for noisy sources. At most one agent turn per N seconds.
- `--digest-interval N` without `--live`: accumulate but write only to bg-job stdout. Peek manually.
- Always pair any long-running mode with `--max-runtime` so forgotten monitors self-terminate.

## Lifecycle: start, peek, stop

### 1. Start

Pick a short descriptive label for `background=` so multiple monitors are distinguishable in `eca__bg_job list`.

```
eca__shell_command
  command    = /<abs repo path>/tools/monitor/monitor.bb --source '<cmd>' --jq '<expr>' --live --max-runtime 600
  background = <label like k8s-warnings-prod>
```

Returns `job-N`. Record the id. Default to `--live --max-runtime 600` (10 minutes) for ad-hoc watches; drop `--live` only when the user explicitly wants the exit-then-piggyback mode; raise `--max-runtime` only when the user has asked for a longer watch explicitly.

### 2. Peek

On your own cadence (natural turn boundaries, not in a tight loop), read new output:

```
eca__bg_job action=read_output job_id=job-N
```

- Empty result - nothing fired, negligible token cost, move on.
- Non-empty result - one or more pre-filtered matches, one per line. Reason about them, report to the user, take follow-up actions with other MCP tools if needed.

### 3. Stop

When the user changes focus, the task completes, or the session is ending:

```
eca__bg_job action=kill job_id=job-N
```

Always kill monitors you started. They do not auto-stop when the source is long-running.

## Session hygiene

- Before starting a new monitor, call `eca__bg_job action=list`. If an identical monitor (same source + filter) is already running, reuse its job id instead of spawning a second one.
- When the user changes what to watch, kill the obsolete monitor before starting the replacement.
- Peek at natural points in the conversation (after answering a question, before asking the user for the next step). Do not open a polling loop that does nothing but peek.

## Choosing a filter

| Source output               | Filter                                  |
|-----------------------------|-----------------------------------------|
| ND-JSON (e.g. `kubectl -w -o json`, `stern -o json`) | `--jq` with `select(cond) \| {reshape}` |
| Plain text logs             | `--regex` (anchored patterns, alternations) |
| Plain text, simple keyword  | `--grep` (literal substring, no regex escaping) |

With `--jq`, combine selection and projection in one expression so emissions are compact:

```
select(.type == "Warning") | {ns: .involvedObject.namespace, kind: .involvedObject.kind, name: .involvedObject.name, reason: .reason, msg: .message}
```

## Recipes

### k8s Warning events

```
--source "kubectl get events -A -w -o json"
--jq    'select(.type == "Warning") | {ns: .involvedObject.namespace, kind: .involvedObject.kind, name: .involvedObject.name, reason: .reason, msg: .message}'
```

### Pods entering CrashLoopBackOff

```
--source "kubectl get pods -A -w -o json"
--jq    'select((.status.containerStatuses // [])[]?.state.waiting.reason == "CrashLoopBackOff") | {ns: .metadata.namespace, pod: .metadata.name}'
```

### Deployment rollout stall (replicas not advancing)

```
--source "kubectl get deploy -A -w -o json"
--jq    'select(.status.unavailableReplicas // 0 > 0) | {ns: .metadata.namespace, name: .metadata.name, unavailable: .status.unavailableReplicas, available: .status.availableReplicas}'
```

### Log pattern via stern

```
--source "stern app -o raw --no-follow=false"
--regex  "ERROR .*timeout|panic:"
```

### Journald service watch

```
--source "journalctl -f -u my-service"
--grep   "OOMKilled"
```

## Buffering caveat

If a monitor is running and the cluster clearly has matching events but peeks stay empty for minutes, suspect block-buffering in a pipeline stage. Wrap the stage in `stdbuf -oL` (Linux) or `gstdbuf -oL` (macOS, from `coreutils`). `kubectl -w -o json` and `stern` are line-buffered and do not need this; shell pipelines with intermediate `jq`/`awk`/`grep` often do.

## Failure modes to recognize

- `monitor: jq not found on PATH` - install jq or switch to `--regex`.
- `monitor: jq compile error: ...` - the `--jq` expression is malformed; the preflight surfaced it before any matches.
- Source exits immediately with non-zero - wrong `--source` command, missing binary, bad kubectl context. The bg job finishes with non-zero exit; `eca__bg_job list` shows it as done; stderr from the source is inherited into the job output.
- `monitor: --max-runtime <N>s reached, exiting` - expected lifecycle end when `--max-runtime` was set. Not an error. If the user still wants to watch, start a fresh monitor, optionally with a larger budget.
- `monitor: emacsclient push failed ...` - `--live` is on but either Emacs is not running a server, `emacsclient` is missing from PATH, or the eval produced an error. The match is still on stdout/in the bg-job buffer; only the live push failed.
- Live mode fires twice for one event - some sources (notably `fswatch`) emit multiple filesystem events per logical change (Created, then Updated on flush). Each matches separately and each pushes. ECA may coalesce pushes that arrive while the agent is still responding. A `--cooldown` flag is on the future list but not built.
- Peeks stay empty forever and `--max-runtime` is not set - either nothing is matching (verify by relaxing the filter) or block-buffering. The monitor is otherwise happy to sit there indefinitely; use `--max-runtime` to bound this case.

## Report back to the user

When reporting matches:

- Summarize them, do not dump raw lines unless the user asked for raw.
- Group by the key the user cares about (namespace, pod, reason).
- Cite the monitor by label and job id so the user can kill it themselves if they want to.
- Mention when the monitor is still running vs has exited.
