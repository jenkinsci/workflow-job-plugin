package org.jenkinsci.plugins.workflow.job.properties;

import hudson.Extension;
import jenkins.model.OptionalJobProperty;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.WorkflowJobProperty;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;

/**
 * Hint about the desired {@link FlowDurabilityHint}.
 * Note that setting {@link DisableResumeJobProperty} overrides this to {@link FlowDurabilityHint#NO_PROMISES}.
 * @author Sam Van Oort
 */
public class DurabilityHintProperty extends WorkflowJobProperty {
    private final FlowDurabilityHint hint;

    public FlowDurabilityHint getHint() {
        return hint;
    }

    @DataBoundConstructor
    public DurabilityHintProperty(@Nonnull FlowDurabilityHint hint) {
        this.hint = hint;
    }

    @Extension
    @Symbol("durabilityHint")
    public static class DescriptorImpl extends OptionalJobProperty.OptionalJobPropertyDescriptor {

        @Override public String getDisplayName() {
            return "How hard should we try to render the pipeline nondurable?";
        }

    }
}
