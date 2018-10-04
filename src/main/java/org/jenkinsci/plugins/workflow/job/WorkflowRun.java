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
import hudson.console.ConsoleNote;
import hudson.console.ModelHyperlinkNote;
import hudson.model.BuildListener;
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
import hudson.util.Iterators;
import hudson.util.NullStream;
import hudson.util.PersistedList;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
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
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.console.NewNodeConsoleNote;
import org.jenkinsci.plugins.workflow.log.LogStorage;
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
@SuppressFBWarnings(value={"RC_REF_COMPARISON_BAD_PRACTICE_BOOLEAN"},
        justification="For Boolean comparison, this is for deserializing handle null completion states from legacy builds")
public final class WorkflowRun extends Run<WorkflowJob,WorkflowRun> implements FlowExecutionOwner.Executable, LazyBuildMixIn.LazyLoadingRun<WorkflowJob,WorkflowRun>, RunWithSCM<WorkflowJob,WorkflowRun> {

    private static final Logger LOGGER = Logger.getLogger(WorkflowRun.class.getName());

    private enum StopState {
        TERM, KILL;

        public String url() {
            return this.name().toLowerCase(Locale.ENGLISH);
        }
    }

    /** Null until started, or after serious failures or hard kill. */
    @CheckForNull volatile FlowExecution execution; // Not private for test use only

    /**
     * {@link Future} that yields {@link #execution}, when it is fully configured and ready to be exposed.
     */
    @CheckForNull
    private transient volatile SettableFuture<FlowExecution> executionPromise = SettableFuture.create();

    private transient final LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun> runMixIn = new LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun>() {
        @Override protected WorkflowRun asRun() {
            return WorkflowRun.this;
        }
    };
    private transient BuildListener listener;

    private transient boolean allowTerm;

    private transient boolean allowKill;

    /** Controls whether or not our execution has been initialized via its {@link FlowExecution#onLoad(FlowExecutionOwner)} method yet.*/
    volatile transient boolean executionLoaded = false;  // NonPrivate for tests

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
    volatile Boolean completed;  // Non-private for testing

    /** Protects the access to logsToCopy, completed, and branchNameCache that are used in the logCopy process */
    private transient Object logCopyGuard = new Object();

    /** JENKINS-26761: supposed to always be set but sometimes is not. Access only through {@link #checkouts(TaskListener)}. */
    private @CheckForNull List<SCMCheckout> checkouts;
    // TODO could use a WeakReference to reduce memory, but that complicates how we add to it incrementally; perhaps keep a List<WeakReference<ChangeLogSet<?>>>
    private transient List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets;

    /** True when first started, false when running after a restart. */
    private transient boolean firstTime;

    /** Obtain our guard object for log copying, lazily initializing if needed.
     *  Note: to avoid deadlocks, when nesting locks we ALWAYS need to lock on the logCopyGuard first, THEN the WorkflowRun.
     *  Synchronizing this helps ensure that fields are not mutated during a {@link #save()} operation, since that locks on the Run.
     */
    private synchronized Object getLogCopyGuard() { // TODO no longer used for log copying, so rename
        if (logCopyGuard == null) {
            logCopyGuard = new Object();
        }
        return logCopyGuard;
    }

    /** Avoids creating new instances, analogous to {@link TaskListener#NULL} but as full StreamBuildListener. */
    static final StreamBuildListener NULL_LISTENER = new StreamBuildListener(new NullStream());

