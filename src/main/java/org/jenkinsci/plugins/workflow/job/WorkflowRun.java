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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.XmlFile;
import hudson.console.AnnotatedLargeText;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.listeners.SCMListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.security.ACL;
import hudson.util.Iterators;
import hudson.util.NullStream;
import hudson.util.PersistedList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.model.lazy.BuildReference;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.apache.commons.codec.Charsets;
import org.jenkinsci.plugins.workflow.FilePathUtils;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.flow.StashManager;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.console.PipelineLargeText;
import org.jenkinsci.plugins.workflow.job.console.WorkflowRunConsoleNote;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;
import org.jenkinsci.plugins.workflow.support.steps.input.POSTHyperlinkNote;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

@SuppressWarnings("SynchronizeOnNonFinalField")
@SuppressFBWarnings(value="JLM_JSR166_UTILCONCURRENT_MONITORENTER", justification="completed is an unusual usage")
public final class WorkflowRun extends Run<WorkflowJob,WorkflowRun> implements FlowExecutionOwner.Executable, LazyBuildMixIn.LazyLoadingRun<WorkflowJob,WorkflowRun> {

    private static final Logger LOGGER = Logger.getLogger(WorkflowRun.class.getName());

    /** null until started, or after serious failures or hard kill */
    private @CheckForNull FlowExecution execution;

    /**
     * {@link Future} that yields {@link #execution}, when it is fully configured and ready to be exposed.
     */
    private transient SettableFuture<FlowExecution> executionPromise = SettableFuture.create();

    private transient final LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun> runMixIn = new LazyBuildMixIn.RunMixIn<WorkflowJob,WorkflowRun>() {
        @Override protected WorkflowRun asRun() {
            return WorkflowRun.this;
        }
    };
    private transient StreamBuildListener listener;

    /**
     * Flag for whether or not the build has completed somehow.
     * Non-null soon after the build starts or is reloaded from disk.
     * Recomputed in {@link #onLoad} based on {@link FlowExecution#isComplete}.
     * TODO may be better to make this a persistent field.
     * That would allow the execution of a completed build to be loaded on demand, reducing overhead for some operations.
     * It would also remove the need to null out {@link #execution} merely to force {@link #isInProgress} to be false
     * in the case of broken or hard-killed builds which lack a single head node.
     */
    private transient AtomicBoolean completed;

    /** JENKINS-26761: supposed to always be set but sometimes is not. Access only through {@link #checkouts(TaskListener)}. */
    private @CheckForNull List<SCMCheckout> checkouts;
    // TODO could use a WeakReference to reduce memory, but that complicates how we add to it incrementally; perhaps keep a List<WeakReference<ChangeLogSet<?>>>
    private transient List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets;

    /** True when first started, false when running after a restart. */
    private transient boolean firstTime;

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

