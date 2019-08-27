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

import com.gargoylesoftware.htmlunit.WebResponse;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.Permission;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import jenkins.security.QueueItemAuthenticatorConfiguration;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockQueueItemAuthenticator;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.workflow.support.actions.EnvironmentAction;

public class WorkflowRunTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Test public void basics() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("println('hello')", true));
        WorkflowRun b1 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertFalse(b1.isBuilding());
        assertFalse(b1.isInProgress());
        assertFalse(b1.isLogUpdated());
        assertTrue(b1.completed);
        assertTrue(b1.executionLoaded);
        assertTrue(b1.getDuration() > 0);
        WorkflowRun b2 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertEquals(b1, b2.getPreviousBuild());
        assertNull(b1.getPreviousBuild());
        r.assertLogContains("hello", b1);
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

    @Issue("JENKINS-46945")
    @Test public void durationIsCalculated() throws Exception {
        WorkflowJob duration = r.jenkins.createProject(WorkflowJob.class, "duration");
        duration.setDefinition(new CpsFlowDefinition("echo \"duration should not be 0 in DurationRunListener\"",true));
        r.assertBuildStatusSuccess(duration.scheduleBuild2(0));
        assertNotEquals(0L, DurationRunListener.duration);
    }

    @Extension
    public static final class DurationRunListener extends RunListener<Run> {
        static long duration = 0L;
        @Override
        public void onCompleted(Run run, @Nonnull TaskListener listener) {
            duration = run.getDuration();
        }
    }

    @Test public void funnyParameters() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo \"a.b=${params['a.b']}\"", true));
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
            "println('hello')\n",
            true));

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

        p.makeDisabled(true);
        assertSame(BallColor.DISABLED, p.getIconColor());
        p.makeDisabled(false);

        // get another one going
        q = p.scheduleBuild2(0);
        WorkflowRun b2 = q.getStartCondition().get();
        e = (CpsFlowExecution) b2.getExecutionPromise().get();

        // initial state should be blinking blue because the last one was blue
        assertFalse(b2.hasntStartedYet());
        assertColor(b2, BallColor.BLUE_ANIME);

        p.makeDisabled(true);
        assertSame(BallColor.DISABLED_ANIME, p.getIconColor());
        p.makeDisabled(false);

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
    private void assertColor(WorkflowRun b, BallColor color) {
        assertSame(color, b.getIconColor());
        assertSame(color, b.getParent().getIconColor());
    }



    @Test public void scriptApproval() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.READ).everywhere().to("dev").
            grant(Item.PERMISSIONS.getPermissions().toArray(new Permission[0])).everywhere().to("dev"));
        final WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        final String groovy = "println 'hello'";
        try (ACLContext context = ACL.as(User.getById("dev", true))) {
            p.setDefinition(new CpsFlowDefinition(groovy, false /* for mock authorization strategy */));
        }
        r.assertLogContains("UnapprovedUsageException", r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get()));
        Set<ScriptApproval.PendingScript> pendingScripts = ScriptApproval.get().getPendingScripts();
        assertEquals(1, pendingScripts.size());
        ScriptApproval.PendingScript pendingScript = pendingScripts.iterator().next();
        assertEquals(groovy, pendingScript.script);
        // only works if configured via WebClient: assertEquals(p, pendingScript.getContext().getItem());
        assertEquals("dev", pendingScript.getContext().getUser());
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
                        "}}",
                true));
        QueueTaskFuture<WorkflowRun> workflowRunQueueTaskFuture = p.scheduleBuild2(0);
        WorkflowRun run = r.assertBuildStatus(Result.FAILURE, workflowRunQueueTaskFuture.get());

        // The issue was that the WorkflowRun's execution instance got initialised even though the script
        // was bad and the execution never started. The fix is to ensure that it only gets initialised
        // if the execution instance starts successfully i.e. in the case of this test, that it doesn't
        // get initialised.
        assertNull(run.getExecution());
    }

    @Issue("JENKINS-29571")
    @Test public void buildRecordAfterRename() throws Exception {
        {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p1");
            p.setDefinition(new CpsFlowDefinition("echo 'hello world'", true));
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
        p.setDefinition(new CpsFlowDefinition("sleep 1; semaphore 'hang'", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        Thread.sleep(500); // TODO sleeps should not be necessary but seems to randomly fail to receive interrupt otherwise
        SemaphoreStep.waitForStart("hang/1", b1);
        Thread.sleep(500);
        Executor ex = b1.getExecutor();
        assertNotNull(ex);
        ex.interrupt(Result.NOT_BUILT, new CauseOfInterruption.UserInterruption("bob"));
        r.assertBuildStatus(Result.NOT_BUILT, r.waitForCompletion(b1));
        InterruptedBuildAction iba = b1.getAction(InterruptedBuildAction.class);
        assertNotNull(iba);
        assertEquals(Collections.singletonList(new CauseOfInterruption.UserInterruption("bob")), iba.getCauses());
        Thread.sleep(500);
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        assertEquals(2, b2.getNumber());
        Thread.sleep(500);
        SemaphoreStep.waitForStart("hang/2", b2);
        Thread.sleep(500);
        ex = b2.getExecutor();
        assertNotNull(ex);
        ex.interrupt();
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b2));
        iba = b2.getAction(InterruptedBuildAction.class);
        assertNull(iba);
    }

    @Issue("JENKINS-41276")
    @Test public void interruptCause() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        ScriptApproval.get().approveSignature("method org.jenkinsci.plugins.workflow.steps.FlowInterruptedException getCauses"); // TODO should probably be @Whitelisted
        ScriptApproval.get().approveSignature("method jenkins.model.CauseOfInterruption$UserInterruption getUser"); // ditto
        p.setDefinition(new CpsFlowDefinition("@NonCPS def users(e) {e.causes*.user}; try {semaphore 'wait'} catch (e) {echo(/users=${users(e)}/); throw e}", true));
        final WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b1);
        try (ACLContext context = ACL.as(User.getById("dev", true))) {
            b1.getExecutor().doStop();
        }
        r.assertBuildStatus(Result.ABORTED, r.waitForCompletion(b1));
        r.assertLogContains("users=[dev]", b1);
        InterruptedBuildAction iba = b1.getAction(InterruptedBuildAction.class);
        assertNotNull(iba);
        assertEquals(Collections.singletonList(new CauseOfInterruption.UserInterruption("dev")), iba.getCauses());
        String log = JenkinsRule.getLog(b1);
        assertEquals(log, 1, StringUtils.countMatches(log, jenkins.model.Messages.CauseOfInterruption_ShortDescription("dev")));
    }

    @Test
    @Issue("JENKINS-43396")
    public void globalNodePropertiesInEnv() throws Exception {
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> original = r.jenkins.getGlobalNodeProperties();
        EnvironmentVariablesNodeProperty envProp = new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry("KEY", "VALUE"));

        original.add(envProp);

        WorkflowJob j = r.jenkins.createProject(WorkflowJob.class, "envVars");
        j.setDefinition(new CpsFlowDefinition("echo \"KEY is ${env.KEY}\"", true));

        WorkflowRun b = r.assertBuildStatusSuccess(j.scheduleBuild2(0));
        r.assertLogContains("KEY is " + envProp.getEnvVars().get("KEY"), b);
    }

    @Test
    @Issue("JENKINS-24141")
    public void culprits() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("import org.jvnet.hudson.test.FakeChangeLogSCM\n" +
                "semaphore 'waitFirst'\n" +
                "def testScm = new FakeChangeLogSCM()\n" +
                "testScm.addChange().withAuthor(/alice$BUILD_NUMBER/)\n" +
                "node {\n" +
                "    checkout(testScm)\n" +
                "    semaphore 'waitSecond'\n" +
                "    def secondScm = new FakeChangeLogSCM()\n" +
                "    secondScm.addChange().withAuthor(/bob$BUILD_NUMBER/)\n" +
                "    checkout(secondScm)\n" +
                "    semaphore 'waitThird'\n" +
                "    def thirdScm = new FakeChangeLogSCM()\n" +
                "    thirdScm.addChange().withAuthor(/charlie$BUILD_NUMBER/)\n" +
                "    checkout(thirdScm)\n" +
                "}\n", false /* for org.jvnet.hudson.test.FakeChangeLogSCM */));

        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();

        SemaphoreStep.waitForStart("waitFirst/1", b1);
        assertTrue(b1.getCulpritIds().isEmpty());
        SemaphoreStep.success("waitFirst/1", null);

        SemaphoreStep.waitForStart("waitSecond/1", b1);
        assertCulprits(b1, "alice1");
        SemaphoreStep.success("waitSecond/1", null);

        SemaphoreStep.waitForStart("waitThird/1", b1);
        assertCulprits(b1, "alice1", "bob1");
        SemaphoreStep.failure("waitThird/1", new AbortException());

        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b1));

        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();

        SemaphoreStep.waitForStart("waitFirst/2", b2);
        assertCulprits(b2, "alice1", "bob1");
        SemaphoreStep.success("waitFirst/2", null);

        SemaphoreStep.waitForStart("waitSecond/2", b2);
        assertCulprits(b2, "alice1", "bob1", "alice2");
        SemaphoreStep.success("waitSecond/2", null);

        SemaphoreStep.waitForStart("waitThird/2", b2);
        assertCulprits(b2, "alice1", "bob1", "alice2", "bob2");
        SemaphoreStep.success("waitThird/2", b2);

        r.assertBuildStatusSuccess(r.waitForCompletion(b2));
        assertCulprits(b2, "alice1", "bob1", "alice2", "bob2", "charlie2");
    }

    private void assertCulprits(WorkflowRun b, String... expectedIds) throws IOException, SAXException {
        Set<String> actual = new TreeSet<>(b.getCulpritIds());
        assertEquals(actual, new TreeSet<>(Arrays.asList(expectedIds)));

        if (expectedIds.length > 0) {
            JenkinsRule.WebClient wc = r.createWebClient();
            WebResponse response = wc.goTo(b.getUrl() + "api/json?tree=culprits[id]", "application/json").getWebResponse();
            JSONObject json = JSONObject.fromObject(response.getContentAsString());

            Object culpritsArray = json.get("culprits");
            assertNotNull(culpritsArray);
            assertTrue(culpritsArray instanceof JSONArray);
            Set<String> fromApi = new TreeSet<>();
            for (Object o : ((JSONArray)culpritsArray).toArray()) {
                assertTrue(o instanceof JSONObject);
                Object id = ((JSONObject)o).get("id");
                if (id instanceof String) {
                    fromApi.add((String)id);
                }
            }
            assertEquals(fromApi, new TreeSet<>(Arrays.asList(expectedIds)));
        }
    }

    @Issue("JENKINS-46652")
    @Test public void noComputerBuildPermissionOnMaster() throws Exception {
        r.waitOnline(r.createSlave("remote", null, null));
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.ADMINISTER).everywhere().to("admin"));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        // Control case:
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().add(new MockQueueItemAuthenticator(Collections.singletonMap("p", User.getById("admin", true).impersonate())));
        r.buildAndAssertSuccess(p);
        // Test case: build is never scheduled, queue item hangs with “Waiting for next available executor on master”
        QueueItemAuthenticatorConfiguration.get().getAuthenticators().replace(new MockQueueItemAuthenticator(Collections.singletonMap("p", User.getById("dev", true).impersonate())));
        r.buildAndAssertSuccess(p);
    }

    @Issue("JENKINS-31096")
    @Test public void unicode() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        String message = "¡Čau → there!";
        p.setDefinition(new CpsFlowDefinition("echo '" + message + "'", true));
        r.assertLogContains(message, r.buildAndAssertSuccess(p));
    }

    @Issue("JENKINS-54128")
    @SuppressWarnings("deprecation")
    @Test public void getLogFile() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'sample text'", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertEquals(IOUtils.toString(b.getLogInputStream()), FileUtils.readFileToString(b.getLogFile()));
    }

    @Issue("JENKINS-59083")
    @Test public void logAfterBuildComplete() throws Exception {
        logging.record(WorkflowRun.class, Level.WARNING).capture(1);
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("env.KEY = 'value'", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertEquals("value", b.getAction(EnvironmentAction.class).getEnvironment().get("KEY"));
        assertFalse(logging.getRecords().stream().findAny().isPresent());
        assertFalse(b.isLogUpdated());
    }

}
