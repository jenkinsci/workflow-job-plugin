package org.jenkinsci.plugins.workflow.job;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jenkins.plugins.git.junit.jupiter.WithGitSampleRepo;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import hudson.cli.CLICommandInvoker;
import hudson.model.Executor;
import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import hudson.security.WhoAmI;
import hudson.triggers.SCMTrigger;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.plugins.git.GitSampleRepoRule;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RunLoadCounter;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@WithGitSampleRepo
class WorkflowJobTest {
    private static final Logger LOGGER = Logger.getLogger(WorkflowJobTest.class.getName());

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();
    private JenkinsRule r;
    private GitSampleRepoRule sampleRepo;

    @BeforeEach
    void beforeEach(JenkinsRule rule, GitSampleRepoRule repo) {
        r = rule;
        sampleRepo = repo;
    }

    @Issue("JENKINS-40255")
    @Test
    void getSCM() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                """
                node {
                  checkout(new hudson.scm.NullSCM())
                }
                """, false /* for hudson.scm.NullSCM */));
        assertTrue(p.getSCMs().isEmpty(), "No runs has been performed and there should be no SCMs");

        r.buildAndAssertSuccess(p);

        assertEquals(1, p.getSCMs().size(), "Expecting one SCM");

        p.setDefinition(new CpsFlowDefinition("error 'Fail!'", true));

        r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));

        assertEquals(1, p.getSCMs().size(), "Expecting one SCM even though last run failed");

        p.setDefinition(new CpsFlowDefinition("echo 'Pass!'", true));

        r.buildAndAssertSuccess(p);

        assertEquals(0, p.getSCMs().size(), "Expecting zero SCMs");
    }

    @Issue("JENKINS-34716")
    @Test
    void polling() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        r.assertLogContains("first version", r.buildAndAssertSuccess(p));
        sampleRepo.write("Jenkinsfile", "echo 'second version'");
        sampleRepo.git("commit", "-a", "-m", "init");
        r.jenkins.setQuietPeriod(0);
        r.createWebClient().getPage(new WebRequest(r.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        r.waitUntilNoActivity();
        WorkflowRun b2 = p.getLastBuild();
        assertEquals(2, b2.getNumber());
        r.assertLogContains("second version", b2);
    }

    @Issue("JENKINS-38669")
    @Test
    void nonEmptySCMListForGitSCMJobBeforeBuild() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        CpsScmFlowDefinition def = new CpsScmFlowDefinition(new GitSCM("I don't care"), "Jenkinsfile");
        assertEquals(1, def.getSCMs().size(), "Expecting one SCM for definition");
        p.setDefinition(def);
        assertEquals(1, p.getSCMs().size(), "Expecting one SCM");
    }

    @Issue("JENKINS-38669")
    @Test
    void neverBuiltSCMBasedJobMustBeTriggerableByHook() throws Exception {
        sampleRepo.init();
        sampleRepo.write("Jenkinsfile", "echo 'first version'");
        sampleRepo.git("add", "Jenkinsfile");
        sampleRepo.git("commit", "-m", "init");
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.addTrigger(new SCMTrigger(""));
        p.setDefinition(new CpsScmFlowDefinition(new GitSCM(sampleRepo.toString()), "Jenkinsfile"));
        r.jenkins.setQuietPeriod(0);
        r.createWebClient().getPage(new WebRequest(r.createWebClient().createCrumbedUrl(p.getUrl() + "polling"), HttpMethod.POST));
        r.waitUntilNoActivity();
        WorkflowRun b1 = p.getLastBuild();
        assertEquals(1, b1.getNumber());
        r.assertLogContains("first version", b1);
    }

    @Test
    void addAction() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        WhoAmI a = new WhoAmI();
        p.addAction(a);
        assertNotNull(p.getAction(WhoAmI.class));
    }

    @Issue("JENKINS-27299")
    @Test
    void disabled() throws Exception {
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        JenkinsRule.WebClient wc = r.createWebClient();
        assertDisabled(p, false, wc);

        // Disable the project
        HtmlForm form = wc.getPage(p, "configure").getFormByName("config");
        HtmlCheckBoxInput checkbox = form.getInputByName("enable");
        assertTrue(checkbox.isChecked());
        checkbox.setChecked(false);
        r.submit(form);
        assertDisabled(p, true, wc);

        // Re-enable the project
        form = wc.getPage(p, "configure").getFormByName("config");
        checkbox = form.getInputByName("enable");
        assertFalse(checkbox.isChecked());
        checkbox.setChecked(true);
        r.submit(form);
        assertDisabled(p, false, wc);

        wc.getPage(new WebRequest(wc.createCrumbedUrl(p.getUrl() + "disable"), HttpMethod.POST));
        assertDisabled(p, true, wc);
        assertNull(p.scheduleBuild2(0));
        assertThat(new CLICommandInvoker(r, "enable-job").invokeWithArgs("p"), CLICommandInvoker.Matcher.succeededSilently());
        assertDisabled(p, false, wc);
    }

    private void assertDisabled(WorkflowJob p, boolean disabled, JenkinsRule.WebClient wc) throws Exception {
        assertThat(p.isDisabled(), is(disabled));
        assertThat(p.isBuildable(), is(!disabled));
        assertThat(wc.getJSON(p.getUrl() + "api/json?tree=disabled,buildable").getJSONObject(),
            is(new JSONObject().accumulate("_class", WorkflowJob.class.getName()).accumulate("disabled", disabled).accumulate("buildable", !disabled)));
    }

    @Test
    void newBuildsShouldNotLoadOld() throws Throwable {
        var p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("", true));
        for (int i = 0; i < 10; i++) {
            r.buildAndAssertSuccess(p);
        }
        RunLoadCounter.assertMaxLoads(p, /* just lastBuild */ 1, () -> {
            for (int i = 0; i < 5; i++) {
                r.buildAndAssertSuccess(p);
            }
            return null;
        });
    }

    @Issue("JENKINS-73824")
    @Test
    void deletionShouldWaitForBuildsToComplete() throws Throwable {
        var p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                """
                try {
                  echo 'about to sleep'
                  sleep 999
                } catch(e) {
                  echo 'aborting soon'
                  sleep 3
                }
                """, true));
        var b = p.scheduleBuild2(0).waitForStart();
        r.waitForMessage("about to sleep", b);
        // The build isn't done and catches the interruption, so ItemDeletion.cancelBuildsInProgress should have to wait at least 3 seconds for it to complete.
        LOGGER.info(() -> "Deleting " + p);
        p.delete();
        LOGGER.info(() -> "Deleted " + p);
        // Make sure that the job really has been deleted.
        assertThat(r.jenkins.getItemByFullName(p.getFullName()), nullValue());
        // ItemDeletion.cancelBuildsInProgress should guarantee that the queue is empty at this point.
        var executables = Stream.of(r.jenkins.getComputers())
                .flatMap(c -> c.getAllExecutors().stream())
                .map(Executor::getCurrentExecutable)
                .filter(Objects::nonNull)
                .toList();
        assertThat(executables, empty());
    }

}
