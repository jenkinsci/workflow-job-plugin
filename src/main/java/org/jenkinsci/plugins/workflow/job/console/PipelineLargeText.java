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
import com.jcraft.jzlib.GZIPInputStream;
import com.jcraft.jzlib.GZIPOutputStream;
import com.trilead.ssh2.crypto.Base64;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleAnnotationOutputStream;
import hudson.console.ConsoleAnnotator;
import hudson.remoting.ClassFilter;
import hudson.remoting.ObjectInputStreamEx;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Writer;
import static java.lang.Math.abs;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import jenkins.model.Jenkins;
import jenkins.security.CryptoConfidentialKey;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.AnnotatedLogAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * Provides customized log behavior.
 */
@Restricted(NoExternalUse.class)
public class PipelineLargeText extends AnnotatedLargeText<WorkflowRun> {

    private final WorkflowRun context;

    public PipelineLargeText(WorkflowRun build) {
        this(build, new HackedByteBuffer());
    }

    /** Records length of the raw log file, so that {@link #doProgressText} does not think we have blown past the end. */
    static class HackedByteBuffer extends ByteBuffer {
        long length;
        @Override public long length() {
            return Math.max(length, super.length());
        }
    }

    private PipelineLargeText(WorkflowRun build, HackedByteBuffer buf) {
        super(buf, Charsets.UTF_8, !build.isLogUpdated(), build);
        // TODO for simplicitly, currently just making a copy of the log into a memory buffer.
        // Overriding writeLogTo would work to strip annotations from plain-text console output more efficiently,
        // though it would be cumbersome to also override all the other LargeText methods, esp. doProgressText.
        // (We could also override ByteBuffer to stream output after stripping, but the length would be wrong, if anyone cares.)
        FlowExecutionOwner owner = build.asFlowExecutionOwner();
        assert owner != null;
        try (InputStream log = owner.getLog(0); CountingInputStream cis = new CountingInputStream(log)) {
            AnnotatedLogAction.strip(cis, buf);
            buf.length = cis.getByteCount();
        } catch (IOException ex) {
            Logger.getLogger(PipelineLargeText.class.getName()).log(Level.SEVERE, null, ex);
        }
        this.context = build;
    }

    // It does *not* work to override writeHtmlTo to strip node annotations after ConsoleNote’s are processed:
    // AbstractMarkupText.wrapBy and similar routinely put the close tag on the next line,
    // since the marked-up text includes the newline.
    // Thus we would be trying to parse, e.g., "123¦<span class='red'>Some headline\n</span>123¦Regular line\n"
    // and it is not necessarily obvious where the boundaries of the ID are.
    // Anyway AnnotatedLogAction.annotateHtml is an easier way of handling node annotations.
    @Override public long writeHtmlTo(long start, Writer w) throws IOException {
        ConsoleAnnotationOutputStream<WorkflowRun> caw = AnnotatedLogAction.annotateHtml(
                w, createAnnotator(Stapler.getCurrentRequest()), context);
        FlowExecutionOwner owner = context.asFlowExecutionOwner();
        assert owner != null;
        long r;
        try (InputStream log = owner.getLog(start)) {
            CountingInputStream cis = new CountingInputStream(log);
            IOUtils.copy(cis, caw);
            r = start + cis.getByteCount();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Cipher sym = PASSING_ANNOTATOR.encrypt();
        try (ObjectOutputStream oos = new ObjectOutputStream(new GZIPOutputStream(new CipherOutputStream(baos, sym)))) {
            oos.writeLong(System.currentTimeMillis());
            oos.writeObject(caw.getConsoleAnnotator());
        }
        StaplerResponse rsp = Stapler.getCurrentResponse();
        if (rsp != null) {
            rsp.setHeader("X-ConsoleAnnotator", new String(Base64.encode(baos.toByteArray())));
        }
        return r;
    }

    private ConsoleAnnotator<WorkflowRun> createAnnotator(StaplerRequest req) throws IOException {
        try {
            String base64 = req != null ? req.getHeader("X-ConsoleAnnotator") : null;
            if (base64 != null) {
                Cipher sym = PASSING_ANNOTATOR.decrypt();
                try (ObjectInputStream ois = new ObjectInputStreamEx(new GZIPInputStream(
                        new CipherInputStream(new ByteArrayInputStream(Base64.decode(base64.toCharArray())), sym)),
                        Jenkins.getActiveInstance().pluginManager.uberClassLoader,
                        ClassFilter.DEFAULT)) {
                    long timestamp = ois.readLong();
                    if (TimeUnit.HOURS.toMillis(1) > abs(System.currentTimeMillis() - timestamp)) {
                        @SuppressWarnings("unchecked") ConsoleAnnotator<WorkflowRun> annotator = (ConsoleAnnotator) ois.readObject();
                        return annotator;
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        return ConsoleAnnotator.initial(context);
    }

    private static final CryptoConfidentialKey PASSING_ANNOTATOR = new CryptoConfidentialKey(PipelineLargeText.class, "consoleAnnotator");

}
