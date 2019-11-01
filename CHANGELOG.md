## Changelog

### 2.35

Release date: 2019-09-10

-   [JENKINS-59083](https://issues.jenkins-ci.org/browse/JENKINS-59083):
    Fix issue where Pipeline builds were considered to be in progress
    even after they had completed in some cases.
-   Internal: Refactor some tests to make them more consistent ([PR
    139](https://github.com/jenkinsci/workflow-job-plugin/pull/139))

### 2.34

Release date: 2019-08-21

-   [JENKINS-52189](https://issues.jenkins-ci.org/browse/JENKINS-52189):
    Call `FlowExecutionListener.onCreated` method added in Pipeline: API
    Plugin 2.36 when a Pipeline execution is created but before it
    starts executing.
-   Internal: Clean up internal logic using newer and non-deprecated
    Java and Jenkins core APIs, fix compiler warnings, etc. ([PR 132](https://github.com/jenkinsci/workflow-job-plugin/pull/132), [PR 134](https://github.com/jenkinsci/workflow-job-plugin/pull/134), [PR 135](https://github.com/jenkinsci/workflow-job-plugin/pull/135), [PR 136](https://github.com/jenkinsci/workflow-job-plugin/pull/136))

### 2.33

Release date: 2019-06-27

-   [JENKINS-46076](https://issues.jenkins-ci.org/browse/JENKINS-46076):
    Modify `WorkflowRun.isLogUpdated` so that it does not return false
    while a Pipeline build is in the process of completing in some
    cases. Fixes some issues where `JenkinsRule.waitForMessage` worked
    inconsistently in tests.
-   Add internationalization support for the "Pipeline Steps" sidebar
    link in Pipeline builds. ([PR 128](https://github.com/jenkinsci/workflow-job-plugin/pull/128))

### 2.32

Release date: 2019-03-08

-   Internal: Update tests and test-scope dependencies so that the
    plugin can build with all tests passing on Java 11. ([PR
    124](https://github.com/jenkinsci/workflow-job-plugin/pull/124),
    [PR
    126](https://github.com/jenkinsci/workflow-job-plugin/pull/126))
-   Internal: Clean up internal logic using newer Jenkins core APIs.
    ([PR
    123](https://github.com/jenkinsci/workflow-job-plugin/pull/123))

### 2.31

Release date: 2018-12-12

-   [JENKINS-54304](https://issues.jenkins-ci.org/browse/JENKINS-54304):
    Prefix log output with the name of the parallel branch that produced
    it in the console view of the classic UI whenever log output
    transitions from one branch to another.

### 2.30

Release date: 2018-12-06

-   [JENKINS-54904](https://issues.jenkins-ci.org/browse/JENKINS-54904):
    Make the links that show and hide steps in the console view of
    Pipeline jobs work correctly in Firefox.
-   Avoid using deprecated APIs ([PR
    \#103](https://github.com/jenkinsci/workflow-job-plugin/pull/103))

### 2.29

Release date: 2018-11-09

> **WARNING**: Although issues have been fixed since 2.28, this version may carry extra risk and is not fully backwards compatible with 2.25 or older; consider waiting a few days to update in critical environments.

-   [JENKINS-54128](https://issues.jenkins-ci.org/browse/JENKINS-54128):
    Change the implementation of `WorkflowRun#getLogFile` to avoid
    creating a new temporary file each time the method is called.
-   Fix: Do not call `WorkflowRun#getLogFile` when an error occurs while
    opening the log file for a Pipeline to avoid logging an additional
    stack trace.

### 2.28

Release date: 2018-11-06

> **WARNING**: Although issues have been fixed since 2.27, this version may carry extra risk and is not fully backwards compatible with 2.25 or older; consider waiting a few days to update in critical environments.

-   Bugfix: avoid reading entire log to a buffer when fetching console
    text, avoiding potential OutOfMemoryException with large logs
-   Bugfix: log console text cut off after 10k lines
    (LargeText.MAX\_LINES\_READ) and won't show additional content
-   Avoid using deprecated APIs ([PR
    \#109](https://github.com/jenkinsci/workflow-job-plugin/pull/109))

### 2.27

Release date: 2018-11-01

> **WARNING**: Although major issues have been fixed since 2.26, this version carries extra risk and is not fully backwards compatible with 2.25 or older; consider waiting a few days to update in critical environments.

-   [JENKINS-54073](https://issues.jenkins-ci.org/browse/JENKINS-54073):
    Buffer remote log output to fix logging-related performance issues

### 2.26

Release date: 2018-10-12

> **WARNING**: This version carries extra risk and is not fully backwards compatible; consider waiting a few days to update in critical environments. Update Pipeline Groovy Plugin to 2.58+ along with this update.

-   [JEP-210](https://jenkins.io/jep/210): redesigned
    log storage system for Pipeline builds. **This update involves a new
    log file format.** Older releases will be able to display the
    whole-build log in a somewhat degraded fashion in the classic,
    though not Blue Ocean, UI. You are advised to also update [Pipeline Groovy Plugin](https://github.com/jenkinsci/workflow-cps-plugin) to 2.58.
-   [JENKINS-45693](https://issues.jenkins-ci.org/browse/JENKINS-45693):
    support for `TaskListenerDecorator` API.

-   Chinese localizations have been migrated to the [Localization:
    Chinese (Simplified)
    Plugin](https://github.com/jenkinsci/localization-zh-cn-plugin).  

Major reported issues:

-   Claimed excessive CPU usage on loaded systems ([JENKINS-54073](https://issues.jenkins-ci.org/browse/JENKINS-54073)). Recommend installing the [Support Core Plugin](https://github.com/jenkinsci/support-core-plugin)  and/or being prepared to offer thread dumps correlated with `top` output.
-   Loss of timestamps ([JENKINS-54081](https://issues.jenkins-ci.org/browse/JENKINS-54081)) and some forms of console colorization ([JENKINS-54133](https://issues.jenkins-ci.org/browse/JENKINS-54133)) when running builds on agents.

### 2.26-beta-1

Release date: 2018-10-04

-   [JEP-210](https://jenkins.io/jep/210): redesigned
    log storage system for Pipeline builds. **This update involves a new
    log file format.** Older releases will be able to display the
    whole-build log in a somewhat degraded fashion in the classic,
    though not Blue Ocean, UI. You are advised to also update [Pipeline Groovy Plugin](https://github.com/jenkinsci/workflow-cps-plugin) to 2.58-beta-1.
-   [JENKINS-45693](https://issues.jenkins-ci.org/browse/JENKINS-45693):
    support for `TaskListenerDecorator` API.

### 2.25

Release date: 2018-09-05

-   Fix: Prevent builds from resuming in some cases despite their
    execution having
    completed ([JENKINS-50199](https://issues.jenkins-ci.org/browse/JENKINS-50199))

### 2.24

Release date: 2018-08-07

> **IMPORTANT**: also upgrade the [Pipeline Nodes and Processes Plugin](https://github.com/jenkinsci/workflow-durable-task-step-plugin) to v2.20+ along with this upgrade, to avoid potential log encoding issues.

-   [JEP-206](https://github.com/jenkinsci/jep/blob/master/jep/206/README.adoc) Use
    UTF-8 for all Pipeline build logs

### 2.23

Release date: 2018-07-10

-   Reduce addition thread contention bottlenecks around the
    `WorkflowJob.getSettablePromise` and `WorkflowJob.getExecutionPromise`
    methods 
    -   Usually only important in specific edge cases such as highly
        parallel builds with lots of build trigger steps
-   Chinese Translations (thanks, community contributor
    [Suren](https://github.com/LinuxSuRen)!)

### 2.22

Release date: 2018-06-28

-   Reduce thread contention bottlenecks (especially with highly
    parallel Pipelines) - some introduced with fixes in 2.18-2.21
    ([JENKINS-51377](https://issues.jenkins-ci.org/browse/JENKINS-51377))

### 2.21

Release date: 2018-05-02

-   **We strongly encourage applying this fix, and encourage installing
    it at the same time as Pipeline Groovy (workflow-cps plugin) v2.50 
    or later due to related fixes**
-   **Fix**: `NullPointerException` when saving modifying build
    descriptions and saving
    ([JENKINS-50888](https://issues.jenkins-ci.org/browse/JENKINS-50888))
-   **Major Fix**: Builds that failed in specific ways would
    mysteriously resume
    ([JENKINS-50199](https://issues.jenkins-ci.org/browse/JENKINS-50199))
-   **Fix**: Runs showing as still running or incomplete when they
    failed to load key data ("FlowNodes")
-   **Fix**: Restore protection against double `onLoad` calls to builds
-   Fix various issues around lazy loading of executions
-   Fix variety of synchronization issues where race conditions could
    occur

### 2.20

Release date: 2018-04-20

-   **Bugfix**: Fix a regression from lazy-loading FlowExecutions that
    prevented jobs from showing as Replayable and caused a few other
    small issues
    ([JENKINS-50784](https://issues.jenkins-ci.org/browse/JENKINS-50784))
-   Improvement: Remove unnecessary starts/stops of triggers (thanks to
    community contributor Scott Herbert)

### 2.19

Release date: 2018-04-13

-   **Bugfix**: `StackOverflowException` when resuming a large paused
    Pipeline
    ([JENKINS-38383](https://issues.jenkins-ci.org/browse/JENKINS-38383),
    also incidentally fixes
    [JENKINS-50350](https://issues.jenkins-ci.org/browse/JENKINS-50350))

### 2.18

Release date: 2018-04-08

> WARNING Build Format Not Back-Compatible  - trying to load WorkflowRuns with 2.17 or earlier will show a 'double onLoad error'

-   **Major bugfix / improvements**: numerous fixes & improvements to
    make Pipeline persistence & resume more robust (across all
    Durability Settings)  
    -   These do not have individual JIRAs because they were spinoffs
        from testing other work, discovered with fuzzing-like approaches
    -   Many of these bugs would result in irreproducible errors that
        may have been reported - **link any related JIRAs here**: (TBD)
    -   Improves error-handling logic
-   **Improvement**: Lazy-Load FlowExecution For Completed Pipelines -
    Avoids Loading FlowNodes If Not Needed
    ([JENKINS-45585](https://issues.jenkins-ci.org/browse/JENKINS-45585))  
    -   Improves performance **significantly** when plugins/operations
        load many Pipeline builds (weather column, etc) just to check
        the Result
    -   Makes an *especially* large difference for Performance-Optimized
        Pipelines with many steps
    -   This makes the 'completed' field persistent for Pipeline Runs
        and will trigger a one-time re-save for legacy builds
    -   **Back-compatibility** **Issue: the presence of the 'completed'
        field will introduce errors about double onLoad calls when
        downgrading to version 2.17.  This can be fixed (to allow for
        loading in earlier versions) by searching for all build.xml
        files containing the following strings and removing those
        lines:**
        -   `<completed>true</completed>`
        -   `<completed>false</completed>`
-   **Bugfix**: Fix one case where running Pipelines would not reflect an
    updated Resume Disabled status on the job
-   **Part of Bugfix**: Error "NullPointerException in
    SandboxContinuable.run0" after restart in Performance-Optimized
    Durability Setting
    ([JENKINS-50407](https://issues.jenkins-ci.org/browse/JENKINS-50407))
    -   Remainder of bugfix is in Pipeline Groovy Plugin, version 2.47
-   **Part of Major Bugfix**: Error "NullPointerException in CPS VM
    thread at WorkflowRun$GraphL.onNewHead" as result of a race
    condition
    ([JENKINS-49686](https://issues.jenkins-ci.org/browse/JENKINS-49686))
    -   The other part of the bugfix is in the Pipeline Groovy Plugin -
        version 2.47
-   NullPointerException can be thrown due to null triggers list
    ([JENKINS-49170](https://issues.jenkins-ci.org/browse/JENKINS-49170)).
-   Bugfix / Improvement: Users can use Simple Theme Plugin to hide
    extra pipeline logging, because the 'pipeline-annotated' is now
    included
    ([JENKINS-41845](https://issues.jenkins-ci.org/browse/JENKINS-41845))
-   **Part of Major Bugfix**: Failed pipelines resume and won't die even
    when marked to not resume, and show resume failures 
    ([JENKINS-50199](https://issues.jenkins-ci.org/browse/JENKINS-50199))
    -   The other part of the bugfix is in the Pipeline Groovy Plugin -
        version 2.47
-   Update text: fix use of "slave" rather than "agent" for build
    computers - thank to Github user acrewdson for the contribution.

### 2.17

Release date: 2018-01-22

-   **Major Feature**: Support Durability Settings for a Pipeline
    ([JENKINS-47300](https://issues.jenkins-ci.org/browse/JENKINS-47300))  
    -   Allows user to greatly reduce IO needs of pipeline and improve
        performance
    -   This plugin consumes the setting and will reduce IO under the
        right settings
    -   Add property & UX for user to configure the Durability Setting
-   **Major Feature**: Add property and UI for user to mark a Pipeline
    as do-not-resume-at-restart
    ([JENKINS-33761](https://issues.jenkins-ci.org/browse/JENKINS-33761))
-   Requires Jenkins 2.62

### 2.16

Release date: 2017-12-04

-   Feature: label ThreadNames with something meaningful (i.e. for
    copyLogs) for better diagnosability
-   Feature: Add Chinese Translation (thanks LinuxSuRen!)
-   Fix [JENKINS-46945](https://issues.jenkins-ci.org/browse/JENKINS-46945) -
    now follow convention of setting Run duration after fireCompleted
    triggers evaluate
-   Fix: Add missing ABORT permission to WorkflowJob
    ([JENKINS-43834](https://issues.jenkins-ci.org/browse/JENKINS-43834))
-   Cleanup: Avoid adding PipelineTriggersJobProperty property unless a
    trigger is defined, and remove redundant Queue task overrides
-   Cleanup: Remove redundant calls in obtaining build Environment
    ([JENKINS-29537](https://issues.jenkins-ci.org/browse/JENKINS-29537))

### 2.15

Release date: 2017-10-13

-   [JENKINS-46934](https://issues.jenkins-ci.org/browse/JENKINS-46934) -
    Fix hangs due to build rotation
-   [JENKINS-46082](https://issues.jenkins-ci.org/browse/JENKINS-46082) -
    List culprits (SCM committers) responsible for triggering a build
    and export this to API 
-   [JENKINS-45043](https://issues.jenkins-ci.org/browse/JENKINS-45043) -
    Fix "changes.title" showing up in the view for Pipeline changes.
-   [JENKINS-45460](https://issues.jenkins-ci.org/browse/JENKINS-45460) -
    fix adding triggers after migration from 2.3 or earlier.

### 2.14.1 

Release date: 2017-08-02

-   Added a `FlowCopier` for `checkouts`

### 2.14 

Release date: 2017-08-01

-   Catch Exceptions thrown upon loading the execution and null the
    execution, as a defense
    against [JENKINS-44548](https://issues.jenkins-ci.org/browse/JENKINS-44548) 

### 2.13

Release date: 2017-06-19

Requires **Jenkins core 2.62+**.

-   Japanese translation.

-   Internal code simplifications enabled by newer core dependency.

### 2.12.2 

Release date: 2017-08-02

-   Added a `FlowCopier` for `checkouts`

### 2.12.1

Release date: 2017-06-19

-   [JENKINS-43055](https://issues.jenkins-ci.org/browse/JENKINS-43055) Supporting
    a new API `FlowExecutionListener`.

-   Print a message about a build’s authentication, like a freestyle
    build would.
-   Defend against exceptions thrown by `Trigger` implementations.

### 2.12

Release date: 2017-05-16

> Requires **Jenkins core 2.60+**. (This is expected to be the basis of the next LTS line.)

-   [JENKINS-24141](https://issues.jenkins-ci.org/browse/JENKINS-24141) / [JENKINS-26100](https://issues.jenkins-ci.org/browse/JENKINS-26100) Support
    for more standard SCM idioms in Pipeline, such as a culprits list.

### 2.11.2 

Release date: 2017-08-02

-   Added a `FlowCopier` for `checkouts`

### 2.11.1

Release date: 2017-06-20

Same changes as in 2.12.1 but compatible with older versions of Jenkins.

### 2.11

Release date: 2017-05-12

-   [JENKINS-27299](https://issues.jenkins-ci.org/browse/JENKINS-27299) Support
    for disabling Pipeline jobs.
-   [JENKINS-34716](https://issues.jenkins-ci.org/browse/JENKINS-34716) Support
    for `polling` REST endpoint on Pipeline jobs.
-   [JENKINS-43396](https://issues.jenkins-ci.org/browse/JENKINS-43396) Include
    global node properties in environment outside any `node` block.
-   Minor memory leak when builds are being rapidly created and then
    deleted.

### 2.10

Release date: 2017-02-10

-   [JENKINS-40255](https://issues.jenkins-ci.org/browse/JENKINS-40255)
    SCM list for a job now considers the last successful build if the
    last completed was a failure.
-   [JENKINS-33721](https://issues.jenkins-ci.org/browse/JENKINS-33721)
    Sidepanel option to terminate or kill a build.
-   [JENKINS-41276](https://issues.jenkins-ci.org/browse/JENKINS-41276)
    Fixed initialization of causes for build aborts.
-   API needed for
    [JENKINS-40521](https://issues.jenkins-ci.org/browse/JENKINS-40521).

### 2.9

Release date: 2016-11-10

-   [JENKINS-35098](https://issues.jenkins-ci.org/browse/JENKINS-35098)
    Work around performance problem in Jenkins 1.x cores.
-   Improved exception reporting from `Job.logRotate`.

### 2.8

Release date: 2016-10-24

-   [JENKINS-38454](https://issues.jenkins-ci.org/browse/JENKINS-38454)
    Since 2.4, plugins like [GitHub Integration
    Plugin](https://github.com/KostyaSha/github-integration-plugin)
    providing triggers which relied on undocumented assumptions may not
    have worked in Pipeline jobs.

### 2.7

Release date: 2016-09-23

-   [JENKINS-28222](https://issues.jenkins-ci.org/browse/JENKINS-28222)
    `parallel` branch labels were getting dropped after the end of a
    block inside the branch.
-   [JENKINS-30910](https://issues.jenkins-ci.org/browse/JENKINS-30910)
    Including build parameters as environment variables, which also
    fixes
    [JENKINS-28447](https://issues.jenkins-ci.org/browse/JENKINS-28447).
-   Log file collection could block system threads under unreproducible
    conditions, leading to apparent hangs.

### 2.6

Release date: 2016-08-26

-   [JENKINS-37477](https://issues.jenkins-ci.org/browse/JENKINS-37477)
    Fixed documentation and *Snippet Generator* for `pipelineTriggers`
    from 2.4.
-   Fixing cleanup of serious bugs in build setup.
-   [JENKINS-37664](https://issues.jenkins-ci.org/browse/JENKINS-37664)
    Behave more gracefully in an as-yet-undiagnosed race condition
    affecting step logs which formerly caused an irrecoverable hang with
    100% CPU.

### 2.5

Release date: 2016-08-08

-   Indicating in the build log when a restart occurred.
-   Generalized fix for
    [JENKINS-34281](https://issues.jenkins-ci.org/browse/JENKINS-34281)
    which may fix an unreproducible issue possibly involving the stage
    view, Jenkins restarts, and access control.
-   `NullPointerException` under unknown conditions, probably following
    a hard kill.
-   Make
    [JENKINS-29922](https://issues.jenkins-ci.org/browse/JENKINS-29922)
    integration (simplified `properties` step syntax) work in Jenkins 1
    as well as 2.

### 2.4

Release date: 2016-07-28

-   [JENKINS-34547](https://issues.jenkins-ci.org/browse/JENKINS-34547)
    Converted concurrency setting to a job property, allowing it to be
    defined in a multibranch `Jenkinsfile` via the `properties` step.
-   [JENKINS-34005](https://issues.jenkins-ci.org/browse/JENKINS-34005)
    Converted triggers to a job property for the same reason. (Note that
    multibranch projects do not need an SCM trigger since branch
    indexing automatically triggers builds by default.)
-   Robustness improvement in case an SCM plugin is disabled.

### 2.3

Release date: 2016-06-16

-   Infrastructure for
    [JENKINS-26130](https://issues.jenkins-ci.org/browse/JENKINS-26130).

### 2.2

Release date: 2016-05-20

-   [JENKINS-34450](https://issues.jenkins-ci.org/browse/JENKINS-34450)
    Work around a core bug causing deadlocks under some conditions when
    interrupting builds.
-   When using CloudBees Jenkins Enterprise checkpoints, clear stashes
    whenever discarding build artifacts.

### 2.1

Release date: 2016-04-12

-   Programmatic interruptions of a build such as
    `Executor.interrupt(Result)` were discarding the build status.

### 2.0

Release date: 2016-04-05

-   First release under per-plugin versioning scheme. See [1.x
    changelog](https://github.com/jenkinsci/workflow-plugin/blob/82e7defa37c05c5f004f1ba01c93df61ea7868a5/CHANGES.md)
    for earlier releases.
-   [JENKINS-31162](https://issues.jenkins-ci.org/browse/JENKINS-31162)
    Support *New Item* categorization in Jenkins 2.x.