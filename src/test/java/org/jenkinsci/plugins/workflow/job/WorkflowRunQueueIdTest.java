package org.jenkinsci.plugins.workflow.job;

import hudson.model.Queue;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.test.JenkinsRuleExt;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunQueueIdTest {

    @Test
    void testQueueIdCapturedBeforeRunStart() throws Exception {
        JenkinsRule j = JenkinsRuleExt.create(); // JUnit 5 helper

        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "unluckyJob");
        job.setDefinition(new CpsFlowDefinition("echo 'hi'", true));

        // Schedule build but DO NOT run immediately
        Queue.Item item = job.scheduleBuild2(0).getStartCondition().get();
        assertNotNull(item, "Queue item should not be null");

        // Now retrieve the run
        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull(run, "Run should exist");

        // The fix ensures queueId is set before run starts
        assertEquals(item.getId(), run.getQueueId(), "queueId should match Queue.Item ID");
    }
}
