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

import hudson.cli.CLICommandInvoker;
import java.io.File;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class CLITest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void deleteBuilds() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        for (int i = 0; i < 5; i++) {
            r.buildAndAssertSuccess(p);
        }
        assertThat(new CLICommandInvoker(r, "delete-builds").invokeWithArgs("p", "2-4"), CLICommandInvoker.Matcher.succeeded());
        assertEquals("[5, 1]", p.getBuildsAsMap().keySet().toString());
    }

    @Test public void listChanges() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("node {def s = new org.jvnet.hudson.test.FakeChangeLogSCM(); s.addChange().withAuthor('alice').withMsg('hello'); checkout s}", false));
        r.buildAndAssertSuccess(p);
        CLICommandInvoker.Result res = new CLICommandInvoker(r, "list-changes").invokeWithArgs("p", "1");
        assertThat(res, CLICommandInvoker.Matcher.succeeded());
        assertThat(res.stdout(), containsString("alice\thello"));
    }

    @Issue("JENKINS-41527")
    @Test public void console() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'this is what I said'", true));
        r.buildAndAssertSuccess(p);
        CLICommandInvoker.Result res = new CLICommandInvoker(r, "console").invokeWithArgs("p");
        assertThat(res, CLICommandInvoker.Matcher.succeeded());
        assertThat(res.stdout(), containsString("this is what I said"));
    }

    @Issue("JENKINS-30785")
    @Test public void reload() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'first version'", true));
        r.assertLogContains("first version", r.buildAndAssertSuccess(p));
        File configXml = new File(p.getRootDir(), "config.xml");
        FileUtils.write(configXml, FileUtils.readFileToString(configXml).replace("first version", "second version"));
        assertThat(new CLICommandInvoker(r, "reload-job").invokeWithArgs("p"), CLICommandInvoker.Matcher.succeededSilently());
        r.assertLogContains("second version", r.buildAndAssertSuccess(p));
        CLICommandInvoker.Result res = new CLICommandInvoker(r, "reload-job").invokeWithArgs("q");
        assertThat(res, CLICommandInvoker.Matcher.failedWith(3));
        assertThat(res.stderr(), containsString("No such item ‘q’ exists. Perhaps you meant ‘p’?"));
    }

    @Test public void setBuildDescriptionAndDisplayName() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        assertThat(new CLICommandInvoker(r, "set-build-description").invokeWithArgs("p", "1", "the desc"), CLICommandInvoker.Matcher.succeededSilently());
        assertEquals("the desc", b.getDescription());
        assertThat(new CLICommandInvoker(r, "set-build-display-name").invokeWithArgs("p", "1", "the name"), CLICommandInvoker.Matcher.succeededSilently());
        assertEquals("the name", b.getDisplayName());
    }

}
