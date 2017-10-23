/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.BuildTrigger;
import hudson.util.FormValidation;
import java.util.Collections;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class BuildTriggerTest {
    
    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

    @Issue("JENKINS-28113")
    @Test public void smokes() throws Exception {
        BuildTrigger.DescriptorImpl d = r.jenkins.getDescriptorByType(BuildTrigger.DescriptorImpl.class);
        FreeStyleProject us = r.createProject(FreeStyleProject.class, "us");
        WorkflowJob ds = r.createProject(WorkflowJob.class, "ds");
        ds.setDefinition(new CpsFlowDefinition("", true));
        assertEquals(Collections.singletonList("ds"), d.doAutoCompleteChildProjects("d", us, r.jenkins).getValues());
        FormValidation validation = d.doCheck(us, "ds");
        assertEquals(validation.renderHtml(), FormValidation.Kind.OK, validation.kind);
        us.getPublishersList().add(new BuildTrigger("ds", Result.SUCCESS));
        r.jenkins.setQuietPeriod(0);
        FreeStyleBuild us1 = r.buildAndAssertSuccess(us);
        r.waitUntilNoActivity();
        WorkflowRun ds1 = ds.getLastBuild();
        assertNotNull("triggered", ds1);
        Cause.UpstreamCause cause = ds1.getCause(Cause.UpstreamCause.class);
        assertNotNull(cause);
        assertEquals(us1, cause.getUpstreamRun());
    }

}
