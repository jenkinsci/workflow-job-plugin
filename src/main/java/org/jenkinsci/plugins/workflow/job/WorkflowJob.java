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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.BulkChange;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Action;
import hudson.model.BallColor;
import hudson.model.BuildAuthorizationToken;
import hudson.model.BuildableItem;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Items;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.ResourceList;
import hudson.model.Run;
import hudson.model.RunMap;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.TopLevelItemDescriptor;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SCMListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.SubTask;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;
import hudson.search.SearchIndexBuilder;
import hudson.security.ACL;
import hudson.slaves.WorkspaceList;
import hudson.triggers.SCMTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.AlternativeUiTextProvider;
import hudson.util.DescribableList;
import hudson.widgets.HistoryWidget;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import jenkins.model.BlockedBecauseOfBuildInProgress;

import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.lazy.LazyBuildMixIn;
import jenkins.triggers.SCMTriggerItem;
import jenkins.util.TimeDuration;
import net.sf.json.JSONObject;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinitionDescriptor;
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.interceptor.RequirePOST;

@SuppressWarnings({"unchecked", "rawtypes"})
public final class WorkflowJob extends Job<WorkflowJob,WorkflowRun> implements BuildableItem, LazyBuildMixIn.LazyLoadingJob<WorkflowJob,WorkflowRun>, ParameterizedJobMixIn.ParameterizedJob, TopLevelItem, Queue.FlyweightTask, SCMTriggerItem {

    private static final Logger LOGGER = Logger.getLogger(WorkflowJob.class.getName());

    private FlowDefinition definition;
    /** @deprecated - use {@link PipelineTriggersJobProperty} */
    private DescribableList<Trigger<?>,TriggerDescriptor> triggers = new DescribableList<>(this);
    private volatile Integer quietPeriod;
    @SuppressWarnings("deprecation")
    private hudson.model.BuildAuthorizationToken authToken;
    private transient LazyBuildMixIn<WorkflowJob,WorkflowRun> buildMixIn;
    /** @deprecated replaced by {@link DisableConcurrentBuildsJobProperty} */
    private @CheckForNull Boolean concurrentBuild;
    /**
     * Map from {@link SCM#getKey} to last version we encountered during polling.
     * TODO is it important to persist this? {@link hudson.model.AbstractProject#pollingBaseline} is not persisted.
     */
    private transient volatile Map<String,SCMRevisionState> pollingBaselines;
    private volatile boolean disabled;

    public WorkflowJob(ItemGroup parent, String name) {
        super(parent, name);
        buildMixIn = createBuildMixIn();
    }

    @Override public void onCreatedFromScratch() {
        super.onCreatedFromScratch();
        buildMixIn.onCreatedFromScratch();
    }

    @Override public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        super.onLoad(parent, name);

        if (buildMixIn == null) {
            buildMixIn = createBuildMixIn();
        }
        buildMixIn.onLoad(parent, name);
        if (triggers != null && !triggers.isEmpty()) {
            setTriggers(triggers.toList());
        }
        if (concurrentBuild != null) {
            setConcurrentBuild(concurrentBuild);
        }

