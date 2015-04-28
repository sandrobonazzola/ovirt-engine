package org.ovirt.engine.ui.webadmin.section.main.view.popup.host;

import java.util.ArrayList;

import org.ovirt.engine.ui.common.idhandler.ElementIdHandler;
import org.ovirt.engine.ui.common.widget.dialog.AdvancedParametersExpander;
import org.ovirt.engine.ui.common.widget.editor.EntityModelCellTable;
import org.ovirt.engine.ui.common.widget.editor.EntityModelCellTable.SelectionMode;
import org.ovirt.engine.ui.common.widget.editor.ListModelRadioGroupEditor;
import org.ovirt.engine.ui.common.widget.editor.generic.IntegerEntityModelTextBoxEditor;
import org.ovirt.engine.ui.common.widget.table.column.AbstractCheckboxColumn;
import org.ovirt.engine.ui.common.widget.table.column.AbstractTextColumn;
import org.ovirt.engine.ui.common.widget.table.header.AbstractCheckboxHeader;
import org.ovirt.engine.ui.common.widget.uicommon.popup.AbstractModelBoundPopupWidget;
import org.ovirt.engine.ui.uicommonweb.models.ListModel;
import org.ovirt.engine.ui.uicommonweb.models.hosts.VfsConfigModel;
import org.ovirt.engine.ui.uicommonweb.models.hosts.VfsConfigModel.AllNetworksSelector;
import org.ovirt.engine.ui.uicommonweb.models.hosts.VfsConfigNetwork;
import org.ovirt.engine.ui.uicompat.Event;
import org.ovirt.engine.ui.uicompat.EventArgs;
import org.ovirt.engine.ui.uicompat.IEventListener;
import org.ovirt.engine.ui.webadmin.ApplicationConstants;
import org.ovirt.engine.ui.webadmin.ApplicationMessages;
import org.ovirt.engine.ui.webadmin.ApplicationTemplates;
import org.ovirt.engine.ui.webadmin.gin.AssetProvider;

import com.google.gwt.cell.client.FieldUpdater;
import com.google.gwt.core.client.GWT;
import com.google.gwt.editor.client.SimpleBeanEditorDriver;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.text.shared.AbstractRenderer;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ValueLabel;
import com.google.inject.Inject;

public class VfsConfigWidget extends AbstractModelBoundPopupWidget<VfsConfigModel> {

    interface Driver extends SimpleBeanEditorDriver<VfsConfigModel, VfsConfigWidget> {
    }

    interface WidgetUiBinder extends UiBinder<FlowPanel, VfsConfigWidget> {
        WidgetUiBinder uiBinder = GWT.create(WidgetUiBinder.class);
    }

    interface WidgetIdHandler extends ElementIdHandler<VfsConfigWidget> {
        WidgetIdHandler idHandler = GWT.create(WidgetIdHandler.class);
    }

    private final Driver driver = GWT.create(Driver.class);

    interface WidgetStyle extends CssResource {
        String valueWidth();
    }

    @UiField
    WidgetStyle style;

    @UiField
    @Ignore
    AdvancedParametersExpander numVfsExpander;

    @UiField
    @Ignore
    FlowPanel numVfsExpanderContent;

    @UiField
    @Path(value = "numOfVfs.entity")
    IntegerEntityModelTextBoxEditor numOfVfs;

    @UiField(provided = true)
    @Path(value = "maxNumOfVfs.entity")
    ValueLabel<Integer> maxVfsLabel;

    @UiField
    @Ignore
    Label allowedNetworksLabel;

    @UiField
    @Ignore
    Label selectNetworksLabel;

    @UiField
    @Path(value = "allNetworksAllowed.selectedItem")
    ListModelRadioGroupEditor<AllNetworksSelector> allNetworksSelectorEditor;

    @UiField(provided = true)
    @Ignore
    EntityModelCellTable<ListModel<VfsConfigNetwork>> networks;

    @UiField
    FlowPanel allowedNetworksPanel;

    private final static ApplicationConstants constants = AssetProvider.getConstants();
    private final static ApplicationMessages messages = AssetProvider.getMessages();
    private final static ApplicationTemplates templates = AssetProvider.getTemplates();

    @Inject
    public VfsConfigWidget() {
        maxVfsLabel = new ValueLabel<>(new AbstractRenderer<Integer>() {

            @Override
            public String render(Integer object) {
                return messages.maxVfs(object);
            }
        });

        networks = new EntityModelCellTable<ListModel<VfsConfigNetwork>>(SelectionMode.NONE, true);

        initWidget(WidgetUiBinder.uiBinder.createAndBindUi(this));
        WidgetIdHandler.idHandler.generateAndSetIds(this);

        initExpander(constants);
        localize(constants);
        driver.initialize(this);
        addStyles();
    }

