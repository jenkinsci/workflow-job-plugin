package org.jenkinsci.plugins.workflow.job;

import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.FreeStyleProject;
import hudson.model.Queue.Task;
import hudson.slaves.DumbSlave;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

/**
 * Verifies that WorkflowRun captures the correct queueId when a Pipeline build waits in queue.
 */
public class WorkflowRunQueueIdTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void testQueueIdCapturedBeforeRunStarts() throws Exception {
        // Force builds to stay queued.
        r.jenkins.setNumExecutors(0);

        // Create a simple pipeline job.
        WorkflowJob job = r.jenkins.createProject(WorkflowJob.class, "unluckyJob");
        job.setDefinition(new CpsFlowDefinition("echo 'hello world'", true));

        // Schedule the build. It will enter the queue and stay there.
        Queue.Task task = job;
        Queue.Item item = r.jenkins.getQueue().schedule2(job, 0).getItem();
        assertNotNull("Build should be queued", item);

        long expectedQueueId = item.getId();
        assertTrue("Queue ID must be > 0", expectedQueueId > 0);

        // Now obtain the run. Jenkins creates the WorkflowRun object immediately.
        WorkflowRun run = job.getBuildByNumber(1);
        assertNotNull("Run object should exist", run);

        // Allow the code under test to execute bindQueueItemInternal() via run() path.
        // run.run() won't execute fully because there are no executors,
        // but the queueId fix should apply before execution starts.
        r.waitForMessage("hello world", run); // Forces run.loading/execution lifecycle

        // The run should now contain the correct queueId (after your fix).
        long actualQueueId = run.getQueueId();
        assertEquals("WorkflowRun must have the same queueId as Queue.Item",
                     expectedQueueId, actualQueueId);
    }
}
