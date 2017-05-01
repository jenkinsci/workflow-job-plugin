package org.jenkinsci.plugins.workflow.job;

import hudson.model.Result;
import hudson.security.WhoAmI;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class WorkflowJobTest {
    @Rule public JenkinsRule j = new JenkinsRule();

    @Issue("JENKINS-40255")
    @Test public void getSCM() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  checkout(new hudson.scm.NullSCM())\n" +
            "}"));
        assertTrue("No runs has been performed and there should be no SCMs", p.getSCMs().isEmpty());

        j.buildAndAssertSuccess(p);

        assertEquals("Expecting one SCM", 1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("error 'Fail!'"));

        j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));

        assertEquals("Expecting one SCM even though last run failed",1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("echo 'Pass!'"));

        j.buildAndAssertSuccess(p);

        assertEquals("Expecting zero SCMs",0, p.getSCMs().size());
    }

    @Test
    public void addAction() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        WhoAmI a = new WhoAmI();
        p.addAction(a);
        assertNotNull(p.getAction(WhoAmI.class));
    }

}
