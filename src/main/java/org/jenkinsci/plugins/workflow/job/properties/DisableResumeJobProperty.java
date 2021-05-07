package org.jenkinsci.plugins.workflow.job.properties;

import hudson.Extension;
import hudson.model.Item;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.flow.DurabilityHintProvider;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Disables resuming a pipeline if the controller restarts - the run will simply fail instead, just like a FreeStyle job.
 * @author Sam Van Oort
 */
public class DisableResumeJobProperty extends OptionalJobProperty<WorkflowJob> {
    @DataBoundConstructor
    public DisableResumeJobProperty(){ }

    @Extension
    @Symbol("disableResume")
    public static class DescriptorImpl extends OptionalJobPropertyDescriptor implements DurabilityHintProvider{

        @Override public String getDisplayName() {
            return Messages.do_not_allow_resume_if_master_restarts();
        }

        @Override
        public int ordinal() {
            return 50;
        }

        @CheckForNull
        @Override
        public FlowDurabilityHint suggestFor(@Nonnull Item x) {
            if (x instanceof WorkflowJob) {
                DisableResumeJobProperty prop = ((WorkflowJob) x).getProperty(DisableResumeJobProperty.class);
                return (prop != null) ? FlowDurabilityHint.PERFORMANCE_OPTIMIZED : null;
            }
            return null;
        }
    }
}
