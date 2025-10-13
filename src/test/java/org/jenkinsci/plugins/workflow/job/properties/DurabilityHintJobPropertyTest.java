package org.jenkinsci.plugins.workflow.job.properties;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@WithJenkins
class DurabilityHintJobPropertyTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void configRoundTripAndRun() throws Exception{
        WorkflowJob defaultCase = r.jenkins.createProject(WorkflowJob.class, "testCase");
        defaultCase.setDefinition(new CpsFlowDefinition("echo 'cheese is delicious'", true));

        assertNull(defaultCase.getProperty(DurabilityHintJobProperty.class));

        for (FlowDurabilityHint hint : FlowDurabilityHint.values()) {
            try {
                defaultCase.addProperty(new DurabilityHintJobProperty(hint));
                assertEquals(hint, defaultCase.getProperty(DurabilityHintJobProperty.class).getHint());
                r.configRoundtrip(defaultCase);
                assertEquals(hint, defaultCase.getProperty(DurabilityHintJobProperty.class).getHint());

                r.buildAndAssertSuccess(defaultCase);
                assertEquals(hint, defaultCase.getLastBuild().getExecution().getDurabilityHint());

                defaultCase.removeProperty(DurabilityHintJobProperty.class);
                assertNull(defaultCase.getProperty(DurabilityHintJobProperty.class));
            } catch (Exception ex) {
                throw new Exception("Error with FlowDurabilityHint " + hint, ex);
            }
        }
    }
}
