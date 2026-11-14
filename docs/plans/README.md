# Design plans

Historical design documents. They record **decisions and their reasoning at the time**, not the
current state of the code. Where a plan disagrees with the source, the source wins.

They are kept because the "why" behind a rule is usually invisible in the code — a comment can say
"validate before the gate", but only the plan says which outage made that necessary.

| Document | What it covers |
|---|---|
| [LOGGING-AND-POLICY-PLAN.md](LOGGING-AND-POLICY-PLAN.md) | The approval gate, the policy model, and the audit log. The largest one, and the one most rules trace back to. |
| [SECRET-BROKER-PLAN.md](SECRET-BROKER-PLAN.md) | Third-party API calls routed through the phone, so keys never leave it. |
| [EXCEPTIONS-PLAN.md](EXCEPTIONS-PLAN.md) | Per-`plugin × verb` rules on the exceptions screen. |
| [GOOGLE-GATEWAY-PLAN.md](GOOGLE-GATEWAY-PLAN.md) | Planned: Gmail/Calendar/Drive as first-class capabilities. |
| [PROTON-PLAN.md](PROTON-PLAN.md) | Whether Proton exposes anything usable at all. |
| [TASKER-PLAN.md](TASKER-PLAN.md) | Notification-driven automation. |
| [SECURITY-REVIEW.md](SECURITY-REVIEW.md) | The threat model and what it deliberately does not defend against. |
| [SHELL-HIGHLIGHT-PLAN.md](SHELL-HIGHLIGHT-PLAN.md) | The zsh-compatible shell lexer, and what the SSH card does with it. |

## Reading order

Start with `SECURITY-REVIEW.md` — it states the threat model the rest assume. Then
`LOGGING-AND-POLICY-PLAN.md`, because almost every other rule is a consequence of it.

## These are not documentation

For how to run or configure the bridge, see [../README.md](../../README.md). For what changed and
when, see [../../CHANGELOG.md](../../CHANGELOG.md).
