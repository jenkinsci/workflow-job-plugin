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
package org.jenkinsci.plugins.workflow.job.properties;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.time.Duration;
import java.time.Instant;
import jenkins.model.CauseOfInterruption;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * {@link OptionalJobProperty} for setting whether a job should allow concurrent builds.
 */
@ExportedBean
public class DisableConcurrentBuildsJobProperty extends OptionalJobProperty<WorkflowJob> {

    private boolean abortPrevious;

    @DataBoundConstructor
    public DisableConcurrentBuildsJobProperty() {}

    public boolean isAbortPrevious() {
        return abortPrevious;
    }

    @DataBoundSetter
    public void setAbortPrevious(boolean abortPrevious) {
        this.abortPrevious = abortPrevious;
    }

    @Extension
    @Symbol("disableConcurrentBuilds")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.do_not_allow_concurrent_builds();
        }
    }

    /**
     * Records that a build was canceled because of {@link #isAbortPrevious}.
     */
    public static final class CancelledCause extends CauseOfInterruption {

        private static final long serialVersionUID = 1;

        private final String newerBuild;
        private final String displayName;

        public CancelledCause(Run<?, ?> newerBuild) {
            this.newerBuild = newerBuild.getExternalizableId();
            this.displayName = newerBuild.getDisplayName();
        }

        @Exported
        @Nullable
        public Run<?, ?> getNewerBuild() {
            return newerBuild != null ? Run.fromExternalizableId(newerBuild) : null;
        }

        @Override
        public String getShortDescription() {
            return "Superseded by " + displayName;
        }

        @Override
        public void print(TaskListener listener) {
            Run<?, ?> b = getNewerBuild();
            if (b != null) {
                listener.getLogger().println("Superseded by " + ModelHyperlinkNote.encodeTo(b));
            } else {
                super.print(listener);
            }
        }
    }

    @Extension
    public static final class Listener extends RunListener<WorkflowRun> {
        @Override
        public void onStarted(WorkflowRun r, TaskListener listener) {
            var dcb = r.getParent().getProperty(DisableConcurrentBuildsJobProperty.class);
            if (dcb != null && dcb.isAbortPrevious()) {
                var prev = r.getPreviousBuild();
                if (prev != null && prev.isBuilding()) {
                    var e = prev.getExecutor();
                    if (e != null) {
                        e.interrupt(Result.NOT_BUILT, new DisableConcurrentBuildsJobProperty.CancelledCause(r));
                        var waitLimit = WorkflowRun.termGracePeriod()
                                .plus(WorkflowRun.killGracePeriod())
                                .plus(Duration.ofSeconds(10));
                        var deadline = Instant.now().plus(waitLimit);
                        var notifyAt = Instant.now().plusSeconds(10);
                        var notified = false;
                        while (prev.isLogUpdated()) {
                            var now = Instant.now();
                            if (now.isAfter(deadline)) {
                                listener.getLogger()
                                        .printf(
                                                "Previous build %s did not complete within %ds; proceeding anyway%n",
                                                prev.getDisplayName(), waitLimit.toSeconds());
                                break;
                            }
                            if (!notified && now.isAfter(notifyAt)) {
                                notified = true;
                                listener.getLogger()
                                        .printf("Waiting for previous build %s to complete…%n", prev.getDisplayName());
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException x) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                // Not bothering to look for other older builds in progress, since once we turn this on,
                // going forward there should be at most one.
            }
        }
    }
}
