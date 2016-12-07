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

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.util.NameValuePair;
import hudson.model.Item;
import hudson.model.Items;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PipelineTriggersJobPropertyTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();

    /**
     * Needed to ensure that we get a fresh {@code startsAndStops} with each test run. Has to be *after* rather than
     * *before* to avoid weird ordering issues with {@code @LocalData}.
     */
    @After
    public void resetStartsAndStops() {
        MockTrigger.startsAndStops = new ArrayList<>();
        QueryingMockTrigger.startsAndStops = new ArrayList<>();
    }

    @Test
    public void loadCallsStartFalse() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
        p = (WorkflowJob) Items.load(p.getParent(), p.getRootDir());
        t = (MockTrigger) p.getTriggers().get(t.getDescriptor());
        assertNotNull(t);
        // The  first "null, false" is due to the p.addTrigger(t) call and won't apply in the real world.
        assertEquals("[null, false, null, false]", MockTrigger.startsAndStops.toString());
        Boolean currentStatus = t.currentStatus();
        assertNotNull(currentStatus);
        assertFalse(currentStatus);
    }

    @Test
    public void submitCallsStartTrue() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
        p = (WorkflowJob) r.configRoundtrip((Item) p);
        t = (MockTrigger) p.getTriggers().get(t.getDescriptor());
        assertNotNull(t);
        // The  first "null, false" is due to the p.addTrigger(t) call and won't apply in the real world.
        assertEquals("[null, false, null, true]", MockTrigger.startsAndStops.toString());
        Boolean currentStatus = t.currentStatus();
        assertNotNull(currentStatus);
        assertTrue(currentStatus);
    }

    @Test
    public void previousTriggerStopped() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        MockTrigger t = new MockTrigger();
        p.addTrigger(t);
        p.save();
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

        assertEquals(2, triggerProp.getTriggers().size());
        assertEquals(2, p.getTriggers().size());

        Trigger timerFromProp = getTriggerFromList(TimerTrigger.class, triggerProp.getTriggers());
        assertNotNull(timerFromProp);
        assertEquals(TimerTrigger.class, timerFromProp.getClass());

        Trigger timerFromJob = p.getTriggers().get(timerFromProp.getDescriptor());
        assertEquals(timerFromProp, timerFromJob);

        Trigger mockFromProp = getTriggerFromList(MockTrigger.class, triggerProp.getTriggers());
        assertNotNull(mockFromProp);
        assertEquals(MockTrigger.class, mockFromProp.getClass());

        Trigger mockFromJob = p.getTriggers().get(mockFromProp.getDescriptor());
        assertEquals(mockFromProp, mockFromJob);

        assertNotNull(((MockTrigger)mockFromProp).currentStatus());
        assertEquals("[null, false, null, false, null, false]", MockTrigger.startsAndStops.toString());
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
        assertNotNull(((MockTrigger)modTriggers.get(0)).currentStatus());

        // The  first "null, false" is due to the p.addTrigger(t) call and won't apply in the real world.
        assertEquals("[null, false, null, true]", MockTrigger.startsAndStops.toString());
    }

    @Test
    public void triggerPresentDuringStart() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "triggerPresent");
        r.configRoundtrip(p);
        assertNull(getTriggerFromList(QueryingMockTrigger.class,
                p.getTriggersJobProperty().getTriggers()));
        JenkinsRule.WebClient wc = r.createWebClient();
        String newConfig = org.apache.commons.io.IOUtils.toString(
                PipelineTriggersJobPropertyTest.class.getResourceAsStream(
                        "/org/jenkinsci/plugins/workflow/job/properties/PipelineTriggersJobPropertyTest/triggerPresentDuringStart.json"), "UTF-8");
        WebRequest request = new WebRequest(new URL(p.getAbsoluteUrl() + "configSubmit"), HttpMethod.POST);
        wc.addCrumb(request);
        List<NameValuePair> params = new ArrayList<>();
        params.addAll(request.getRequestParameters());
        params.add(new NameValuePair("json", newConfig));
        request.setRequestParameters(params);
        wc.getPage(request);
        QueryingMockTrigger t = getTriggerFromList(QueryingMockTrigger.class,
                p.getTriggersJobProperty().getTriggers());
        assertNotNull(t);
        assertTrue(t.isStarted);
        assertTrue(t.foundSelf);
    }

    private <T extends Trigger> T getTriggerFromList(Class<T> clazz, List<Trigger<?>> triggers) {
        for (Trigger t : triggers) {
            if (clazz.isInstance(t)) {
                return clazz.cast(t);
            }
        }

        return null;
    }
}



