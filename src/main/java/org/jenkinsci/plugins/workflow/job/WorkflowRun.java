/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.BulkChange;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.XmlFile;
import hudson.console.AnnotatedLargeText;
import hudson.console.LineTransformationOutputStream;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.model.listeners.RunListener;
import hudson.model.listeners.SCMListener;
import hudson.model.listeners.SaveableListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.security.ACL;
import hudson.slaves.NodeProperty;
import hudson.util.DaemonThreadFactory;
import hudson.util.Iterators;
import hudson.util.NamingThreadFactory;
import hudson.util.NullStream;
import hudson.util.PersistedList;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.lazy.BuildReference;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.scm.RunWithSCM;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.BlockableResume;
import org.jenkinsci.plugins.workflow.flow.DurabilityHintProvider;
import org.jenkinsci.plugins.workflow.flow.FlowCopier;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.console.WorkflowConsoleLogger;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.PipelineIOUtils;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;
import org.jenkinsci.plugins.workflow.support.concurrent.WithThreadName;
import org.jenkinsci.plugins.workflow.support.steps.input.POSTHyperlinkNote;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

@SuppressWarnings("SynchronizeOnNonFinalField")
@SuppressFBWarnings(value="RC_REF_COMPARISON_BAD_PRACTICE_BOOLEAN", justification="We need to appropriately handle null completion states from legacy builds.")
public final class WorkflowRun extends Run<WorkflowJob,WorkflowRun> implements FlowExecutionOwner.Executable, LazyBuildMixIn.LazyLoadingRun<WorkflowJob,WorkflowRun>, RunWithSCM<WorkflowJob,WorkflowRun> {

    private static final Logger LOGGER = Logger.getLogger(WorkflowRun.class.getName());

    private enum StopState {
        TERM, KILL;

        public String url() {
            return this.name().toLowerCase(Locale.ENGLISH);
        }
    }

    /** Null until started, or after serious failures or hard kill. */
    @CheckForNull FlowExecution execution; // Not private for test use only

    /**
     * {@link Future} that yields {@link #execution}, when it is fully configured and ready to be exposed.
     */
    @CheckForNull
    private transient SettableFuture<FlowExecution> executionPromise = SettableFuture.create();

    private transient final LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun> runMixIn = new LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun>() {
        @Override protected WorkflowRun asRun() {
            return WorkflowRun.this;
        }
    };
    private transient StreamBuildListener listener;

    private transient boolean allowTerm;

    private transient boolean allowKill;

    /** Controls whether or not our execution has been initialized via its {@link FlowExecution#onLoad(FlowExecutionOwner)} method yet.*/
    transient boolean executionLoaded = false;  // NonPrivate for tests

    /**
     * Cumulative list of people who contributed to the build problem.
     *
     * <p>
     * This is a list of {@link User#getId() user ids} who made a change
     * since the last non-broken build. Can be null (which should be
     * treated like empty set), because of the compatibility.
     *
     * <p>
     * This field is semi-final --- once set the value will never be modified.
     */
    private volatile Set<String> culprits;

    /**
     * Flag for whether or not the build has completed somehow.
     * This was previously a transient field, so we may need to recompute in {@link #onLoad} based on {@link FlowExecution#isComplete}.
     */
    Boolean completed;  // Non-private for testing

    private transient Object logCopyGuard = new Object();

    /** map from node IDs to log positions from which we should copy text */
    Map<String,Long> logsToCopy;  // Exposed for testing

    /** JENKINS-26761: supposed to always be set but sometimes is not. Access only through {@link #checkouts(TaskListener)}. */
    private @CheckForNull List<SCMCheckout> checkouts;
    // TODO could use a WeakReference to reduce memory, but that complicates how we add to it incrementally; perhaps keep a List<WeakReference<ChangeLogSet<?>>>
    private transient List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets;

    /** True when first started, false when running after a restart. */
    private transient boolean firstTime;

    private synchronized Object getLogCopyGuard() {
        if (logCopyGuard == null) {
            logCopyGuard = new Object();
        }
        return logCopyGuard;
    }

    /** Avoids creating new instances, analogous to {@link TaskListener#NULL} but as full StreamBuildListener. */
    static final StreamBuildListener NULL_LISTENER = new StreamBuildListener(new NullStream());

    /** Used internally to ensure listener has been initialized correctly. */
    StreamBuildListener getListener() {
        // Un-synchronized to prevent deadlocks (combination of run and logCopyGuard) until the log-handling rewrite removes the log copying
        // Note that in portions where multithreaded access is possible we are already synchronizing on logCopyGuard
        if (listener == null) {
            try {
                OutputStream logger = new FileOutputStream(getLogFile(), true);
                listener = new StreamBuildListener(logger, Charset.defaultCharset());
            } catch (FileNotFoundException fnf) {
                LOGGER.log(Level.WARNING, "Error trying to open build log file for writing, output will be lost: "+getLogFile(), fnf);
                return NULL_LISTENER;
            }
        }
        return listener;
    }

