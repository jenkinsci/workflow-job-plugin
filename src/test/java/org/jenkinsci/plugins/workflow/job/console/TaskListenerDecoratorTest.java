/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

import com.google.common.collect.ImmutableSet;
import hudson.console.ConsoleLogFilter;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import jenkins.security.MasterToSlaveCallable;
import jenkins.util.JenkinsJVM;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

@Issue("JENKINS-45693")
@For(TaskListenerDecorator.class)
public class TaskListenerDecoratorTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void smokes() throws Exception {
        r.createSlave("remote", null, null);
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("filter {decorate {node('remote') {remotePrint()}}}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("[job/p/1/] Started", b);
        r.assertLogContains("[decorated] [filtered] [job/p/1/] Running on remote in ", b);
        r.assertLogContains("[decorated via remote] [filtered via remote] [job/p/1/ via remote] printed a message on controller=false", b);
        String log = JenkinsRule.getLog(b);
        assertThat(log, log.indexOf("master=false"), lessThan(log.indexOf("// node")));
    }

    private static final class DecoratorImpl extends TaskListenerDecorator {
        private static final long serialVersionUID = 1L;
        
        private final String message;
        DecoratorImpl(String message) {
            this.message = message;
        }
        private Object writeReplace() {
            Channel ch = Channel.current();
            return ch != null ? new DecoratorImpl(message + " via " + ch.getName()) : this;
        }
        @Override public OutputStream decorate(OutputStream logger) {
            return new LineTransformationOutputStream() {
                @Override protected void eol(byte[] b, int len) throws IOException {
                    logger.write(("[" + message + "] ").getBytes());
                    logger.write(b, 0, len);
                }
                @Override public void close() throws IOException {
                    super.close();
                    logger.close();
                }
                @Override public void flush() throws IOException {
                    logger.flush();
                }
            };
        }
        @Override public String toString() {
            return "DecoratorImpl[" + message + "]";
        }
    }

    @TestExtension public static final class DecoratorFactory implements TaskListenerDecorator.Factory {
        @Override public TaskListenerDecorator of(FlowExecutionOwner owner) {
            try {
                return new DecoratorImpl(owner.getUrl());
            } catch (IOException x) {
                throw new AssertionError(x);
            }
        }
    }

    public static final class DecoratorStep extends Step {
        @DataBoundConstructor public DecoratorStep() {}
        @Override public StepExecution start(StepContext context) {
            return new Execution(context);
        }
        private static final class Execution extends StepExecution {
            private static final long serialVersionUID = 1L;
            
            Execution(StepContext context) {
                super(context);
            }
            @Override public boolean start() {
                getContext().newBodyInvoker().withContext(new DecoratorImpl("decorated")).withCallback(BodyExecutionCallback.wrap(getContext())).start();
                return false;
            }
        }
        @TestExtension public static final class DescriptorImpl extends StepDescriptor {
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.emptySet();
            }
            @Override public String getFunctionName() {
                return "decorate";
            }
            @Override public boolean takesImplicitBlockArgument() {
                return true;
            }
        }
    }

    public static final class FilterStep extends Step {
        @DataBoundConstructor public FilterStep() {}
        @Override public StepExecution start(StepContext context) {
            return new Execution(context);
        }
        private static final class Execution extends StepExecution {
            private static final long serialVersionUID = 1L;
            
            Execution(StepContext context) {
                super(context);
            }
            @Override public boolean start() {
                getContext().newBodyInvoker().withContext(new Filter("filtered")).withCallback(BodyExecutionCallback.wrap(getContext())).start();
                return false;
            }
        }
        private static final class Filter extends ConsoleLogFilter implements Serializable {
            private static final long serialVersionUID = 1L;
            
            private final String message;
            Filter(String message) {
                this.message = message;
            }
            private Object writeReplace() {
                Channel ch = Channel.current();
                return ch != null ? new Filter(message + " via " + ch.getName()) : this;
            }
            @SuppressWarnings("rawtypes")
            @Override public OutputStream decorateLogger(AbstractBuild _ignore, OutputStream logger) {
                return new DecoratorImpl(message).decorate(logger);
            }
            @Override public String toString() {
                return "Filter[" + message + "]";
            }
        }
        @TestExtension public static final class DescriptorImpl extends StepDescriptor {
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.emptySet();
            }
            @Override public String getFunctionName() {
                return "filter";
            }
            @Override public boolean takesImplicitBlockArgument() {
                return true;
            }
        }
    }

    public static final class RemotePrintStep extends Step {
        @DataBoundConstructor public RemotePrintStep() {}
        @Override public StepExecution start(StepContext context) {
            return new Execution(context);
        }
        private static final class Execution extends SynchronousNonBlockingStepExecution<Void> {
            private static final long serialVersionUID = 1L;
            
            Execution(StepContext context) {
                super(context);
            }
            @Override protected Void run() throws Exception {
                return getContext().get(Node.class).getChannel().call(new PrintCallable(getContext().get(TaskListener.class)));
            }
        }
        private static final class PrintCallable extends MasterToSlaveCallable<Void, RuntimeException> {            
            private static final long serialVersionUID = 1L;
            
            private final TaskListener listener;
            PrintCallable(TaskListener listener) {
                this.listener = listener;
            }
            @Override public Void call() throws RuntimeException {
                listener.getLogger().println("printed a message on controller=" + JenkinsJVM.isJenkinsJVM());
                listener.getLogger().flush();
                return null;
            }
        }
        @TestExtension public static final class DescriptorImpl extends StepDescriptor {
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return ImmutableSet.of(Node.class, TaskListener.class);
            }
            @Override public String getFunctionName() {
                return "remotePrint";
            }
        }
    }

}
