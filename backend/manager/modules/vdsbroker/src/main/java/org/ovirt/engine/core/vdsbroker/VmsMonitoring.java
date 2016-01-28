package org.ovirt.engine.core.vdsbroker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.ovirt.engine.core.common.BackendService;
import org.ovirt.engine.core.common.businessentities.IVdsEventListener;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VmDynamic;
import org.ovirt.engine.core.common.businessentities.VmGuestAgentInterface;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.TransactionScopeOption;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;
import org.ovirt.engine.core.vdsbroker.vdsbroker.entities.VmInternalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * invoke all Vm analyzers in hand and iterate over their report
 * and take actions - fire VDSM commands (destroy,run/rerun,migrate), report complete actions,
 * hand-over migration and save-to-db
 */
@Singleton
public class VmsMonitoring implements BackendService {

    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private DbFacade dbFacade;
    @Inject
    private ResourceManager resourceManager;

    private static final Logger log = LoggerFactory.getLogger(VmsMonitoring.class);

    private static VmsMonitoring instance;

    public VmsMonitoring() {
    }

    @PostConstruct
    private void init() {
        instance = this;
    }

    public static VmsMonitoring getInstance() {
        return instance;
    }

    /**
     * analyze and react upon changes on the monitoredVms. relevant changes would
     * be persisted and state transitions and internal commands would
     * take place accordingly.
     *
     * @param monitoredVms The Vms we want to monitor and analyze for changes.
-    * VM object represent the persisted object(namely the one in db) and the VmInternalData
-    * is the running one as reported from VDSM
     * @param fetchTime When the VMs were fetched
     * @param vdsManager The manager of the monitored host
     * @param updateStatistics Whether or not this monitoring should include VM statistics
     */
    public void perform(
            List<Pair<VM, VmInternalData>> monitoredVms,
            long fetchTime,
            VdsManager vdsManager,
            boolean updateStatistics) {
        if (monitoredVms.isEmpty()) {
            return;
        }

        List<VmAnalyzer> vmAnalyzers = Collections.emptyList();
        try {
            vmAnalyzers = refreshVmStats(monitoredVms, fetchTime, vdsManager, updateStatistics);
            afterVMsRefreshTreatment(vmAnalyzers, vdsManager);
            vdsManager.vmsMonitoringInitFinished();
        } catch (RuntimeException ex) {
            log.error("Failed during vms monitoring on host {} error is: {}", vdsManager.getVdsName(), ex);
            log.error("Exception:", ex);
        } finally {
            unlockVms(vmAnalyzers);
        }

    }

    /**
     * lock Vms which has db entity i.e they are managed by a VmManager
     * @return true if lock acquired
     */
    private boolean tryLockVmForUpdate(Pair<VM, VmInternalData> pair, long fetchTime, Guid vdsId) {
        Guid vmId = getVmId(pair.getFirst(), pair.getSecond());
        VmManager vmManager = resourceManager.getVmManager(vmId);

        if (!vmManager.trylock()) {
            log.debug("skipping VM '{}' from this monitoring cycle" +
                    " - the VM is locked by its VmManager ", vmId);
            return false;
        }

        if (!vmManager.isLatestData(pair.getSecond(), vdsId)) {
            log.warn("skipping VM '{}' from this monitoring cycle" +
                    " - newer VM data was already processed", vmId);
            vmManager.unlock();
            return false;
        }

        if (vmManager.getVmDataChangedTime() != null && fetchTime - vmManager.getVmDataChangedTime() <= 0) {
            log.warn("skipping VM '{}' from this monitoring cycle" +
                    " - the VM data has changed since fetching the data", vmId);
            vmManager.unlock();
            return false;
        }

        return true;
    }

    private void unlockVms(List<VmAnalyzer> vmAnalyzers) {
        vmAnalyzers.stream().map(VmsMonitoring::getVmId).forEach(vmId -> {
            VmManager vmManager = resourceManager.getVmManager(vmId);
            vmManager.updateVmDataChangedTime();
            vmManager.unlock();
        });
    }

