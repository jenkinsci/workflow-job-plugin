package org.jenkinsci.plugins.workflow.job;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.html.HtmlCheckBoxInput;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.cli.CLICommandInvoker;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.scm.PollingResult;
import hudson.scm.SCMRevisionState;
import hudson.security.WhoAmI;
import hudson.triggers.SCMTrigger;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.scm.impl.mock.MockSCM;
import jenkins.scm.impl.mock.MockSCMRevision;
import org.hamcrest.Matchers;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SingleFileSCM;
import org.jvnet.hudson.test.TestExtension;

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
        assertThat(new CLICommandInvoker(j, "enable-job").invokeWithArgs("p"), CLICommandInvoker.Matcher.succeededSilently());
        assertFalse(p.isDisabled());
    }

    @Issue("JENKINS-44520")
    @Test public void shouldInvokeLauncherDecoratorForNodeDuringPolling() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "node {\n" +
                    "  checkout(new " + WorkflowJobTest.class.getName() + ".MySCM())\n" +
            "}"));
        assertTrue("No runs has been performed and there should be no SCMs", p.getSCMs().isEmpty());
        // Init checkout history
        j.buildAndAssertSuccess(p);
        // Init polling baselines
        p.poll(TaskListener.NULL);
        
        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TaskListener l = new StreamTaskListener(ostream);
        p.poll(l);

        assertThat("Variable has not been printed", ostream.toString(), Matchers.containsString("INJECTED=MYSCM"));
    }
    
    @TestExtension(value = "shouldInvokeLauncherDecoratorForNodeDuringPolling")
    public static final class MyNodeLauncherDecorator extends LauncherDecorator {

        @Override
        public Launcher decorate(Launcher lnchr, Node node) {
            // Just inject the environment variable
            Map<String, String> env = new HashMap<>();
            env.put("INJECTED", "MYSCM");
            return lnchr.decorateByEnv(new EnvVars(env));
        }  
    }
    
    public static final class MySCM extends SingleFileSCM {

        public MySCM() throws UnsupportedEncodingException {
            super("Jenkinsfile", "echo Done");
        }

        @Override
        public boolean requiresWorkspaceForPolling() {
            return true;
        }

        @Override
        public SCMRevisionState calcRevisionsFromBuild(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
            // TODO: ideally should be implemented in SingleFileSCM, because it inherits stub impl from NullSCM
            return new SCMRevisionState() {
                @Override
                public String getDisplayName() {
                    return "MySCM";
                }

                @Override
                public String getIconFileName() {
                    return "fingerprint.png";
                }

                @Override
                public String getUrlName() {
                    return "foo";
                }  
            };
        }

        @Override
        public PollingResult compareRemoteRevisionWith(Job<?, ?> project, Launcher launcher, FilePath workspace, TaskListener listener, SCMRevisionState baseline) throws IOException, InterruptedException {
            List<String> commandLine = 
                    Arrays.asList("echo", hudson.remoting.Launcher.isWindows() ? "INJECTED=%INJECTED%" : "INJECTED=$INJECTED");
            Launcher.ProcStarter p = launcher.launch().cmds(commandLine).stdout(listener);
            p.start().join();
            
            // Do the original stuff
            return super.compareRemoteRevisionWith(project, launcher, workspace, listener, baseline); //To change body of generated methods, choose Tools | Templates.
        }
    }
}
