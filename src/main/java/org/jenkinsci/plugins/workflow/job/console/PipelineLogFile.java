/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
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

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.LessAbstractTaskListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Means of replacing the {@link FlowExecutionOwner#getListener} and {@link FlowExecutionOwner#getLog} for a {@link WorkflowRun}.
 */
@Restricted(Beta.class) // likely to be replaced by a core API
public abstract class PipelineLogFile implements ExtensionPoint {

    /**
     * Provides an alternate way of emitting output from a build.
     * May implement {@link AutoCloseable} to clean up at the end of a build.
     * @param b a build about to start
     * @return a (remotable) build listener (typically {@link LessAbstractTaskListener}), or null to fall back to the next implementation or the default using {@link Run#getLogFile} and {@link StreamBuildListener}
     */
    protected abstract @CheckForNull BuildListener listenerFor(@Nonnull WorkflowRun b) throws IOException, InterruptedException;

    /**
     * Provides an alternate way of retrieving output from a build.
     * @param b a build which may or may not be completed
     * @param start the start position to begin reading from (normally 0); if past the end, the stream should just return EOF immediately; if you can do no better, try {@link IOUtils#skipFully(InputStream, long)}
     * @return a log input stream, or null to fall back to the next implementation or the default using {@link Run#getLogInputStream}
     * @throws EOFException if the start position is larger than the log size (or you may simply return EOF immediately when read)
     */
    protected abstract @CheckForNull InputStream logFor(@Nonnull WorkflowRun b, long start) throws IOException;

    @Restricted(NoExternalUse.class) // API for call from WorkflowRun
    public static @Nonnull BuildListener listener(@Nonnull WorkflowRun b) throws IOException, InterruptedException {
        for (PipelineLogFile impl : ExtensionList.lookup(PipelineLogFile.class)) {
            BuildListener l = impl.listenerFor(b);
            if (l != null) {
                return l;
            }
        }
        OutputStream logger = new FileOutputStream(b._getLogFile(), true);
        // TODO JENKINS-30777 decorate with ConsoleLogFilter.all()
        return new StreamBuildListener(logger, b.getCharset());
    }

    @Restricted(NoExternalUse.class) // API for call from WorkflowRun
    public static @Nonnull InputStream log(@Nonnull WorkflowRun b, long start) throws IOException {
        for (PipelineLogFile impl : ExtensionList.lookup(PipelineLogFile.class)) {
            InputStream is = impl.logFor(b, start);
            if (is != null) {
                return is;
            }
        }
        return b._getLogInputStream(start);
    }

}
