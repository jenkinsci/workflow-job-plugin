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

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
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
    }
}
