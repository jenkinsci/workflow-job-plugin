/*
 * The MIT License
 *
 * Copyright 2026 CloudBees, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.workflowRunOnLoadTest.WeirdAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.RealJenkinsExtension;

/**
 * Regression test for {@link WorkflowRun#onLoad} behavior when basic fields are corrupt.
 */
final class WorkflowRunOnLoadTest {

    @RegisterExtension
    private final RealJenkinsExtension rj = new RealJenkinsExtension()
            .addSyntheticPlugin(
                    new RealJenkinsExtension.SyntheticPlugin(WeirdAction.class).shortName("WorkflowRunOnLoadTest"));

    @Test
    void brokenDeser() throws Throwable {
        rj.then(j -> {
            var p = j.jenkins.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("// OK", true));
            var b = j.buildAndAssertSuccess(p);
            assertThat(b.getAction(WeirdAction.class), notNullValue());
        });
        rj.then(j -> {
            var p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            var b = p.getBuildByNumber(1);
            if (b != null) {
                var xml = b.getRootDir().toPath().resolve("build.xml");
                fail("did not expect to be able to load " + xml + "\n" + Files.readString(xml));
            }
            // could also assert message from RunMap.retrieve
        });
        rj.javaOptions("-Dfixed=true");
        rj.then(j -> {
            var p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
            var b = p.getBuildByNumber(1);
            assertThat("now it should deser OK", b, notNullValue());
            Files.copy(b.getRootDir().toPath().resolve("build.xml"), System.out);
            assertThat("WeirdAction should still be on disk", b.getAction(WeirdAction.class), notNullValue());
            assertThat("timestamp must be non-zero after restart", b.getStartTimeInMillis(), greaterThan(0L));
        });
    }
}
