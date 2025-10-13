package org.jenkinsci.plugins.workflow.job;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.MemoryAssert;

import java.lang.ref.WeakReference;
import java.util.logging.Level;

import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 *  Verifies we do proper garbage collection of memory
 */
@WithJenkins
class MemoryCleanupTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private final LogRecorder logging = new LogRecorder();
    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void cleanup() throws Exception {
        logging.record("", Level.INFO).capture(256); // like WebAppMain would do, if in a real instance rather than JenkinsRule
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        WorkflowRun b1 = r.buildAndAssertSuccess(p);
        WeakReference<WorkflowRun> b1r = new WeakReference<>(b1);
        b1.delete();
        b1 = null;
        r.jenkins.getQueue().clearLeftItems(); // so we do not need to wait 5m
        try {
            MemoryAssert.assertGC(b1r, false);
        } catch (NoClassDefFoundError x) {
            assumeTrue("org/netbeans/insane/hook/MakeAccessible".equals(x.getMessage()), "TODO https://github.com/jenkinsci/bom/issues/1551 " + x);
            throw x;
        }
    }
}
