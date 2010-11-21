/*******************************************************************************
 * Copyright (c) 2010 JVM Monitor project. All rights reserved. 
 * 
 * This code is distributed under the terms of the Eclipse Public License v1.0
 * which is available at http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.jvmmonitor.internal.ui.properties.timeline;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.jvmmonitor.core.IActiveJvm;
import org.jvmmonitor.core.JvmCoreException;
import org.jvmmonitor.core.JvmModelEvent;
import org.jvmmonitor.core.JvmModelEvent.State;
import org.jvmmonitor.core.mbean.IMBeanServer;
import org.jvmmonitor.core.mbean.IMBeanServerChangeListener;
import org.jvmmonitor.core.mbean.IMonitoredMXBeanAttribute;
import org.jvmmonitor.core.mbean.IMonitoredMXBeanGroup;
import org.jvmmonitor.core.mbean.MBeanServerEvent;
import org.jvmmonitor.internal.ui.IHelpContextIds;
import org.jvmmonitor.internal.ui.RefreshJob;
import org.jvmmonitor.internal.ui.actions.RefreshAction;
import org.jvmmonitor.internal.ui.properties.AbstractJvmPropertySection;
import org.jvmmonitor.internal.ui.properties.memory.GarbageCollectorAction;
import org.jvmmonitor.ui.Activator;

/**
 * The timeline section.
 */
public class TimelineSection extends AbstractJvmPropertySection {

    /** The charts. */
    private List<TimelineChart> charts;

    /** The action to clear timeline data. */
    private ClearTimelineDataAction clearAction;

    /** The action to refresh section. */
    private RefreshAction refreshAction;

    /** The action to create a new chart. */
    private NewChartAction newChartAction;

    /** The action to load chart set. */
    private LoadChartSetAction loadChartSetAction;

    /** The action to run garbage collector. */
    private GarbageCollectorAction garbageCollectorAction;

    /** The action to save chart set as given name. */
    private SaveChartSetAsAction saveChartSetAsAction;

    /** The separator. */
    private Separator separator;

    /** The MBean server change listener. */
    private IMBeanServerChangeListener mBeanServerChangeListener;

    /** The section container. */
    private Composite sectionContainer;

    /** The state indicating if the default chart set have to be loaded. */
    private boolean forceLoadDefaultChartSet;

    /**
     * The constructor.
     */
    public TimelineSection() {
        charts = new ArrayList<TimelineChart>();
        clearAction = new ClearTimelineDataAction(this);
        refreshAction = new RefreshAction(this);
        newChartAction = new NewChartAction(this);
        loadChartSetAction = new LoadChartSetAction(this);
        garbageCollectorAction = new GarbageCollectorAction(this);
        saveChartSetAsAction = new SaveChartSetAsAction(this);
        separator = new Separator();
        forceLoadDefaultChartSet = true;
    }

    /*
     * @see AbstractJvmPropertySection#createControls(Composite)
     */
    @Override
    protected void createControls(Composite parent) {
        sectionContainer = parent;
        parent.setBackground(Display.getDefault().getSystemColor(
                SWT.COLOR_LIST_BACKGROUND));
        GridLayout layout = new GridLayout(2, true);
        layout.horizontalSpacing = 15;
        sectionContainer.setLayout(layout);

        mBeanServerChangeListener = new IMBeanServerChangeListener() {
            @Override
            public void serverChanged(MBeanServerEvent event) {
                new RefreshJob("Reconstruct charts", getId()) {
                    @Override
                    protected void refreshModel(IProgressMonitor monitor) {
                        // do nothing
                    }

                    @Override
                    protected void refreshUI() {
                        IActiveJvm jvm = getJvm();
                        if (jvm != null) {
                            reconstructCharts(jvm, false);
                        }
                    }
                }.schedule();
            }
        };

        PlatformUI.getWorkbench().getHelpSystem()
                .setHelp(parent, IHelpContextIds.TIMELINE_PAGE);
    }

