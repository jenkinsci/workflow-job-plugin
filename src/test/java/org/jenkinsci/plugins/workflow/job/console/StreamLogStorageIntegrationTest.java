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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import hudson.Functions;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.log.StreamLogStorage;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

@Issue("JENKINS-38381")
public class StreamLogStorageIntegrationTest {

    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void consoleNotes() throws Exception {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("hyperlink()", true));
        User alice = User.getById("alice", true);
        Cause cause;
        try (ACLContext context = ACL.as(alice)) {
            cause = new Cause.UserIdCause();
        }
        WorkflowRun b = r.assertBuildStatusSuccess(p.scheduleBuild2(0, new CauseAction(cause)));
        HtmlPage page = r.createWebClient().goTo(b.getUrl() + "console");
        assertLogContains(page, hudson.model.Messages.Cause_UserIdCause_ShortDescription(alice.getDisplayName()), alice.getUrl());
        assertLogContains(page, "Running inside " + b.getDisplayName(), b.getUrl());
        assertThat(page.getWebResponse().getContentAsString().replace("\r\n", "\n"),
            containsString("<span class=\"pipeline-new-node\" nodeId=\"3\" enclosingId=\"2\">[Pipeline] hyperlink\n</span><span class=\"pipeline-node-3\">Running inside <a href="));
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
        assertThat(html, not(containsString(StreamLogStorage.NODE_ID_SEP)));
    }
    public static class HyperlinkingStep extends Step {
        @DataBoundConstructor public HyperlinkingStep() {}
        @Override public StepExecution start(StepContext context) throws Exception {
            return new Execution(context);
        }
        static class Execution extends SynchronousStepExecution<Void> {
            Execution(StepContext context) {
                super(context);
            }
            @Override protected Void run() throws Exception {
                getContext().get(TaskListener.class).getLogger().println("Running inside " + ModelHyperlinkNote.encodeTo(getContext().get(Run.class)));
                return null;
            }
        }
        @TestExtension("consoleNotes") public static class DescriptorImpl extends StepDescriptor {
            @Override public String getFunctionName() {
                return "hyperlink";
            }
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return ImmutableSet.of(TaskListener.class, Run.class);
            }
        }
    }

    // TODO figure out how to test doProgressText/Html on a running build

    @Test public void performance() throws Exception {
        assumeFalse(Functions.isWindows()); // needs newline fixes; not bothering for now
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("@NonCPS def giant() {(0..999999).join('\\n')}; echo giant()", true));
        long start = System.nanoTime();
        WorkflowRun b = r.buildAndAssertSuccess(p);
        System.out.printf("Took %dms to run the build%n", (System.nanoTime() - start) / 1000 / 1000);
        // Whole-build HTML output:
        StringWriter sw = new StringWriter();
        start = System.nanoTime();
        b.getLogText().writeHtmlTo(0, sw);
        System.out.printf("Took %dms to write HTML of whole build%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(sw.toString(), containsString("\n456788\n456789\n456790\n"));
        assertThat(sw.toString(), containsString("\n999999\n</span>"));
        // Length check (cf. Run/console.jelly, WorkflowRun/sidepanel.jelly):
        start = System.nanoTime();
        long length = b.getLogText().length();
        System.out.printf("Took %dms to compute length of whole build%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(length, greaterThan(200000L));
        // Truncated (cf. Run/console.jelly):
        long offset = length - 150 * 1024;
        sw = new StringWriter();
        start = System.nanoTime();
        b.getLogText().writeHtmlTo(offset, sw);
        System.out.printf("Took %dms to write truncated HTML of whole build%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(sw.toString(), not(containsString("\n456789\n")));
        assertThat(sw.toString(), containsString("\n999923\n"));
        assertThat(sw.toString(), containsString("\n999999\n</span>"));
        // Raw:
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        start = System.nanoTime();
        IOUtils.copy(b.getLogInputStream(), baos);
        System.out.printf("Took %dms to write plain text of whole build%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(baos.toString(), containsString("\n456789\n"));
        // Per node:
        FlowNode echo = b.getExecution().getCurrentHeads().get(0).getParents().get(0);
        assertEquals("echo", echo.getDisplayFunctionName());
        String prefix = echo.getId() + StreamLogStorage.NODE_ID_SEP;
        String rawLog = FileUtils.readFileToString(new File(b.getRootDir(), "log"));
        assertThat(rawLog, containsString(prefix + "0\n"));
        assertThat(rawLog, containsString(prefix + "999999\n"));
        LogAction la = echo.getAction(LogAction.class);
        assertNotNull(la);
        baos = new ByteArrayOutputStream();
        la.getLogText().writeRawLogTo(0, baos);
        assertThat(baos.toString(), not(containsString("Pipeline")));
        // Whole-build:
        sw = new StringWriter();
        start = System.nanoTime();
        la.getLogText().writeHtmlTo(0, sw);
        System.out.printf("Took %dms to write HTML of one node%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(sw.toString(), containsString("\n456789\n"));
        // Length check (cf. AnnotatedLogAction/index.jelly):
        start = System.nanoTime();
        length = la.getLogText().length();
        System.out.printf("Took %dms to compute length of one node%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(length, greaterThan(200000L));
        // Truncated (cf. AnnotatedLogAction/index.jelly):
        sw = new StringWriter();
        offset = length - 150 * 1024;
        start = System.nanoTime();
        la.getLogText().writeHtmlTo(offset, sw);
        System.out.printf("Took %dms to write truncated HTML of one node%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(sw.toString(), not(containsString("\n456789\n")));
        assertThat(sw.toString(), containsString("\n999923\n"));
        // Raw (currently not exposed in UI but could be):
        baos = new ByteArrayOutputStream();
        start = System.nanoTime();
        la.getLogText().writeRawLogTo(0, baos);
        System.out.printf("Took %dms to write plain text of one node%n", (System.nanoTime() - start) / 1000 / 1000);
        assertThat(baos.toString(), containsString("\n456789\n"));
    }

}
