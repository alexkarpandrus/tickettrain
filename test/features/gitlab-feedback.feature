@acceptance
Feature: Approval-gated GitLab review feedback
  Native-boundary CLI scenarios are implemented in ttt.gitlab-feedback-test.

  Scenario: Preview does not change review feedback
    Given an MR with a threaded note and existing reviewers
    When I preview a reply, note edit, resolution, and reviewer batch
    Then the proposal pins the project, MR, source head, native IDs, and exact mutations
    And neither native state nor recovery files change

  Scenario: Exact approval applies only the requested review feedback
    Given an approved feedback batch for the current source head
    When I apply that unchanged batch
    Then the reply stays in the selected discussion and the selected note changes
    And the resolvable discussion reaches its requested state
    And existing reviewers and unrelated MR metadata remain intact
    And final readback reports all unresolved discussions

  Scenario: Invalid or changed identities cannot mutate another target
    Given a wrong project, stale head, changed note, or non-resolvable discussion
    When I preview or apply the feedback batch
    Then the CLI rejects the operation before its native write

  Scenario: Readback includes every discussion page
    Given an MR with 101 unresolved discussions
    When I inspect feedback for its explicit ID
    Then all 101 discussions and native note metadata are returned

  Scenario: Retry skips successful operations after partial failure
    Given a reply succeeded and the next operation was rejected
    When I retry the unchanged approved batch
    Then the reply is not repeated and remaining safe operations can finish

  Scenario: A lost response cannot cause a duplicate reply
    Given a reply was accepted but its response was lost
    When I retry the same approved batch
    Then its unknown operation stops without another native write
    And per-operation outcomes and final native feedback remain visible

  Scenario: Recovery or readback failure does not hide acknowledged writes
    Given a native reply succeeded but checkpoint storage or final readback failed
    When the CLI returns partial failure
    Then it reports the successful operation and its affected native ID
    And retry does not send another reply

  Scenario: Reviewer replacement requires exact explicit approval
    Given an MR with existing reviewers
    When I approve replacement with a concrete reviewer ID array
    Then only the reviewer set is replaced
    And an empty reviewer set requires explicit replacement


  Scenario: Replacing a project or MR cannot retain an old approval
    Given a preview for a specific immutable native project and MR
    When the project or MR is replaced without changing its slug, IID, head, or presentation
    Then the old approval cannot authorize a native write
    And replacement before a pending write stops that batch
    And acknowledged outcomes remain visible during recovery

  Scenario: Local batches cannot overwrite an acknowledged reviewer addition
    Given two approved batches based on the same reviewer set
    When they apply concurrently in the same repository
    Then the active MR batch owns the mutation lock through final readback
    And the competing batch stops before writing
    And its old approval cannot overwrite the updated reviewer set

  Scenario: Outage retry still reports acknowledged native results
    Given a reply succeeded and its outcome is durably journaled
    When feedback inspection is unavailable during preview or apply retry
    Then the matching request reports the saved successful outcome and native note ID
    And no native write occurs
    And a different request, profile, or configuration cannot claim those outcomes

  Scenario: HTTP rejection and ambiguous acceptance have different retry behavior
    Given native responses are decoded through the real HTTP error boundary
    When a reply receives HTTP 403 rejection or HTTP 408 or 500 after acceptance
    Then rejected operations can retry while ambiguous operations remain unknown
    And subsequent operations remain pending until safe recovery
    And retry never sends another ambiguously accepted reply

  Scenario: Mutation targets a non-first thread and note exactly
    Given two populated discussions with multiple notes
    When I approve a reply, note edit, and resolution in the second discussion
    Then the non-first selected note and thread change as requested
    And unrelated feedback remains unchanged
    And absent discussions and notes belonging to another thread fail before writes

  Scenario: Native JSON discussion arrays are accepted and malformed bodies are rejected
    Given a native HTTP discussion response
    When I inspect feedback or preview a batch
    Then a valid decoded JSON array is accepted
    And null, scalar, or object discussion bodies fail before writes