    private void initExpander(ApplicationConstants constants) {
        numVfsExpander.initWithContent(numVfsExpanderContent.getElement());
        numVfsExpander.setTitleWhenExpanded(constants.numOfVfsSetting());
        numVfsExpander.setTitleWhenCollapsed(constants.numOfVfsSetting());
    }

    private void localize(ApplicationConstants constants) {
        numOfVfs.setLabel(constants.numOfVfs());
        allowedNetworksLabel.setText(constants.allowedNetworks());
        selectNetworksLabel.setText(constants.selectNetworks());
    }

    protected void addStyles() {
        numOfVfs.addContentWidgetContainerStyleName(style.valueWidth());
    }

    interface Style extends CssResource {
        String valueBox();
    }

    @Override
    public void edit(final VfsConfigModel model) {
        driver.edit(model);
        networks.asEditor().edit(model.getNetworks());
        initNetworksTable();

        updateAllowedNetworksPanelVisibility(model);
        model.getAllNetworksAllowed().getSelectedItemChangedEvent().addListener(new IEventListener<EventArgs>() {

            @Override
            public void eventRaised(Event<? extends EventArgs> ev, Object sender, EventArgs args) {
                updateAllowedNetworksPanelVisibility(model);
            }
        });
    }

    private void updateAllowedNetworksPanelVisibility(final VfsConfigModel model) {
        allowedNetworksPanel.setVisible(AllNetworksSelector.specificNetworks == model.getAllNetworksAllowed()
                .getSelectedItem());
    }

    @Override
    public VfsConfigModel flush() {
        networks.asEditor().flush();
        return driver.flush();
    }

    void initNetworksTable() {
        networks.enableColumnResizing();

        networks.addColumn(
                new AttachedIndicatorCheckboxColumn(),
                new AttachedIndicatorCheckboxHeader(), "90px"); //$NON-NLS-1$

        networks.addColumn(new AbstractTextColumn<VfsConfigNetwork>() {
            @Override
            public String getValue(VfsConfigNetwork object) {
                return object.getEntity().getName();
            }
        }, constants.vfsConfigNetworkName(), "85px"); //$NON-NLS-1$

        networks.addColumn(new AbstractTextColumn<VfsConfigNetwork>() {
            @Override
            public String getValue(VfsConfigNetwork object) {
                return object.getLabelViaAttached();
            }
        }, constants.vfsConfigViaLabel(), "85px"); //$NON-NLS-1$

    }

    private final class AttachedIndicatorCheckboxColumn extends AbstractCheckboxColumn<VfsConfigNetwork> {
        private AttachedIndicatorCheckboxColumn() {
            super(new AttachedIndicatorFieldUpdater());
        }

        @Override
        public Boolean getValue(VfsConfigNetwork vfsConfigNetwork) {
            return vfsConfigNetwork.isAttached();
        }

        @Override
        protected boolean canEdit(VfsConfigNetwork vfsConfigNetwork) {
            return canEditAssign(vfsConfigNetwork);
        }
    }

    private final class AttachedIndicatorFieldUpdater implements FieldUpdater<VfsConfigNetwork, Boolean> {
        @Override
        public void update(int index, VfsConfigNetwork vfsConfigNetwork, Boolean value) {
            vfsConfigNetwork.setAttached(value);
            refreshNetworksTable();
        }
    }

    private final class AttachedIndicatorCheckboxHeader extends AbstractCheckboxHeader {

        @Override
        protected void selectionChanged(Boolean value) {
            for (VfsConfigNetwork vfsConfigNetwork : getNetworksTableItems()) {
                if (canEditAssign(vfsConfigNetwork)) {
                    vfsConfigNetwork.setAttached(value);
                }
            }
            refreshNetworksTable();
        }

        @Override
        public Boolean getValue() {
            boolean allEntriesDisabled = !isEnabled();
            for (VfsConfigNetwork vfsConfigNetwork : getNetworksTableItems()) {
                if ((allEntriesDisabled || canEditAssign(vfsConfigNetwork)) && !vfsConfigNetwork.isAttached()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isEnabled() {
            for (VfsConfigNetwork vfsConfigNetwork : getNetworksTableItems()) {
                if (canEditAssign(vfsConfigNetwork)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public SafeHtml getTooltip() {
            return templates.textForCheckBoxHeader(constants.attachAll());
        }
    }

    private boolean canEditAssign(VfsConfigNetwork vfsConfigNetwork) {
        return vfsConfigNetwork.getLabelViaAttached() == null;
    }

    private void refreshNetworksTable() {
        networks.asEditor().edit(networks.asEditor().flush());
    }

    private Iterable<VfsConfigNetwork> getNetworksTableItems() {
        ListModel<VfsConfigNetwork> tableModel = networks.asEditor().flush();
        return tableModel != null ? tableModel.getItems() : new ArrayList<VfsConfigNetwork>();
    }
}
