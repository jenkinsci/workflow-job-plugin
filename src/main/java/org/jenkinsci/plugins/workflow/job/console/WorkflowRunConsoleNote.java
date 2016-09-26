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

import hudson.MarkupText;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;

/**
 * Console note for Workflow metadata specific messages.
 */
public class WorkflowRunConsoleNote extends ConsoleNote<Run<?, ?>> {

    /**
     * Prefix used in metadata lines.
     */
    private static final String CONSOLE_NOTE_PREFIX = "[Pipeline] ";

    /**
     * CSS color selector.
     */
    private static final String TEXT_COLOR = "9A9999";

    private static final String START_NOTE = "<span style=\"color:#"+ TEXT_COLOR +"\">";
    private static final String END_NOTE = "</span>";

    public static void print(String message, TaskListener listener) {
        try {
            listener.annotate(new WorkflowRunConsoleNote());
        } catch (IOException x) {
            // never mind
        }
        listener.getLogger().println(CONSOLE_NOTE_PREFIX + message);
    }

    private WorkflowRunConsoleNote() {}

    @Override
    public ConsoleAnnotator<?> annotate(Run<?, ?> context, MarkupText text, int charPos) {
        text.addMarkup(0, text.length(), START_NOTE, END_NOTE);
        return null;
    }

    private static final long serialVersionUID = 1L;

}
