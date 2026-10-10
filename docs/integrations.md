# Adding Trackers and Forges

`ttt` separates provider-neutral orchestration from concrete tracker and forge transports. A bundled provider registers a descriptor and returns a plain capability map; it does not add provider branches to `ttt.core`, `ttt.cli.agent`, or `ttt.cli.workflow`.

## Architecture

1. `ttt.cli.main` and `ttt.cli.agent` load normalized configuration.
2. `ttt.adapters/runtime` resolves registry descriptors, validates local configuration, builds adapters, and verifies capabilities.
3. `ttt.core` plans and applies neutral item/change-request operations.
4. `ttt.cli.agent` owns schema-v2 JSON, proposal hashes, search, and approval gating.
5. `ttt.cli.workflow` owns prompts, dry-runs, progress output, and local Git operations.
6. Concrete adapters own GraphQL, subprocess calls, native IDs, and native payloads.

The core must not know GraphQL, `gh`, `task`, Linear payload fields, GitHub routes, Jira, Taskwarrior, or GitLab.

## Normalized resources

All resources that enter shared code use a neutral identity and presentation fields:

```clojure
{:ref {:provider :github
       :kind :change-request
       :container "org/repo"
       :id "7"}
 :display-id "org/repo#7"
 :title "Improve retry handling"
 :url "https://github.com/org/repo/pull/7"}
```

Tracker items, projects, labels, and scopes follow the same shape. Selection and mutation entities carry `:scopes`; an empty label scope is global. Shared code compares refs and scopes, never native IDs or team fields.

Tracker items also use the neutral work-item fields `:state`, `:priority`, `:due-at`, `:available-at`, and `:blocked-by`. States are `open`, `active`, `waiting`, `completed`, or `canceled`; priorities are `none`, `low`, `medium`, `high`, or `urgent`. Blockers are normalized tracker-item entities. Native status names, date formats, dependency IDs, and priority codes stay inside the concrete adapter.

## Registry descriptors

Bundled registries live at the composition boundary:

```clojure
;; ttt.providers.forge/registry
{:github {:build github/neutral-adapter
          :validate-config! github/assert-ready!
          :setup github/setup}
 :gitlab {:build gitlab/neutral-adapter
          :validate-config! gitlab/assert-ready!
          :setup gitlab/setup}
 :bitbucket {:build bitbucket/neutral-adapter
             :validate-config! bitbucket/assert-ready!
             :setup bitbucket/setup}}

;; ttt.providers.tracker/registry
{:linear {:build linear/neutral-adapter
          :validate-config! linear/assert-ready!
          :setup linear/setup}
 :jira {:build jira/neutral-adapter
        :validate-config! jira/assert-ready!
        :setup jira/setup}
 :github-issues {:build github-issues/neutral-adapter
                 :validate-config! github-issues/assert-ready!
                 :setup github-issues/setup}
 :asana {:build asana/neutral-adapter
         :validate-config! asana/assert-ready!
         :setup asana/setup}
 :taskwarrior {:build taskwarrior/neutral-adapter
               :validate-config! taskwarrior/assert-ready!
               :setup taskwarrior/setup}
 :logseq {:build logseq/neutral-adapter
          :validate-config! logseq/assert-ready!
          :setup logseq/setup}}
```

A descriptor has a required `:build` function and optional `:validate-config!` and `:setup` functions. Runtime construction calls `:validate-config!` before `:build`; `ttt setup` calls `:setup`. `ttt.adapters/build` rejects unknown providers, registry/provider mismatches, undeclared capabilities, and missing capability functions. Dynamic plugin discovery and config-resolved symbols are intentionally unsupported.

## Capability maps

A forge declares every capability in `ttt.adapters/required-capabilities`. A tracker declares configured scope, listing, searches, resolvers, creation, updates, and commenting. Optional concepts include `:item-titles` and `:item-descriptions` for explicit replacement on `update_item`, plus lifecycle, priority, dates, availability, and blockers. Creation text and managed backlinks use existing mandatory operations, not replacement capabilities. Preview rejects undeclared concepts and validates native intents for both standalone and linked creation.

```clojure
{:provider :example-tracker
 :capabilities #{...}
 :item-capabilities #{:item-lifecycle :item-priority}
 :configured-scope (fn [] normalized-scope)
 :list-items (fn [matches? limit] [normalized-item ...])
 :resolve-labels (fn [label-refs scope] [normalized-label ...])
 :create-item! (fn [context intent] normalized-item)
 :update-item! (fn [item intent] normalized-item)
 :comment-item! (fn [item body] nil)}
```

A tracker may provide `:approval-context (fn [request] context-or-nil)` for read-only destination metadata. It receives the normalized request; non-nil data appears as `approvalContext.trackerContext` and participates in the proposal hash. Logseq file-graph default journal creation binds the journal date; explicit native page selection uses the page identity instead.

`:list-items` receives a predicate over normalized items. It must continue provider pagination until it returns `limit` matching items or the provider is exhausted.

