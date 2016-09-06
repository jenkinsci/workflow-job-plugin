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

import hudson.model.BallColor;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
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
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.GitStep;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.recipes.LocalData;

public class WorkflowRunTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

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

    @Test public void parameters() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo \"param=${PARAM}\"",true));
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("PARAM", null)));
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0, new ParametersAction(new StringParameterValue("PARAM", "value"))));
        r.assertLogContains("param=value", b);
    }

    @Test public void funnyParameters() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo \"a.b=${binding['a.b']}\"", /* TODO Script.binding does not work in sandbox */false));
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

    private void assertCulprits(WorkflowRun b, String... expectedIds) {
        Set<String> actual = new TreeSet<>();
        for (User u : b.getCulprits()) {
            actual.add(u.getId());
        }
        assertEquals(TreeSet<>(Arrays.asList(expectedIds), actual));
    }

    private void updateJenkinsfileWithCommitter(String committerName, String committerEmail, String jenkinsfileText)
            throws Exception {
        sampleRepo.git("config", "user.name", committerName);
        sampleRepo.git("config", "user.email", committerEmail);
        sampleRepo.write("Jenkinsfile", jenkinsfileText);
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "--all", "--message=testing");
    }

    @Issue("JENKINS-37872")
    @Test
    public void culprits() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");

        sampleRepo.init();

        p.setDefinition(new CpsScmFlowDefinition(new GitStep(sampleRepo.toString()).createSCM(), "Jenkinsfile"));

        // 1st build, successful, no culprits. No changesets at all, actually, since it's the first build!
        updateJenkinsfileWithCommitter("alice", "alice@example.com", "echo 'commit by alice'; echo 'Build successful'");
        r.assertBuildStatusSuccess(p.scheduleBuild2(0));

        // 2nd build
        updateJenkinsfileWithCommitter("bob", "bob@example.com", "echo 'commit by bob'; currentBuild.result = 'FAILURE'");
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b2));
        assertCulprits(b2, "bob");

        // 3rd build. bob continues to be in culprit
        updateJenkinsfileWithCommitter("charlie", "charlie@example.com", "echo 'commit by charlie'; currentBuild.result = 'FAILURE'");
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b3));
        assertCulprits(b3, "bob", "charlie");

        // 4th build, unstable. culprit list should continue
        updateJenkinsfileWithCommitter("dave", "dave@example.com", "echo 'commit by dave'; currentBuild.result = 'UNSTABLE'");
        WorkflowRun b4 = p.scheduleBuild2(0).waitForStart();
        r.assertBuildStatus(Result.UNSTABLE, r.waitForCompletion(b4));
        assertCulprits(b4, "bob", "charlie", "dave");

        // 5th build, unstable, just a re-run of the previous one.
        WorkflowRun b5 = p.scheduleBuild2(0).waitForStart();
        r.assertBuildStatus(Result.UNSTABLE, r.waitForCompletion(b5));
        assertCulprits(b5, "bob", "charlie", "dave");

        // 6th build, unstable. culprit list should continue
        updateJenkinsfileWithCommitter("eve", "eve@example.com", "echo 'commit by eve'; currentBuild.result = 'UNSTABLE'");
        WorkflowRun b6 = p.scheduleBuild2(0).waitForStart();
        r.assertBuildStatus(Result.UNSTABLE, r.waitForCompletion(b6));
        assertCulprits(b6, "bob", "charlie", "dave", "eve");

        // 7th build, success, accumulation continues up to this point
        updateJenkinsfileWithCommitter("fred", "fred@example.com", "echo 'commit by fred'; echo 'Build successful'");
        WorkflowRun b7 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertCulprits(b7, "bob", "charlie", "dave", "eve", "fred");

        // 8th build, back to culprits just containing who ever committed to this one specific build.
        updateJenkinsfileWithCommitter("george", "george@example.com", "echo 'commit by george'; echo 'Build successful'");
        WorkflowRun b8 = r.assertBuildStatusSuccess(p.scheduleBuild2(0));
        assertCulprits(b8, "george");
    }

}
