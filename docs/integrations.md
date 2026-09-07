# Adding Trackers and Forges

`ttt` separates provider-neutral orchestration from concrete tracker and forge transports. A bundled provider registers a descriptor and returns a plain capability map; it does not add provider branches to `ttt.core`, `ttt.cli.agent`, or `ttt.cli.workflow`.

## Architecture

1. `ttt.cli.main` and `ttt.cli.agent` load normalized configuration.
2. `ttt.adapters/runtime` resolves registry descriptors, validates local configuration, builds adapters, and verifies capabilities.
3. `ttt.core` plans and applies neutral item/change-request operations.
4. `ttt.cli.agent` owns schema-v2 JSON, proposal hashes, search, and approval gating.
5. `ttt.cli.workflow` owns prompts, dry-runs, progress output, and local Git operations.
6. Concrete adapters own GraphQL, subprocess calls, native IDs, and native payloads.

The core must not know GraphQL, `gh`, Linear payload fields, GitHub routes, Jira, or GitLab.

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

## Registry descriptors

Bundled registries live at the composition boundary:

```clojure
{:github {:build github/neutral-adapter
          :validate-config! github/assert-ready!}

 :gitlab {:build gitlab/neutral-adapter
          :validate-config! gitlab/assert-ready!}

 :bitbucket {:build bitbucket/neutral-adapter
             :validate-config! bitbucket/assert-ready!}}

{:linear {:build linear/neutral-adapter
          :validate-config! linear/assert-ready!}

 :github-issues {:build github-issues/neutral-adapter
                 :validate-config! github-issues/assert-ready!}

 :jira {:build jira/neutral-adapter
        :validate-config! jira/assert-ready!}

 :asana {:build asana/neutral-adapter
         :validate-config! asana/assert-ready!}}
```

A descriptor has a required `:build` function and an optional local-only `:validate-config!` function. `ttt.adapters/build` rejects unknown providers, registry/provider mismatches, undeclared capabilities, and missing capability functions. Dynamic plugin discovery and config-resolved symbols are intentionally unsupported.

## Capability maps

A forge declares every capability in `ttt.adapters/required-capabilities`, including inspection, identification, creation, update, and title prefixing. A tracker declares configured scope, searches and resolvers, and `:create-item!`/`:update-item!`.

```clojure
{:provider :example-tracker
 :capabilities #{...}
 :configured-scope (fn [] normalized-scope)
 :resolve-labels (fn [label-refs scope] [normalized-label ...])
 :create-item! (fn [context intent] normalized-item)
 :update-item! (fn [item intent] normalized-item)}
```

Shared code passes normalized label entities. Only the concrete tracker translates them to native IDs. Scope validation happens in `ttt.core` before label resolution or mutation.

## Registering a bundled provider

1. Add a concrete namespace under `src/ttt/providers/forge/` or `src/ttt/providers/tracker/`.
2. Normalize every resource to `:ref`, `:display-id`, and neutral presentation fields.
3. Add `:scopes` to tracker entities used by shared code.
4. Implement and declare the complete role capability set.
5. Add its descriptor to `ttt.providers.forge/registry` or `ttt.providers.tracker/registry`.
6. Add provider unit tests and a local-stub provider contract test.
7. Do not change `ttt.core` for provider-specific behavior.

## Managed links and tests

Change-request bodies and tracker descriptions use validated managed Markdown sections. Renderers escape labels and destinations; malformed content is rejected before rewrite for manual repair. Source markers serialize the provider from the normalized ref.

`bb test` is local and deterministic: it runs pure core tests, provider unit tests, and registry-backed provider integration tests with GraphQL/subprocess stubs. Tests must not require credentials, `gh auth`, or network access. Provider contract tests cover capabilities, normalized identities/scopes, native payload translation, and tracker mutation before forge mutation.

## Recovery boundary

This release does not implement durable create-and-link recovery, idempotency, or native attachments. If tracker creation succeeds and a later forge mutation fails, operators must inspect the tracker before retrying. Branch ticket metadata is only a no-change-request retry hint, not a saga.
