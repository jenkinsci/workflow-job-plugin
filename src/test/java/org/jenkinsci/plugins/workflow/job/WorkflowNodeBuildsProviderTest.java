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

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.slaves.DumbSlave;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class WorkflowNodeBuildsProviderTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void listsRunsOnTheNodesTheyUsed() throws Throwable {
        sessions.then(r -> {
            DumbSlave agent = r.createOnlineSlave();
            WorkflowJob onAgent = r.createProject(WorkflowJob.class, "onAgent");
            onAgent.setDefinition(new CpsFlowDefinition("node('" + agent.getNodeName() + "') { echo 'hi' }", true));
            WorkflowJob both = r.createProject(WorkflowJob.class, "both");
            both.setDefinition(new CpsFlowDefinition(
                    "node('built-in') { echo 'one' }; node('" + agent.getNodeName() + "') { echo 'two' }", true));
            WorkflowJob noNode = r.createProject(WorkflowJob.class, "noNode");
            noNode.setDefinition(new CpsFlowDefinition("echo 'no node'", true));
            FreeStyleProject freestyle = r.createFreeStyleProject("freestyle");
            freestyle.setAssignedNode(agent);
            r.buildAndAssertSuccess(onAgent);
            r.buildAndAssertSuccess(noNode);
            r.buildAndAssertSuccess(freestyle);
            r.buildAndAssertSuccess(both);
            assertBuilds(r);
        });
        // completed runs whose flow executions have not been loaded since startup
        sessions.then(WorkflowNodeBuildsProviderTest::assertBuilds);
    }

    private static void assertBuilds(JenkinsRule r) throws Exception {
        Computer agent = r.jenkins.getComputer("slave0");
        List<String> onAgentBuilds = List.of("both #1", "freestyle #1", "onAgent #1");
        List<String> builtInBuilds = List.of("both #1");
        assertEquals(onAgentBuilds, names(agent.getBuilds()));
        assertEquals(builtInBuilds, names(r.jenkins.toComputer().getBuilds()));

        JenkinsRule.WebClient wc = r.createWebClient();
        assertEquals(onAgentBuilds, names(wc, agent));
        assertEquals(builtInBuilds, names(wc, r.jenkins.toComputer()));
    }

    private static List<String> names(Iterable<? extends Run<?, ?>> runs) {
        List<String> names = new ArrayList<>();
        for (Run<?, ?> run : runs) {
            names.add(run.getFullDisplayName());
        }
        return names;
    }

    private static List<String> names(JenkinsRule.WebClient wc, Computer computer) throws Exception {
        JSONArray builds = wc.getJSON(computer.getUrl() + "buildList")
                .getJSONObject()
                .getJSONObject("data")
                .getJSONArray("builds");
        List<String> names = new ArrayList<>();
        for (Object build : builds) {
            JSONObject json = (JSONObject) build;
            names.add(json.getString("parentFullDisplayName") + " " + json.getString("displayName"));
        }
        return names;
    }
}
