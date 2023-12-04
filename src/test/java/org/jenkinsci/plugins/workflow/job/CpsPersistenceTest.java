package org.jenkinsci.plugins.workflow.job;

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
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Stack;

// utilities adapted from org.jenkinsci.plugins.workflow.cps.PersistenceProblemsTest
public class CpsPersistenceTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

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
        Assert.assertFalse(run.isBuilding());
        Assert.assertNotNull(run.getResult());
        FlowExecution fe = run.getExecution();
        FlowExecutionList.get().forEach(f -> {
            if (fe != null && f == fe) {
                Assert.fail("FlowExecution still in FlowExecutionList!");
            }
        });
        Assert.assertTrue("Queue not empty after completion!", Queue.getInstance().isEmpty());

        if (fe instanceof CpsFlowExecution) {
            CpsFlowExecution cpsExec = (CpsFlowExecution)fe;
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertEquals(Boolean.TRUE, getCpsDoneFlag(cpsExec));
            Assert.assertEquals(1, cpsExec.getCurrentHeads().size());
            Assert.assertTrue(cpsExec.isComplete());
            Assert.assertTrue(cpsExec.getCurrentHeads().get(0) instanceof FlowEndNode);
            Stack<BlockStartNode> starts = getCpsBlockStartNodes(cpsExec);
            Assert.assertTrue(starts == null || starts.isEmpty());
            Thread.sleep(1000); // TODO seems to be flaky
            Assert.assertFalse(cpsExec.blocksRestart());
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
        while(exec.getCurrentHeads().isEmpty() || (exec.getCurrentHeads().get(0) instanceof FlowStartNode)) {  // Wait until input step starts
            System.out.println("Waiting for input step to begin");
            Thread.sleep(50);
        }
        while(run.getAction(InputAction.class) == null) {  // Wait until input step starts
            System.out.println("Waiting for input action to get attached to run");
            Thread.sleep(50);
        }
        Thread.sleep(100L);  // A little extra buffer for persistence etc
        if (durabilityHint != FlowDurabilityHint.PERFORMANCE_OPTIMIZED) {
            CpsFlowExecution execution = (CpsFlowExecution) run.getExecution();
            Method m = execution.getClass().getDeclaredMethod("getProgramDataFile");
            m.setAccessible(true);
            File f = (File) m.invoke(execution);
            while (!Files.exists(f.toPath())) {
                System.out.println("Waiting for program to be persisted");
                Thread.sleep(50);
            }
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
    public void completedExecutionButRunIncomplete() {
        final int[] build = new int[1];
        story.thenWithHardShutdown( j -> {
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
        });
        story.then( j->{
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
        story.then( j->{ // Once more, just to be sure it sticks
            WorkflowJob r = j.jenkins.getItemByFullName(DEFAULT_JOBNAME, WorkflowJob.class);
            WorkflowRun run = r.getBuildByNumber(build[0]);
            assertCompletedCleanly(run);
            Assert.assertEquals(Result.SUCCESS, run.getResult());
        });
    }

}
