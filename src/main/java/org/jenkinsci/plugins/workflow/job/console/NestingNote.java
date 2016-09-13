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

import hudson.MarkupText;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import java.util.List;

/**
 * Encodes the block-scoped nesting of a step.
 */
public class NestingNote extends ConsoleNote<Run<?,?>> {

    private static final long serialVersionUID = 1L;

    private final List<String> nesting;

    public NestingNote(List<String> nesting) {
        this.nesting = nesting;
    }

    @SuppressWarnings("rawtypes")
    @Override public ConsoleAnnotator annotate(Run<?,?> context, MarkupText text, int charPos) {
        StringBuilder b = new StringBuilder("<span class=\"");
        for (int i = 0; i < nesting.size(); i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append("pipeline-sect-").append(nesting.get(i));
        }
        b.append("\">");
        text.addMarkup(0, text.length(), b.toString(), "</span>");
        return null;
    }

}
