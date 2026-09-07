# Manual testing checklist

Test each provider against a real account before claiming multi-tool support. Contract tests are green (153 tests / 347 assertions) but do not exercise live APIs.

## Status

| Provider | Role | Config via | Status |
| --- | --- | --- | --- |
| GitHub | forge | `gh auth login` | ✅ re-verified 2026-09-07 |
| Linear | tracker | `LINEAR_*` | ✅ re-verified 2026-09-07 |
| GitLab | forge | `GITLAB_TOKEN` | ⚠️ contract-only 2026-09-07 |
| Jira | tracker | `JIRA_EMAIL` + `JIRA_API_TOKEN` + `JIRA_SITE_URL` + `JIRA_CLOUD_ID` | ✅ re-verified 2026-09-07 |
| GitHub Issues | tracker | `gh auth login` (reuses) | ✅ re-verified 2026-09-07 |
| Bitbucket | forge | `BITBUCKET_EMAIL` + `BITBUCKET_API_TOKEN` | ✅ re-verified 2026-09-07 |
| Asana | tracker | `ASANA_TOKEN` (+ `ASANA_WORKSPACE`) | ✅ re-verified 2026-09-07 |

Mark a row ✅ once the full create-and-link pass succeeds.

## Common steps (run per tracker × forge combo)

From inside a git repo with a branch, run:

1. **Configure** — set the provider in `config/ttt.edn` and the credentials in `.env` (or exported env vars).
2. **Auth check** — `ttt setup` prints the authenticated identity without error.
3. **Inspect** — `ttt inspect` resolves the current branch/PR/repo.
4. **Search** — `ttt search --kind item --query "<real word in your backlog>"` returns real results.
5. **Create + link** — `ttt -i --project "<real project>"` (or `--parent "<key or text>"`), confirm, and let it create the issue, rename the branch, push, open the PR/MR, and write the managed links.
6. **Verify both sides**:
   - PR/MR body has a `## <Tracker>` managed section with the issue link.
   - Tracker issue description has the `## Pull requests` section with the PR/MR link.
7. **Link existing** — on a branch that already has an open PR/MR, `ttt -i --parent "<existing key>"`, confirm the issue got the PR link without duplicating.

## Per-provider notes

### GitLab (forge)

- Needs a Personal Access Token with `api` scope.
- `GITLAB_BASE_URL` only for self-hosted; default is `https://gitlab.com`.
- Check the MR URL/display id renders as `group/proj!7`.

### Jira (tracker)

- **Highest-risk spot:** `description` is sent as ADF (v3 format). Confirm the issue description renders as text, not a JSON blob.
- Scoped API tokens use Basic authentication through `https://api.atlassian.com/ex/jira/{cloudId}`. Human links use `JIRA_SITE_URL`.
- Needs `JIRA_PROJECT` (key) or pass `--project`.
- Parent creation makes a **sub-task** (issue type `Sub-task`); a top-level issue uses `JIRA_ISSUE_TYPE` (default `Task`).

### GitHub Issues (tracker)

- **Parent is intentionally unsupported** — creating with `--parent` throws `:unsupported-parent`. Use `--project` (milestone) or no scope.
- Milestones map to "project"; confirm `--project "<milestone>"` works.

### Bitbucket (forge)

- Bitbucket disabled app passwords on 2026-06-09. Use an API token with repository and pull-request read/write scopes.
- `BITBUCKET_EMAIL` is the Atlassian account email. Git push credentials remain separate from the REST API settings.
- Confirm the PR URL/display id renders as `team/repo#7`.

### Asana (tracker)

- **Highest-risk spot:** item search uses `GET /workspaces/{gid}/tasks/search`, which requires a paid Asana plan. Free workspaces fall back to the first 100 tasks assigned to the authenticated user.
- Labels = Asana tags; the tag must already exist (creation by name is not done).
- `ASANA_WORKSPACE` is a numeric gid; if unset, the first workspace is auto-discovered.

## Result log