Shared code passes normalized label and blocker entities. Only the concrete tracker translates them to native IDs. `ttt.core` passes the configured scope to label resolution and validates resolved labels and blockers before mutation. A missing or ambiguous blocker reference fails preview.

Create and update intents contain only requested work-item fields. Omitted fields remain unchanged. A JSON `null` clears an optional scalar, and an empty `blockedBy` array clears blockers. Comments remain separate append-only operations.

`:item-custom-fields` gates creation-only `customFields`, an opaque native JSON map (`:custom-fields` internally). Jira is the only bundled implementation; its adapter accepts only `customfield_<digits>` IDs. Shared code neither interprets native values nor permits built-in overrides. The complete creation intent, including resolved labels, reaches native validation before approval. Jira checks paginated create metadata for both standalone and linked creation, returns approval-bound `:validation` data with the exact issue-type ID and a workflow-validation limit, and creates with that ID. Metadata failures stop preview. Required fields with native defaults may be omitted; explicit empty required values fail. Schema/option validation beyond discoverable required fields remains Jira's responsibility. See [Jira create metadata](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/).

Taskwarrior implements lifecycle, priority, due dates, availability, and blockers. Linear and Jira implement lifecycle, priority, due dates, and blockers. Asana implements lifecycle, due dates, availability, and blockers. GitHub Issues implements lifecycle. Both `logseq` (file SDK) and `logseq-db` (native CLI) implement lifecycle and title updates; native pages are projects, bodies remain native text, and comments remain child blocks. Adapters reject unrepresentable values, such as `active` for Asana or GitHub Issues. Jira discovers its default `Blocks` link type during preview; set `JIRA_BLOCKER_LINK_TYPE` for a custom ID or name.

## Registering a bundled provider

1. Add a concrete namespace under `src/ttt/providers/forge/` or `src/ttt/providers/tracker/`.
2. Normalize every resource to `:ref`, `:display-id`, and neutral presentation fields.
3. Add `:scopes` to tracker entities used by shared code.
4. Implement and declare the complete role capability set.
5. Add the provider's canonical setting metadata to `ttt.config/provider-settings`. Each setting declares its config `:key` and optional `:env`, `:label`, `:required?`, `:secret?`, and `:transform`. Reference those settings from the provider registry descriptor alongside `:display-name`, `:setup-order`, and `:setup`. Guided setup and environment overrides derive from this shared metadata.
6. Add provider unit tests and a local-stub provider contract test.
7. Do not change `ttt.core` for provider-specific behavior.

## Managed links and tests

Change-request bodies and tracker descriptions use validated managed Markdown sections. Renderers escape labels and destinations; resources without native URLs render as escaped plain text. Malformed content is rejected before rewrite for manual repair. Change-request bodies serialize the normalized tracker identity in a `ttt:item` marker. Tracker descriptions store change-request links and remove legacy `ttt:source` markers during updates. Taskwarrior stores item descriptions in the `details` user-defined attribute (UDA), separate from append-only comment annotations. Legacy marked annotations remain readable and move to `details` on update; unrelated annotations remain intact. If a legacy description conflicts with existing `details`, resolve the conflict manually before updating.

`bb test` is local and deterministic: it runs pure core tests, provider unit tests, and registry-backed provider integration tests with GraphQL/subprocess stubs. Tests must not require credentials, `gh auth`, or network access. Provider contract tests cover capabilities, normalized identities/scopes, native payload translation, and tracker mutation before forge mutation.

## Review feedback

Forge feedback is optional. Adapters declare `:feedback-capabilities` from `:reply`, `:edit-note`, `:resolve-discussion`, and `:update-reviewers`. All three bundled forges implement all four operations. `ttt version` reports `forgeFeedbackCapabilities` per provider.

Keep one batch action and shared approval/recovery, with two operation groups: **discussions** (`reply`, `edit-note`, `resolve-discussion`) and **reviewer management** (`update-reviewers`). These are separate from top-level comments and PR metadata changes. Adapters declare `:get-feedback` and `:apply-feedback!` when implementing review operations; only reviewer management requires `:resolve-reviewer`. Native normalization and transport remain in each forge, not shared core/CLI orchestration.
`ttt inspect --feedback --change-request ID` reads an explicit change request in the current repository. Adapters validate native repository/change-request identity and source head, follow every feedback page, and return normalized discussion/note identities, text, authors, timestamps, positions, reviewer identities, and unresolved discussion refs. Native user identities are scoped to the forge instance. GitLab validates project/MR ownership and scopes note containers to the repository and MR IID.

The schema-v2 `review_change_request` action requires `changeRequest` (positive PR/MR number string), a unique repository-local `batchId`, and a non-empty `operations` array. Batch IDs use one to 120 ASCII letters, numbers, dots, underscores, or hyphens, beginning with a letter or number. Optional `expectedHead` asserts the reviewed source commit. A one-operation batch provides each individual operation:

