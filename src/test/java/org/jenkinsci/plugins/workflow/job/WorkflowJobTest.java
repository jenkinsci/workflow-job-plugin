package org.jenkinsci.plugins.workflow.job;


import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

        p.scheduleBuild2(0);
        j.waitUntilNoActivity();

        assertEquals("Expecting one SCM",1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("error 'Fail!'"));
        p.scheduleBuild2(0);
        j.waitUntilNoActivity();

        assertEquals("Last run should have failed", Result.FAILURE, p.getLastBuild().getResult());
        assertEquals("Expecting one SCM even though last run failed",1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("echo 'Pass!'"));
        p.scheduleBuild2(0);
        j.waitUntilNoActivity();

        assertEquals("Last run should have succeeded", Result.SUCCESS, p.getLastBuild().getResult());
        assertEquals("Expecting zero SCMs",0, p.getSCMs().size());
    }
}