        getTriggersJobProperty().stopTriggers();
        getTriggersJobProperty().startTriggers(Items.currentlyUpdatingByXml());
    }

    private LazyBuildMixIn<WorkflowJob,WorkflowRun> createBuildMixIn() {
        return new LazyBuildMixIn<WorkflowJob,WorkflowRun>() {
            @Override protected WorkflowJob asJob() {
                return WorkflowJob.this;
            }
            @Override protected Class<WorkflowRun> getBuildClass() {
                return WorkflowRun.class;
            }
        };
    }

    private ParameterizedJobMixIn<WorkflowJob,WorkflowRun> createParameterizedJobMixIn() {
        return new ParameterizedJobMixIn<WorkflowJob,WorkflowRun>() {
            @Override protected WorkflowJob asJob() {
                return WorkflowJob.this;
            }
        };
    }

    public FlowDefinition getDefinition() {
        return definition;
    }

    public void setDefinition(FlowDefinition definition) {
        this.definition = definition;
        try {
            save();
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "could not save " + this, x);
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void submit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException, Descriptor.FormException {
        super.submit(req, rsp);
        JSONObject json = req.getSubmittedForm();
        definition = req.bindJSON(FlowDefinition.class, json.getJSONObject("definition"));
        authToken = hudson.model.BuildAuthorizationToken.create(req);

        if (req.getParameter("hasCustomQuietPeriod") != null) {
            quietPeriod = Integer.parseInt(req.getParameter("quiet_period"));
        } else {
            quietPeriod = null;
        }

        makeDisabled(json.optBoolean("disable"));
    }


    @Override public void addProperty(JobProperty jobProp) throws IOException {
        super.addProperty(jobProp);
        getTriggersJobProperty().stopTriggers();
        getTriggersJobProperty().startTriggers(Items.currentlyUpdatingByXml());
    }

    @Override public boolean isBuildable() {
        for (JobProperty<?> property : properties) {
            if (property instanceof WorkflowJobProperty) {
                Boolean buildable = ((WorkflowJobProperty) property).isBuildable();
                if (buildable != null) {
                    return buildable;
                }
            }
        }
        // TODO https://github.com/jenkinsci/jenkins/pull/2866: return ParameterizedJobMixIn.ParameterizedJob.super.isBuildable();
        return !isDisabled() && !isHoldOffBuildUntilSave();
    }

    @Override protected RunMap<WorkflowRun> _getRuns() {
        return buildMixIn._getRuns();
    }

    @Override public LazyBuildMixIn<WorkflowJob,WorkflowRun> getLazyBuildMixIn() {
        return buildMixIn;
    }

    @Override protected void removeRun(WorkflowRun run) {
        buildMixIn.removeRun(run);
    }

    @Override @Deprecated public WorkflowRun getBuild(String id) {
        return buildMixIn.getBuild(id);
    }

    @Override public WorkflowRun getBuildByNumber(int n) {
        return buildMixIn.getBuildByNumber(n);
    }

    @Override public WorkflowRun getFirstBuild() {
        return buildMixIn.getFirstBuild();
    }

    @Override public WorkflowRun getLastBuild() {
        return buildMixIn.getLastBuild();
    }

    @Override public WorkflowRun getNearestBuild(int n) {
        return buildMixIn.getNearestBuild(n);
    }

    @Override public WorkflowRun getNearestOldBuild(int n) {
        return buildMixIn.getNearestOldBuild(n);
    }

    @Override protected HistoryWidget createHistoryWidget() {
        return buildMixIn.createHistoryWidget();
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    @Override public Queue.Executable createExecutable() throws IOException {
        if (isDisabled()) {
            return null;
        }
        return buildMixIn.newBuild();
    }

    @Deprecated
    @Override public boolean scheduleBuild() {
        return createParameterizedJobMixIn().scheduleBuild();
    }

    @Override public boolean scheduleBuild(Cause c) {
        return createParameterizedJobMixIn().scheduleBuild(c);
    }

    @Deprecated
    @Override public boolean scheduleBuild(int quietPeriod) {
        return createParameterizedJobMixIn().scheduleBuild(quietPeriod);
    }

    @Override public boolean scheduleBuild(int quietPeriod, Cause c) {
        return createParameterizedJobMixIn().scheduleBuild(quietPeriod, c);
    }

    @Override public @CheckForNull QueueTaskFuture<WorkflowRun> scheduleBuild2(int quietPeriod, Action... actions) {
        return createParameterizedJobMixIn().scheduleBuild2(quietPeriod, actions);
    }

    public void doBuild(StaplerRequest req, StaplerResponse rsp, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        createParameterizedJobMixIn().doBuild(req, rsp, delay);
    }

    public void doBuildWithParameters(StaplerRequest req, StaplerResponse rsp, @QueryParameter TimeDuration delay) throws IOException, ServletException {
        createParameterizedJobMixIn().doBuildWithParameters(req, rsp, delay);
    }

    @RequirePOST public void doCancelQueue(StaplerRequest req, StaplerResponse rsp ) throws IOException, ServletException {
        createParameterizedJobMixIn().doCancelQueue(req, rsp);
    }

    @Override protected SearchIndexBuilder makeSearchIndex() {
        return createParameterizedJobMixIn().extendSearchIndex(super.makeSearchIndex());
    }

    public boolean isParameterized() {
        return createParameterizedJobMixIn().isParameterized();
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 @Override
    public boolean isDisabled() {
        return disabled;
    }

    @Restricted(DoNotUse.class)
    // TODO https://github.com/jenkinsci/jenkins/pull/2866 @Override
    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 @Override
    public boolean supportsMakeDisabled() {
        return true;
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    public void makeDisabled(boolean b) throws IOException {
        if (isDisabled() == b) {
            return; // noop
        }
        if (b && !supportsMakeDisabled()) {
            return; // do nothing if the disabling is unsupported
        }
        setDisabled(b);
        if (b) {
            Jenkins.getActiveInstance().getQueue().cancel(this);
        }
        save();
        ItemListener.fireOnUpdated(this);
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    @RequirePOST
    public HttpResponse doDisable() throws IOException, ServletException {
        checkPermission(CONFIGURE);
        makeDisabled(true);
        return new HttpRedirect(".");
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    @RequirePOST
    public HttpResponse doEnable() throws IOException, ServletException {
        checkPermission(CONFIGURE);
        makeDisabled(false);
        return new HttpRedirect(".");
    }

    @Override public BallColor getIconColor() {
        if (isDisabled()) {
            return isBuilding() ? BallColor.DISABLED_ANIME : BallColor.DISABLED;
        } else {
            return super.getIconColor();
        }
    }

    @SuppressWarnings("deprecation")
    @Override public hudson.model.BuildAuthorizationToken getAuthToken() {
        return authToken;
    }

    @Override public int getQuietPeriod() {
        return quietPeriod != null ? quietPeriod : Jenkins.getActiveInstance().getQuietPeriod();
    }

    @Restricted(DoNotUse.class) // for config-quietPeriod.jelly
    public boolean getHasCustomQuietPeriod() {
        return quietPeriod!=null;
    }

    public void setQuietPeriod(Integer seconds) throws IOException {
        this.quietPeriod = seconds;
        save();
    }

    @Override public String getBuildNowText() {
        return createParameterizedJobMixIn().getBuildNowText();
    }

    @Override public boolean isBuildBlocked() {
        return getCauseOfBlockage() != null;
    }

    @Deprecated
    @Override public String getWhyBlocked() {
        CauseOfBlockage c = getCauseOfBlockage();
        return c != null ? c.getShortDescription() : null;
    }

    @Override public CauseOfBlockage getCauseOfBlockage() {
        if (isLogUpdated() && !isConcurrentBuild()) {
            WorkflowRun lastBuild = getLastBuild();
            if (lastBuild != null) {
                return new BlockedBecauseOfBuildInProgress(lastBuild);
            } // else race condition, cf. AbstractProject
        }
        return null;
    }

    @Exported
    @Override public boolean isConcurrentBuild() {
        return getProperty(DisableConcurrentBuildsJobProperty.class) == null;
    }

    public void setConcurrentBuild(boolean b) throws IOException {
        concurrentBuild = null;

        boolean propertyExists = getProperty(DisableConcurrentBuildsJobProperty.class) != null;

        // If the property exists, concurrent builds are disabled. So if the argument here is true and the
        // property exists, we need to remove the property, while if the argument is false and the property
        // does not exist, we need to add the property. Yay for flipping boolean values around!
        if (propertyExists == b) {
            BulkChange bc = new BulkChange(this);
            try {
                removeProperty(DisableConcurrentBuildsJobProperty.class);
                if (!b) {
                    addProperty(new DisableConcurrentBuildsJobProperty());
                }
                bc.commit();
            } finally {
                bc.abort();
            }
        }
    }

    @Override public ACL getACL() {
        ACL acl = super.getACL();
        for (JobProperty<?> property : properties) {
            if (property instanceof WorkflowJobProperty) {
                acl = ((WorkflowJobProperty) property).decorateACL(acl);
            }
        }
        return acl;
    }

    @Override public void checkAbortPermission() {
        checkPermission(CANCEL);
    }

    @Override public boolean hasAbortPermission() {
        return hasPermission(CANCEL);
    }

    @Override public Collection<? extends SubTask> getSubTasks() {
        // TODO mostly copied from AbstractProject, except SubTaskContributor is not available:
        List<SubTask> subTasks = new ArrayList<>();
        subTasks.add(this);
        for (JobProperty<? super WorkflowJob> p : properties) {
            subTasks.addAll(p.getSubTasks());
        }
        return subTasks;
    }

    @Override public Authentication getDefaultAuthentication() {
        return ACL.SYSTEM;
    }

    @Override public Authentication getDefaultAuthentication(Queue.Item item) {
        return getDefaultAuthentication();
    }

    @SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification="TODO 1.653+ switch to Jenkins.getInstanceOrNull")
    @Override public Label getAssignedLabel() {
        Jenkins j = Jenkins.getInstance();
        if (j == null) {
            return null;
        }
        return j.getSelfLabel();
    }

    @Override public Node getLastBuiltOn() {
        return Jenkins.getInstance();
    }

    @Override public Queue.Task getOwnerTask() {
        return this;
    }

    @Override public Object getSameNodeConstraint() {
        return this;
    }

    @Override public ResourceList getResourceList() {
        return ResourceList.EMPTY;
    }

    @Override public String getPronoun() {
        return AlternativeUiTextProvider.get(PRONOUN, this, "Pipeline");
    }

    @Override public TopLevelItemDescriptor getDescriptor() {
        return (DescriptorImpl) Jenkins.getActiveInstance().getDescriptorOrDie(WorkflowJob.class);
    }

    @Override public Map<TriggerDescriptor, Trigger<?>> getTriggers() {
        return getTriggersJobProperty().getTriggersMap();
    }

    public PipelineTriggersJobProperty getTriggersJobProperty() {
        PipelineTriggersJobProperty triggerProp = getProperty(PipelineTriggersJobProperty.class);

        if (triggerProp == null) {
            triggerProp = new PipelineTriggersJobProperty(new ArrayList<Trigger>());
        }

        return triggerProp;
    }

    @Restricted(NoExternalUse.class)
    public void addTriggersJobPropertyWithoutStart(PipelineTriggersJobProperty prop) throws IOException {
        super.addProperty(prop);
    }

    public void setTriggers(List<Trigger<?>> inputTriggers) throws IOException {
        triggers = null;
        BulkChange bc = new BulkChange(this);
        try {
            PipelineTriggersJobProperty originalProp = getTriggersJobProperty();

            removeProperty(PipelineTriggersJobProperty.class);

            PipelineTriggersJobProperty triggerProp = new PipelineTriggersJobProperty(null);
            triggerProp.setTriggers(inputTriggers);

            addProperty(triggerProp);
            bc.commit();

            originalProp.stopTriggers();

            // No longer need to start triggers here - that's done by when we add the property.
        } finally {
            bc.abort();
        }
    }

    public void addTrigger(Trigger trigger) throws IOException {
        BulkChange bc = new BulkChange(this);
        try {
            PipelineTriggersJobProperty originalProp = getTriggersJobProperty();
            Trigger old = originalProp.getTriggerForDescriptor(trigger.getDescriptor());
            if (old != null) {
                originalProp.removeTrigger(old);
                old.stop();
            }

            originalProp.addTrigger(trigger);
            removeProperty(PipelineTriggersJobProperty.class);

            addProperty(originalProp);
            bc.commit();
        } finally {
            bc.abort();
        }
    }

    @Override
    public void removeProperty(JobProperty jobProperty) throws IOException {
        // Need to make sure we stop any triggers.
        if (jobProperty instanceof PipelineTriggersJobProperty) {
            ((PipelineTriggersJobProperty)jobProperty).stopTriggers();
        }

        super.removeProperty(jobProperty);
    }

    @Override
    public void addAction(Action a) {
        super.getActions().add(a);
    }

    @Override
    public void replaceAction(Action a) {
        // CopyOnWriteArrayList does not support Iterator.remove, so need to do it this way:
        List<Action> old = new ArrayList<>(1);
        List<Action> current = super.getActions();
        for (Action a2 : current) {
            if (a2.getClass() == a.getClass()) {
                old.add(a2);
            }
        }
        current.removeAll(old);
        addAction(a);
    }

    @Override public Item asItem() {
        return this;
    }

    @Override public SCMTrigger getSCMTrigger() {
        for (Trigger t : getTriggersJobProperty().getTriggers()) {
            if (t instanceof SCMTrigger) {
                return (SCMTrigger) t;
            }
        }

        return null;
    }

    @Override public Collection<? extends SCM> getSCMs() {
        WorkflowRun b = getLastSuccessfulBuild();
        if (b == null) {
            b = getLastCompletedBuild();
        }
        if (b == null) {
            return Collections.emptySet();
        }
        Map<String,SCM> scms = new LinkedHashMap<>();
        for (WorkflowRun.SCMCheckout co : b.checkouts(null)) {
            scms.put(co.scm.getKey(), co.scm);
        }
        return scms.values();
    }

    public @CheckForNull SCM getTypicalSCM() {
        SCM typical = null;
        for (SCM scm : getSCMs()) {
            if (typical == null) {
                typical = scm;
            } else if (typical.getDescriptor() != scm.getDescriptor()) {
                return null;
            }
        }
        return typical;
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    public boolean schedulePolling() {
        if (isDisabled()) {
            return false;
        }
        SCMTrigger scmt = getSCMTrigger();
        if (scmt == null) {
            return false;
        }
        scmt.run();
        return true;
    }

    // TODO https://github.com/jenkinsci/jenkins/pull/2866 remove override
    @SuppressWarnings("deprecation")
    public void doPolling(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        BuildAuthorizationToken.checkPermission((Job) this, getAuthToken(), req, rsp);
        schedulePolling();
        rsp.sendRedirect(".");
    }

    @SuppressFBWarnings(value="RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE", justification="TODO 1.653+ switch to Jenkins.getInstanceOrNull")
    @Override public PollingResult poll(TaskListener listener) {
        if (!isBuildable()) {
            listener.getLogger().println("Build disabled");
            return PollingResult.NO_CHANGES;
        }
        // TODO 2.11+ call SCMDecisionHandler
        // TODO call SCMPollListener
        WorkflowRun lastBuild = getLastBuild();
        if (lastBuild == null) {
            listener.getLogger().println("no previous build to compare to");
            // Note that we have no equivalent of AbstractProject.NoSCM because without an initial build we do not know if this project has any SCM at all.
            return Queue.getInstance().contains(this) ? PollingResult.NO_CHANGES : PollingResult.BUILD_NOW;
        }
        WorkflowRun perhapsCompleteBuild = getLastSuccessfulBuild();
        if (perhapsCompleteBuild == null) {
            perhapsCompleteBuild = lastBuild;
        }
        if (pollingBaselines == null) {
            pollingBaselines = new ConcurrentHashMap<>();
        }
        PollingResult result = PollingResult.NO_CHANGES;
        for (WorkflowRun.SCMCheckout co : perhapsCompleteBuild.checkouts(listener)) {
            if (!co.scm.supportsPolling()) {
                listener.getLogger().println("polling not supported from " + co.workspace + " on " + co.node);
                continue;
            }
            String key = co.scm.getKey();
            SCMRevisionState pollingBaseline = pollingBaselines.get(key);
            if (pollingBaseline == null) {
                pollingBaseline = co.pollingBaseline; // after a restart, transient cache will be empty
            }
            if (pollingBaseline == null) {
                listener.getLogger().println("no polling baseline in " + co.workspace + " on " + co.node);
                continue;
            }
            try {
                FilePath workspace;
                Launcher launcher;
                WorkspaceList.Lease lease;
                if (co.scm.requiresWorkspaceForPolling()) {
                    Jenkins j = Jenkins.getInstance();
                    if (j == null) {
                        listener.error("Jenkins is shutting down");
                        continue;
                    }
                    Computer c = j.getComputer(co.node);
                    if (c == null) {
                        listener.error("no such computer " + co.node);
                        continue;
                    }
                    workspace = new FilePath(c.getChannel(), co.workspace);
                    launcher = workspace.createLauncher(listener).decorateByEnv(getEnvironment(c.getNode(), listener));
                    lease = c.getWorkspaceList().acquire(workspace, !isConcurrentBuild());
                } else {
                    workspace = null;
                    launcher = null;
                    lease = null;
                }
                PollingResult r;
                try {
                    r = co.scm.compareRemoteRevisionWith(this, launcher, workspace, listener, pollingBaseline);
                    if (r.remote != null) {
                        pollingBaselines.put(key, r.remote);
                    }
                } finally {
                    if (lease != null) {
                        lease.release();
                    }
                }
                if (r.change.compareTo(result.change) > 0) {
                    result = r; // note that if we are using >1 checkout, we can clobber baseline/remote here; anyway SCMTrigger only calls hasChanges()
                }
            } catch (AbortException x) {
                listener.error("polling failed in " + co.workspace + " on " + co.node + ": " + x.getMessage());
            } catch (Exception x) {
                listener.error("polling failed in " + co.workspace + " on " + co.node).println(Functions.printThrowable(x).trim()); // TODO 2.43+ use Functions.printStackTrace
            }
        }
        return result;
    }
    @Extension public static final class SCMListenerImpl extends SCMListener {
        @Override public void onCheckout(Run<?,?> build, SCM scm, FilePath workspace, TaskListener listener, File changelogFile, SCMRevisionState pollingBaseline) throws Exception {
            if (build instanceof WorkflowRun && pollingBaseline != null) {
                WorkflowJob job = ((WorkflowRun) build).getParent();
                if (job.pollingBaselines == null) {
                    job.pollingBaselines = new ConcurrentHashMap<>();
                }
                job.pollingBaselines.put(scm.getKey(), pollingBaseline);
            }
        }
    }

    @Override protected void performDelete() throws IOException, InterruptedException {
        makeDisabled(true);
        // TODO call SCM.processWorkspaceBeforeDeletion
        super.performDelete();
    }

    @Initializer(before=InitMilestone.EXTENSIONS_AUGMENTED)
    public static void alias() {
        Items.XSTREAM2.alias("flow-definition", WorkflowJob.class);
        WorkflowRun.alias();
    }

    @Extension(ordinal=1) public static final class DescriptorImpl extends TopLevelItemDescriptor {

        @Override public String getDisplayName() {
            return "Pipeline";
        }

        @Override public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new WorkflowJob(parent, name);
        }

        /**
         * Needed if it wants Pipeline jobs are categorized in Jenkins 2.x.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         * TODO: Replace to {@code StandaloneProjectsCategory.ID}
         *
         * @return A string it represents a ItemCategory identifier.
         */
        public String getCategoryId() {
            return "standalone-projects";
        }

        /**
         * Needed if it wants Pipeline jobs are categorized in Jenkins 2.x.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string with the Item description.
         */
        public String getDescription() {
            return Messages.WorkflowJob_Description();
        }

        /**
         * Needed if it wants Pipeline jobs are categorized in Jenkins 2.x.
         *
         * TODO: Override when the baseline is upgraded to 2.x
         *
         * @return A string it represents a URL pattern to get the Item icon in different sizes.
         */
        public String getIconFilePathPattern() {
            return "plugin/workflow-job/images/:size/pipelinejob.png";
        }

        /** TODO JENKINS-20020 can delete this in case {@code f:dropdownDescriptorSelector} defaults to applying {@code h.filterDescriptors} */
        @Restricted(DoNotUse.class) // Jelly
        public Collection<FlowDefinitionDescriptor> getDefinitionDescriptors(WorkflowJob context) {
            return DescriptorVisibilityFilter.apply(context, ExtensionList.lookup(FlowDefinitionDescriptor.class));
        }

    }

}
