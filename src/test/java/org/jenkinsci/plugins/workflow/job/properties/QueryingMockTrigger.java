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
 *
 *
 */

package org.jenkinsci.plugins.workflow.job.properties;

import hudson.Extension;
import hudson.model.BuildableItem;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

public class QueryingMockTrigger extends Trigger<BuildableItem> {

    /** elements are true or false for {@code start}, null for {@code stop} */
    public static List<Boolean> startsAndStops = new ArrayList<>();

    public transient boolean isStarted;

    public transient boolean foundSelf = false;

    @DataBoundConstructor
    public QueryingMockTrigger() {}

    @Override public void start(BuildableItem project, boolean newInstance) {
        super.start(project, newInstance);
        for (Trigger t : ((ParameterizedJobMixIn.ParameterizedJob)project).getTriggers().values()) {
            if (t instanceof QueryingMockTrigger) {
                foundSelf = true;
            }
        }

        startsAndStops.add(newInstance);
        isStarted = true;
    }

    @Override public void stop() {
        super.stop();
        startsAndStops.add(null);
        isStarted = false;
    }

    public Boolean currentStatus() {
        if (!startsAndStops.isEmpty()) {
            return startsAndStops.get(startsAndStops.size() - 1);
        } else {
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        @Override public boolean isApplicable(Item item) {
            return true;
        }

    }
}
