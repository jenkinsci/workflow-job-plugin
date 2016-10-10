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

import hudson.Extension;
import hudson.MarkupText;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.support.actions.AnnotatedLogAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Console line with note printed when a new {@link FlowNode} is added to the graph.
 * Defines the {@code pipeline-new-node} CSS class and several attributes which may be used to control subsequent behavior:
 * <ul>
 * <li>{@code nodeId} for {@link FlowNode#getId}
 * <li>{@code parentIds} for {@link FlowNode#getParents}
 * <li>{@code startId} for {@link BlockEndNode#getStartNode} (otherwise absent)
 * </ul>
 * @see AnnotatedLogAction#annotateHtml
 */
@Restricted(NoExternalUse.class)
public class NewNodeConsoleNote extends ConsoleNote<Run<?, ?>> {

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
    private final @Nonnull String[] parents;
    private final @CheckForNull String start;

    private NewNodeConsoleNote(FlowNode node) {
        id = node.getId();
        List<FlowNode> parentNodes = node.getParents();
        parents = new String[parentNodes.size()];
        for (int i = 0; i < parentNodes.size(); i++) {
            parents[i] = parentNodes.get(i).getId();
        }
        start = node instanceof BlockEndNode ? ((BlockEndNode) node).getStartNode().getId() : null;
    }

    @Override
    public ConsoleAnnotator<?> annotate(Run<?, ?> context, MarkupText text, int charPos) {
        StringBuilder startTag = new StringBuilder("<span class=\"pipeline-new-node\" nodeId=\"").append(id);
        for (int i = 0; i < parents.length; i++) {
            startTag.append(i == 0 ? "\" parentIds=\"" : " ").append(parents[i]);
        }
        if (start != null) {
            startTag.append("\" startId=\"").append(start);
        }
        startTag.append("\">");
        text.addMarkup(0, text.length(), startTag.toString(), "</span>");
        // TODO should we also add another span around the actual displayFunctionName text, to make it easy to parse out?
        return null;
    }

    private static final long serialVersionUID = 1L;

    @Extension public static final class DescriptorImpl extends ConsoleAnnotationDescriptor {}

}