    /**
     * Analyze the VM data pair
     * Skip analysis on VMs which cannot be locked
     * note: metrics calculation like memCommited and vmsCoresCount should be calculated *before*
     *   this filtering.
     * @return The analyzers which hold all the data per VM
     */
    private List<VmAnalyzer> refreshVmStats(
            List<Pair<VM, VmInternalData>> monitoredVms,
            long fetchTime,
            VdsManager vdsManager,
            boolean updateStatistics) {
        List<VmAnalyzer> vmAnalyzers = new ArrayList<>(monitoredVms.size());
        monitoredVms.forEach(vm -> {
            // TODO filter out migratingTo VMs if no action is taken on them
            if (tryLockVmForUpdate(vm, fetchTime, vdsManager.getVdsId())) {
                VmAnalyzer vmAnalyzer = getVmAnalyzer(vm, vdsManager, updateStatistics);
                vmAnalyzers.add(vmAnalyzer);
                vmAnalyzer.analyze();
            }
        });
        addUnmanagedVms(vmAnalyzers, vdsManager.getVdsId());
        flush(vmAnalyzers);
        return vmAnalyzers;
    }

    protected VmAnalyzer getVmAnalyzer(
            Pair<VM, VmInternalData> pair,
            VdsManager vdsManager,
            boolean updateStatistics) {
        VmAnalyzer vmAnalyzer = new VmAnalyzer(pair.getFirst(), pair.getSecond(), updateStatistics);
        vmAnalyzer.setDbFacade(dbFacade);
        vmAnalyzer.setResourceManager(resourceManager);
        vmAnalyzer.setAuditLogDirector(auditLogDirector);
        vmAnalyzer.setVdsManager(vdsManager);
        return vmAnalyzer;
    }

    private void afterVMsRefreshTreatment(List<VmAnalyzer> vmAnalyzers, VdsManager vdsManager) {
        Collection<Guid> movedToDownVms = new ArrayList<>();
        List<Guid> succeededToRunVms = new ArrayList<>();
        List<Guid> autoVmsToRun = new ArrayList<>();
        List<Guid> coldRebootVmsToRun = new ArrayList<>();

        // now loop over the result and act
        for (VmAnalyzer vmAnalyzer : vmAnalyzers) {

            // rerun all vms from rerun list
            if (vmAnalyzer.isRerun()) {
                log.error("Rerun VM '{}'. Called from VDS '{}'", vmAnalyzer.getDbVm().getId(), vdsManager.getVdsName());
                resourceManager.rerunFailedCommand(vmAnalyzer.getDbVm().getId(), vdsManager.getVdsId());
            }

            if (vmAnalyzer.isSuccededToRun()) {
                vdsManager.succeededToRunVm(vmAnalyzer.getDbVm().getId());
                succeededToRunVms.add(vmAnalyzer.getDbVm().getId());
            }

            // Refrain from auto-start HA VM during its re-run attempts.
            if (vmAnalyzer.isAutoVmToRun() && !vmAnalyzer.isRerun()) {
                autoVmsToRun.add(vmAnalyzer.getDbVm().getId());
            }

            if (vmAnalyzer.isColdRebootVmToRun()) {
                coldRebootVmsToRun.add(vmAnalyzer.getDbVm().getId());
            }

            // process all vms that their ip changed.
            if (vmAnalyzer.isClientIpChanged()) {
                final VmDynamic vmDynamic = vmAnalyzer.getVdsmVm().getVmDynamic();
                getVdsEventListener().processOnClientIpChange(vmDynamic.getId(),
                        vmDynamic.getClientIp());
            }

            // process all vms that powering up.
            if (vmAnalyzer.isPoweringUp()) {
                getVdsEventListener().processOnVmPoweringUp(vmAnalyzer.getVdsmVm().getVmDynamic().getId());
            }

            if (vmAnalyzer.isMovedToDown()) {
                movedToDownVms.add(vmAnalyzer.getDbVm().getId());
            }

            if (vmAnalyzer.isRemoveFromAsync()) {
                resourceManager.removeAsyncRunningVm(vmAnalyzer.getDbVm().getId());
            }
        }

        getVdsEventListener().updateSlaPolicies(succeededToRunVms, vdsManager.getVdsId());

        // run all vms that crashed that marked with auto startup
        getVdsEventListener().runFailedAutoStartVMs(autoVmsToRun);

        // run all vms that went down as a part of cold reboot process
        getVdsEventListener().runColdRebootVms(coldRebootVmsToRun);

        // process all vms that went down
        getVdsEventListener().processOnVmStop(movedToDownVms, vdsManager.getVdsId());

        getVdsEventListener().refreshHostIfAnyVmHasHostDevices(succeededToRunVms, vdsManager.getVdsId());
    }

