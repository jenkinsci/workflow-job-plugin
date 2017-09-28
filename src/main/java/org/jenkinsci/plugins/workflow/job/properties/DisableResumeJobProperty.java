package org.jenkinsci.plugins.workflow.job.properties;

import hudson.Extension;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Disables resuming a pipeline if the master restarts - the run will simply fail instead, just like a FreeStyle job.
 * @author Sam Van Oort
 */
public class DisableResumeJobProperty extends OptionalJobProperty<WorkflowJob> {
    @DataBoundConstructor
    public DisableResumeJobProperty(){ }

    @Extension
    @Symbol("disableResume")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor {

        @Override public String getDisplayName() {
            return "Do not allow the pipeline to resume if the master restarts.";
        }

    }
}