    public WorkflowRun(WorkflowJob job) throws IOException {
        super(job);
        firstTime = true;
        checkouts = new PersistedList<>(this);
        //System.err.printf("created %s @%h%n", this, this);
    }

    public WorkflowRun(WorkflowJob job, File dir) throws IOException {
        super(job, dir);
        //System.err.printf("loaded %s @%h%n", this, this);
    }

    @Override public LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun> getRunMixIn() {
        return runMixIn;
    }

    @Override protected BuildReference<WorkflowRun> createReference() {
        return getRunMixIn().createReference();
    }

    @Override protected void dropLinks() {
        getRunMixIn().dropLinks();
    }

    @Exported
    @Override public WorkflowRun getPreviousBuild() {
        return getRunMixIn().getPreviousBuild();
    }

    @Exported
    @Override public WorkflowRun getNextBuild() {
        return getRunMixIn().getNextBuild();
    }

    /**
     * Actually executes the workflow.
     */
    @Override public void run() {
        if (!firstTime) {
            throw sleep();
        }
        try {
            onStartBuilding();
            StreamBuildListener myListener = getListener();
            myListener.started(getCauses());
            Authentication auth = Jenkins.getAuthentication();
            if (!auth.equals(ACL.SYSTEM)) {
                String name = auth.getName();
                if (!auth.equals(Jenkins.ANONYMOUS)) {
                    name = ModelHyperlinkNote.encodeTo(User.get(name));
                }
                myListener.getLogger().println(hudson.model.Messages.Run_running_as_(name));
            }
            RunListener.fireStarted(this, myListener);
            updateSymlinks(myListener);
            FlowDefinition definition = getParent().getDefinition();
            if (definition == null) {
                throw new AbortException("No flow definition, cannot run");
            }

            Owner owner = new Owner(this);
            FlowExecution newExecution = definition.create(owner, myListener, getAllActions());

            boolean loggedHintOverride = false;
            if (newExecution instanceof BlockableResume) {
                boolean blockResume = getParent().isResumeBlocked();
                ((BlockableResume) newExecution).setResumeBlocked(blockResume);
                if (blockResume) {
                    listener.getLogger().println("Resume disabled by user, switching to high-performance, low-durability mode.");
                    loggedHintOverride = true;
                }
            }
            if (!loggedHintOverride) {  // Avoid double-logging
                myListener.getLogger().println("Running in Durability level: "+DurabilityHintProvider.suggestedFor(this.project));
            }
            save();  // Save before we add to the FlowExecutionList, to ensure we never have a run with a null build.
            synchronized (getLogCopyGuard()) {  // Technically safe but it makes FindBugs happy
                FlowExecutionList.get().register(owner);
                newExecution.addListener(new GraphL());
                completed = Boolean.FALSE;
                logsToCopy = new ConcurrentSkipListMap<>();
                executionLoaded = true;
                execution = newExecution;
            }
            SettableFuture<FlowExecution> exec = getSettableExecutionPromise();
            if (!exec.isDone()) {
                exec.set(newExecution);
            }
            newExecution.start();  // We should probably have the promise set before beginning, no?
            FlowExecutionListener.fireRunning(newExecution);

        } catch (Throwable x) {
            execution = null; // ensures isInProgress returns false
            finish(Result.FAILURE, x);
            try {
                SettableFuture<FlowExecution> exec = getSettableExecutionPromise();
                if (!exec.isDone()) {
                    exec.setException(x);
                }
            } catch (Error e) {
                if (e != x) { // cf. CpsThread.runNextChunk
                    throw e;
                }
            }
            return;
        }
        throw sleep();
    }

