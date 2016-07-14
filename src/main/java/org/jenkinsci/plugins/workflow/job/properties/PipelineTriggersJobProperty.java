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
import hudson.model.Action;
import hudson.model.Descriptor;
import hudson.model.Items;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PipelineTriggersJobProperty extends JobProperty<WorkflowJob> {
    private List<Trigger<?>> triggers;

    @DataBoundConstructor
    public PipelineTriggersJobProperty(List<Trigger<?>> triggers) {
        this.triggers = triggers;
    }

    public void setTriggers(List<Trigger<?>> triggers) {
        this.triggers = triggers;
    }

    public List<Trigger<?>> getTriggers() {
        return triggers;
    }

    public void addTrigger(Trigger t) {
        triggers.add(t);
    }

    public Trigger getTriggerForDescriptor(TriggerDescriptor td) {
        for (Trigger trigger : triggers) {
            if (td.equals(trigger.getDescriptor())) {
                return trigger;
            }
        }

        return null;
    }

    public void stopTrigggers() {
        for (Trigger trigger : triggers) {
            trigger.stop();
        }
    }

    public void startTriggers(boolean newInstance) {
        for (Trigger trigger: triggers) {
            trigger.start(owner, newInstance);
        }
    }

    public void removeTrigger(Trigger t) {
        // TODO: Will we get equality for trigger instances of the same Descriptor?
        Trigger toRemove = getTriggerForDescriptor(t.getDescriptor());

        if (toRemove != null) {
            triggers.remove(toRemove);
        }
    }

    public Map<TriggerDescriptor,Trigger<?>> getTriggersMap() {
        Map<TriggerDescriptor,Trigger<?>> triggerMap = new HashMap<>();

        for (Trigger t : getTriggers()) {
            TriggerDescriptor td = t.getDescriptor();
            triggerMap.put(td, t);
        }

        return triggerMap;
    }

    public List<Action> getAllTriggerActions() {
        List<Action> triggerActions = new ArrayList<>();

        for (Trigger<?> t : triggers) {
            triggerActions.addAll(t.getProjectActions());
        }

        return triggerActions;
    }

    @Override
    public void setOwner(WorkflowJob owner) {
        this.owner = owner;

        startTriggers(Items.currentlyUpdatingByXml());
    }

    @CheckForNull
    @Override
    public PipelineTriggersJobProperty reconfigure(@Nonnull StaplerRequest req, @CheckForNull JSONObject form) throws Descriptor.FormException {
        PipelineTriggersJobProperty thisProp;

        // TODO: See if we actually need to check this or can assume it's always non-null
        if (form != null) {
            thisProp = (PipelineTriggersJobProperty)getDescriptor().newInstance(req, form);
        } else {
            thisProp = this;
        }

        this.stopTrigggers();

        thisProp.startTriggers(true);

        return thisProp;
    }

    @Extension
    @Symbol("pipelineTriggers")
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Build triggers";
        }
    }

    @Extension
    public static class Factory extends TransientActionFactory<WorkflowJob> {

        @Override
        public Class<WorkflowJob> type() {
            return WorkflowJob.class;
        }

        @Override
        public @Nonnull Collection<? extends Action> createFor(@Nonnull WorkflowJob job) {
            return job.getTriggersJobProperty().getAllTriggerActions();
        }

    }

}
