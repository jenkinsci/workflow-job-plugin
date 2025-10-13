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
import hudson.security.ACLContext;
import hudson.slaves.NodeProperty;
import hudson.util.Iterators;
import hudson.util.LogTaskListener;
import hudson.util.NullStream;
import hudson.util.PersistedList;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.lazy.BuildReference;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.scm.RunWithSCM;
import jenkins.util.SystemProperties;
import jenkins.util.Timer;
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
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.console.NewNodeConsoleNote;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
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
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.springframework.security.core.Authentication;

@SuppressWarnings("SynchronizeOnNonFinalField")
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
    private transient volatile BuildListener listener;

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

    /**
     * Whether {@link #onLoad} might be needed from {@link #reload}.
     * Unfortunately {@link #reload} can be called either directly or implicitly via {@link #WorkflowRun(WorkflowJob, File)}
     * and this is the only way to tell which of those cases this is.
     */
    private transient volatile boolean loaded;

    /**
     * Protects access to {@link #completed} etc.
     * @see #getMetadataGuard
     */
    private transient volatile Object metadataGuard = new Object();

    /** JENKINS-26761: supposed to always be set but sometimes is not. Access only through {@link #checkouts(TaskListener)}. */
    private @CheckForNull List<SCMCheckout> checkouts;
    // TODO could use a WeakReference to reduce memory, but that complicates how we add to it incrementally; perhaps keep a List<WeakReference<ChangeLogSet<?>>>
    private transient List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets;

    /** True when first started, false when running after a restart. */
    private transient boolean firstTime;

    /** Obtain our guard object for metadata, lazily initializing if needed.
     *  Note: to avoid deadlocks, when nesting locks we ALWAYS need to lock on the guard first, THEN the WorkflowRun.
     *  Synchronizing this helps ensure that fields are not mutated during a {@link #save()} operation, since that locks on the Run.
     */
    private Object getMetadataGuard() {
        if (metadataGuard == null) {
            synchronized (this) {
                if (metadataGuard == null) {
                    metadataGuard = new Object();
                }
            }
        }
        assert !Thread.holdsLock(this) || Thread.holdsLock(metadataGuard): "Synchronizing on WorkflowRun before metadataGuard may cause deadlocks";
        return metadataGuard;
    }

    /** Avoids creating new instances, analogous to {@link TaskListener#NULL} but as full StreamBuildListener. */
    static final StreamBuildListener NULL_LISTENER = new StreamBuildListener(new NullStream());

    /** Used internally to ensure listener has been initialized correctly. */
    private BuildListener getListener() {
        // Un-synchronized to prevent deadlocks (combination of run and metadataGuard)
        // Note that in portions where multithreaded access is possible we are already synchronizing on metadataGuard
        if (listener == null) {
            if (Boolean.TRUE.equals(completed)) {
                LOGGER.log(Level.WARNING, null, new IllegalStateException("trying to open a build log on " + this + " after it has completed"));
                return NULL_LISTENER;
            }
            try {
                listener = TaskListenerDecorator.apply(LogStorage.of(asFlowExecutionOwner()).overallListener(), asFlowExecutionOwner(), null);
            } catch (IOException | InterruptedException x) {
                LOGGER.log(Level.WARNING, "Error trying to open build log file for writing, output will be lost: " + this, x);
                return NULL_LISTENER;
            }
        }
        return listener;
    }

    public WorkflowRun(WorkflowJob job) throws IOException {
        super(job);
        firstTime = true;
        loaded = true;
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

    @NonNull
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
            Authentication auth = Jenkins.getAuthentication2();
            if (!auth.equals(ACL.SYSTEM2)) {
                String name = auth.getName();
                if (!auth.equals(Jenkins.ANONYMOUS2)) {
                    User user = User.getById(name, false);
                    if (user != null) {
                        name = ModelHyperlinkNote.encodeTo(user);
                    }
                }
                myListener.getLogger().println(/* hudson.model.Messages.Run_running_as_(name) */ "Running as " + name);
            }
            RunListener.fireStarted(this, myListener);
            FlowDefinition definition = getParent().getDefinition();
            if (definition == null) {
                throw new AbortException("No flow definition, cannot run");
            }

            Owner owner = new Owner(this);
            FlowExecution newExecution = definition.create(owner, myListener, getAllActions());

            if (newExecution instanceof BlockableResume) {
                boolean blockResume = getParent().isResumeBlocked();
                ((BlockableResume) newExecution).setResumeBlocked(blockResume);
                if (blockResume) {
                    myListener.getLogger().println("Resume disabled by user, switching to high-performance, low-durability mode.");
                }
            }
            LOGGER.fine(() -> "Running in Durability level: " + DurabilityHintProvider.suggestedFor(this.project));
            save();  // Save before we add to the FlowExecutionList, to ensure we never have a run with a null build.
            synchronized (getMetadataGuard()) {  // Technically safe but it makes FindBugs happy
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
            FlowExecutionListener.fireCreated(newExecution);
            newExecution.start();  // We should probably have the promise set before beginning, no?
            FlowExecutionListener.fireRunning(newExecution);

            DisableConcurrentBuildsJobProperty dcb = getParent().getProperty(DisableConcurrentBuildsJobProperty.class);
            if (dcb != null && dcb.isAbortPrevious()) {
                WorkflowRun prev = getPreviousBuild();
                if (prev != null && prev.isBuilding()) {
                    Executor e = prev.getExecutor();
                    if (e != null) {
                        e.interrupt(Result.NOT_BUILT, new DisableConcurrentBuildsJobProperty.CancelledCause(this));
                    }
                }
                // Not bothering to look for other older builds in progress, since once we turn this on, going forward there should be at most one.
            }
        } catch (Throwable x) {
            execution = null; // ensures isInProgress returns false
            executionLoaded = true;
            Executor executor = Executor.currentExecutor();
            if (Thread.interrupted() && executor != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    // In general, the exception thrown by whatever code noticed that the thread was interrupted is not useful.
                    LOGGER.log(Level.FINE, this + " was interrupted during startup", x);
                }
                Result result = executor.abortResult();
                Collection<CauseOfInterruption> causes = executor.getCausesOfInterruption();
                finish(result, new FlowInterruptedException(result, causes.toArray(new CauseOfInterruption[0])));
            } else {
                finish(Result.FAILURE, x);
            }
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
                Timer.get().submit(() -> {
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
                        fetchedExecution.interrupt(executor.abortResult(), causes.toArray(new CauseOfInterruption[0]));
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, null, x);
                    }
                    executor.recordCauseOfInterruption(WorkflowRun.this, getListener());
                    printLater(StopState.TERM, "Click here to forcibly terminate running steps");
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
        Timer.get().schedule(() -> {
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
        }, 15, TimeUnit.SECONDS);
    }

    /** Sends {@link StepContext#onFailure} to all running (leaf) steps. */
    public void doTerm() {
        checkPermission(Item.CANCEL);
        if (!isInProgress() || /* redundant, but make FindBugs happy */ execution == null) {
            return;
        }
        final Throwable x = new FlowInterruptedException(Result.ABORTED);
        FlowExecution exec = getExecution();
        if (exec == null) { // Already dead, just make sure statuses reflect that.
            synchronized (getMetadataGuard()) {
                // Null execution means a hard-kill of the execution and build is by definition dead
                // So we should make sure the result is set to failure if un-set and it's completed and then save.
                boolean modified = false;
                if (result == null) {
                    setResult(Result.FAILURE);
                    modified = true;
                }
                if (!Boolean.TRUE.equals(completed)) {
                    completed = true;
                    modified = true;
                }
                if (modified) {
                    saveWithoutFailing(true);
                    completeAsynchronousExecution();
                }
                return;
            }
        }
        Futures.addCallback(exec.getCurrentExecutions(/* cf. JENKINS-26148 */true), new FutureCallback<List<StepExecution>>() {
            @Override public void onSuccess(@NonNull List<StepExecution> l) {
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
            @Override public void onFailure(@NonNull Throwable t) {}
        });
        printLater(StopState.KILL, "Click here to forcibly kill entire build");
    }

    /** Sends {@link StepContext#onFailure} to all running (leaf) steps. */
    @RequirePOST
    @WebMethod(name = "term")
    @Restricted(DoNotUse.class) // for Stapler routing only
    public HttpResponse httpTerm() {
        doTerm();
        return HttpResponses.forwardToPreviousPage();
    }

    /** Immediately kills the build. */
    public void doKill() {
        checkPermission(Item.CANCEL);
        if (!isBuilding() || /* probably redundant, but just to be sure */ execution == null) {
            return;
        }
        synchronized (getMetadataGuard()) {
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

    /** Immediately kills the build. */
    @RequirePOST
    @WebMethod(name = "kill")
    @Restricted(DoNotUse.class) // for Stapler routing only
    public HttpResponse httpKill() {
        doKill();
        return HttpResponses.forwardToPreviousPage();
    }

    @NonNull
    @Override public EnvVars getEnvironment(@NonNull TaskListener listener) throws IOException, InterruptedException {
        EnvVars env = super.getEnvironment(listener);

        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance != null) {
            for (NodeProperty<?> nodeProperty : instance.getGlobalNodeProperties()) {
                nodeProperty.buildEnvVars(env, listener);
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


    /** Hack to allow {@link #execution} to use an {@link Owner} referring to this run, even when it has not yet been loaded. */
    @Override public void reload() throws IOException {
        LOGGER.fine(() -> "Adding " + getExternalizableId() + " to LOADING_RUNS");
        synchronized (LOADING_RUNS) {
            LOADING_RUNS.put(getExternalizableId(), this);
        }

        // super.reload() forces result to be FAILURE, so working around that
        new XmlFile(XSTREAM,new File(getRootDir(),"build.xml")).unmarshal(this);
        synchronized (this) {
            var _completed = completed;
            var _executionLoaded = executionLoaded;
            var _execution = execution;
            LOGGER.fine(() -> getExternalizableId() + " completed=" + _completed + " executionLoaded=" + _executionLoaded);
            if (Boolean.TRUE.equals(_completed) && _executionLoaded && _execution != null) {
                _execution.onLoad(new Owner(this));
            }
        }
        if (loaded) {
            super.onLoad();
        } // else from WorkflowRun(WorkflowJob, File), and RunMap.retrieve will call onLoad
    }

    @Override protected void onLoad() {
        super.onLoad();
        try {
            synchronized (getMetadataGuard()) {
                loaded = true;
                if (executionLoaded) {
                    LOGGER.log(Level.WARNING, "Double onLoad of build " + this, new Throwable());
                    return;
                }
                boolean needsToPersist = completed == null;
                if (Boolean.TRUE.equals(completed) && result == null) {
                    LOGGER.log(Level.FINE, "Completed build with no result set, defaulting to failure for "+this);
                    setResult(Result.FAILURE);
                    needsToPersist = true;
                }

                // TODO See if we can simplify this, especially around interactions with 'completed'.

                if (execution != null && !Boolean.TRUE.equals(completed)) {
                    FlowExecution fetchedExecution = getExecution();  // Triggers execution.onLoad so we can resume running if not done

                    if (fetchedExecution != null) {
                        if (completed == null) {
                            completed = fetchedExecution.isComplete();
                        }

                        if (Boolean.FALSE.equals(completed)) {
                            getListener().getLogger().println("Resuming build at " + new Date() + " after Jenkins restart");
                            Timer.get().submit(() -> Queue.getInstance().schedule(new AfterRestartTask(WorkflowRun.this), 0)); // JENKINS-31614

                            // we've been restarted while we were running. let's get the execution going again.
                            FlowExecutionListener.fireResumed(fetchedExecution);
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
                    completeAsynchronousExecution();
                }
            }
        } finally {  // Ensure the run is ALWAYS removed from loading even if something failed, so threads awaken.
            checkouts(null); // only for diagnostics
            LOGGER.fine(() -> "Removing " + getExternalizableId() + " from LOADING_RUNS");
            synchronized (LOADING_RUNS) {
                LOADING_RUNS.remove(getExternalizableId()); // or could just make the value type be WeakReference<WorkflowRun>
                LOADING_RUNS.notifyAll();
            }
        }
    }

    // Overridden since super version has an unwanted assertion about this.state, which we do not use.
    @Override public void setResult(@NonNull Result r) {
        if (result == null || r.isWorseThan(result)) {
            result = r;
            LOGGER.log(Level.FINE, this + " in " + getRootDir() + ": result is set to " + r, LOGGER.isLoggable(Level.FINER) ? new Exception() : null);
        }
    }

    /** Handles normal build completion (including errors) but also handles the case that the flow did not even start correctly, for example due to an error in {@link FlowExecution#start}. */
    private void finish(@NonNull Result r, @CheckForNull Throwable t) {
        try {
            setResult(r);
            BuildListener myListener;
            synchronized (getMetadataGuard()) {
                myListener = getListener();
                completed = true;
            }
            duration = Math.max(0, System.currentTimeMillis() - getStartTimeInMillis());
            LOGGER.log(Level.FINE, "{0} completed: {1}", new Object[]{toString(), getResult()});
            if (myListener == null) {
                // Never even made it to running, either failed when fresh-started or resumed -- otherwise getListener would have run
                LOGGER.log(Level.WARNING, this + " failed to start", t);
            } else {
                if (t instanceof AbortException) {
                    myListener.error(t.getMessage());
                } else if (t instanceof FlowInterruptedException) {
                    ((FlowInterruptedException) t).handle(this, myListener);
                } else if (t != null) {
                    Functions.printStackTrace(t, myListener.getLogger());
                }
                RunListener.fireCompleted(WorkflowRun.this, myListener);
                fireCompleted();
                myListener.finished(getResult());
                try {
                    StashManager.maybeClearAll(this, myListener);
                } catch (IOException | InterruptedException x) {
                    Functions.printStackTrace(x, myListener.error("Failed to clean up stashes"));
                }
                if (myListener instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) myListener).close();
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "could not close build log for " + this, x);
                    }
                }
                listener = null;
            }
            saveWithoutFailing(true);
            onEndBuilding();
        } finally {  // Ensure this is ALWAYS removed from FlowExecutionList
            FlowExecutionList.get().unregister(new Owner(this));
            completeAsynchronousExecution();
        }
    }

    private void fireCompleted(){
        FlowExecution exec = getExecution();
        if (exec != null) {
            FlowExecutionListener.fireCompleted(exec);
        }
    }
    @Override public void deleteArtifacts() throws IOException {
        super.deleteArtifacts();
        try {
            StashManager.clearAll(this, /* core API defines no log sink for this */ new LogTaskListener(LOGGER, Level.FINE));
        } catch (InterruptedException x) {
            throw new IOException(x);
        }
    }

    /**
     * Gets the associated execution state, and do a more expensive loading operation if not initialized.
     * Performs all the needed initialization for the execution pre-loading too -- sets the executionPromise, adds Listener, calls onLoad on it etc.
     * @return non-null after the flow has started, even after finished (but may be null temporarily when about to start, or if starting failed)
     */
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "deliberate")
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
                    if (!Boolean.TRUE.equals(this.completed)) {
                        finishListener = new FailOnLoadListener();
                        fetchedExecution.addListener(finishListener);  // So we can still ensure build finishes if onLoad generates a FlowEndNode
                    }
                    fetchedExecution.onLoad(new Owner(this));
                    if (!Boolean.TRUE.equals(this.completed)) {
                        if (fetchedExecution.isComplete()) {  // See JENKINS-50199 for cases where the execution is marked complete but build is not
                            // Somehow arrived at one of those weird states
                            LOGGER.log(Level.WARNING, "Found incomplete build with completed execution - display name: "+this.getFullDisplayName());
                            this.completed = true;
                            Result finalResult = Result.FAILURE;
                            List<FlowNode> heads = fetchedExecution.getCurrentHeads();
                            if (!heads.isEmpty() && heads.get(0) instanceof FlowEndNode) {
                                finalResult = ((FlowEndNode) heads.get(0)).getResult();
                            }
                            setResult(finalResult);
                            fetchedExecution.removeListener(finishListener);
                            saveWithoutFailing(true);
                            completeAsynchronousExecution();
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
                    setResult(Result.FAILURE);
                    if (SystemProperties.getBoolean("org.jenkinsci.plugins.workflow.cps.CpsFlowExecution.initializeStorageFromOnLoad", true)) {
                        LOGGER.log(Level.WARNING, "Nulling out FlowExecution due to error in build " + this, x);
                        execution = null; // probably too broken to use
                        saveWithoutFailing(true); // Ensure we do not try to load again
                    } else {
                        LOGGER.log(Level.WARNING, "error in build " + this, x);
                    }
                    executionLoaded = true;
                    completeAsynchronousExecution();
                    return null;
                }
            }
        }
    }

    /**
     * Allows the caller to block on {@link FlowExecution}, which gets created relatively quickly
     * after the build gets going.
     */
    @NonNull
    public ListenableFuture<FlowExecution> getExecutionPromise() {
        return getSettableExecutionPromise();
    }

    /** Initializes and returns the executionPromise to avoid null risk */
    @NonNull
    private SettableFuture<FlowExecution> getSettableExecutionPromise() {
        SettableFuture<FlowExecution> execOut = executionPromise;
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

    @Override public @NonNull FlowExecutionOwner asFlowExecutionOwner() {
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
    @Override public boolean isInProgress() {
        if (Boolean.TRUE.equals(completed)) {  // Has a persisted completion state
            return false;
        } else {
            var _execution = execution;
            return _execution != null && !_execution.isComplete();
        }
    }

    @Override public boolean isLogUpdated() {
        return listener != null || isBuilding(); // there is no equivalent to a post-production state for flows
    }

    synchronized @NonNull List<SCMCheckout> checkouts(@CheckForNull TaskListener listener) {
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

    public @NonNull List<SCM> getSCMs() {
        List<SCMCheckout> scmCheckouts = checkouts(TaskListener.NULL);
        List<SCM> scmList = new ArrayList<>();
        for (SCMCheckout checkout : scmCheckouts) {
            scmList.add(checkout.getScm());
        }
        return scmList;
    }

    @NonNull
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
    @NonNull public Set<User> getCulprits() {
        return RunWithSCM.super.getCulprits();
    }

    @Override
    public boolean shouldCalculateCulprits() {
        return isBuilding() || culprits == null;
    }

    @RequirePOST
    public HttpResponse doStop() {
        Executor e = getOneOffExecutor();
        if (e != null) {
            return e.doStop();
        } else {
            return httpKill();
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
        SCMCheckout(SCM scm, String node, String workspace, @CheckForNull File changelogFile, @CheckForNull SCMRevisionState pollingBaseline) {
            this.scm = scm;
            this.node = node;
            this.workspace = workspace;
            this.changelogFile = changelogFile;
            this.pollingBaseline = pollingBaseline;
        }
        public SCM getScm() {
            return scm;
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
        private @NonNull WorkflowRun run() throws IOException {
            if (run==null) {
                WorkflowRun candidate;
                synchronized (LOADING_RUNS) {
                    candidate = LOADING_RUNS.get(getExternalizableId());
                }
                if (candidate != null && candidate.getParent().getFullName().equals(job) && candidate.getId().equals(id)) {
                    run = candidate;
                } else {
                    final Jenkins jenkins = Jenkins.getInstanceOrNull();
                    if (jenkins == null) {
                        throw new IOException("Jenkins is not running"); // do not use Jenkins.getActiveInstance() as that is an ISE
                    }
                    WorkflowJob j;
                    try (ACLContext context = ACL.as(ACL.SYSTEM)) {
                        j = jenkins.getItemByFullName(job, WorkflowJob.class);
                    }
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

        @NonNull
        @Override public FlowExecution get() throws IOException {
            WorkflowRun r = run();
            synchronized (LOADING_RUNS) {
                int count = 5;
                while (r.execution == null && LOADING_RUNS.containsKey(getExternalizableId()) && count-- > 0) {
                    try (WithThreadName naming = new WithThreadName(": waiting for " + getExternalizableId())) {
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
        @Override public @NonNull String getExternalizableId() {
            return job + '#' + id;
        }

        @NonNull
        @Override public TaskListener getListener() throws IOException {
            return run().getListener();
        }
        @Override public String toString() {
            return getExternalizableId();
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
                    synchronized (getMetadataGuard()) {
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
                    saveWithoutFailing(false);
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
    @NonNull
    @Override public AnnotatedLargeText getLogText() {
        return LogStorage.of(asFlowExecutionOwner()).overallLog(this, !isLogUpdated());
    }

    /**
     * For use by Jelly only.
     */
    @Restricted(DoNotUse.class)
    public String getFlowGraphDataAsHtml() {
        FlowExecution exec = getExecution();
        if (exec != null) {
            DepthFirstScanner scanner = new DepthFirstScanner();
            if (scanner.setup(exec.getCurrentHeads())) {
                StringBuilder html = new StringBuilder();
                for (FlowNode node : scanner) {
                    String startId;
                    String enclosingId;
                    if (node instanceof BlockEndNode) {
                        enclosingId = null;
                        startId = ((BlockEndNode<?>) node).getStartNode().getId();
                    } else {
                        Iterator<BlockStartNode> it = node.iterateEnclosingBlocks().iterator();
                        enclosingId = it.hasNext() ? it.next().getId() : null;
                        startId = node instanceof BlockStartNode ? node.getId() : null;
                    }
                    html.append(NewNodeConsoleNote.startTagFor(this, node.getId(), startId, enclosingId)).append("Test</span>");
                }
                return html.toString();
            }
        }
        return null;
    }

    // TODO log-related overrides pending JEP-207:

    @NonNull
    @Override public InputStream getLogInputStream() throws IOException {
        // Inefficient but probably rarely used anyway.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeLogTo(getLogText()::writeRawLogTo, baos);
        return new ByteArrayInputStream(baos.toByteArray());
    }

    @Override public void doConsoleText(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        rsp.setContentType("text/plain;charset=UTF-8");
        try (OutputStream os = rsp.getOutputStream()) {
            writeLogTo(getLogText()::writeLogTo, os);
        }
    }

    @NonNull
    @Override public Reader getLogReader() throws IOException {
        return getLogText().readAll();
    }

    @SuppressWarnings("deprecation")
    @NonNull
    @Override public String getLog() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeLogTo(getLogText()::writeRawLogTo, baos);
        return baos.toString("UTF-8");
    }

    @FunctionalInterface private interface WriteMethod {
        long writeLogTo(long start, OutputStream os) throws IOException;
    }

    private void writeLogTo(WriteMethod method, OutputStream os) throws IOException {
        // Similar to Run#writeWholeLogTo but terminates even if !logText.complete:
        long pos = 0;
        while (true) {
            long pos2 = method.writeLogTo(pos, os);
            if (pos2 <= pos) {
                break;
            }
            pos = pos2;
        }
    }

    @NonNull
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

    @Deprecated
    @NonNull
    @Override public File getLogFile() {
        LOGGER.log(Level.WARNING, "Avoid calling getLogFile on " + this, new UnsupportedOperationException());
        return LogStorage.of(asFlowExecutionOwner()).getLogFile(this, !isLogUpdated());
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
    private void saveWithoutFailing(boolean flush) {
        try {
            if (flush) {
                BulkChange bc = BulkChange.current();
                if (bc != null) {
                    bc.commit();
                    return;
                }
            }
            save();
        } catch (Exception x) {
            LOGGER.log(Level.WARNING, "Failed to save " + this, x);
        }
    }

    @Override
    public void save() throws IOException {

        if(BulkChange.contains(this))   return;
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

    private void completeAsynchronousExecution() {
        Executor executor = getExecutor();
        if (executor != null) {
            AsynchronousExecution asynchronousExecution = executor.getAsynchronousExecution();
            if (asynchronousExecution != null) {
                asynchronousExecution.completed(null);
            }
        }
    }

}
