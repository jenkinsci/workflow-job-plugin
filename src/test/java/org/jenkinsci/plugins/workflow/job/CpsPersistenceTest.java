package org.jenkinsci.plugins.workflow.job;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Stack;

// utilities adapted from org.jenkinsci.plugins.workflow.cps.PersistenceProblemsTest
class CpsPersistenceTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private static boolean getCpsDoneFlag(CpsFlowExecution exec) throws Exception {
        Field doneField = exec.getClass().getDeclaredField("done");
        doneField.setAccessible(true);
        return doneField.getBoolean(exec);
    }

    private static Stack<BlockStartNode> getCpsBlockStartNodes(CpsFlowExecution exec) throws Exception {
        Field startField = exec.getClass().getDeclaredField("startNodes");
        startField.setAccessible(true);
        Object ob = startField.get(exec);
        if (ob instanceof Stack) {
            @SuppressWarnings("unchecked")
            Stack<BlockStartNode> result = (Stack<BlockStartNode>)ob;
            return result;
        }
        return null;
    }

    /** Verifies all the assumptions about a cleanly finished build. */
    private static void assertCompletedCleanly(WorkflowRun run) throws Exception {
        if (run.isBuilding()) {
            System.out.println("Run initially building, going to wait a second to see if it finishes, run="+run);
            Thread.sleep(1000);
        }
        assertFalse(run.isBuilding());
        assertNotNull(run.getResult());
        FlowExecution fe = run.getExecution();
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                fail("FlowExecution still in FlowExecutionList!");
            }
        });
        assertTrue(Queue.getInstance().isEmpty(), "Queue not empty after completion!");

        if (fe instanceof CpsFlowExecution cpsExec) {
            assertTrue(cpsExec.isComplete());
            assertEquals(Boolean.TRUE, getCpsDoneFlag(cpsExec));
            assertEquals(1, cpsExec.getCurrentHeads().size());
            assertTrue(cpsExec.isComplete());
            assertInstanceOf(FlowEndNode.class, cpsExec.getCurrentHeads().get(0));
            Stack<BlockStartNode> starts = getCpsBlockStartNodes(cpsExec);
            assertTrue(starts == null || starts.isEmpty());
            Thread.sleep(1000); // TODO seems to be flaky
            assertFalse(cpsExec.blocksRestart());
        } else {
            System.out.println("WARNING: no FlowExecutionForBuild");
        }
    }

    /** Sets up a running build that is waiting on input. */
    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber, FlowDurabilityHint durabilityHint) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, jobName);
        job.setDefinition(new CpsFlowDefinition("input 'pause'", true));
        job.addProperty(new DurabilityHintJobProperty(durabilityHint));

        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        ListenableFuture<FlowExecution> listener = run.getExecutionPromise();
        FlowExecution exec = listener.get();
        // Wait until input step starts
        await().until(() -> !exec.getCurrentHeads().isEmpty() && !(exec.getCurrentHeads().get(0) instanceof FlowStartNode));
        // Wait until input step starts
        await().until(() -> run.getAction(InputAction.class) != null);
        // A little extra buffer for persistence etc
        Thread.sleep(100L);
        if (durabilityHint != FlowDurabilityHint.PERFORMANCE_OPTIMIZED) {
            CpsFlowExecution execution = (CpsFlowExecution) run.getExecution();
            Method m = execution.getClass().getDeclaredMethod("getProgramDataFile");
            m.setAccessible(true);
            File f = (File) m.invoke(execution);
            await().until(() -> Files.exists(f.toPath()));
        }
        jobIdNumber[0] = run.getNumber();
        return run;
    }

    private static WorkflowRun runBasicPauseOnInput(JenkinsRule j, String jobName, int[] jobIdNumber) throws Exception {
        return runBasicPauseOnInput(j, jobName, jobIdNumber, FlowDurabilityHint.MAX_SURVIVABILITY);
    }

    private static InputStepExecution getInputStepExecution(WorkflowRun run, String inputMessage) throws Exception {
        InputAction ia = run.getAction(InputAction.class);
        List<InputStepExecution> execList = ia.getExecutions();
        return execList.stream().filter(e -> inputMessage.equals(e.getInput().getMessage())).findFirst().orElse(null);
    }

    private static final String DEFAULT_JOBNAME = "testJob";

    /** Replicates case where builds resume when the should not due to build's completion not being saved. */
    @Test
    @Issue("JENKINS-50199")
    void completedExecutionButRunIncomplete() throws Throwable {
        final int[] build = new int[1];
        sessions.then(j -> {
            WorkflowRun run = runBasicPauseOnInput(j, DEFAULT_JOBNAME, build);
            InputStepExecution exec = getInputStepExecution(run, "pause");
            exec.doProceedEmpty();
            j.waitForCompletion(run);


            // Set run back to being incomplete as if persistence failed
            Field completedField = run.getClass().getDeclaredField("completed");
            completedField.setAccessible(true);
            completedField.set(run, false);

            Field resultField = Run.class.getDeclaredField("result");
            resultField.setAccessible(true);
            resultField.set(run, null);

            run.save();

            // simulates abrupt shutdown
            j.restart();
        });
        sessions.then(j-> {
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            assertEquals(Result.SUCCESS, run.getResult());
        });
        sessions.then(j-> { // Once more, just to be sure it sticks
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            assertEquals(Result.SUCCESS, run.getResult());
        });
    }

}
