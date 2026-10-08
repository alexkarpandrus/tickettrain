@acceptance
Feature: Taskwarrior descriptions do not overwrite native task details
  Native-boundary scenarios are implemented in ttt.taskwarrior-test and
  ttt.github-taskwarrior-integration-test.

  Scenario: Managed descriptions coexist with native details and comments
    Given a task with native details and timestamped comment annotations
    When I apply a managed description update
    Then the description is stored in tttDescription
    And the native details and unrelated annotations remain unchanged

  Scenario: Legacy marked descriptions migrate without data loss
    Given a task with a legacy managed annotation and native details
    When I apply a managed description update
    Then the legacy description moves to tttDescription
    And the native details and unrelated annotations keep their values and timestamps

  Scenario: Conflicting managed description sources cannot overwrite each other
    Given a task with conflicting managed descriptions
    When I preview an update
    Then the update fails before importing a task
    And all task data remains unchanged

  Scenario: One malformed task does not prevent access to unrelated tasks
    Given a task with duplicate or conflicting managed descriptions
    And an unrelated task with a valid description
    When I list tasks or select the unrelated task by title
    Then the list and unrelated selection succeed
    And selecting the malformed task fails for manual repair

  Scenario: Creation, comments, and completion keep description storage separate
    Given an approved managed task creation
    When I create the task, append a comment, and complete it
    Then managed text stays separate from native details and comments
    And existing annotations and native fields remain intact
