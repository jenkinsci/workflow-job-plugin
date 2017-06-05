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
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Saveable;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.DescribableList;
import jenkins.model.TransientActionFactory;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
public class PipelineTriggersJobProperty extends JobProperty<WorkflowJob> {
    private static final Logger LOGGER = Logger.getLogger(PipelineTriggersJobProperty.class.getName());

    private List<Trigger<?>> triggers = new ArrayList<>();

    @DataBoundConstructor
    public PipelineTriggersJobProperty(List<Trigger> triggers) {
        // Defensive handling of when we get called via {@code Descriptor.newInstance} with no form data.
        if (triggers == null) {
            this.triggers = new ArrayList<>();
        } else {
            for (Trigger t : triggers) {
                this.triggers.add((Trigger<?>)t);
            }
        }
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

    public void stopTriggers() {
        for (Trigger trigger : triggers) {
            trigger.stop();
        }
    }

    public void startTriggers(boolean newInstance) {
        for (Trigger trigger : triggers) {
            try {
                trigger.start(owner, newInstance);
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Can't start trigger.", ex);
            }
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

    @CheckForNull
    @Override
    public PipelineTriggersJobProperty reconfigure(@Nonnull StaplerRequest req, @CheckForNull JSONObject form) throws Descriptor.FormException {
        DescribableList<Trigger<?>, TriggerDescriptor> trigList = new DescribableList<>(Saveable.NOOP);
        try {
            JSONObject triggerSection = new JSONObject();
            if (form != null) {
                triggerSection = form.getJSONObject("triggers");
            }
            trigList.rebuild(req, triggerSection, Trigger.for_(owner));
        } catch (IOException e) {
            throw new Descriptor.FormException(e, "triggers");
        }

        PipelineTriggersJobProperty oldProp = owner.getTriggersJobProperty();

        try {
            owner.removeProperty(this);
            PipelineTriggersJobProperty thisProp = new PipelineTriggersJobProperty(new ArrayList<Trigger>(trigList.toList()));

            owner.addTriggersJobPropertyWithoutStart(thisProp);

            thisProp.startTriggers(true);
            return thisProp;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "could not configure triggers", e);
        }

        if (owner.getTriggersJobProperty() == null && oldProp != null) {
            try {
                owner.addTriggersJobPropertyWithoutStart(oldProp);
                oldProp.startTriggers(true);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "could not revert to original configured triggers", e);
                throw new Descriptor.FormException("Could not revert to original configured triggers", e, "triggers");
            }
        }

        return oldProp;
    }

    @Extension(ordinal = -100)
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
