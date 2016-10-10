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

import org.jenkinsci.plugins.workflow.job.*;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.collect.Lists;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import java.util.List;
import jenkins.security.NotReallyRoleSensitiveCallable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.jenkinsci.plugins.workflow.support.actions.AnnotatedLogAction;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

@Issue("JENKINS-38381")
public class PipelineLargeTextTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

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
        assertThat(page.getWebResponse().getContentAsString().replace("\r\n", "\n"),
            containsString("<span class=\"pipeline-new-node\" nodeId=\"3\" parentIds=\"2\">[Pipeline] hyperlink\n</span><span class=\"pipeline-node-3\">Running inside <a href="));
        DepthFirstScanner scanner = new DepthFirstScanner();
        scanner.setup(b.getExecution().getCurrentHeads());
        List<FlowNode> nodes = Lists.newArrayList(scanner.filter(FlowScanningUtils.hasActionPredicate(LogAction.class)));
        assertEquals(1, nodes.size());
        page = r.createWebClient().goTo(nodes.get(0).getUrl() + nodes.get(0).getAction(LogAction.class).getUrlName());
        assertLogContains(page, "Running inside " + b.getDisplayName(), b.getUrl());
        r.assertLogContains("\nRunning inside " + b.getDisplayName(), b);
    }
    private void assertLogContains(HtmlPage page, String plainText, String url) throws Exception {
        String html = page.getWebResponse().getContentAsString();
        assertThat(page.getUrl() + " looks OK as text:\n" + html, page.getDocumentElement().getTextContent(), containsString(plainText));
        String absUrl = r.contextPath + "/" + url;
        assertNotNull("found " + absUrl + " in:\n" + html, page.getAnchorByHref(absUrl));
        assertThat(html, not(containsString(AnnotatedLogAction.NODE_ID_SEP)));
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

    // TODO figure out how to test doProgressText/Html on a running build

}
