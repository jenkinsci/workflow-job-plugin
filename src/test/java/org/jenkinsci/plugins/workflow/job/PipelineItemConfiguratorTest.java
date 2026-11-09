package org.jenkinsci.plugins.workflow.job;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.Rule;
import org.junit.Test;

import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PipelineItemConfiguratorTest {

	@Rule
	public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

	@Test
	@ConfiguredWithCode("create-pipeline.yaml")
	public void shouldCreateNewPipeline() {
		WorkflowJob job = (WorkflowJob) Jenkins.get().getItem("my-new-pipeline");

		assertNotNull("Pipeline job should have been created by JCasC", job);
		assertEquals("A brand new pipeline created by JCasC", job.getDescription());

		assertTrue("Definition should be CpsFlowDefinition", job.getDefinition() instanceof CpsFlowDefinition);
		CpsFlowDefinition definition = (CpsFlowDefinition) job.getDefinition();
		assertEquals("node { echo 'Hello, JCasC!' }", definition.getScript());
		assertTrue("Sandbox should be true", definition.isSandbox());
	}

	@Test
	public void shouldUpdateExistingPipeline() throws Exception {
		WorkflowJob existingJob = j.jenkins.createProject(WorkflowJob.class, "existing-pipeline");
		existingJob.setDescription("Old Description");
		existingJob.setDefinition(new CpsFlowDefinition("node { echo 'Old script' }", false));

		ConfigurationAsCode.get().configure(
			Objects.requireNonNull(getClass().getResource("update-pipeline.yaml")).toExternalForm()
		);

		WorkflowJob updatedJob = (WorkflowJob) Jenkins.get().getItem("existing-pipeline");

		assertNotNull(updatedJob);
		assertEquals("Updated description via JCasC", updatedJob.getDescription());

		CpsFlowDefinition definition = (CpsFlowDefinition) updatedJob.getDefinition();
		assertEquals("node { echo 'Updated script!' }", definition.getScript());
		assertTrue("Sandbox should be reset to true", definition.isSandbox());
	}
}