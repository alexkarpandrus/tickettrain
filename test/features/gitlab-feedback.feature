@acceptance
Feature: Approval-gated GitLab review feedback
  Native-boundary CLI scenarios are implemented in ttt.feedback-test.

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
