package org.jenkinsci.plugins.workflow.job;

import hudson.model.Queue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WorkflowRunQueueIdTest {

    @Test
    public void testQueueIdAssignment() throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("echo 'hello'", true));

        Queue.Item item = Queue.getInstance().schedule(job, 0);
        assertNotNull(item);

        WorkflowRun run = job.scheduleBuild2(0).waitForStart();

        long expected = item.getId();
        long actual = run.getQueueId();

        assertEquals(expected, actual, "QueueId should match Queue.Item");
    }
}