    private StreamBuildListener createBuildListener() throws IOException, InterruptedException {
        // TODO introduce API to wrap out log location (cf. Owner.getLog)
        OutputStream logger = new FileOutputStream(getLogFile(), true);
        // TODO JENKINS-30777 decorate with ConsoleLogFilter.all()
        return new StreamBuildListener(logger, Charsets.UTF_8);
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
            listener = createBuildListener();
            listener.started(getCauses());
            RunListener.fireStarted(this, listener);
            updateSymlinks(listener);
            FlowDefinition definition = getParent().getDefinition();
            if (definition == null) {
                throw new AbortException("No flow definition, cannot run");
            }
            Owner owner = new Owner(this);
            
            FlowExecution newExecution = definition.create(owner, listener, getAllActions());
            FlowExecutionList.get().register(owner);
            newExecution.addListener(new GraphL());
            newExecution.addListener(new NodePrintListener());
            completed = new AtomicBoolean();
            execution = newExecution;
            newExecution.start();
            executionPromise.set(newExecution);
        } catch (Throwable x) {
            execution = null; // ensures isInProgress returns false
            finish(Result.FAILURE, x);
            try {
                executionPromise.setException(x);
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
                        if (execution == null) {
                            return;
                        }
                        Executor executor = getExecutor();
                        try {
                            execution.interrupt(executor.abortResult());
                        } catch (Exception x) {
                            LOGGER.log(Level.WARNING, null, x);
                        }
                        executor.recordCauseOfInterruption(WorkflowRun.this, listener);
                        printLater("term", "Click here to forcibly terminate running steps");
                    }
                });
            }
            @Override public boolean blocksRestart() {
                return execution != null && execution.blocksRestart();
            }
            @Override public boolean displayCell() {
                return blocksRestart();
            }
        };
    }

    private void printLater(final String url, final String message) {
        Timer.get().schedule(new Runnable() {
            @Override public void run() {
                if (!isInProgress()) {
                    return;
                }
                listener.getLogger().println(POSTHyperlinkNote.encodeTo("/" + getUrl() + url, message));
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
        Futures.addCallback(execution.getCurrentExecutions(/* cf. JENKINS-26148 */true), new FutureCallback<List<StepExecution>>() {
            @Override public void onSuccess(List<StepExecution> l) {
                for (StepExecution e : Iterators.reverse(l)) {
                    StepContext context = e.getContext();
                    context.onFailure(x);
                    try {
                        FlowNode n = context.get(FlowNode.class);
                        if (n != null) {
                            listener.getLogger().println("Terminating " + n.getDisplayFunctionName());
                        }
                    } catch (Exception x) {
                        LOGGER.log(Level.FINE, null, x);
                    }
                }
            }
            @Override public void onFailure(Throwable t) {}
        });
        printLater("kill", "Click here to forcibly kill entire build");
    }

    /** Immediately kills the build. */
    @RequirePOST
    public void doKill() {
        checkPermission(Item.CANCEL);
        if (!isBuilding() || /* probably redundant, but just to be sure */ execution == null) {
            return;
        }
        if (listener != null) {
            listener.getLogger().println("Hard kill!");
        }
        execution = null; // ensures isInProgress returns false
        FlowInterruptedException suddenDeath = new FlowInterruptedException(Result.ABORTED);
        finish(Result.ABORTED, suddenDeath);
        executionPromise.setException(suddenDeath);
        // TODO CpsFlowExecution.onProgramEnd does some cleanup which we cannot access here; perhaps need a FlowExecution.halt(Throwable) API?
    }

    @Override public EnvVars getEnvironment(TaskListener listener) throws IOException, InterruptedException {
        EnvVars env = super.getEnvironment(listener);
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
        super.onLoad();
        if (completed != null) {
            throw new IllegalStateException("double onLoad of " + this);
        }
        if (execution != null) {
            try {
                execution.onLoad(new Owner(this));
            } catch (IOException x) {
                LOGGER.log(Level.WARNING, null, x);
                execution = null; // probably too broken to use
            }
        }
        if (execution != null) {
            execution.addListener(new GraphL());
            execution.addListener(new NodePrintListener());
            executionPromise.set(execution);
            if (!execution.isComplete()) {
                // we've been restarted while we were running. let's get the execution going again.
                try {
                    // TODO add a @Terminator to close the old listener in case a new set of objects gets loaded after in-VM restart and starts writing to the same file
                    listener = createBuildListener();
                    listener.getLogger().println("Resuming build at " + new Date() + " after Jenkins restart");
                } catch (IOException | InterruptedException x) {
                    LOGGER.log(Level.WARNING, null, x);
                    listener = new StreamBuildListener(new NullStream());
                }
                completed = new AtomicBoolean();
                Timer.get().submit(new Runnable() { // JENKINS-31614
                    @Override public void run() {
                        Queue.getInstance().schedule(new AfterRestartTask(WorkflowRun.this), 0);
                    }
                });
            }
        }
        checkouts(null); // only for diagnostics
        synchronized (LOADING_RUNS) {
            LOADING_RUNS.remove(key()); // or could just make the value type be WeakReference<WorkflowRun>
            LOADING_RUNS.notifyAll();
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
        setResult(r);
        LOGGER.log(Level.INFO, "{0} completed: {1}", new Object[] {this, getResult()});
        // TODO set duration
        if (listener == null) {
            LOGGER.log(Level.WARNING, this + " failed to start", t);
        } else {
            RunListener.fireCompleted(WorkflowRun.this, listener);
            if (t instanceof AbortException) {
                listener.error(t.getMessage());
            } else if (t instanceof FlowInterruptedException) {
                ((FlowInterruptedException) t).handle(this, listener);
            } else if (t != null) {
                t.printStackTrace(listener.getLogger());
            }
            listener.finished(getResult());
            listener.closeQuietly();
        }
        duration = Math.max(0, System.currentTimeMillis() - getStartTimeInMillis());
        try {
            save();
            getParent().logRotate();
        } catch (Exception x) {
            LOGGER.log(Level.WARNING, null, x);
        }
        onEndBuilding();
        if (completed != null) {
            synchronized (completed) {
                completed.set(true);
            }
        }
        Executor executor = getExecutor();
        if (executor != null) {
            AsynchronousExecution asynchronousExecution = executor.getAsynchronousExecution();
            if (asynchronousExecution != null) {
                asynchronousExecution.completed(null);
            }
        }
        FlowExecutionList.get().unregister(new Owner(this));
        try {
            StashManager.maybeClearAll(this);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "failed to clean up stashes from " + this, x);
        }
    }

    @Override public void deleteArtifacts() throws IOException {
        super.deleteArtifacts();
        StashManager.clearAll(this);
    }

    /**
     * Gets the associated execution state.
     * @return non-null after the flow has started, even after finished (but may be null temporarily when about to start, or if starting failed)
     */
    public @CheckForNull FlowExecution getExecution() {
        return execution;
    }

    /**
     * Allows the caller to block on {@link FlowExecution}, which gets created relatively quickly
     * after the build gets going.
     */
    public ListenableFuture<FlowExecution> getExecutionPromise() {
        return executionPromise;
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
        return execution != null && !execution.isComplete() && (completed == null || !completed.get());
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
                while (r.execution == null && LOADING_RUNS.containsKey(key())) {
                    try {
                        LOADING_RUNS.wait();
                    } catch (InterruptedException x) {
                        LOGGER.log(Level.WARNING, "failed to wait for " + r + " to be loaded", x);
                        break;
                    }
                }
            }
            FlowExecution exec = r.execution;
            if (exec != null) {
                return exec;
            } else {
                throw new IOException(r + " did not yet start");
            }
        }
        @Override public FlowExecution getOrNull() {
            try {
                ListenableFuture<FlowExecution> promise = run().getExecutionPromise();
                if (promise.isDone()) {
                    return promise.get();
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
            StreamBuildListener l = run().listener;
            if (l != null) {
                return l;
            } else {
                // Seems to happen at least once during resume, but anyway TryRepeatedly will call this method again, rather than caching the result.
                LOGGER.log(Level.FINE, "No listener yet for {0}", this);
                return TaskListener.NULL;
            }
        }
        @Override public InputStream getLog() throws IOException {
            // TODO introduce API to swap out log location
            return run().getLogInputStream();
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

    private final class GraphL implements GraphListener {
        @Override public void onNewHead(FlowNode node) {
            node.addAction(new TimingAction());
            if (node instanceof FlowEndNode) {
                finish(((FlowEndNode) node).getResult(), execution != null ? execution.getCauseOfFailure() : null);
            } else {
                try {
                    save();
                } catch (IOException x) {
                    LOGGER.log(Level.WARNING, null, x);
                }
            }
        }
    }

    /**
     * Prints nodes as they appear (including block start and end nodes).
     */
    private final class NodePrintListener implements GraphListener.Synchronous {
        @Override public void onNewHead(FlowNode node) {
            WorkflowRunConsoleNote.print(node.getDisplayFunctionName(), listener);
        }
    }

    @Override public AnnotatedLargeText getLogText() {
        return new PipelineLargeText(this);
    }

    // TODO also override getLogInputStream, getLogReader, getLogFile, getLog(), getLog(int); and maybe set Run.charset for the benefit of callers of getCharset, namely ConsoleCommand

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

}
