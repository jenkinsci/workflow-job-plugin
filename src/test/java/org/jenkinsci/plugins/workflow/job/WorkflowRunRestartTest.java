/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.job;

import hudson.ExtensionList;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.TaskListener;
import java.util.Collections;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

public class WorkflowRunRestartTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Issue("JENKINS-27299")
    @Test public void disabled() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node {semaphore 'wait'}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            p.makeDisabled(true);
        });
        story.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            assertTrue(p.isDisabled());
            WorkflowRun b = p.getBuildByNumber(1);
            assertTrue(b.isBuilding());
            assertTrue(b.executionLoaded);
            assertFalse(b.completed);
            SemaphoreStep.success("wait/1", null);
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            assertTrue(b.completed);
        });
    }

    @Issue("JENKINS-33761")
    @Test public void resumeDisabled() {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("node {semaphore 'wait'}", true));
            p.setResumeBlocked(true);
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
        });
        story.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            assertTrue(p.isResumeBlocked());
            WorkflowRun b = p.getBuildByNumber(1);
            r.waitForCompletion(b);
            assertFalse(b.executionLoaded);
            assertTrue(b.completed);
            assertFalse(b.isBuilding());
            assertEquals(Result.ABORTED, b.getResult());
            FlowExecution fe = b.getExecution();
            assertTrue(b.executionLoaded);
            assertNotNull(fe.getOwner());
        });
    }

    @Issue({"JENKINS-45585", "JENKINS-50784"})  // Verifies execution lazy-load
    @Test public void lazyLoadExecution() {
        story.thenWithHardShutdown(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.addProperty(new DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY));
            p.setDefinition(new CpsFlowDefinition("echo 'dosomething'", true));
            r.buildAndAssertSuccess(p);
            WorkflowRun run = p.getLastBuild();
            assertTrue(run.executionLoaded);
            assertTrue(run.completed);
            assertNotNull(run.getExecution().getOwner());

            // Just verify we don't somehow trigger onLoad and mangle something in the future
            FlowExecution fe = run.getExecution();
            assertTrue(run.executionLoaded);
            assertNotNull(run.getExecution().getOwner());
        });
        story.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            assertNotNull(b.asFlowExecutionOwner());
            assertFalse(b.executionLoaded);
            assertTrue(b.completed);
            assertFalse(b.isBuilding());
            assertNull(b.asFlowExecutionOwner().getOrNull());

            // Trigger lazy-load of execution
            FlowExecution fe = b.getExecution();
            assertNotNull(b.execution.getOwner());
            assertTrue(b.executionLoaded);
            assertNotNull(b.asFlowExecutionOwner().getOrNull());
            assertNotNull(b.asFlowExecutionOwner().get());
        });
        story.then( r-> {  // Verify that the FlowExecutionOwner can trigger lazy-load correctly
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            assertNotNull(b.asFlowExecutionOwner().get());
            assertTrue(b.executionLoaded);
            assertNotNull(b.asFlowExecutionOwner().getOrNull());
        });
    }

    @Issue("JENKINS-25550")
    @Test public void hardKill() throws Exception {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.addProperty( new DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY));
            p.setDefinition(new CpsFlowDefinition("def seq = 0; retry (99) {zombie id: ++seq}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("[1] undead", b);
            Executor ex = b.getExecutor();
            assertNotNull(ex);
            ex.interrupt();
            r.waitForMessage("[1] bwahaha FlowInterruptedException #1", b);
            ex.interrupt();
            r.waitForMessage("[1] bwahaha FlowInterruptedException #2", b);
            b.doTerm();
            r.waitForMessage("[2] undead", b);
            ex.interrupt();
            r.waitForMessage("[2] bwahaha FlowInterruptedException #1", b);
            b.doKill();
            r.waitForMessage("Hard kill!", b);
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.ABORTED, b);
        });
        story.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            assertFalse(b.isBuilding());
            assertFalse(b.executionLoaded);
        });
    }

    @Issue("JENKINS-33721")
    @Test public void termAndKillInSidePanel() throws Exception {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("def seq = 0; retry (99) {zombie id: ++seq}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("[1] undead", b);
            Executor ex = b.getExecutor();
            assertNotNull(ex);
            ex.interrupt();
            r.waitForMessage("[1] bwahaha FlowInterruptedException #1", b);
            ex.interrupt();
            r.waitForMessage("[1] bwahaha FlowInterruptedException #2", b);
            assertFalse(hasTermOrKillLink(b, "term"));
            assertFalse(hasTermOrKillLink(b, "kill"));
            r.waitForMessage("Click here to forcibly terminate running steps", b);
            assertTrue(hasTermOrKillLink(b, "term"));
            assertFalse(hasTermOrKillLink(b, "kill"));
            b.doTerm();
            r.waitForMessage("[2] undead", b);
            r.waitForMessage("Click here to forcibly kill entire build", b);
            assertTrue(hasTermOrKillLink(b, "term"));
            assertTrue(hasTermOrKillLink(b, "kill"));
            b.doKill();
            r.waitForMessage("Hard kill!", b);
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.ABORTED, b);
            assertTrue(b.completed);
        });
    }

    private boolean hasTermOrKillLink(WorkflowRun b, String termOrKill) throws Exception {
        return !story.j.createWebClient().getPage(b)
                .getByXPath("//a[@href = '#' and contains(@onclick, '/" + b.getUrl() + termOrKill + "')]").isEmpty();
    }

    public static class Zombie extends Step {
        @DataBoundSetter public int id;
        @DataBoundConstructor public Zombie() {}
        @Override public StepExecution start(StepContext context) throws Exception {
            return new Execution(context, id);
        }
        private static class Execution extends StepExecution {
            int id;
            int count;
            Execution(StepContext context, int id) {
                super(context);
                this.id = id;
            }
            @Override public boolean start() throws Exception {
                getContext().get(TaskListener.class).getLogger().printf("[%d] undead%n", id);
                return false;
            }
            @Override public void stop(Throwable cause) throws Exception {
                getContext().get(TaskListener.class).getLogger().printf("[%d] bwahaha %s #%d%n", id, cause.getClass().getSimpleName(), ++count);
            }
        }
        @TestExtension public static class DescriptorImpl extends StepDescriptor {
            @Override public String getFunctionName() {
                return "zombie";
            }
            @Override public String getDisplayName() {
                return "zombie";
            }
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.singleton(TaskListener.class);
            }
        }
    }

    @Issue("JENKINS-43055")
    @Test
    public void flowExecutionListener() throws Exception {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("echo 'Running for listener'\n" +
                "sleep 0\n" +
                "semaphore 'wait'\n" +
                "sleep 0\n" +
                "semaphore 'post-resume'\n" +
                "sleep 0\n" +
                "error 'fail'\n", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
            ExecListener listener = ExtensionList.lookup(FlowExecutionListener.class).get(ExecListener.class);
            assertNotNull(listener);
            assertEquals(1, listener.started);
            assertEquals(0, listener.resumed);
            assertEquals(0, listener.finished);
        });
        story.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getLastBuild();
            assertTrue(b.isBuilding());
            SemaphoreStep.success("wait/1", null);

            SemaphoreStep.waitForStart("post-resume/1", b);
            ExecListener listener = ExtensionList.lookup(FlowExecutionListener.class).get(ExecListener.class);
            assertNotNull(listener);
            assertEquals(0, listener.started);
            assertEquals(1, listener.resumed);
            assertEquals(0, listener.finished);

            SemaphoreStep.success("post-resume/1", null);

            r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b));
            r.assertLogContains("Running for listener", b);

            assertEquals(0, listener.started);
            assertEquals(1, listener.resumed);
            assertEquals(1, listener.finished);
            assertTrue(listener.graphListener.wasCalledBeforeExecListener);
        });

    }

    @TestExtension("flowExecutionListener")
    public static class ExecListener extends FlowExecutionListener {
        int started;
        int finished;
        int resumed;
        ExecGraphListener graphListener = new ExecGraphListener();

        @Override
        public void onRunning(FlowExecution execution) {
            addGraphListenerCheckList(execution);
            started++;
        }

        @Override
        public void onResumed(FlowExecution execution) {
            addGraphListenerCheckList(execution);
            resumed++;
        }

        private void addGraphListenerCheckList(FlowExecution execution) {
            execution.addListener(graphListener);
            boolean listHasExec = false;
            for (FlowExecution e : FlowExecutionList.get()) {
                if (e.equals(execution)) {
                    listHasExec = true;
                }
            }
            assertTrue(listHasExec);
        }

        @Override
        public void onCompleted(FlowExecution execution) {
            finished++;
            for (FlowExecution e : FlowExecutionList.get()) {
                assertNotEquals(e, execution);
            }

            assertTrue(execution.isComplete());
            assertNotNull(execution.getCauseOfFailure());
            List<FlowNode> heads = execution.getCurrentHeads();
            assertEquals(1, heads.size());
            assertTrue(heads.get(0) instanceof FlowEndNode);
            FlowEndNode node = (FlowEndNode)heads.get(0);
            assertEquals(Result.FAILURE, node.getResult());
        }
    }

    public static class ExecGraphListener implements GraphListener.Synchronous {
        boolean wasCalledBeforeExecListener;

        @Override
        public void onNewHead(FlowNode node) {
            if (node instanceof FlowEndNode) {
                ExecListener listener = ExtensionList.lookup(FlowExecutionListener.class).get(ExecListener.class);
                if (listener.finished == 0) {
                    wasCalledBeforeExecListener = true;
                }
            }
        }
    }
}
