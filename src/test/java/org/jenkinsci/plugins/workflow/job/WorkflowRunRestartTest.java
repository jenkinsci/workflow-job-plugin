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

import com.google.inject.Inject;
import hudson.ExtensionList;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.List;

public class WorkflowRunRestartTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Issue("JENKINS-27299")
    @Test public void disabled() {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node {semaphore 'wait'}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait/1", b);
                p.makeDisabled(true);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                assertTrue(p.isDisabled());
                WorkflowRun b = p.getBuildByNumber(1);
                assertTrue(b.isBuilding());
                SemaphoreStep.success("wait/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));
            }
        });
    }

    @Issue("JENKINS-25550")
    @Test public void hardKill() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("def seq = 0; retry (99) {zombie id: ++seq}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[1] undead", b);
                Executor ex = b.getExecutor();
                assertNotNull(ex);
                ex.interrupt();
                story.j.waitForMessage("[1] bwahaha FlowInterruptedException #1", b);
                ex.interrupt();
                story.j.waitForMessage("[1] bwahaha FlowInterruptedException #2", b);
                b.doTerm();
                story.j.waitForMessage("[2] undead", b);
                ex.interrupt();
                story.j.waitForMessage("[2] bwahaha FlowInterruptedException #1", b);
                b.doKill();
                story.j.waitForMessage("Hard kill!", b);
                story.j.waitForCompletion(b);
                story.j.assertBuildStatus(Result.ABORTED, b);
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);
                assertFalse(b.isBuilding());
            }
        });
    }

    @Issue("JENKINS-33721")
    @Test public void termAndKillInSidePanel() throws Exception {
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("def seq = 0; retry (99) {zombie id: ++seq}"));
                WorkflowRun b = p.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("[1] undead", b);
                Executor ex = b.getExecutor();
                assertNotNull(ex);
                ex.interrupt();
                story.j.waitForMessage("[1] bwahaha FlowInterruptedException #1", b);
                ex.interrupt();
                story.j.waitForMessage("[1] bwahaha FlowInterruptedException #2", b);
                assertFalse(hasTermOrKillLink(b, "term"));
                assertFalse(hasTermOrKillLink(b, "kill"));
                story.j.waitForMessage("Click here to forcibly terminate running steps", b);
                assertTrue(hasTermOrKillLink(b, "term"));
                assertFalse(hasTermOrKillLink(b, "kill"));
                b.doTerm();
                story.j.waitForMessage("[2] undead", b);
                story.j.waitForMessage("Click here to forcibly kill entire build", b);
                assertTrue(hasTermOrKillLink(b, "term"));
                assertTrue(hasTermOrKillLink(b, "kill"));
                b.doKill();
                story.j.waitForMessage("Hard kill!", b);
                story.j.waitForCompletion(b);
                story.j.assertBuildStatus(Result.ABORTED, b);
            }
        });
    }

    private boolean hasTermOrKillLink(WorkflowRun b, String termOrKill) throws Exception {
        return !story.j.createWebClient().getPage(b)
                .getByXPath("//a[@href = '#' and contains(@onclick, '/" + b.getUrl() + termOrKill + "')]").isEmpty();
    }

    public static class Zombie extends AbstractStepImpl {
        @DataBoundSetter public int id;
        @DataBoundConstructor public Zombie() {}
        public static class Execution extends AbstractStepExecutionImpl {
            @Inject(optional=true) private transient Zombie step;
            @StepContextParameter private transient TaskListener listener;
            int id;
            int count;
            @Override public boolean start() throws Exception {
                id = step.id;
                listener.getLogger().printf("[%d] undead%n", id);
                return false;
            }
            @Override public void stop(Throwable cause) throws Exception {
                listener.getLogger().printf("[%d] bwahaha %s #%d%n", id, cause.getClass().getSimpleName(), ++count);
            }

        }
        @TestExtension public static class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {
                return "zombie";
            }
            @Override public String getDisplayName() {
                return "zombie";
            }
        }
    }

    @Issue("JENKINS-43055")
    @Test
    public void flowExecutionListener() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
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
            }
        });
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
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

                story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b));
                story.j.assertLogContains("Running for listener", b);

                assertEquals(0, listener.started);
                assertEquals(1, listener.resumed);
                assertEquals(1, listener.finished);
                assertTrue(listener.graphListener.wasCalledBeforeExecListener);
            }
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
