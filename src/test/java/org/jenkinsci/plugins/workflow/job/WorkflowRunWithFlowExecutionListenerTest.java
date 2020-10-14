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

import hudson.ExtensionList;
import hudson.model.Queue;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.Assert.assertNotNull;

public class WorkflowRunWithFlowExecutionListenerTest {

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Test public void testOnCompleteIsExecutedBeforeListenerIsClosed() throws Exception {
        story.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("echo 'Running for listener'", true));
            WorkflowRun b = r.assertBuildStatus(Result.SUCCESS, p.scheduleBuild2(0));

            WorkflowRunWithFlowExecutionListenerTest.Listener listener = ExtensionList.lookup(FlowExecutionListener.class).get(WorkflowRunWithFlowExecutionListenerTest.Listener.class);
            assertNotNull(listener);

            r.waitForMessage("Finished: SUCCESS", b); // This message is printed directly after the completion listeners are called.

            r.assertLogContains("Running", b);
            r.assertLogContains("blah blah blah", b);
            r.assertLogContains("Build Number :1", b);
        });
    }


    @TestExtension("testOnCompleteIsExecutedBeforeListenerIsClosed")
    public static class Listener extends FlowExecutionListener {
        @Override
        public void onCompleted(@Nonnull FlowExecution execution) {
            super.onCompleted(execution);
            Queue.Executable executable = null;
            try {
                PrintStream logger = execution.getOwner().getListener().getLogger();
                logger.println("blah blah blah");
                executable = execution.getOwner().getExecutable();
                if( executable instanceof WorkflowRun){
                    WorkflowRun run = (WorkflowRun) executable;
                    WorkflowJob workflowJob = run.getParent();
                    int number = workflowJob.getLastBuild().number;
                    logger.println("Build Number :" + number);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