| Date | Provider | Step | Pass? | Notes |
|---|---|---|---|---|
| 2026-09-07 | GitHub + Linear | Setup and auth | ❌ | Initial setup wrote `In Review`; Threadlog has no matching state. Retest passed with `Todo`. |
| 2026-09-07 | GitHub + Linear | Inspect existing branch | ❌ | Resolved merged PR #4 as the current change request, then allowed it to be updated. |
| 2026-09-07 | GitHub + Linear | Create + link, no PR | ✅ | Created THR-50, renamed and pushed the branch, opened PR #5, and rendered both links. |
| 2026-09-07 | GitHub + Linear | Existing PR + parent | ✅ | Created THR-51 under THR-49, updated PR #6, rendered both links, and blocked a duplicate retry. |
| 2026-09-07 | GitHub + GitHub Issues | Setup and search | ✅ | GitHub auth passed. Milestone and issue searches returned the live test records. |
| 2026-09-07 | GitHub + GitHub Issues | Create + link | ✅ | Created issue #7, renamed and pushed the branch, opened PR #8, and rendered both links and the milestone. |
| 2026-09-07 | GitHub Issues | No-scope create | ❌ | Interactive and schema-v2 requests reject it with `create_new requires parent or project`. |
| 2026-09-07 | GitHub Issues | Parent preview | ❌ | Preview accepted parent `1` and resolved merged PR #1 as tracker item #1 instead of returning `unsupported-parent`; apply was not run. |
| 2026-09-07 | Asana | Setup and project search | ✅ | Authenticated as Alexander Karpenko and resolved project `test` (`1182987059881499`). |
| 2026-09-07 | Asana | Item search | ❌ | `ttt` reported HTTP 400. The equivalent API request returned HTTP 402: `Search is only available to premium users.` |
| 2026-09-07 | Bitbucket | Credential validation | ❌ | `setup` accepted `alexkarpandrus`, but `inspect` returned HTTP 401. The API token worked only when `BITBUCKET_USERNAME` contained the Atlassian account email. |
| 2026-09-07 | Bitbucket | Repository push | ❌ | Created private repository `brahe2/tickettrain-manual-test-20260907`, but Bitbucket rejected the first push with HTTP 402 because workspace `brahe2` is restricted to read-only access after exceeding its user limit. |
| 2026-09-07 | Asana + Bitbucket | Inspect and preview | ⚠️ | `inspect` and `create_new` preview passed with the email workaround. Apply was not run because the required branch push cannot succeed. |
| 2026-09-07 | Jira | Board and authentication probe | ❌ | Project `KAN` exists. The old adapter returned HTTP 403. The scoped token returned HTTP 200 only with Basic authentication through the cloud API gateway. |
| 2026-09-07 | Jira | Issue search | ❌ | Live search returned HTTP 410 because the adapter used removed endpoint `/rest/api/3/search`; Jira requires `/rest/api/3/search/jql`. |
| 2026-09-07 | Jira | Issue search rerun | ✅ | The patched adapter authenticated through the cloud gateway, resolved project `KAN` (`tttttest`), and completed issue search with zero matches. |
| 2026-09-07 | GitHub | Merged PR inspection rerun | ✅ | The patched forge returned `changeRequest: null` for merged PR #4. |
| 2026-09-07 | GitHub Issues | Parent preview rerun | ✅ | Preview now returns `unsupported-parent` before approval or mutation. |
| 2026-09-07 | GitHub Issues | No-scope preview rerun | ✅ | Schema validation and interactive dry-run accept creation without a parent or milestone. |
| 2026-09-07 | Jira | Labels | ✅ | The adapter now follows Jira's string-valued label page. Live label search completed with zero matches. |
| 2026-09-07 | Asana | Item search rerun | ✅ | Free-plan fallback returned assigned tasks for `design`. Nested project names now render, including `Firefly`. |
| 2026-09-07 | Bitbucket | Credential validation rerun | ✅ | Setup authenticated for `brahe2/tickettrain-manual-test-20260907`; inspect resolved the repository and branch. |
| 2026-09-07 | Bitbucket | Repository push rerun | ✅ | Branch `manual/ttt-asana-bitbucket-20260907` exists remotely at commit `6ff42f2`. |
| 2026-09-07 | GitLab | Live verification | ⚠️ | Blocked because no `GITLAB_TOKEN` or authenticated `glab` session is available. Contract and GitLab + Jira integration tests pass. |
| 2026-09-07 | Packaging | Temporary install | ✅ | Local installer, installed `ttt version`, and installed `ttt --llm` passed from a clean temporary home. |
| 2026-09-07 | Jira + GitHub | Create + link and native rendering | ✅ | Created `KAN-1`, linked PR #15, verified reciprocal links and exact-key search, and confirmed native ADF headings, lists, emphasis, code, and links. |
| 2026-09-07 | Asana + Bitbucket | Create + link | ✅ | Created task `1218235721923599`, opened Bitbucket PR #1, linked both records, and verified exact-gid search. |
| 2026-09-07 | Asana + Bitbucket | Native rendering repair | ✅ | Applied proposal `lp2_09fa3f21290931fd04da4954`; Asana rendered native rich-text nodes and Bitbucket rendered links and lists without visible `ttt` markers. |

Record any failure here with the `ttt` stderr — it maps to a specific adapter fix.