    private void flush(List<VmAnalyzer> vmAnalyzers) {
        saveVmDynamic(vmAnalyzers);
        saveVmStatistics(vmAnalyzers);
        saveVmInterfaceStatistics(vmAnalyzers);
        saveVmDiskImageStatistics(vmAnalyzers);
        saveVmLunDiskStatistics(vmAnalyzers);
        saveVmGuestAgentNetworkDevices(vmAnalyzers);
        saveVmJobsToDb(vmAnalyzers);
    }

    private void saveVmLunDiskStatistics(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getLunDao().updateAllInBatch(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmLunDisksToSave)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }

    private void saveVmDiskImageStatistics(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getDiskImageDynamicDao().updateAllDiskImageDynamicWithDiskIdByVmId(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmDiskImageDynamicToSave)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
    }

    private void saveVmDynamic(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getVmDynamicDao().updateAllInBatch(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmDynamicToSave)
                .filter(vmDynamic -> vmDynamic != null)
                .collect(Collectors.toList()));
    }

    private void saveVmInterfaceStatistics(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getVmNetworkStatisticsDao().updateAllInBatch(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmNetworkStatistics)
                .flatMap(List::stream)
                .collect(Collectors.toList()));
    }

    private void saveVmStatistics(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getVmStatisticsDao().updateAllInBatch(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmStatisticsToSave)
                .filter(statistics -> statistics != null)
                .collect(Collectors.toList()));
    }

    protected void addUnmanagedVms(List<VmAnalyzer> vmAnalyzers, Guid vdsId) {
        List<Guid> unmanagedVmIds = vmAnalyzers.stream()
                .filter(VmAnalyzer::isUnmanagedVm)
                .map(VmsMonitoring::getVmId)
                .collect(Collectors.toList());
        getVdsEventListener().addUnmanagedVms(vdsId, unmanagedVmIds);
    }

    // ***** DB interaction *****

    private void saveVmGuestAgentNetworkDevices(List<VmAnalyzer> vmAnalyzers) {
        Map<Guid, List<VmGuestAgentInterface>> vmGuestAgentNics = vmAnalyzers.stream()
                .filter(analyzer -> !analyzer.getVmGuestAgentNics().isEmpty())
                .map(analyzer -> new Pair<>(analyzer.getDbVm().getId(), analyzer.getVmGuestAgentNics()))
                .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
        if (!vmGuestAgentNics.isEmpty()) {
            TransactionSupport.executeInScope(TransactionScopeOption.Required, () -> {
                for (Guid vmId : vmGuestAgentNics.keySet()) {
                    dbFacade.getVmGuestAgentInterfaceDao().removeAllForVm(vmId);
                }

                for (List<VmGuestAgentInterface> nics : vmGuestAgentNics.values()) {
                    if (nics != null) {
                        for (VmGuestAgentInterface nic : nics) {
                            dbFacade.getVmGuestAgentInterfaceDao().save(nic);
                        }
                    }
                }
                return null;
            });
        }
    }

    private void saveVmJobsToDb(List<VmAnalyzer> vmAnalyzers) {
        dbFacade.getVmJobDao().updateAllInBatch(vmAnalyzers.stream()
                .map(VmAnalyzer::getVmJobsToUpdate)
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));

        List<Guid> vmJobIdsToRemove = vmAnalyzers.stream()
                .map(VmAnalyzer::getVmJobIdsToRemove)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        if (!vmJobIdsToRemove.isEmpty()) {
            TransactionSupport.executeInScope(TransactionScopeOption.Required, () -> {
                dbFacade.getVmJobDao().removeAll(vmJobIdsToRemove);
                return null;
            });
        }
    }


    // ***** Helpers and sub-methods *****

    private static Guid getVmId(VmAnalyzer vmAnalyzer) {
        return getVmId(vmAnalyzer.getDbVm(), vmAnalyzer.getVdsmVm());
    }

    private static Guid getVmId(VM dbVm, VmInternalData vdsmVm) {
        return dbVm != null ? dbVm.getId() : vdsmVm.getVmDynamic().getId();
    }

    protected IVdsEventListener getVdsEventListener() {
        return resourceManager.getEventListener();
    }

}
