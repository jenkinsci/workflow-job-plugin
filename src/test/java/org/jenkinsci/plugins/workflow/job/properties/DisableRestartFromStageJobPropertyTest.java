package org.jenkinsci.plugins.workflow.job.properties;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static org.junit.Assert.*;

public class DisableRestartFromStageJobPropertyTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();

//    @Issue("JENKINS-54250")
//    @Test
//    public void restartFromStageOnByDefault() throws IOException {
//        WorkflowJob p = r.createProject(WorkflowJob.class);
//        assertNotNull(p);
//        assertNull(p.getProperty(DisableRestartFromStageJobProperty.class));
//        assertTrue(p.isRestartableFromStage());
//    }
//
//    @Issue("JENKINS-54250")
//    @Test
//    public void restartFromStageOff() throws IOException {
//        WorkflowJob p = r.createProject(WorkflowJob.class);
//        assertNotNull(p);
//        assertNull(p.getProperty(DisableRestartFromStageJobProperty.class));
//        p.setRestartableFromStage(false);
//        assertNotNull(p.getProperty(DisableRestartFromStageJobProperty.class));
//        assertFalse(p.isRestartableFromStage());
//    }
}
