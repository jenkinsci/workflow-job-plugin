package org.jenkinsci.plugins.workflow.job.properties;

import hudson.model.JobProperty;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.OutputStream;

/**
 * Abstract property to allow pipeline output decoration
 */
public abstract class LoggerDecorationJobProperty extends JobProperty<WorkflowJob> {

    /**
     * Decorate output stream with additional logic.
     *
     * @param job Workflow job that the property needs to be applied to
     * @param run Execution run to be decorated
     * @param logger Output logger to be wrapped
     * @return replacement logger with decoration added
     */
    public abstract OutputStream decorateLogger(WorkflowJob job, WorkflowRun run, OutputStream logger);
}
