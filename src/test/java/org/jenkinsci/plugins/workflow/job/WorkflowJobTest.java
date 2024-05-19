package org.jenkinsci.plugins.workflow.job;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import hudson.cli.CLICommandInvoker;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.security.WhoAmI;
import hudson.triggers.SCMTrigger;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RunLoadCounter;

public class WorkflowJobTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();

    @Issue("JENKINS-40255")
    @Test public void getSCM() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  checkout(new hudson.scm.NullSCM())\n" +
            "}", false /* for hudson.scm.NullSCM */));
        assertTrue("No runs has been performed and there should be no SCMs", p.getSCMs().isEmpty());

        j.buildAndAssertSuccess(p);

        assertEquals("Expecting one SCM", 1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("error 'Fail!'", true));

        j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));

        assertEquals("Expecting one SCM even though last run failed",1, p.getSCMs().size());

        p.setDefinition(new CpsFlowDefinition("echo 'Pass!'", true));

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

    @Issue("JENKINS-38669")
    @Test public void nonEmptySCMListForGitSCMJobBeforeBuild() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        CpsScmFlowDefinition def = new CpsScmFlowDefinition(new GitSCM("I don't care"), "Jenkinsfile");
        assertEquals("Expecting one SCM for definition", 1, def.getSCMs().size());
        p.setDefinition(def);
        assertEquals("Expecting one SCM", 1, p.getSCMs().size());
    }

    @Issue("JENKINS-38669")
    @Test public void neverBuiltSCMBasedJobMustBeTriggerableByHook() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        j.jenkins.setQuietPeriod(0);
        j.createWebClient().getPage(new WebRequest(j.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        j.waitUntilNoActivity();
        WorkflowRun b1 = p.getLastBuild();
        assertEquals(1, b1.getNumber());
        j.assertLogContains("first version", b1);
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

        // Disable the project
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        HtmlCheckBoxInput checkbox = form.getInputByName("enable");
        assertTrue(checkbox.isChecked());
        checkbox.setChecked(false);
        j.submit(form);
        assertTrue(p.isDisabled());
        assertFalse(p.isBuildable());

        // Re-enable the project
        form = wc.getPage(p, "configure").getFormByName("config");
        checkbox = form.getInputByName("enable");
        assertFalse(checkbox.isChecked());
        checkbox.setChecked(true);
        j.submit(form);
        assertFalse(p.isDisabled());
        assertTrue(p.isBuildable());

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "disable"), HttpMethod.POST));
        assertTrue(p.isDisabled());
        assertNull(p.scheduleBuild2(0));
        assertThat(new CLICommandInvoker(j, "enable-job").invokeWithArgs("p"), CLICommandInvoker.Matcher.succeededSilently());
        assertFalse(p.isDisabled());
    }

    @Test
    public void newBuildsShouldNotLoadOld() throws Throwable {
        var p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        for (int i = 0; i < 10; i++) {
            j.buildAndAssertSuccess(p);
        }
        RunLoadCounter.assertMaxLoads(p, /* just lastBuild */ 1, () -> {
            for (int i = 0; i < 5; i++) {
                j.buildAndAssertSuccess(p);
            }
            return null;
        });
    }

}
