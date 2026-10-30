/*
 * The MIT License
 *
 * Copyright (c) 2026, Tim Jacomb
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.job;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Node;
import hudson.model.Run;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import jenkins.model.NodeBuildsProvider;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Lists the {@link WorkflowRun}s that used a node, as recorded by a {@link WorkspaceAction} on one of their flow nodes.
 */
@Restricted(NoExternalUse.class)
@Extension
public class WorkflowNodeBuildsProvider extends NodeBuildsProvider {

    @NonNull
    @Override
    public Iterable<? extends Run<?, ?>> getCandidates(@NonNull Node node) {
        return RunList.fromJobs(Jenkins.get().allItems(WorkflowJob.class));
    }

    @Override
    public boolean usedNode(@NonNull Run<?, ?> run, @NonNull Node node) {
        if (!(run instanceof WorkflowRun)) {
            return false;
        }
        FlowExecution execution = ((WorkflowRun) run).getExecution();
        if (execution == null) {
            return false;
        }
        String nodeName = node.getNodeName();
        return new DepthFirstScanner().findFirstMatch(execution, flowNode -> {
                    WorkspaceAction action = flowNode.getPersistentAction(WorkspaceAction.class);
                    return action != null && action.getNode().equals(nodeName);
                })
                != null;
    }
}
