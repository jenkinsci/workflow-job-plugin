package org.jenkinsci.plugins.workflow.job;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.security.WhoAmI;
import hudson.triggers.SCMTrigger;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class WorkflowJobTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

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

    @Issue("JENKINS-34716")
    @Test public void polling() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        j.assertLogContains("first version", j.buildAndAssertSuccess(p));
        sampleRepo.write("Jenkinsfile", "echo 'second version'");
        sampleRepo.git("commit", "-a", "-m", "init");
        j.jenkins.setQuietPeriod(0);
        j.createWebClient().getPage(new WebRequest(j.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        j.waitUntilNoActivity();
        WorkflowRun b2 = p.getLastBuild();
        assertEquals(2, b2.getNumber());
        j.assertLogContains("second version", b2);
    }

    @Test
    public void addAction() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        WhoAmI a = new WhoAmI();
        p.addAction(a);
        assertNotNull(p.getAction(WhoAmI.class));
    }

    @Issue("JENKINS-27299")
    @Test public void disabled() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        assertFalse(p.isDisabled());
        assertTrue(p.isBuildable());
        JenkinsRule.WebClient wc = j.createWebClient();
        j.submit(wc.getPage(p).<HtmlForm>getHtmlElementById("disable-project"));
        assertTrue(p.isDisabled());
        assertFalse(p.isBuildable());
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        HtmlCheckBoxInput checkbox = form.getInputByName("disable");
        assertTrue(checkbox.isChecked());
        checkbox.setChecked(false);
        j.submit(form);
        assertFalse(p.isDisabled());
        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "disable"), HttpMethod.POST));
        assertTrue(p.isDisabled());
        assertNull(p.scheduleBuild2(0));
        // TODO https://github.com/jenkinsci/jenkins/pull/2866 reënable by CLI
    }

}
