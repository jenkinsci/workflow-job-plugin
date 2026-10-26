package org.jenkinsci.plugins.workflow.job;

import hudson.Extension;
import hudson.model.Descriptor;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.ItemConfigurator;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;

import java.io.IOException;

@Extension
public class PipelineItemConfigurator implements ItemConfigurator<WorkflowJob> {

	@Override
	public String getName() {
		return "pipeline";
	}

	@Override
	public Class<WorkflowJob> getTarget() {
		return WorkflowJob.class;
	}

	@Override
	public WorkflowJob configure(String name, CNode config, ConfigurationContext context) throws ConfiguratorException {
		try {
			Jenkins jenkins = Jenkins.get();
			WorkflowJob job = (WorkflowJob) jenkins.getItem(name);

			if (job == null) {
				job = jenkins.createProject(WorkflowJob.class, name);
			}

			Mapping mapping = config.asMapping();

			if (mapping.containsKey("description")) {
				job.setDescription(mapping.getScalarValue("description"));
			}

			CNode definitionNode = mapping.get("definition");
			if (definitionNode != null) {
				Mapping defMapping = definitionNode.asMapping();
				if (defMapping.containsKey("cps")) {
					String script = defMapping.get("cps").asMapping().getScalarValue("script");
					job.setDefinition(new CpsFlowDefinition(script, true));
				}
			}

			job.save();
			return job;

		} catch (IOException | Descriptor.FormException e) {
			throw new ConfiguratorException("Failed to configure pipeline: " + name, e);
		}
	}
}