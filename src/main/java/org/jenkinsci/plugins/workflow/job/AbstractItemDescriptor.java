package org.jenkinsci.plugins.workflow.job;

import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

public interface AbstractItemDescriptor {
    @Restricted(NoExternalUse.class)
    public default FormValidation doCheckDisplayNameOrNull(@AncestorInPath WorkflowJob job, @QueryParameter String value) {
        return Jenkins.get().doCheckDisplayName(value, job.getName());
    }
}
