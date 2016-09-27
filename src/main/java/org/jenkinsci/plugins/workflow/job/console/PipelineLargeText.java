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

import com.google.common.base.Charsets;
import hudson.console.AnnotatedLargeText;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.AnnotatedLogAction;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * Provides customized log behavior.
 */
public class PipelineLargeText extends AnnotatedLargeText<WorkflowRun> {

    public PipelineLargeText(WorkflowRun build) {
        this(build, new ByteBuffer());
    }

    private PipelineLargeText(WorkflowRun build, ByteBuffer buf) {
        super(buf, Charsets.UTF_8, !build.isLogUpdated(), build);
        // Overriding getLogTo works to strip annotations from plain-text console output.
        // It does *not* work to override writeHtmlTo:
        // AbstractMarkupText.wrapBy and similar routinely put the close tag on the next line,
        // since the marked-up text includes the newline.
        // Thus we would be trying to strip, e.g. "<span class='red'>Some headline\n</span>¦123Regular line\n".
        FlowExecutionOwner owner = build.asFlowExecutionOwner();
        if (owner != null) {
            try (InputStream log = owner.getLog()) {
                AnnotatedLogAction.strip(log, buf);
            } catch (IOException ex) {
                Logger.getLogger(PipelineLargeText.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

}
