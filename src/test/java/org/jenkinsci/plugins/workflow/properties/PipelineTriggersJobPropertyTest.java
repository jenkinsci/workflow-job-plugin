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
package org.jenkinsci.plugins.workflow.properties;

import hudson.model.Item;
import hudson.model.Items;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.recipes.LocalData;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PipelineTriggersJobPropertyTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void loadCallsStartFalse() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
        p = (WorkflowJob) Items.load(p.getParent(), p.getRootDir());
        t = (MockTrigger)p.getTriggers().get(t.getDescriptor());
        assertNotNull(t);
        assertEquals("[false]", t.calls.toString());
        assertTrue(t.isStarted);
    }

    @Test
    public void submitCallsStartTrue() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
        p = (WorkflowJob) r.configRoundtrip((Item)p);
        t = (MockTrigger)p.getTriggers().get(t.getDescriptor());
        assertNotNull(t);
        assertEquals("[true]", t.calls.toString());
        assertTrue(t.isStarted);
    }

    @Test
    public void previousTriggerStopped() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
        assertTrue(t.isStarted);
        MockTrigger t2 = new MockTrigger();
        p.addTrigger(t2);
        p.save();

        assertFalse(t.isStarted);
        assertTrue(t2.isStarted);
    }

    @LocalData
    @Test
    public void triggerMigration() throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("p", WorkflowJob.class);
        assertNotNull(p);

        PipelineTriggersJobProperty triggerProp = p.getProperty(PipelineTriggersJobProperty.class);
        assertNotNull(triggerProp);
        assertEquals(1, triggerProp.getTriggers().size());
        assertEquals(1, p.getTriggers().size());

        Trigger fromProp = triggerProp.getTriggers().get(0);
        assertEquals(TimerTrigger.class, fromProp.getClass());

        Trigger fromJob = p.getTriggers().get(fromProp.getDescriptor());
        assertEquals(fromProp, fromJob);
    }

    @Test
    public void configRoundTrip() throws Exception {
        WorkflowJob defaultCase = r.jenkins.createProject(WorkflowJob.class, "defaultCase");
        assertTrue(defaultCase.getTriggers().isEmpty());

        WorkflowJob roundTripDefault = r.configRoundtrip(defaultCase);
        assertTrue(roundTripDefault.getTriggers().isEmpty());

        WorkflowJob withTriggerCase = r.jenkins.createProject(WorkflowJob.class, "withTriggerCase");
        withTriggerCase.addTrigger(new MockTrigger());
        assertEquals(1, withTriggerCase.getTriggers().size());
        List<Trigger<?>> origTriggers = new ArrayList<>(withTriggerCase.getTriggers().values());

        assertEquals(MockTrigger.class, origTriggers.get(0).getClass());

        WorkflowJob roundTripWithTrigger = r.configRoundtrip(withTriggerCase);

        assertEquals(1, roundTripWithTrigger.getTriggers().size());
        List<Trigger<?>> modTriggers = new ArrayList<>(roundTripWithTrigger.getTriggers().values());

        assertEquals(MockTrigger.class, modTriggers.get(0).getClass());
    }

    public static class MockTrigger extends Trigger<Item> {

        public transient List<Boolean> calls = new ArrayList<Boolean>();
        public transient boolean isStarted = false;

        @DataBoundConstructor
        public MockTrigger() {}

        @Override public void start(Item project, boolean newInstance) {
            super.start(project, newInstance);
            calls.add(newInstance);
            isStarted = true;
        }

        @Override public void stop() {
            super.stop();
            isStarted = false;
        }

        @Override protected Object readResolve() throws ObjectStreamException {
            calls = new ArrayList<Boolean>();
            return super.readResolve();
        }

        @TestExtension
        public static class DescriptorImpl extends TriggerDescriptor {

            @Override public boolean isApplicable(Item item) {
                return true;
            }

        }
    }

}


