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

import hudson.console.HyperlinkNote;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shows or hides a block by nesting.
 */
public class ShowHideNote extends HyperlinkNote {

    private static final Logger LOGGER = Logger.getLogger(ShowHideNote.class.getName());
    private static final long serialVersionUID = 1L;

    public static String encodeTo(String id, boolean show, String text) {
        try {
            return new ShowHideNote(id, show, text.length()).encode() + text;
        } catch (IOException e) {
            // impossible, but don't make this a fatal problem
            LOGGER.log(Level.WARNING, "Failed to serialize " + ShowHideNote.class, e);
            return text;
        }
    }

    private final String id;
    // TODO better to have a single link that toggles (requires looking up current state, as below)
    private final boolean show;

    private ShowHideNote(String id, boolean show, int length) {
        super("#", length);
        this.id = id;
        this.show = show;
    }

    @Override protected String extraAttributes() {
        // TODO look up any existing rule via .selectorText and change its .style.display (but how can be package this JS into an adjunct?)
        return " onclick=\"var ss = document.styleSheets[0]; ss.insertRule('.pipeline-sect-" + id + " {display: " + (show ? "inline" : "none") + "}', ss.rules.length); return false\"";
    }

}