    /*
     * @see AbstractJvmPropertySection#jvmModelChanged(JvmModelEvent)
     */
    @Override
    public void jvmModelChanged(JvmModelEvent event) {
        super.jvmModelChanged(event);

        if (event.state == State.JvmConnected && !sectionContainer.isDisposed()) {
            final IActiveJvm newJvm = (IActiveJvm) event.jvm;
            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    reconstructCharts(newJvm, true);
                }
            });
            newJvm.getMBeanServer().addServerChangeListener(
                    mBeanServerChangeListener);
        }
    }

    /*
     * @see AbstractJvmPropertySection#setInput(IWorkbenchPart, ISelection,
     * IActiveJvm, IActiveJvm)
     */
    @Override
    protected void setInput(IWorkbenchPart part, ISelection selection,
            IActiveJvm newJvm, IActiveJvm oldJvm) {
        if (newJvm == null || newJvm.equals(oldJvm)) {
            return;
        }

        reconstructCharts(newJvm, false);

        if (oldJvm != null && mBeanServerChangeListener != null) {
            IMBeanServer server = oldJvm.getMBeanServer();
            if (server != null) {
                server.removeServerChangeListener(mBeanServerChangeListener);
            }
        }

        newJvm.getMBeanServer().addServerChangeListener(
                mBeanServerChangeListener);
    }

    /*
     * @see AbstractPropertySection#refresh()
     */
    @Override
    public void refresh() {
        refreshConnectionIndicator();

        IActiveJvm jvm = getJvm();
        if (jvm == null || !jvm.isConnected() || suspendRefresh
                || sectionContainer.isDisposed()) {
            return;
        }

        for (TimelineChart chart : charts) {
            if (!chart.isDisposed()) {
                chart.refresh();
            }
        }
    }

    /*
     * @see AbstractJvmPropertySection#addToolBarActions(IToolBarManager)
     */
    @Override
    protected void addToolBarActions(IToolBarManager manager) {
        manager.insertAfter("defaults", separator); //$NON-NLS-1$
        if (manager.find(refreshAction.getId()) == null) {
            manager.insertAfter("defaults", refreshAction); //$NON-NLS-1$
        }
        if (manager.find(clearAction.getId()) == null) {
            manager.insertAfter("defaults", clearAction); //$NON-NLS-1$
        }
        if (manager.find(newChartAction.getId()) == null) {
            manager.insertAfter("defaults", newChartAction); //$NON-NLS-1$
        }
    }

    /*
     * @see AbstractJvmPropertySection#removeToolBarActions(IToolBarManager)
     */
    @Override
    protected void removeToolBarActions(IToolBarManager manager) {
        manager.remove(separator);
        manager.remove(refreshAction.getId());
        manager.remove(clearAction.getId());
        manager.remove(newChartAction.getId());
    }

    /*
     * @see AbstractJvmPropertySection#addLocalMenus(IMenuManager)
     */
    @Override
    protected void addLocalMenus(IMenuManager manager) {
        manager.add(saveChartSetAsAction);
        manager.add(loadChartSetAction);
        manager.add(separator);
    }

    /*
     * @see AbstractJvmPropertySection#removeLocalMenus(IMenuManager)
     */
    @Override
    protected void removeLocalMenus(IMenuManager manager) {
        manager.remove(saveChartSetAsAction.getId());
        manager.remove(loadChartSetAction.getId());
        manager.remove(separator);

    }

    /*
     * @see AbstractJvmPropertySection#dispose()
     */
    @Override
    public void dispose() {
        super.dispose();
        IActiveJvm jvm = getJvm();
        if (jvm != null) {
            IMBeanServer server = jvm.getMBeanServer();
            if (server != null) {
                server.removeServerChangeListener(mBeanServerChangeListener);
            }
        }
    }

    /**
     * Clears the monitored attributes.
     */
    protected void clear() {
        IActiveJvm jvm = getJvm();
        if (jvm != null) {
            for (IMonitoredMXBeanGroup group : jvm.getMBeanServer()
                    .getMonitoredAttributeGroups()) {
                group.clearAttributes();
            }
        }
    }

    /**
     * Refreshes the connection indicator.
     */
    private void refreshConnectionIndicator() {
        IActiveJvm jvm = getJvm();
        boolean isConnected = jvm != null && jvm.isConnected();
        refreshBackground(sectionContainer, isConnected);
        for (TimelineChart chart : charts) {
            refreshBackground(chart, isConnected);
            refreshBackground(chart.getPlotArea(), isConnected);
            refreshBackground(chart.getSection(), isConnected);
        }

        clearAction.setEnabled(isConnected);
        refreshAction.setEnabled(isConnected);
        newChartAction.setEnabled(isConnected);
        loadChartSetAction.setEnabled(isConnected);
        garbageCollectorAction.setEnabled(isConnected);
    }

    /**
     * Reconstructs charts.
     * <p>
     * The chart configuration should remain even when restarting JVM as long as
     * the properties view where chart has been configured is reused, and as
     * long as JVM doesn't yet have its own configuration.
     * <p>
     * [case 1] JVM gets connected opening Properties view
     * <p>
     * The default monitored attributes will be restored, and then chart
     * configuration will be adapted to the monitored attributes.
     * <p>
     * [case 2] JVM gets connected on already opened Properties view
     * <p>
     * Existing chart configuration will be inherited.
     * <p>
     * [case 3] Properties view gets opened with already connected JVM
     * <p>
     * The chart configuration will be adapted to the monitored attributes.
     * <p>
     * [case 4] another JVM gets selected
     * <p>
     * The chart configuration will be adapted to the monitored attributes.
     * <p>
     * [case 5] monitored attributes gets changed (e.g. when running
     * <tt>RestoreDefaultsAction</tt>)
     * <p>
     * The chart configuration will be adapted to the monitored attributes.
     * 
     * @param activeJvm
     *            The JVM
     * @param connected
     *            True if JVM gets connected
     */
    void reconstructCharts(IActiveJvm activeJvm, boolean connected) {
        if (sectionContainer.isDisposed()) {
            return;
        }

        IMBeanServer mBeanServer = activeJvm.getMBeanServer();
        if (connected) {
            if (forceLoadDefaultChartSet) {
                try {
                    loadChartSetAction.loadDefaultChartSet();
                } catch (JvmCoreException e) {
                    Activator.log(
                            Messages.configureMonitoredAttributesFailedMsg, e);
                }
                forceLoadDefaultChartSet = false;
            } else {
                inheritChartConfiguration(mBeanServer);
            }
        }

        sectionContainer.setVisible(false);
        for (TimelineChart chart : charts) {
            chart.dispose();
        }
        charts.clear();

        List<IMonitoredMXBeanGroup> groups = activeJvm.getMBeanServer()
                .getMonitoredAttributeGroups();
        GridLayout layout = (GridLayout) sectionContainer.getLayout();
        layout.numColumns = (groups.size() > 1) ? 2 : 1;
        sectionContainer.setLayout(layout);

        for (IMonitoredMXBeanGroup group : groups) {
            createSection(sectionContainer, group);
        }
        sectionContainer.layout();
        sectionContainer.setVisible(true);
        refresh();
    }

    /**
     * Inherits the existing chart configuration.
     * 
     * @param mBeanServer
     *            The MBean server
     */
    private void inheritChartConfiguration(IMBeanServer mBeanServer) {
        for (TimelineChart chart : charts) {
            IMonitoredMXBeanGroup oldGroup = chart.getAttributeGroup();
            IMonitoredMXBeanGroup newGroup = mBeanServer
                    .addMonitoredAttributeGroup(chart.getSection().getText(),
                            oldGroup.getAxisUnit());
            for (IMonitoredMXBeanAttribute attribute : oldGroup.getAttributes()) {
                try {
                    newGroup.addAttribute(attribute.getObjectName()
                            .getCanonicalName(), attribute.getAttributeName(),
                            attribute.getRGB());
                } catch (JvmCoreException e) {
                    Activator.log(Messages.getMBeanAttributeFailedMsg, e);
                }
            }
        }
    }

    /**
     * Creates the section.
     * 
     * @param parent
     *            The parent composite
     * @param group
     *            The attribute group
     */
    private void createSection(Composite parent, IMonitoredMXBeanGroup group) {
        if (parent.isDisposed()) {
            return;
        }

        ExpandableComposite section = getWidgetFactory().createSection(parent,
                ExpandableComposite.TITLE_BAR);
        section.setText(group.getName());
        section.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite flatFormComposite = getWidgetFactory()
                .createFlatFormComposite(section);
        FormLayout formLayout = new FormLayout();
        formLayout.marginHeight = 0;
        formLayout.marginWidth = 0;
        flatFormComposite.setLayout(formLayout);
        TimelineChart chart = new TimelineChart(flatFormComposite, section,
                group, SWT.NONE, getId());
        charts.add(chart);

        FormData data;
        data = new FormData();
        data.left = new FormAttachment(0, 0);
        data.right = new FormAttachment(100, 0);
        data.top = new FormAttachment(0, 0);
        data.bottom = new FormAttachment(100, 0);
        chart.setLayoutData(data);

        section.setClient(flatFormComposite);
        List<Action> actions = new ArrayList<Action>();
        for (IMonitoredMXBeanAttribute attribute : group.getAttributes()) {
            if (attribute.getAttributeName().startsWith("HeapMemoryUsage")) { //$NON-NLS-1$
                actions.add(garbageCollectorAction);
                break;
            }
        }
        actions.add(new ConfigureChartAction(chart, this));
        actions.add(new DeleteChartAction(chart, this));
        addSectionActions(section, actions);
    }

    /**
     * Adds the menus on expandable composite.
     * 
     * @param expandableComposite
     *            The expandable composite
     * @param actions
     *            The actions
     */
    private void addSectionActions(ExpandableComposite expandableComposite,
            List<Action> actions) {
        ToolBarManager toolBarManager = new ToolBarManager(SWT.FLAT);
        ToolBar toolbar = toolBarManager.createControl(expandableComposite);

        // set cursor
        final Cursor cursor = new Cursor(Display.getCurrent(), SWT.CURSOR_HAND);
        toolbar.setCursor(cursor);
        toolbar.addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e) {
                if (!cursor.isDisposed()) {
                    cursor.dispose();
                }
            }
        });

        // add menus
        for (Action action : actions) {
            toolBarManager.add(action);
        }
        toolBarManager.update(true);

        expandableComposite.setTextClient(toolbar);
    }
}