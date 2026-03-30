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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the deadlock fix in {@link WorkflowRun}'s {@code GraphL} listener.
 *
 * <p>An ABBA lock ordering inversion between {@code synchronized(this)} on the
 * {@link WorkflowRun} and {@code synchronized(metadataGuard)} can cause a
 * permanent deadlock.
 *
 * <h3>Thread A &mdash; HTTP request thread (holds {@code this}, wants {@code metadataGuard})</h3>
 *
 * <p>A WebSocket agent disconnects, triggering {@code SlaveComputer.closeChannel()}
 * &rarr; {@code WorkflowRun.getExecution()}, which acquires
 * {@code synchronized(this)} and may trigger {@code CpsFlowExecution.onLoad()}
 * &rarr; {@code createPlaceholderNodes()} &rarr; recursive
 * {@code GraphL.onNewHead()} calls. When it hits a {@link FlowEndNode}, the
 * unfixed code calls {@code finish()} which needs {@code metadataGuard}
 * &mdash; <strong>blocked</strong>.
 *
 * <h3>Thread B &mdash; background thread (holds {@code metadataGuard}, wants {@code this})</h3>
 *
 * <p>A periodic task or {@code RunMap} access triggers
 * {@code WorkflowRun.onLoad()}, which acquires {@code metadataGuard} then
 * calls {@code getExecution()} which needs {@code synchronized(this)}
 * &mdash; <strong>blocked</strong>.
 *
 * <p>Additional request threads pile up on the {@code RunMap} lock held by
 * Thread B, causing cascading starvation (100+ threads blocked, UI unresponsive).
 *
 * <p>The fix reads the volatile {@code execution} field directly (avoiding
 * re-entrant {@code getExecution()} calls) and defers {@code finish()} via
 * {@code Timer.get().schedule()} when the run lock is already held, breaking
 * the lock ordering violation.
 */
@WithJenkins
class WorkflowRunDeadlockTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    private JenkinsRule r;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        r = rule;
    }

    /**
     * Reproduces the ABBA deadlock between {@code synchronized(this)} and
     * {@code synchronized(metadataGuard)} when {@code GraphL.onNewHead()}
     * receives a {@link FlowEndNode} while the run lock is held.
     *
     * <p>Thread B holds {@code metadataGuard} (as {@code onLoad()} does) while
     * Thread A holds {@code synchronized(this)} (as {@code getExecution()} does)
     * and fires {@code GraphL.onNewHead(FlowEndNode)}. Without the fix,
     * {@code finish()} tries to acquire {@code metadataGuard} inline &mdash;
     * permanent deadlock. With the fix, {@code finish()} is deferred via
     * {@code Timer.get().schedule()} when the run lock is already held.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void graphListenerShouldNotDeadlockWhenRunLockHeld() throws Exception {
        // Build a simple pipeline to completion so we have a FlowEndNode
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("echo 'hello'", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);

        FlowExecution exec = b.getExecution();
        assertNotNull(exec);
        FlowEndNode endNode = assertInstanceOf(FlowEndNode.class, exec.getCurrentHeads().get(0));

        // Access the private metadataGuard field via reflection
        Field mgField = WorkflowRun.class.getDeclaredField("metadataGuard");
        mgField.setAccessible(true);
        Object metadataGuard = mgField.get(b);
        assertNotNull(metadataGuard);

        // Instantiate the private GraphL inner class via reflection
        Class<?> graphLClass = Arrays.stream(WorkflowRun.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("GraphL"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("GraphL inner class not found"));
        Constructor<?> ctor = graphLClass.getDeclaredConstructor(WorkflowRun.class);
        ctor.setAccessible(true);
        GraphListener graphL = (GraphListener) ctor.newInstance(b);

        // Reproduce ABBA lock ordering:
        //   Thread B: onLoad() -> synchronized(metadataGuard) -> getExecution() -> wants this
        //   Thread A: getExecution() -> synchronized(this) -> onNewHead(FlowEndNode) -> finish() -> wants metadataGuard

        CountDownLatch guardAcquired = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);

        // Thread B: holds metadataGuard (as onLoad() does), would eventually need synchronized(this).
        Thread threadB = new Thread(() -> {
            synchronized (metadataGuard) {
                guardAcquired.countDown();
                try {
                    releaseGuard.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "metadataGuard-holder");
        threadB.start();
        assertTrue(guardAcquired.await(5, TimeUnit.SECONDS),
                "Thread B should have acquired metadataGuard");

        // Thread A: holds synchronized(this) (as getExecution() does) and triggers
        // GraphL.onNewHead(FlowEndNode), which would call finish() -> metadataGuard.
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean onNewHeadReturned = new AtomicBoolean(false);
        CountDownLatch graphLDone = new CountDownLatch(1);

        Thread threadA = new Thread(() -> {
            try {
                synchronized (b) {
                    graphL.onNewHead(endNode);
                    onNewHeadReturned.set(true);
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                graphLDone.countDown();
            }
        }, "run-lock-holder");
        threadA.start();

        // With the fix: finish() is deferred to Timer, so onNewHead() returns immediately.
        // Without the fix: finish() tries metadataGuard inline -> permanent ABBA deadlock.
        boolean threadACompleted = graphLDone.await(5, TimeUnit.SECONDS);

        // Release metadataGuard so the deferred finish() on the Timer thread can proceed.
        releaseGuard.countDown();
        threadB.join(5000);

        assertTrue(threadACompleted,
                "GraphL.onNewHead() should not block when the run lock is held");
        assertNull(error.get(),
                "GraphL.onNewHead() should not throw when run lock is held: " + error.get());
        assertTrue(onNewHeadReturned.get(),
                "GraphL.onNewHead() should have returned successfully");
    }
}