    private static ScheduledExecutorService copyLogsExecutorService;
    private static synchronized ScheduledExecutorService copyLogsExecutorService() {
        if (copyLogsExecutorService == null) {
            copyLogsExecutorService = new /*ErrorLogging*/ScheduledThreadPoolExecutor(5, new NamingThreadFactory(new DaemonThreadFactory(), "WorkflowRun.copyLogs"));
        }
        return copyLogsExecutorService;
    }
    private AsynchronousExecution sleep() {
        final AsynchronousExecution asynchronousExecution = new AsynchronousExecution() {
            @Override public void interrupt(boolean forShutdown) {
                if (forShutdown) {
                    return;
                }
                Timer.get().submit(new Runnable() {
                    @Override public void run() {
                        if (execution == null) {
                            return;
                        }
                        Executor executor = getExecutor();
                        if (executor == null) {
                            LOGGER.log(Level.WARNING, "Lost executor for {0}", WorkflowRun.this);
                            return;
                        }
                        try {
                            Collection<CauseOfInterruption> causes = executor.getCausesOfInterruption();
                            execution.interrupt(executor.abortResult(), causes.toArray(new CauseOfInterruption[causes.size()]));
                        } catch (Exception x) {
                            LOGGER.log(Level.WARNING, null, x);
                        }
                        executor.recordCauseOfInterruption(WorkflowRun.this, getListener());
                        printLater(StopState.TERM, "Click here to forcibly terminate running steps");
                    }
                });
            }
            @Override public boolean blocksRestart() {
                return execution != null && getExecution().blocksRestart();
            }
            @Override public boolean displayCell() {
                return blocksRestart();
            }
        };
        final AtomicReference<ScheduledFuture<?>> copyLogsTask = new AtomicReference<>();
        copyLogsTask.set(copyLogsExecutorService().scheduleAtFixedRate(new Runnable() {
            @Override public void run() {
                synchronized (getLogCopyGuard()) {
                    if (completed == null) {
                        // Loading run, give it a moment.
                        return;
                    }
                    if (completed) {
                        asynchronousExecution.completed(null);
                        copyLogsTask.get().cancel(false);
                        return;
                    }
                    Jenkins jenkins = Jenkins.getInstance();
                    if (jenkins == null || jenkins.isTerminating()) {
                        LOGGER.log(Level.FINE, "shutting down, breaking waitForCompletion on {0}", this);
                        // Stop writing content, in case a new set of objects gets loaded after in-VM restart and starts writing to the same file:
                        getListener().closeQuietly();
                        listener = NULL_LISTENER;
                        return;
                    }
                    try (WithThreadName naming = new WithThreadName(" (" + WorkflowRun.this + ")")) {
                        copyLogs();
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS));
        return asynchronousExecution;
    }

    private void printLater(final StopState state, final String message) {
        Timer.get().schedule(new Runnable() {
            @Override public void run() {
                if (!isInProgress()) {
                    return;
                }
                switch (state) {
                    case TERM:
                        allowTerm = true;
                        break;
                    case KILL:
                        allowKill = true;
                        break;
                }
                getListener().getLogger().println(POSTHyperlinkNote.encodeTo("/" + getUrl() + state.url(), message));
            }
        }, 15, TimeUnit.SECONDS);
    }

    /** Sends {@link StepContext#onFailure} to all running (leaf) steps. */
    @RequirePOST
    public void doTerm() {
        checkPermission(Item.CANCEL);
        if (!isInProgress() || /* redundant, but make FindBugs happy */ execution == null) {
            return;
        }
        final Throwable x = new FlowInterruptedException(Result.ABORTED);
        Futures.addCallback(getExecution().getCurrentExecutions(/* cf. JENKINS-26148 */true), new FutureCallback<List<StepExecution>>() {
            @Override public void onSuccess(List<StepExecution> l) {
                for (StepExecution e : Iterators.reverse(l)) {
                    StepContext context = e.getContext();
                    context.onFailure(x);
                    try {
                        FlowNode n = context.get(FlowNode.class);
                        if (n != null) {
                            getListener().getLogger().println("Terminating " + n.getDisplayFunctionName());
                        }
                    } catch (Exception x) {
                        LOGGER.log(Level.FINE, null, x);
                    }
                }
            }
            @Override public void onFailure(Throwable t) {}
        });
        printLater(StopState.KILL, "Click here to forcibly kill entire build");
    }

    /** Immediately kills the build. */
    @RequirePOST
    public void doKill() {
        checkPermission(Item.CANCEL);
        if (!isBuilding() || /* probably redundant, but just to be sure */ execution == null) {
            return;
        }
        synchronized (getLogCopyGuard()) {
            getListener().getLogger().println("Hard kill!");
        }
        execution = null; // ensures isInProgress returns false
        FlowInterruptedException suddenDeath = new FlowInterruptedException(Result.ABORTED);
        finish(Result.ABORTED, suddenDeath);
        getSettableExecutionPromise().setException(suddenDeath);
        // TODO CpsFlowExecution.onProgramEnd does some cleanup which we cannot access here; perhaps need a FlowExecution.halt(Throwable) API?
    }

    @Override public EnvVars getEnvironment(TaskListener listener) throws IOException, InterruptedException {
        EnvVars env = super.getEnvironment(listener);

        Jenkins instance = Jenkins.getInstance();
        if (instance != null) {
            for (NodeProperty nodeProperty : instance.getGlobalNodeProperties()) {
                nodeProperty.buildEnvVars(env, listener);
            }
        }
        // TODO EnvironmentContributingAction does not support Job yet:
        ParametersAction a = getAction(ParametersAction.class);
        if (a != null) {
            for (ParameterValue v : a) {
                v.buildEnvironment(this, env);
            }
        }

        EnvVars.resolve(env);
        return env;
    }

    @Restricted(DoNotUse.class) // Jelly
    public boolean hasAllowTerm() {
        return isBuilding() && allowTerm;
    }

    @Restricted(DoNotUse.class) // Jelly
    public boolean hasAllowKill() {
        return isBuilding() && allowKill;
    }

    @GuardedBy("logCopyGuard")
    private void copyLogs() {
        if (logsToCopy == null) { // finished
            return;
        }
        if (logsToCopy instanceof LinkedHashMap) { // upgrade while build is running
            logsToCopy = new ConcurrentSkipListMap<>(logsToCopy);
        }
        boolean modified = false;
        for (Map.Entry<String,Long> entry : logsToCopy.entrySet()) {
            String id = entry.getKey();
            FlowNode node;
            try {
                if (execution == null) {
                    return; // broken somehow
                }
                node = getExecution().getNode(id);
            } catch (IOException x) {
                LOGGER.log(Level.WARNING, null, x);
                logsToCopy.remove(id);
                modified = true;
                continue;
            }
            if (node == null) {
                LOGGER.log(Level.WARNING, "no such node {0}", id);
                logsToCopy.remove(id);
                modified = true;
                continue;
            }
            LogAction la = node.getAction(LogAction.class);
            if (la != null) {
                AnnotatedLargeText<? extends FlowNode> logText = la.getLogText();
                try {
                    long old = entry.getValue();
                    OutputStream logger;

                    String prefix = getBranchName(node);
                    if (prefix != null) {
                        logger = new LogLinePrefixOutputFilter(getListener().getLogger(), "[" + prefix + "] ");
                    } else {
                        logger = getListener().getLogger();
                    }

                    try {
                        long revised = writeRawLogTo(logText, old, logger);
                        if (revised != old) {
                            logsToCopy.put(id, revised);
                            modified = true;
                        }
                        if (logText.isComplete()) {
                            writeRawLogTo(logText, revised, logger); // defend against race condition?
                            assert !node.isRunning() : "LargeText.complete yet " + node + " claims to still be running";
                            logsToCopy.remove(id);
                            modified = true;
                        }
                    } finally {
                        if (prefix != null) {
                            ((LogLinePrefixOutputFilter)logger).forceEol();
                        }
                    }
                } catch (IOException x) {
                    LOGGER.log(Level.WARNING, null, x);
                    logsToCopy.remove(id);
                    modified = true;
                }
            } else if (!node.isRunning()) {
                logsToCopy.remove(id);
                modified = true;
            }
        }
        if (modified) {
            try {
                if (this.execution == null || this.execution.getDurabilityHint().isPersistWithEveryStep()) {
                    save();
                }
            } catch (IOException x) {
                LOGGER.log(Level.WARNING, null, x);
            }
        }
    }
    private long writeRawLogTo(AnnotatedLargeText<?> text, long start, OutputStream out) throws IOException {
        long len = text.length();
        if (start > len) {
            LOGGER.log(Level.WARNING, "JENKINS-37664: attempt to copy logs in {0} @{1} past end @{2}", new Object[] {this, start, len});
            return len;
        } else {
            return text.writeRawLogTo(start, out);
        }
    }

    @GuardedBy("logCopyGuard")
    private transient Cache<FlowNode,Optional<String>> branchNameCache;  // TODO Consider making this a top-level FlowNode API

    private Cache<FlowNode, Optional<String>> getBranchNameCache() {
        synchronized (getLogCopyGuard()) {
            if (branchNameCache == null) {
                branchNameCache = CacheBuilder.newBuilder().weakKeys().build();
            }
            return branchNameCache;
        }
    }

    private @CheckForNull String getBranchName(FlowNode node) {
        Cache<FlowNode, Optional<String>> cache = getBranchNameCache();

        Optional<String> output = cache.getIfPresent(node);
        if (output != null) {
            return output.orNull();
        }

        // We must explicitly check for the current node being the start/end of a parallel branch
        if (node instanceof BlockEndNode) {
            output = Optional.fromNullable(getBranchName(((BlockEndNode) node).getStartNode()));
            cache.put(node, output);
            return output.orNull();
        } else if (node instanceof BlockStartNode) { // And of course this node might be the start of a parallel branch
            ThreadNameAction threadNameAction = node.getPersistentAction(ThreadNameAction.class);
            if (threadNameAction != null) {
                String name = threadNameAction.getThreadName();
                cache.put(node, Optional.of(name));
                return name;
            }
        }

        // Check parent which will USUALLY result in a cache hit, but improve performance and avoid a stack overflow by not doing recursion
        List<FlowNode> parents = node.getParents();
        if (!parents.isEmpty()) {
            FlowNode parent = parents.get(0);
            output = cache.getIfPresent(parent);
            if (output != null) {
                cache.put(node, output);
                return output.orNull();
            }
        }

        // Fall back to looking for an enclosing parallel branch... but using more efficient APIs and avoiding stack overflows
        output = Optional.absent();
        for (BlockStartNode myNode : node.iterateEnclosingBlocks()) {
            ThreadNameAction threadNameAction = myNode.getPersistentAction(ThreadNameAction.class);
            if (threadNameAction != null) {
                output = Optional.of(threadNameAction.getThreadName());
                break;
            }
        }
        cache.put(node, output);
        return output.orNull();
    }

    private static final class LogLinePrefixOutputFilter extends LineTransformationOutputStream {

        private final PrintStream logger;
        private final String prefix;

        protected LogLinePrefixOutputFilter(PrintStream logger, String prefix) {
            this.logger = logger;
            this.prefix = prefix;
        }

        @Override
        protected void eol(byte[] b, int len) throws IOException {
            logger.append(prefix);
            logger.write(b, 0, len);
        }
    }
    
    private static final Map<String,WorkflowRun> LOADING_RUNS = new HashMap<>();

    private String key() {
        return getParent().getFullName() + '/' + getId();
    }

    /** Hack to allow {@link #execution} to use an {@link Owner} referring to this run, even when it has not yet been loaded. */
    @Override public void reload() throws IOException {
        synchronized (LOADING_RUNS) {
            LOADING_RUNS.put(key(), this);
        }

        // super.reload() forces result to be FAILURE, so working around that
        new XmlFile(XSTREAM,new File(getRootDir(),"build.xml")).unmarshal(this);
    }

    @Override protected void onLoad() {
        try {
            synchronized (getLogCopyGuard()) {  // CHECKME: Deadlock risks here - copyLogGuard and locks on Run
                if (executionLoaded) {
                    LOGGER.log(Level.WARNING, "Double onLoad of build "+this);
                    return;
                }
                boolean needsToPersist = completed == null;
                super.onLoad();

                if (completed == Boolean.TRUE && result == null) {
                    LOGGER.log(Level.FINE, "Completed build with no result set, defaulting to failure for"+this);
                    setResult(Result.FAILURE);
                    needsToPersist = true;
                }

                // TODO See if we can simplify this, especially around interactions with 'completed'.

                if (execution != null && completed != Boolean.TRUE) {
                    FlowExecution fetchedExecution = getExecution();  // Triggers execution.onLoad so we can resume running if not done

                    if (fetchedExecution != null) {
                        if (completed == null) {
                            completed = Boolean.valueOf(fetchedExecution.isComplete());
                        }

                        if (!completed == Boolean.TRUE) {
                            // we've been restarted while we were running. let's get the execution going again.
                            FlowExecutionListener.fireResumed(fetchedExecution);

                            getListener().getLogger().println("Resuming build at " + new Date() + " after Jenkins restart");
                            Timer.get().submit(() -> Queue.getInstance().schedule(new AfterRestartTask(WorkflowRun.this), 0)); // JENKINS-31614
                        }
                    } else {   // Execution nulled due to a critical failure, explicitly mark completed
                        completed = Boolean.TRUE;
                    }
                } else if (execution == null) {
                    completed = Boolean.TRUE;
                }
                if (needsToPersist && completed) {
                    try {
                        save();
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Error while saving build to update completed flag", ex);
                    }
                }
            }
        } finally {  // Ensure the run is ALWAYS removed from loading even if something failed, so threads awaken.
            checkouts(null); // only for diagnostics
            synchronized (LOADING_RUNS) {
                LOADING_RUNS.remove(key()); // or could just make the value type be WeakReference<WorkflowRun>
                LOADING_RUNS.notifyAll();
            }
        }
    }

    // Overridden since super version has an unwanted assertion about this.state, which we do not use.
    @Override public void setResult(Result r) {
        if (result == null || r.isWorseThan(result)) {
            result = r;
            LOGGER.log(Level.FINE, this + " in " + getRootDir() + ": result is set to " + r, LOGGER.isLoggable(Level.FINER) ? new Exception() : null);
        }
    }

    /** Handles normal build completion (including errors) but also handles the case that the flow did not even start correctly, for example due to an error in {@link FlowExecution#start}. */
    private void finish(@Nonnull Result r, @CheckForNull Throwable t) {
        boolean nullListener = false;
        synchronized (getLogCopyGuard()) {
            nullListener = listener == null;
            setResult(r);
            completed = Boolean.TRUE;
            duration = Math.max(0, System.currentTimeMillis() - getStartTimeInMillis());
        }
        logsToCopy = null;
        branchNameCache = null;
        try {
            LOGGER.log(Level.INFO, "{0} completed: {1}", new Object[]{toString(), getResult()});
            if (nullListener) {
                // Never even made it to running, either failed when fresh-started or resumed -- otherwise getListener would have run
                LOGGER.log(Level.WARNING, this + " failed to start", t);
            } else {
                RunListener.fireCompleted(WorkflowRun.this, getListener());
                if (t instanceof AbortException) {
                    getListener().error(t.getMessage());
                } else if (t instanceof FlowInterruptedException) {
                    ((FlowInterruptedException) t).handle(this, getListener());
                } else if (t != null) {
                    Functions.printStackTrace(t, getListener().getLogger());
                }
                getListener().finished(getResult());
                getListener().closeQuietly();
            }
            logsToCopy = null;
            try {
                save();
            } catch (Exception x) {
                LOGGER.log(Level.WARNING, "failed to save " + this, x);
            }
            Timer.get().submit(() -> {
                try {
                    getParent().logRotate();
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "failed to perform log rotation after " + this, x);
                }
            });
            onEndBuilding();
        } finally {  // Ensure this is ALWAYS removed from FlowExecutionList
            FlowExecutionList.get().unregister(new Owner(this));
        }

        try {
            StashManager.maybeClearAll(this);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "failed to clean up stashes from " + this, x);
        }
        FlowExecution exec = getExecution();
        if (exec != null) {
            FlowExecutionListener.fireCompleted(exec);
        }
    }

    @Override public void deleteArtifacts() throws IOException {
        super.deleteArtifacts();
        StashManager.clearAll(this);
    }

    /**
     * Gets the associated execution state, and do a more expensive loading operation if not initialized.
     * Performs all the needed initialization for the execution pre-loading too -- sets the executionPromise, adds Listener, calls onLoad on it etc.
     * @return non-null after the flow has started, even after finished (but may be null temporarily when about to start, or if starting failed)
     */
    public synchronized @CheckForNull FlowExecution getExecution() {
        if (executionLoaded || execution == null) {
            return execution;
        } else {  // Try to lazy-load execution
            FlowExecution fetchedExecution = execution;
            try {
                if (fetchedExecution instanceof BlockableResume) {
                    BlockableResume blockableExecution = (BlockableResume)execution;
                    boolean parentBlocked = getParent().isResumeBlocked();
                    if (parentBlocked != blockableExecution.isResumeBlocked()) {  // Avoids issues with WF-CPS versions before JENKINS-49961 patch
                        blockableExecution.setResumeBlocked(parentBlocked);
                    }
                }
                GraphListener finishListener = null;
                if (this.completed != Boolean.TRUE) {
                    finishListener = new FailOnLoadListener();
                    fetchedExecution.addListener(finishListener);  // So we can still ensure build finishes if onLoad generates a FlowEndNode
                }
                fetchedExecution.onLoad(new Owner(this));
                if (this.completed != Boolean.TRUE) {
                    // Defer the normal listener to ensure onLoad can complete before finish() is called since that may
                    // need the build to be loaded and can result in loading loops otherwise.
                    fetchedExecution.removeListener(finishListener);
                    fetchedExecution.addListener(new GraphL());
                }
                SettableFuture<FlowExecution> settablePromise = getSettableExecutionPromise();
                if (!settablePromise.isDone()) {
                    settablePromise.set(fetchedExecution);
                }
                executionLoaded = true;
                return fetchedExecution;
            } catch (Exception x) {
                if (result == null) {
                    setResult(Result.FAILURE);
                }
                LOGGER.log(Level.WARNING, "Nulling out FlowExecution due to error in build "+this.getFullDisplayName(), x);
                execution = null; // probably too broken to use
                executionLoaded = true;
                try {
                    save();  // Ensure we do not try to load again
                } catch (IOException ioe) {
                    LOGGER.log(Level.WARNING, "Error saving build to record irrecoverable FlowExecution");
                }
                return null;
            }
        }
    }

    /**
     * Allows the caller to block on {@link FlowExecution}, which gets created relatively quickly
     * after the build gets going.
     */
    @Nonnull
    public ListenableFuture<FlowExecution> getExecutionPromise() {
        return getSettableExecutionPromise();
    }

    /** Initializes and returns the executionPromise to avoid null risk */
    @Nonnull
    private SettableFuture<FlowExecution> getSettableExecutionPromise() {
        synchronized(this) {
            if (executionPromise == null) {
                executionPromise = SettableFuture.create();
            }
            return executionPromise;
        }
    }

    @Override public FlowExecutionOwner asFlowExecutionOwner() {
        return new Owner(this);
    }

    @Override
    public boolean hasntStartedYet() {
        return result == null && execution==null;
    }

    @Override public boolean isBuilding() {
        return result == null || isInProgress();
    }

    @Exported
    @Override protected boolean isInProgress() {
        if (completed == Boolean.TRUE) {  // Has a persisted completion state
            return false;
        } else {
            // This may seem gratuitous but we MUST to check the execution in case 'completed' has not been set yet
            // thus avoiding some (rare but possible) race conditions
            FlowExecution exec = getExecution();
            return exec != null && !exec.isComplete();
        }
    }

    @Override public boolean isLogUpdated() {
        return isBuilding(); // there is no equivalent to a post-production state for flows
    }

    synchronized @Nonnull List<SCMCheckout> checkouts(@CheckForNull TaskListener listener) {
        if (checkouts == null) {
            LOGGER.log(Level.WARNING, "JENKINS-26761: no checkouts in {0}", this);
            if (listener != null) {
                listener.error("JENKINS-26761: list of SCM checkouts in " + this + " was lost; polling will be broken");
            }
            checkouts = new PersistedList<>(this);
            // Could this.save(), but might pollute diagnosis, and (worse) might clobber real data if there is >1 WorkflowRun with the same ID.
        }
        return checkouts;
    }

    @Override
    @Exported
    public synchronized List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets() {
        if (changeSets == null) {
            changeSets = new ArrayList<>();
            for (SCMCheckout co : checkouts(null)) {
                if (co.changelogFile != null && co.changelogFile.isFile()) {
                    try {
                        ChangeLogSet<? extends ChangeLogSet.Entry> changeLogSet =
                                co.scm.createChangeLogParser().parse(this, co.scm.getEffectiveBrowser(), co.changelogFile);
                        if (!changeLogSet.isEmptySet()) {
                            changeSets.add(changeLogSet);
                        }
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "could not parse " + co.changelogFile, x);
                    }
                }
            }
        }
        return changeSets;
    }

    @Override
    @CheckForNull public Set<String> getCulpritIds() {
        if (shouldCalculateCulprits()) {
            HashSet<String> tempCulpritIds = new HashSet<>();
            for (User u : getCulprits()) {
                tempCulpritIds.add(u.getId());
            }
            if (isBuilding()) {
                return ImmutableSortedSet.copyOf(tempCulpritIds);
            } else {
                culprits = ImmutableSortedSet.copyOf(tempCulpritIds);
            }
        }
        return culprits;
    }

    @Override
    @Exported
    @Nonnull public Set<User> getCulprits() {
        return RunWithSCM.super.getCulprits();
    }

    @Override
    public boolean shouldCalculateCulprits() {
        return isBuilding() || culprits == null;
    }

    @RequirePOST
    public synchronized HttpResponse doStop() {
        Executor e = getOneOffExecutor();
        if (e != null) {
            return e.doStop();
        } else {
            doKill();
            return HttpResponses.forwardToPreviousPage();
        }
    }

    private void onCheckout(SCM scm, FilePath workspace, TaskListener listener, @CheckForNull File changelogFile, @CheckForNull SCMRevisionState pollingBaseline) throws Exception {
        if (changelogFile != null && changelogFile.isFile()) {
            ChangeLogSet<?> cls = scm.createChangeLogParser().parse(this, scm.getEffectiveBrowser(), changelogFile);
            if (!cls.isEmptySet()) {
                getChangeSets().add(cls);
            }
            for (SCMListener l : SCMListener.all()) {
                l.onChangeLogParsed(this, scm, listener, cls);
            }
        }
        checkouts(listener).add(new SCMCheckout(scm, FilePathUtils.getNodeName(workspace), workspace.getRemote(), changelogFile, pollingBaseline));
    }

    static final class SCMCheckout {
        final SCM scm;
        final String node;
        final String workspace;
        // TODO JENKINS-27704 make this a String and relativize to Run.rootDir if possible
        final @CheckForNull File changelogFile;
        final @CheckForNull SCMRevisionState pollingBaseline;
        SCMCheckout(SCM scm, String node, String workspace, File changelogFile, SCMRevisionState pollingBaseline) {
            this.scm = scm;
            this.node = node;
            this.workspace = workspace;
            this.changelogFile = changelogFile;
            this.pollingBaseline = pollingBaseline;
        }
        // TODO replace with Run.XSTREAM2.addCriticalField(SCMCheckout.class, "scm") when not @Restricted(NoExternalUse.class)
        private Object readResolve() {
            if (scm == null) {
                throw new IllegalStateException("Unloadable scm field");
            }
            return this;
        }
    }

    private static final class Owner extends FlowExecutionOwner {
        private final String job;
        private final String id;
        private transient @CheckForNull WorkflowRun run;
        Owner(WorkflowRun run) {
            job = run.getParent().getFullName();
            id = run.getId();
            this.run = run;
        }
        private String key() {
            return job + '/' + id;
        }
        private @Nonnull WorkflowRun run() throws IOException {
            if (run==null) {
                WorkflowRun candidate;
                synchronized (LOADING_RUNS) {
                    candidate = LOADING_RUNS.get(key());
                }
                if (candidate != null && candidate.getParent().getFullName().equals(job) && candidate.getId().equals(id)) {
                    run = candidate;
                } else {
                    final Jenkins jenkins = Jenkins.getInstance();
                    if (jenkins == null) {
                        throw new IOException("Jenkins is not running"); // do not use Jenkins.getActiveInstance() as that is an ISE
                    }
                    WorkflowJob j = ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<WorkflowJob,IOException>() {
                        @Override public WorkflowJob call() throws IOException {
                            return jenkins.getItemByFullName(job, WorkflowJob.class);
                        }
                    });
                    if (j == null) {
                        throw new IOException("no such WorkflowJob " + job);
                    }
                    run = j._getRuns().getById(id);
                    if (run == null) {
                        throw new IOException("no such build " + id + " in " + job);
                    }
                    //System.err.printf("getById found %s @%h%n", run, run);
                }
            }
            return run;
        }

        @Override public FlowExecution get() throws IOException {
            WorkflowRun r = run();
            synchronized (LOADING_RUNS) {
                int count = 5;
                while (r.execution == null && LOADING_RUNS.containsKey(key()) && count-- > 0) {
                    try (WithThreadName naming = new WithThreadName(": waiting for " + key())) {
                        LOADING_RUNS.wait(/* 1m */60_000);
                    } catch (InterruptedException x) {
                        LOGGER.log(Level.WARNING, "failed to wait for " + r + " to be loaded", x);
                        break;
                    }
                }
            }
            FlowExecution exec = r.getExecution();
            if (exec != null) {
                return exec;
            } else {
                throw new IOException(r + " did not yet start");
            }
        }
        @Override public FlowExecution getOrNull() {
            try {
                WorkflowRun run = run();
                ListenableFuture<FlowExecution> promise = run.getExecutionPromise();
                if (promise.isDone()) {
                    // Weird, I know, but this ensures we trigger the onLoad for the execution via the lazy-load mechanism
                    return run.getExecution();
                }
            } catch (Exception x) {
                LOGGER.log(/* not important */Level.FINE, null, x);
            }
            return null;
        }
        @Override public File getRootDir() throws IOException {
            return run().getRootDir();
        }
        @Override public Queue.Executable getExecutable() throws IOException {
            return run();
        }
        @Override public String getUrl() throws IOException {
            return run().getUrl();
        }
        @Override public TaskListener getListener() throws IOException {
            return run().getListener();
        }
        @Override public String toString() {
            return "Owner[" + key() + ":" + run + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Owner)) {
                return false;
            }
            Owner that = (Owner) o;
            return job.equals(that.job) && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            return job.hashCode() ^ id.hashCode();
        }
        private static final long serialVersionUID = 1;
    }

    /** Exists solely to handle cases where the build fails to load during onLoad and we need to trigger 'finish' but at a delay.
     */
    private final class FailOnLoadListener implements GraphListener {
        @Override public void onNewHead(FlowNode node) {
            if (node instanceof FlowEndNode) {
                Thread finishThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException ie) {
                            throw new RuntimeException(ie);
                        }
                        synchronized (getLogCopyGuard()) {
                            finish(((FlowEndNode) node).getResult(), execution != null ? execution.getCauseOfFailure() : null);
                        }
                    }
                });
                finishThread.setName("Build delayed finish");
                finishThread.start();
            }
        }
    }

    private final class GraphL implements GraphListener {
        @Override public void onNewHead(FlowNode node) {
            synchronized (getLogCopyGuard()) {
                copyLogs();
                if (logsToCopy == null) {
                    // Only happens when a FINISHED build loses FlowNodeStorage and we have to create placeholder nodes
                    //  after the build is nominally completed.
                    logsToCopy = new HashMap<String, Long>(3);
                }
                logsToCopy.put(node.getId(), 0L);
            }

            if (node.getPersistentAction(TimingAction.class) == null) {
                node.addAction(new TimingAction());
            }

            logNodeMessage(node);
            if (node instanceof FlowEndNode) {
                finish(((FlowEndNode) node).getResult(), execution != null ? execution.getCauseOfFailure() : null);
            } else {
                if (execution != null && execution.getDurabilityHint().isPersistWithEveryStep()) {
                    try {
                        save();
                    } catch (IOException x) {
                        LOGGER.log(Level.WARNING, null, x);
                    }
                }
            }
        }
    }

    private void logNodeMessage(FlowNode node) {
        WorkflowConsoleLogger wfLogger = new WorkflowConsoleLogger(getListener());
        String prefix = getBranchName(node);
        if (prefix != null) {
            wfLogger.log(String.format("[%s] %s", prefix, node.getDisplayFunctionName()));
        } else {
            wfLogger.log(node.getDisplayFunctionName());
        }
        // Flushing to keep logs printed in order as much as possible. The copyLogs method uses
        // LargeText and possibly LogLinePrefixOutputFilter. Both of these buffer and flush, causing strange
        // out of sequence writes to the underlying log stream (and => things being printed out of sequence)
        // if we don't flush the logger here.
        wfLogger.getLogger().flush();
    }

    static void alias() {
        Run.XSTREAM2.alias("flow-build", WorkflowRun.class);
        new XmlFile(null).getXStream().aliasType("flow-owner", Owner.class); // hack! but how else to set it for arbitrary Descriptor’s?
        Run.XSTREAM2.aliasType("flow-owner", Owner.class);
    }

    @Extension public static final class SCMListenerImpl extends SCMListener {
        @Override public void onCheckout(Run<?,?> build, SCM scm, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState pollingBaseline) throws Exception {
            if (build instanceof WorkflowRun) {
                ((WorkflowRun) build).onCheckout(scm, workspace, listener, changelogFile, pollingBaseline);
            }
        }
    }

    @Restricted(DoNotUse.class) // impl
    @Extension public static class Checkouts extends FlowCopier.ByRun {

        @Override public void copy(Run<?, ?> original, Run<?, ?> copy, TaskListener listener) throws IOException, InterruptedException {
            if (original instanceof WorkflowRun && copy instanceof WorkflowRun) {
                ((WorkflowRun)copy).checkouts(null).addAll(((WorkflowRun)original).checkouts(null));
            }
        }
    }

    @Override
    public synchronized void save() throws IOException {

        if(BulkChange.contains(this))   return;
        File loc = new File(getRootDir(),"build.xml");
        XmlFile file = new XmlFile(XSTREAM,loc);

        boolean isAtomic = true;

        if (this.execution != null) {
            FlowDurabilityHint hint = this.execution.getDurabilityHint();
            isAtomic = hint.isAtomicWrite();
        }

        PipelineIOUtils.writeByXStream(this, loc, XSTREAM2, isAtomic);
        SaveableListener.fireOnChange(this, file);
    }
}
