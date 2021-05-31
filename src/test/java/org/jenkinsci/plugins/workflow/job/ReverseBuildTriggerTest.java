/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
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

import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Result;
import jenkins.triggers.ReverseBuildTrigger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.TestExtension;

/** Integration test for special behavior of {@link ReverseBuildTrigger} with {@link WorkflowJob}. */
public class ReverseBuildTriggerTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @Issue("JENKINS-33971")
    @Test public void upstreamMapRebuilding() throws Throwable {
        sessions.then(r -> {
            r.jenkins.setQuietPeriod(0);
            WorkflowJob us = r.jenkins.createProject(WorkflowJob.class, "us");
            us.setDefinition(new CpsFlowDefinition("", true));
            us.addProperty(new SlowToLoad()); // force it to load after ds when we restart
            WorkflowJob ds = r.jenkins.createProject(WorkflowJob.class, "ds");
            ds.setDefinition(new CpsFlowDefinition("", true));
            ReverseBuildTrigger trigger = new ReverseBuildTrigger("us");
            trigger.setThreshold(Result.SUCCESS);
            ds.addTrigger(trigger);
            r.assertBuildStatusSuccess(us.scheduleBuild2(0));
            r.waitUntilNoActivity();
            WorkflowRun ds1 = ds.getLastCompletedBuild();
            assertNotNull(ds1);
            assertEquals(1, ds1.getNumber());
        });
        sessions.then(r -> {
            WorkflowJob us = r.jenkins.getItemByFullName("us", WorkflowJob.class);
            assertNotNull(us);
            WorkflowJob ds = r.jenkins.getItemByFullName("ds", WorkflowJob.class);
            assertNotNull(ds);
            r.assertBuildStatusSuccess(us.scheduleBuild2(0));
            r.waitUntilNoActivity();
            WorkflowRun ds2 = ds.getLastCompletedBuild();
            assertNotNull(ds2);
            assertEquals(2, ds2.getNumber());
        });
    }
    public static class SlowToLoad extends JobProperty<WorkflowJob> {
        @Override protected void setOwner(WorkflowJob owner) {
            super.setOwner(owner);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException x) {
                throw new AssertionError(x);
            }
        }
        @TestExtension("upstreamMapRebuilding") public static class DescriptorImpl extends JobPropertyDescriptor {}
    }

}
