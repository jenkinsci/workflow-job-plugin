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

import hudson.Extension;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import jenkins.model.CauseOfInterruption;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
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
    public DisableConcurrentBuildsJobProperty() {
    }

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
        @Override public String getDisplayName() {
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

        @Override public String getShortDescription() {
            return "Superseded by " + displayName;
        }

        @Override public void print(TaskListener listener) {
            Run<?, ?> b = getNewerBuild();
            if (b != null) {
                listener.getLogger().println("Superseded by " + ModelHyperlinkNote.encodeTo(b));
            } else {
                super.print(listener);
            }
        }

    }

}
