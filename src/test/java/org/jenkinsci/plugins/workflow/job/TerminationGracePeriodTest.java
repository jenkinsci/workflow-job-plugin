/*
 * The MIT License
 *
 * Copyright 2026 CloudBees, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Result;
import hudson.model.TaskListener;
import java.io.Serial;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.job.properties.DurabilityHintJobProperty;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Verifies the behavior of {@link WorkflowRun#doTerm} and {@link WorkflowRun#doKill}.
 */
@Issue("https://github.com/jenkinsci/workflow-job-plugin/issues/644")
final class TerminationGracePeriodTest {

    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void escalation() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.addProperty(new DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY));
            p.setDefinition(new CpsFlowDefinition("""
                def seq = 0
                while (true) {
                  try {
                    zombie id: ++seq
                  } catch (x) {
                    echo "ignoring $x"
                  }
                }""", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("[1] undead", b);
            b.doStop();
            r.waitForMessage("[1] bwahaha FlowInterruptedException #1", b);
            r.waitForMessage("Forcibly terminating running steps", b);
            r.waitForMessage("[2] undead", b);
            r.waitForMessage("Forcibly killing entire build", b);
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.ABORTED, b);
            assertTrue(b.completed);
        });
        sessions.then(r -> {
            WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
            WorkflowRun b = p.getBuildByNumber(1);
            assertFalse(b.isBuilding());
            assertFalse(b.executionLoaded);
        });
    }

    @Test
    void abortPreviousWaits() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.addProperty(new DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY));
            var dcb = new DisableConcurrentBuildsJobProperty();
            dcb.setAbortPrevious(true);
            p.addProperty(dcb);
            p.setDefinition(new CpsFlowDefinition("""
                    if (env.BUILD_NUMBER == '1') {
                      echo 'build 1 waiting'
                      try {
                        sleep 999
                      } catch (x) {
                        echo "caught ${x.class.simpleName}"
                        sleep 10
                      }
                    }
                    echo "build ${env.BUILD_NUMBER} done"
                    """, true));
            WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("build 1 waiting", b1);
            WorkflowRun b2 = p.scheduleBuild2(0).waitForStart();
            r.waitForCompletion(b2);
            r.assertBuildStatus(Result.ABORTED, b1);
            r.assertBuildStatusSuccess(b2);
            r.assertLogContains("caught FlowInterruptedException", b1);
            r.assertLogContains("build 2 done", b2);
        });
    }

    @Test
    void repeatedInterrupt() throws Throwable {
        sessions.then(r -> {
            var p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                while (true) {
                    try {
                      sleep 999
                    } catch (x) {
                      echo "caught $x"
                    }
                }
                """, true));
            var b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("Sleeping for ", b);
            b.doStop();
            r.waitForMessage("caught ", b);
            b.doStop();
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.ABORTED, b);
            assertThat(
                    "Duplicated timers are probably harmless but might lead to race conditions",
                    Pattern.compile("Forcibly terminating running steps")
                            .matcher(JenkinsRule.getLog(b))
                            .results()
                            .count(),
                    is(1L));
        });
    }

    @Test
    void interruptAcrossRestart() throws Throwable {
        sessions.then(r -> {
            var p = r.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                while (true) {
                    try {
                      sleep 999
                    } catch (x) {
                      echo "caught $x"
                    }
                }
                """, true));
            var b = p.scheduleBuild2(0).waitForStart();
            r.waitForMessage("Sleeping for ", b);
            b.doStop();
            r.waitForMessage("caught ", b);
        });
        sessions.then(r -> {
            var b = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            r.waitForCompletion(b);
            r.assertBuildStatus(Result.ABORTED, b);
        });
    }

    @SuppressWarnings("unused")
    public static class Zombie extends Step {
        @DataBoundSetter
        public int id;

        @DataBoundConstructor
        public Zombie() {}

        @Override
        public StepExecution start(StepContext context) {
            return new Execution(context, id);
        }

        private static class Execution extends StepExecution {
            @Serial
            private static final long serialVersionUID = 1L;

            int id;
            int count;

            Execution(StepContext context, int id) {
                super(context);
                this.id = id;
            }

            @Override
            public boolean start() throws Exception {
                getContext().get(TaskListener.class).getLogger().printf("[%d] undead%n", id);
                return false;
            }

            @Override
            public void stop(Throwable cause) throws Exception {
                getContext()
                        .get(TaskListener.class)
                        .getLogger()
                        .printf("[%d] bwahaha %s #%d%n", id, cause.getClass().getSimpleName(), ++count);
            }
        }

        @TestExtension
        public static class DescriptorImpl extends StepDescriptor {

            @Override
            public String getFunctionName() {
                return "zombie";
            }

            @NonNull
            @Override
            public String getDisplayName() {
                return "zombie";
            }

            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Collections.singleton(TaskListener.class);
            }
        }
    }
}
