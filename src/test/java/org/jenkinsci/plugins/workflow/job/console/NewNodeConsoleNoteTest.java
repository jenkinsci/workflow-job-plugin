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
 */

package org.jenkinsci.plugins.workflow.job.console;

import static org.hamcrest.Matchers.containsString;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assert.assertThat;
import org.junit.Test;
import org.junit.ClassRule;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

public class NewNodeConsoleNoteTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Test public void labels() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("parallel first: {}, second: {stage('details') {}}; stage 'not \"blocky\"'", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        String html = r.createWebClient().goTo(b.getUrl() + "console").getWebResponse().getContentAsString();
        assertThat(html, containsString("\" label=\"Branch: first\">[Pipeline] {"));
        assertThat(html, containsString("\" label=\"Branch: second\">[Pipeline] {"));
        assertThat(html, containsString("\" label=\"details\">[Pipeline] {"));
        assertThat(html, containsString("\" label=\"not &quot;blocky&quot;\">[Pipeline] stage"));
    }

}