| Type | Required fields | Behavior |
| --- | --- | --- |
| `reply` | `discussion`, `body` | Add a note inside the selected existing thread; never replace it with a top-level comment. |
| `edit_note` | `discussion`, `note`, `body` | Edit the exact existing non-system note in that discussion. |
| `resolve_discussion` | `discussion`, boolean `resolved` | Resolve or reopen a resolvable discussion. |
| `update_reviewers` | `reviewers` (native reviewer ID strings) | Add reviewers while retaining current reviewers. Optional `replace: true` explicitly replaces the full set; an empty array requires replacement. |

A batch cannot repeat an operation on the same target. Feedback change requests carry an additional normalized `:native-ref` (public `nativeIdentity`) with immutable repository/change-request IDs; logical slug/number identities stay unchanged. Preview pins that identity, the exact source head, and requested changes. Apply rechecks identity, head, permissions, and pending targets before each native write. Writes use pinned native IDs and only requested review fields, not unrelated PR/MR metadata. A new source head or native target requires a new preview and batch. Fixing/testing/pushing code remains separately authorized. These native APIs have no atomic compare-and-set for all review writes, so a remote edit between the last check and the write remains a race.

Feedback batches stop at the first failed or unknown operation. Successful responses and `error.partialResult` contain per-operation status, native affected IDs, final `feedback`, and `feedback.unresolvedDiscussions`; failed final readback adds `readbackError` rather than claiming completion. Preview/apply preflight failure also returns durable saved outcomes when the request, profile, and configuration match the approved journal; it never authorizes writes without successful live validation. Unresolved discussions remain visible even when all requested mutations succeed. A checkpoint-storage failure adds `recoveryError` and retains any acknowledged affected IDs.

Only approved apply creates an owner-only journal in `<git-common-dir>/ttt-feedback` (`git rev-parse --git-common-dir`). Local worktrees share journals and repository/MR-scoped mutation locks; a competing apply fails before writing and must revalidate after the active batch finishes. Batch-ID locks also protect recovery identity. The journal contains the approved proposal and per-operation outcomes, including review text but no credentials. Files use atomic replacement and file/directory sync before a provider write. Retry with the unchanged request, profile, configuration, and proposal ID skips successful mutations. Confirmed HTTP 4xx rejections (except timeout 408) and pre-write failures can retry; lost responses, interrupted writes, and HTTP 5xx outcomes stop without resending. Inspect native feedback before manual recovery, then preview only safe unattempted operations under a new batch ID. Do not discard the journal, reuse a batch on another machine, or assume an identical note body proves ownership of an unknown reply. Remove an abandoned `.lock` or `.target-lock` only after confirming no process owns it and inspecting both the journal and native feedback. Filesystems that cannot restrict permissions or atomically replace and sync checkpoints fail closed before mutation.

### GitHub native feedback

GitHub feedback uses `gh api graphql` with the host obtained from the selected repository's HTTPS URL. It pins immutable repository and PR node IDs, follows reviewer, thread, and nested comment cursors, and writes to exact node IDs. Replies target review threads, not top-level issue comments. Own-note editing requires both `viewerDidAuthor` and `viewerCanUpdate`; reply and resolution permissions are exposed in inspection and rechecked before mutation.

Use GraphQL node IDs returned by inspection for notes and reviewers. Reviewer inspection shows pending review requests, not completed reviews. User, team, enterprise-team, and bot requests are preserved on addition; replacement explicitly sets the requested set, including clearing it. GraphQL errors with HTTP 200 are conservatively treated as unknown after a write starts; confirmed HTTP 4xx rejections (except 408) can retry. No mutation is replayed to infer whether an uncertain response was accepted.

### Bitbucket Cloud native feedback

Bitbucket feedback pins the workspace/repository UUIDs and PR number, addresses writes by those UUIDs, and binds reviewers to the configured API instance. It reconstructs discussions from root/parent comments across every page, including nested replies. The root comment ID is the discussion ID. Deleted roots remain visible but cannot receive replies or resolution changes; deleted notes cannot be edited. Own-note edits compare the note author UUID to the authenticated `/user` UUID. Malformed, duplicate, orphaned, or cyclic comment trees fail inspection rather than selecting another target.

Reviewer IDs are braced UUID strings returned by inspection. Reviewer updates send only `reviewers` and require an open PR; additions retain existing reviewers and clearing requires explicit replacement. Resolution uses the native comment `/resolve` resource: POST resolves and DELETE reopens. Pagination follows only the same API origin and exact comments path with native page parameters, never an arbitrary authenticated URL.

### Verification groups

`ttt.feedback-test` covers the shared request/capability contract. `ttt.gitlab-feedback-test`, `ttt.github-feedback-test`, and `ttt.bitbucket-feedback-test` exercise provider-native inspect/preview/apply boundaries and recovery. Shared fixture helpers own temporary journals; native HTTP/subprocess stubs remain provider-specific. No live provider writes are part of `bb test`.

## Recovery boundary

This release does not implement durable create-and-link recovery, idempotency, or native attachments. If tracker creation succeeds and a later forge mutation fails, operators must inspect the tracker before retrying. Branch ticket metadata is only a no-change-request retry hint, not a saga.
