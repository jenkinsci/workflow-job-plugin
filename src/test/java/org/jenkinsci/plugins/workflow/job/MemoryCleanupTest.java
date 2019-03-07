package org.jenkinsci.plugins.workflow.job;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MemoryAssert;

import java.lang.ref.WeakReference;
import java.util.logging.Level;

import static org.junit.Assume.assumeTrue;

/**
 *  Verifies we do proper garbage collection of memory
 */
public class MemoryCleanupTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule();

    @Test
    public void cleanup() throws Exception {
        // Ignore on Java 9+, because `MemoryAssert.assertGC` below tries to call setAccessible on some internal structure
        assumeTrue(System.getProperty("java.specification.version").startsWith("1."));
        logging.record("", Level.INFO).capture(256); // like WebAppMain would do, if in a real instance rather than JenkinsRule
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p);
        WeakReference<WorkflowRun> b1r = new WeakReference<>(b1);
        b1.delete();
        b1 = null;
        r.jenkins.getQueue().clearLeftItems(); // so we do not need to wait 5m
        MemoryAssert.assertGC(b1r, false);
    }
}
