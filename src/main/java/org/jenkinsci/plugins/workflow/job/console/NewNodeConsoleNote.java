/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc.
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

import com.google.common.base.Predicates;
import hudson.Extension;
import hudson.MarkupText;
import hudson.Util;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.Filterator;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.actions.AnnotatedLogAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Console line with note printed when a new {@link FlowNode} is added to the graph.
 * Defines the {@code pipeline-new-node} CSS class and several attributes which may be used to control subsequent behavior:
 * <ul>
 * <li>{@code nodeId} for {@link FlowNode#getId}
 * <li>{@code startId} {@link FlowNode#getId} for {@link BlockStartNode}, else {@link BlockEndNode#getStartNode}, else absent
 * <li>{@code enclosingId} the immediately enclosing {@link BlockStartNode}, if any
 * <li>{@code label} for {@link LabelAction} if present
 * </ul>
 * @see AnnotatedLogAction#annotateHtml
 */
@Restricted(NoExternalUse.class)
public class NewNodeConsoleNote extends ConsoleNote<WorkflowRun> {

    /**
     * Prefix used in metadata lines.
     */
    private static final String CONSOLE_NOTE_PREFIX = "[Pipeline] ";

    public static void print(FlowNode node, TaskListener listener) {
        try {
            listener.annotate(new NewNodeConsoleNote(node));
        } catch (IOException x) {
            // never mind
        }
        listener.getLogger().println(CONSOLE_NOTE_PREFIX + node.getDisplayFunctionName());
    }

    private final @Nonnull String id;
    private final @CheckForNull String enclosing;
    private final @CheckForNull String start;

    private NewNodeConsoleNote(FlowNode node) {
        id = node.getId();
        if (node instanceof BlockEndNode) {
            enclosing = null;
            start = ((BlockEndNode) node).getStartNode().getId();
        } else {
            Filterator<FlowNode> it = FlowScanningUtils.fetchEnclosingBlocks(node).filter(Predicates.not(Predicates.equalTo(node)));
            enclosing = it.hasNext() ? it.next().getId() : null;
            start = node instanceof BlockStartNode ? node.getId() : null;
        }
    }

    @Override
    public ConsoleAnnotator<?> annotate(WorkflowRun context, MarkupText text, int charPos) {
        StringBuilder startTag = new StringBuilder("<span class=\"pipeline-new-node\" nodeId=\"").append(id);
        if (start != null) {
            startTag.append("\" startId=\"").append(start);
        }
        if (enclosing != null) {
            startTag.append("\" enclosingId=\"").append(enclosing);
        }
        FlowExecution execution = context.getExecution();
        if (execution != null) {
            try {
                FlowNode node = execution.getNode(id);
                if (node != null) {
                    LabelAction a = node.getAction(LabelAction.class);
                    if (a != null) {
                        String displayName = a.getDisplayName();
                        assert displayName != null;
                        startTag.append("\" label=\"").append(Util.escape(displayName)); // TODO is there some better way to escape for attribute values?
                    }
                }
            } catch (IOException x) {
                Logger.getLogger(NewNodeConsoleNote.class.getName()).log(Level.WARNING, null, x);
            }
        }
        startTag.append("\">");
        text.addMarkup(0, text.length(), startTag.toString(), "</span>");
        // TODO should we also add another span around the actual displayFunctionName text, to make it easy to parse out?
        return null;
    }

    private static final long serialVersionUID = 1L;

    @Extension public static final class DescriptorImpl extends ConsoleAnnotationDescriptor {}

}
