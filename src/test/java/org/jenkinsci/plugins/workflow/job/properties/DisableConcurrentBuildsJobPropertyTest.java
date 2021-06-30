/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
 *
 *
 */
package org.jenkinsci.plugins.workflow.job.properties;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.InterruptedBuildAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.assertEquals;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DisableConcurrentBuildsJobPropertyTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();


    @Issue("JENKINS-34547")
    @LocalData
    @Test
    public void concurrentBuildsMigrationOnByDefault() throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        assertNotNull(p);
        assertNull(p.getProperty(DisableConcurrentBuildsJobProperty.class));
        assertTrue(p.isConcurrentBuild());
    }

    @Issue("JENKINS-34547")
    @LocalData
    @Test public void concurrentBuildsMigrationFromFalse() throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        assertNotNull(p);
        assertNotNull(p.getProperty(DisableConcurrentBuildsJobProperty.class));
        assertFalse(p.isConcurrentBuild());
    }

    @Issue("JENKINS-34547")
    @Test public void configRoundTrip() throws Exception {
        WorkflowJob defaultCase = r.jenkins.createProject(WorkflowJob.class, "defaultCase");
        assertTrue(defaultCase.isConcurrentBuild());

        WorkflowJob roundTripDefault = r.configRoundtrip(defaultCase);
        assertTrue(roundTripDefault.isConcurrentBuild());

        WorkflowJob disabledCase = r.jenkins.createProject(WorkflowJob.class, "disableCase");
        disabledCase.setConcurrentBuild(false);
        assertFalse(disabledCase.isConcurrentBuild());

        WorkflowJob roundTripDisabled = r.configRoundtrip(disabledCase);
        assertFalse(roundTripDisabled.isConcurrentBuild());

        DisableConcurrentBuildsJobProperty prop = roundTripDisabled.getProperty(DisableConcurrentBuildsJobProperty.class);
        assertNotNull(prop);
        assertFalse(prop.isAbortPrevious());
        prop.setAbortPrevious(true);
        roundTripDisabled = r.configRoundtrip(roundTripDisabled);
        assertTrue(roundTripDisabled.isConcurrentBuild()); // see comment there
        prop = roundTripDisabled.getProperty(DisableConcurrentBuildsJobProperty.class);
        assertNotNull(prop);
        assertTrue(prop.isAbortPrevious());
    }

    @Issue("JENKINS-43353")
    @Test public void abortPrevious() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("semaphore 'run'", true));

        // Control case: concurrent builds allowed.
        assertTrue(p.isConcurrentBuild());
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("run/1", b1);
        WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("run/2", b2);
        SemaphoreStep.success("run/1", null);
        SemaphoreStep.success("run/2", null);
        r.waitForCompletion(b1);
        r.waitForCompletion(b2);

        // Control case: simple disable concurrent.
        p.setConcurrentBuild(false);
        assertFalse(p.isConcurrentBuild());
        WorkflowRun b3 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("run/3", b3);
        SemaphoreStep.success("run/4", null);
        QueueTaskFuture<WorkflowRun> b4f = p.scheduleBuild2(0);
        Thread.sleep(1000); // TODO is there a cleaner way for the queue to finish processing?
        assertFalse(b4f.getStartCondition().isDone());
        SemaphoreStep.success("run/3", null);
        r.waitForCompletion(b4f.waitForStart());
        r.waitForCompletion(b3);

        // Test case: abort previous.
        p.getProperty(DisableConcurrentBuildsJobProperty.class).setAbortPrevious(true);
        assertTrue(p.isConcurrentBuild()); // see comment there
        WorkflowRun b5 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("run/5", b5);
        WorkflowRun b6 = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("run/6", b6);
        r.assertBuildStatus(Result.NOT_BUILT, r.waitForCompletion(b5));
        InterruptedBuildAction iba = b5.getAction(InterruptedBuildAction.class);
        assertNotNull(iba);
        assertEquals(1, iba.getCauses().size());
        assertEquals(DisableConcurrentBuildsJobProperty.CancelledCause.class, iba.getCauses().get(0).getClass());
        assertEquals(b6, ((DisableConcurrentBuildsJobProperty.CancelledCause) iba.getCauses().get(0)).getNewerBuild());
        SemaphoreStep.success("run/6", null);
        r.assertBuildStatusSuccess(r.waitForCompletion(b6));
    }

}