    /** Used internally to ensure listener has been initialized correctly. */
    BuildListener getListener() {
        // Un-synchronized to prevent deadlocks (combination of run and logCopyGuard)
        // Note that in portions where multithreaded access is possible we are already synchronizing on logCopyGuard
        if (listener == null) {
            try {
                // TODO to better handle in-VM restart (e.g. in JenkinsRule), move CpsFlowExecution.suspendAll logic into a FlowExecution.notifyShutdown override, then make FlowExecutionOwner.notifyShutdown also overridable, which for WorkflowRun.Owner should listener.close() as needed
                // TODO JENKINS-30777 decorate with ConsoleLogFilter.all()
                listener = LogStorage.of(asFlowExecutionOwner()).overallListener();
            } catch (IOException | InterruptedException x) {
                LOGGER.log(Level.WARNING, "Error trying to open build log file for writing, output will be lost: " + getLogFile(), x);
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
            charset = "UTF-8"; // cannot override getCharset, and various Run methods do not call it anyway
            BuildListener myListener = getListener();
            myListener.started(getCauses());
            Authentication auth = Jenkins.getAuthentication();
            if (!auth.equals(ACL.SYSTEM)) {
                String name = auth.getName();
                if (!auth.equals(Jenkins.ANONYMOUS)) {
                    name = ModelHyperlinkNote.encodeTo(User.get(name));
                }
                myListener.getLogger().println(/* hudson.model.Messages.Run_running_as_(name) */ "Running as " + name);
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
                    myListener.getLogger().println("Resume disabled by user, switching to high-performance, low-durability mode.");
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
                newExecution.addListener(new NodePrintListener());
                completed = Boolean.FALSE;
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
            executionLoaded = true;
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

    private AsynchronousExecution sleep() {
        return new AsynchronousExecution() {
            @Override public void interrupt(boolean forShutdown) {
                if (forShutdown) {
                    return;
                }
                Timer.get().submit(new Runnable() {
                    @Override public void run() {
                        FlowExecution fetchedExecution = execution;
                        if (fetchedExecution == null) {
                            return;
                        }
                        Executor executor = getExecutor();
                        if (executor == null) {
                            LOGGER.log(Level.WARNING, "Lost executor for {0}", WorkflowRun.this);
                            return;
                        }
                        try {
                            Collection<CauseOfInterruption> causes = executor.getCausesOfInterruption();
                            fetchedExecution.interrupt(executor.abortResult(), causes.toArray(new CauseOfInterruption[causes.size()]));
                        } catch (Exception x) {
                            LOGGER.log(Level.WARNING, null, x);
                        }
                        executor.recordCauseOfInterruption(WorkflowRun.this, getListener());
                        printLater(StopState.TERM, "Click here to forcibly terminate running steps");
                    }
                });
            }
            @Override public boolean blocksRestart() {
                FlowExecution exec = getExecution();
                return exec != null && exec.blocksRestart();
            }
            @Override public boolean displayCell() {
                return blocksRestart();
            }
        };
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
        FlowExecution exec = getExecution();
        if (exec == null) { // Already dead, just make sure statuses reflect that.
            synchronized (getLogCopyGuard()) {
                // Null execution means a hard-kill of the execution and build is by definition dead
                // So we should make sure the result is set to failure if un-set and it's completed and then save.
                boolean modified = false;
                if (result == null) {
                    setResult(Result.FAILURE);
                    modified = true;
                }
                if (completed != Boolean.TRUE) {
                    completed = true;
                    modified = true;
                }
                if (modified) {
                    saveWithoutFailing();
                }
                return;
            }
        }
        Futures.addCallback(exec.getCurrentExecutions(/* cf. JENKINS-26148 */true), new FutureCallback<List<StepExecution>>() {
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
        synchronized (this) {
            execution = null; // ensures isInProgress returns false
            executionLoaded = true;
        }
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

    @Override protected void onLoad() {  // Here there be DRAGONS: due to lazy-loading and subtle threading risks, be very cautious!
        try {
            synchronized (getLogCopyGuard()) {
                if (executionLoaded) {
                    LOGGER.log(Level.WARNING, "Double onLoad of build "+this);
                    return;
                }
                boolean needsToPersist = completed == null;
                super.onLoad();

                if (completed == Boolean.TRUE && result == null) {
                    LOGGER.log(Level.FINE, "Completed build with no result set, defaulting to failure for "+this);
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
                        LOGGER.log(Level.WARNING, "Error while saving build to update completed flag "+this, ex);
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

    private void endExecutionTask() {
        try {
            Executor executor = getExecutor();
            if (executor != null) {
                AsynchronousExecution asynchronousExecution = executor.getAsynchronousExecution();
                if (asynchronousExecution != null) {
                    asynchronousExecution.completed(null);
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Error when trying to end the FlyWeightTask for run "+this, ex);
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
                if (listener instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) listener).close();
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "could not close build log for " + this, x);
                    }
                }
                listener = null;
            }
            saveWithoutFailing();
            endExecutionTask();
            Timer.get().submit(() -> {
                try {
                    getParent().logRotate();
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, "failed to perform log rotation after " + this, x);
                }
            });
            onEndBuilding();
        } finally {  // Ensure this is ALWAYS removed from FlowExecutionList and finished
            endExecutionTask();  // Just in case an exception was thrown above
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
    public @CheckForNull FlowExecution getExecution() {
        if (executionLoaded || execution == null) {  // Avoids highly-contended synchronization on run
            return execution;
        } else {  // Try to lazy-load execution
            synchronized (this) {  // Double-checked locking rendered safe by use of volatile field
                FlowExecution fetchedExecution = execution;
                if (executionLoaded || fetchedExecution == null) {
                    return fetchedExecution;
                }
                try {
                    if (fetchedExecution instanceof BlockableResume) {
                        BlockableResume blockableExecution = (BlockableResume) fetchedExecution;
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
                        if (fetchedExecution.isComplete()) {  // See JENKINS-50199 for cases where the execution is marked complete but build is not
                            // Somehow arrived at one of those weird states
                            LOGGER.log(Level.WARNING, "Found incomplete build with completed execution - display name: "+this.getFullDisplayName());
                            this.completed = true;
                            Result finalResult = Result.FAILURE;
                            List<FlowNode> heads = fetchedExecution.getCurrentHeads();
                            if (!heads.isEmpty() && heads.get(0) instanceof FlowEndNode) {
                                finalResult = ((FlowEndNode)(heads.get(0))).getResult();
                            }
                            setResult(finalResult);
                            fetchedExecution.removeListener(finishListener);
                            saveWithoutFailing();
                        } else {
                            // Defer the normal listener to ensure onLoad can complete before finish() is called since that may
                            // need the build to be loaded and can result in loading loops otherwise.
                            fetchedExecution.removeListener(finishListener);
                            fetchedExecution.addListener(new GraphL());
                            fetchedExecution.addListener(new NodePrintListener());
                        }
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
                    LOGGER.log(Level.WARNING, "Nulling out FlowExecution due to error in build " + this, x);
                    execution = null; // probably too broken to use
                    executionLoaded = true;
                    saveWithoutFailing(); // Ensure we do not try to load again
                    return null;
                }
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
        SettableFuture execOut = executionPromise;
        if (execOut == null) { // Double-checked locking safe rendered safe by volatile field
            synchronized(this) {
                execOut = executionPromise; // Fetch again from field in case another thread created it
                if (execOut == null) {
                    execOut = SettableFuture.create();
                    executionPromise = execOut;
                }
                return execOut;
            }
        }
        return execOut;
    }

    @Override public @Nonnull FlowExecutionOwner asFlowExecutionOwner() {
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
                Timer.get().schedule(() -> {
                    synchronized (getLogCopyGuard()) {
                        finish(((FlowEndNode) node).getResult(), execution != null ? execution.getCauseOfFailure() : null);
                    }
                }, 1, TimeUnit.SECONDS);
            }
        }
    }

    private final class GraphL implements GraphListener {
        @Override public void onNewHead(FlowNode node) {
            if (node.getPersistentAction(TimingAction.class) == null) {
                node.addAction(new TimingAction());
            }

            FlowExecution exec = getExecution();
            if (node instanceof FlowEndNode) {
                finish(((FlowEndNode) node).getResult(), exec != null ? exec.getCauseOfFailure() : null);
            } else {
                if (exec != null && exec.getDurabilityHint().isPersistWithEveryStep()) {
                    saveWithoutFailing();
                }
            }
        }
    }

    /**
     * Prints nodes as they appear (including block start and end nodes).
     */
    private final class NodePrintListener implements GraphListener.Synchronous {
        @Override public void onNewHead(FlowNode node) {
            NewNodeConsoleNote.print(node, getListener());
        }
    }

    @SuppressWarnings("rawtypes")
    @Override public AnnotatedLargeText getLogText() {
        return LogStorage.of(asFlowExecutionOwner()).overallLog(this, !isLogUpdated());
    }

    // TODO log-related overrides pending JEP-207:

    @Override public InputStream getLogInputStream() throws IOException {
        // Inefficient but probably rarely used anyway.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        getLogText().writeRawLogTo(0, baos);
        return new ByteArrayInputStream(baos.toByteArray());
    }

    @Override public Reader getLogReader() throws IOException {
        return getLogText().readAll();
    }

    @SuppressWarnings("deprecation")
    @Override public String getLog() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        getLogText().writeRawLogTo(0, baos);
        return baos.toString("UTF-8");
    }

    @Override public List<String> getLog(int maxLines) throws IOException {
        int lineCount = 0;
        List<String> logLines = new LinkedList<>();
        if (maxLines == 0) {
            return logLines;
        }
        try (BufferedReader reader = new BufferedReader(getLogReader())) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                logLines.add(line);
                ++lineCount;
                if (lineCount > maxLines) {
                    logLines.remove(0);
                }
            }
        }
        if (lineCount > maxLines) {
            logLines.set(0, "[...truncated " + (lineCount - (maxLines - 1)) + " lines...]");
        }
        return ConsoleNote.removeNotes(logLines);
    }

    @Override public File getLogFile() {
        LOGGER.log(Level.WARNING, "Avoid calling getLogFile on " + this, new UnsupportedOperationException());
        try {
            File f = File.createTempFile("deprecated", ".log", getRootDir());
            f.deleteOnExit();
            try (OutputStream os = new FileOutputStream(f)) {
                getLogText().writeRawLogTo(0, os);
            }
            return f;
        } catch (IOException x) {
            throw new RuntimeException(x);
        }
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

    /** Save the run but swallow and log any exception */
    private void saveWithoutFailing() {
        try {
            save();
        } catch (Exception x) {
            LOGGER.log(Level.WARNING, "Failed to save " + this, x);
        }
    }

    @Override
    public void save() throws IOException {
        /* Checking for completion ensures the final save can complete.
         */
        if(BulkChange.contains(this) && completed != Boolean.TRUE)   return;
        File loc = new File(getRootDir(),"build.xml");
        XmlFile file = new XmlFile(XSTREAM,loc);

        boolean isAtomic = true;
        FlowExecution fetchedExecution = this.execution;  // Avoid triggering loading unless we need to
        if (fetchedExecution != null) {
            FlowDurabilityHint hint = fetchedExecution.getDurabilityHint();
            isAtomic = hint.isAtomicWrite();
        }

        synchronized (this) {
            PipelineIOUtils.writeByXStream(this, loc, XSTREAM2, isAtomic);
            SaveableListener.fireOnChange(this, file);
        }
    }
}
