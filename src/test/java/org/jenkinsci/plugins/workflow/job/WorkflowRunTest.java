/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.collect.Lists;
import hudson.console.ModelHyperlinkNote;
import hudson.model.BallColor;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.Permission;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.Matchers.containsString;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.recipes.LocalData;
import org.kohsuke.stapler.DataBoundConstructor;

public class WorkflowRunTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void basics() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("println('hello')"));
        WorkflowRun b1 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertFalse(b1.isBuilding());
        assertFalse(b1.isInProgress());
        assertFalse(b1.isLogUpdated());
        assertTrue(b1.getDuration() > 0);
        WorkflowRun b2 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(b1, b2.getPreviousBuild());
        assertEquals(null, b1.getPreviousBuild());
        r.assertLogContains("hello\n", b1);
    }

    @Issue("JENKINS-30910")
    @Test public void parameters() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo \"param=${PARAM}\"",true));
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("PARAM", null)));
        r.assertLogContains("param=value", r.assertBuildStatusSuccess(p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("PARAM", "value")))));
        p.setDefinition(new CpsFlowDefinition("echo \"param=${env.PARAM}\"",true));
        r.assertLogContains("param=value", r.assertBuildStatusSuccess(p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("PARAM", "value")))));
    }

    @Test public void funnyParameters() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo \"a.b=${env['a.b']}\"", true));
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("a.b", null)));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("a.b", "v"))));
        r.assertLogContains("a.b=v", b);
    }

    /**
     * Verifies that {@link WorkflowRun#getIconColor()} returns the right color.
     */
    @Test
    public void iconColor() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "println('hello')\n"+
            "semaphore 'wait'\n"+
            "println('hello')\n"
        ));

        // no build exists yet
        assertSame(p.getIconColor(),BallColor.NOTBUILT);

        // get a build going
        QueueTaskFuture<WorkflowRun> q = p.scheduleBuild2(0);
        WorkflowRun b1 = q.getStartCondition().get();
        CpsFlowExecution e = (CpsFlowExecution) b1.getExecutionPromise().get();

        // initial state should be blinking gray
        assertFalse(b1.hasntStartedYet());
        assertColor(b1, BallColor.NOTBUILT_ANIME);

        SemaphoreStep.waitForStart("wait/1", b1);

        // at the pause point, it should be still blinking gray
        assertFalse(b1.hasntStartedYet());
        assertColor(b1, BallColor.NOTBUILT_ANIME);

        SemaphoreStep.success("wait/1", null);

        // bring it to the completion
        q.get(5, TimeUnit.SECONDS);
        assertTrue(e.isComplete());

        // and the color should be now solid blue
        assertFalse(b1.hasntStartedYet());
        assertColor(b1, BallColor.BLUE);

        // get another one going
        q = p.scheduleBuild2(0);
        WorkflowRun b2 = q.getStartCondition().get();
        e = (CpsFlowExecution) b2.getExecutionPromise().get();

        // initial state should be blinking blue because the last one was blue
        assertFalse(b2.hasntStartedYet());
        assertColor(b2, BallColor.BLUE_ANIME);

        SemaphoreStep.waitForStart("wait/2", b2);

        // at the pause point, it should be still blinking gray
        assertFalse(b2.hasntStartedYet());
        assertColor(b2, BallColor.BLUE_ANIME);

        // bring it to the completion
        SemaphoreStep.success("wait/2", null);
        q.get(5, TimeUnit.SECONDS);

        // and the color should be now solid blue
        assertFalse(b2.hasntStartedYet());
        assertColor(b2, BallColor.BLUE);
    }
    private void assertColor(WorkflowRun b, BallColor color) throws IOException {
        assertSame(color, b.getIconColor());
        assertSame(color, b.getParent().getIconColor());
    }

    @Test public void scriptApproval() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.READ).everywhere().to("devel").
            grant(Item.PERMISSIONS.getPermissions().toArray(new Permission[0])).everywhere().to("devel"));
        final WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        final String groovy = "println 'hello'";
        ACL.impersonate(User.get("devel").impersonate(), new Runnable() {
            @Override public void run() {
                p.setDefinition(new CpsFlowDefinition(groovy));
            }
        });
        r.assertLogContains("UnapprovedUsageException", r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
        Set<ScriptApproval.PendingScript> pendingScripts = ScriptApproval.get().getPendingScripts();
        assertEquals(1, pendingScripts.size());
        ScriptApproval.PendingScript pendingScript = pendingScripts.iterator().next();
        assertEquals(groovy, pendingScript.script);
        // only works if configured via WebClient: assertEquals(p, pendingScript.getContext().getItem());
        assertEquals("devel", pendingScript.getContext().getUser());
        ScriptApproval.get().approveScript(pendingScript.getHash());
        r.assertLogContains("hello", r.assertBuildStatusSuccess(p.scheduleBuild2(0)));
    }

    @Test @Issue("JENKINS-29221")
    public void failedToStartRun() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "{{stage 'dev'\n" +
                        "def hello = new HelloWorld()\n" +
                        "public class HelloWorld()\n" +
                        "{ // <- invalid class definition }\n" +
                        "}}"
        ));
        QueueTaskFuture<WorkflowRun> workflowRunQueueTaskFuture = p.scheduleBuild2(0);
        WorkflowRun run = r.assertBuildStatus(Result.FAILURE, workflowRunQueueTaskFuture.get());

        // The issue was that the WorkflowRun's execution instance got initialised even though the script
        // was bad and the execution never started. The fix is to ensure that it only gets initialised
        // if the execution instance starts successfully i.e. in the case of this test, that it doesn't
        // get initialised.
        assertNull(run.getExecution());
    }

    @Issue("JENKINS-27531")
    @LocalData
    @Test public void loadMigratedBuildRecord() throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        assertNotNull(p);
        WorkflowRun b = p.getLastBuild();
        assertNotNull(b);
        r.assertBuildStatusSuccess(r.waitForCompletion(b));
    }

    @Issue("JENKINS-29571")
    @Test public void buildRecordAfterRename() throws Exception {
        {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p1");
            p.setDefinition(new CpsFlowDefinition("echo 'hello world'"));
            r.assertBuildStatusSuccess(p.scheduleBuild2(0));
            p.renameTo("p2");
        }
        r.jenkins.reload();
        {
            WorkflowJob p = r.jenkins.getItemByFullName("p2", WorkflowJob.class);
            assertNotNull(p);
            WorkflowRun b = p.getLastBuild();
            assertNotNull(b);
            System.out.println(FileUtils.readFileToString(new File(b.getRootDir(), "build.xml")));
            r.assertLogContains("hello world", b);
            FlowExecution exec = b.getExecution();
            assertNotNull(exec);
            FlowGraphWalker w = new FlowGraphWalker(exec);
            List<String> steps = new ArrayList<>();
            for (FlowNode n : w) {
                if (n instanceof StepNode) {
                    steps.add(((StepNode) n).getDescriptor().getFunctionName());
                }
            }
            assertEquals("[echo]", steps.toString());
        }
    }

    @Test public void interruptWithResult() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("semaphore 'hang'"));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("hang/1", b1);
        Executor ex = b1.getExecutor();
        assertNotNull(ex);
        ex.interrupt(Result.NOT_BUILT, new CauseOfInterruption.UserInterruption("bob"));
        r.assertBuildStatus(Result.NOT_BUILT, r.waitForCompletion(b1));
        InterruptedBuildAction iba = b1.getAction(InterruptedBuildAction.class);
        assertNotNull(iba);
        assertEquals(Collections.singletonList(new CauseOfInterruption.UserInterruption("bob")), iba.getCauses());
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        assertEquals(2, b2.getNumber());
        SemaphoreStep.waitForStart("hang/2", b2);
        ex = b2.getExecutor();
        assertNotNull(ex);
        ex.interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b2));
        iba = b2.getAction(InterruptedBuildAction.class);
        assertNotNull(iba);
        assertEquals(Collections.emptyList(), iba.getCauses());
    }

    @Issue("JENKINS-38381")
    @Test public void consoleNotes() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("hyperlink()", true));
        User alice = User.get("alice");
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0, new CauseAction(ACL.impersonate(alice.impersonate(), new NotReallyRoleSensitiveCallable<Cause,RuntimeException>() {
            @Override public Cause call() throws RuntimeException {
                return new Cause.UserIdCause();
            }
        }))));
        HtmlPage page = r.createWebClient().goTo(b.getUrl() + "console");
        assertLogContains(page, hudson.model.Messages.Cause_UserIdCause_ShortDescription(alice.getDisplayName()), alice.getUrl());
        assertLogContains(page, "Running inside " + b.getDisplayName(), b.getUrl());
        DepthFirstScanner scanner = new DepthFirstScanner();
        scanner.setup(b.getExecution().getCurrentHeads());
        List<FlowNode> nodes = Lists.newArrayList(scanner.filter(FlowScanningUtils.hasActionPredicate(LogAction.class)));
        assertEquals(1, nodes.size());
        page = r.createWebClient().goTo(nodes.get(0).getUrl() + nodes.get(0).getAction(LogAction.class).getUrlName());
        assertLogContains(page, "Running inside " + b.getDisplayName(), b.getUrl());
    }
    private void assertLogContains(HtmlPage page, String plainText, String url) throws Exception {
        String html = page.getWebResponse().getContentAsString();
        assertThat(page.getUrl() + " looks OK as text:\n" + html, page.getDocumentElement().getTextContent(), containsString(plainText));
        String absUrl = r.contextPath + "/" + url;
        assertNotNull("found " + absUrl + " in:\n" + html, page.getAnchorByHref(absUrl));
    }
    public static class HyperlinkingStep extends AbstractStepImpl {
        @DataBoundConstructor public HyperlinkingStep() {}
        public static class Execution extends AbstractSynchronousStepExecution<Void> {
            @StepContextParameter Run<?,?> run;
            @StepContextParameter TaskListener listener;
            @Override protected Void run() throws Exception {
                listener.getLogger().println("Running inside " + ModelHyperlinkNote.encodeTo(run));
                return null;
            }
        }
        @TestExtension("consoleNotes") public static class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {
                return "hyperlink";
            }
        }
    }

    @Ignore("TODO currently not implemented")
    @Test
    @Issue({"JENKINS-26122", "JENKINS-28222"})
    public void parallelBranchLabels() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "parallel a: {\n" +
            "  echo 'a-outside-1'\n" +
            "  withEnv(['A=1']) {echo 'a-inside-1'}\n" +
            "  echo 'a-outside-2'\n" +
            "  withEnv(['A=1']) {echo 'a-inside-2'}\n" +
            "}, b: {\n" +
            "  echo 'b-outside-1'\n" +
            "  withEnv(['B=1']) {echo 'b-inside-1'}\n" +
            "  echo 'b-outside-2'\n" +
            "  withEnv(['B=1']) {echo 'b-inside-2'}\n" +
            "}", true));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        r.assertLogContains("[a] a-outside-1", b);
        r.assertLogContains("[b] b-outside-1", b);
        r.assertLogContains("[a] a-inside-1", b);
        r.assertLogContains("[b] b-inside-1", b);
        r.assertLogContains("[a] a-outside-2", b);
        r.assertLogContains("[b] b-outside-2", b);
        r.assertLogContains("[a] a-inside-2", b);
        r.assertLogContains("[b] b-inside-2", b);
    }

}
