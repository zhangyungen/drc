package com.ctrip.framework.drc.console.service.impl;

import com.ctrip.framework.drc.console.aop.PossibleRemote;
import com.ctrip.framework.drc.console.config.DefaultConsoleConfig;
import com.ctrip.framework.drc.console.dao.*;
import com.ctrip.framework.drc.console.dao.entity.*;
import com.ctrip.framework.drc.console.dto.MessengerMetaDto;
import com.ctrip.framework.drc.console.dto.MetaProposalDto;
import com.ctrip.framework.drc.console.dto.RouteDto;
import com.ctrip.framework.drc.console.enums.BooleanEnum;
import com.ctrip.framework.drc.console.enums.EstablishStatusEnum;
import com.ctrip.framework.drc.console.enums.TableEnum;
import com.ctrip.framework.drc.console.monitor.delay.config.DbClusterSourceProvider;
import com.ctrip.framework.drc.console.service.DrcBuildService;
import com.ctrip.framework.drc.console.utils.DalUtils;
import com.ctrip.framework.drc.console.utils.MySqlUtils;
import com.ctrip.framework.drc.console.utils.XmlUtils;
import com.ctrip.framework.drc.console.vo.DrcBuildPreCheckVo;
import com.ctrip.framework.drc.console.vo.SimplexDrcBuildVo;
import com.ctrip.framework.drc.console.vo.TableCheckVo;
import com.ctrip.framework.drc.console.vo.response.StringSetApiResult;
import com.ctrip.framework.drc.core.http.ApiResult;
import com.ctrip.framework.drc.core.monitor.enums.ModuleEnum;
import com.ctrip.framework.drc.core.server.common.filter.table.aviator.AviatorRegexFilter;
import com.ctrip.xpipe.api.endpoint.Endpoint;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ctrip.framework.drc.console.config.ConsoleConfig.*;

@Service
public class DrcBuildServiceImpl implements DrcBuildService {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private DalUtils dalUtils = DalUtils.getInstance();
    private MhaTblDao mhaTblDao = dalUtils.getMhaTblDao();
    private MhaGroupTblDao mhaGroupTblDao = dalUtils.getMhaGroupTblDao();
    private ReplicatorGroupTblDao replicatorGroupTblDao = dalUtils.getReplicatorGroupTblDao();
    private ApplierGroupTblDao applierGroupTblDao = dalUtils.getApplierGroupTblDao();
    

    @Autowired private MetaInfoServiceImpl metaInfoService;

    @Autowired private DefaultConsoleConfig consoleConfig;
    
    @Autowired private DbClusterSourceProvider dbClusterSourceProvider;

    @Autowired  private MessengerGroupTblDao messengerGroupTblDao;
    @Autowired  private MessengerTblDao messengerTblDao;

    @Override
    public String submitConfig(MetaProposalDto metaProposalDto) throws Exception {
        // 0. check if two mha are same
        if(metaProposalDto.getSrcMha().equalsIgnoreCase(metaProposalDto.getDestMha())) {
            logger.info("{} {} same mha", metaProposalDto.getSrcMha(), metaProposalDto.getDestMha());
            return metaProposalDto.getSrcMha() + " and " + metaProposalDto.getDestMha() + " are same mha, which is not allowed.";
        }

        MhaTbl srcMhaTbl = mhaTblDao.queryByMhaName(metaProposalDto.getSrcMha(), BooleanEnum.FALSE.getCode());
        MhaTbl destMhaTbl = mhaTblDao.queryByMhaName(metaProposalDto.getDestMha(), BooleanEnum.FALSE.getCode());
        // 1. check if two MHAs are in the same group
        Long mhaGroupId = metaInfoService.getMhaGroupId(metaProposalDto.getSrcMha(), metaProposalDto.getDestMha());
        if(mhaGroupId == null) {
            logger.info("{} {} not same group", metaProposalDto.getSrcMha(), metaProposalDto.getDestMha());
            return metaProposalDto.getSrcMha() + " and " + metaProposalDto.getDestMha() + " are NOT in same mha group, cannot establish DRC";
        }
        // 2. update Mha applyMode
        if (!srcMhaTbl.getApplyMode().equals(metaProposalDto.getSrcApplierApplyMode())) {
            srcMhaTbl.setApplyMode(metaProposalDto.getSrcApplierApplyMode());
            mhaTblDao.update(srcMhaTbl);
        } 
        if (!destMhaTbl.getApplyMode().equals(metaProposalDto.getDestApplierApplyMode())) {
            destMhaTbl.setApplyMode(metaProposalDto.getDestApplierApplyMode());
            mhaTblDao.update(destMhaTbl);
        }
        
        // get opposite clusterName as default value
        if (StringUtils.isBlank(metaProposalDto.getSrcClusterName())) {
            metaProposalDto.setSrcClusterName(getClusterName(destMhaTbl));
        }
        if (StringUtils.isBlank(metaProposalDto.getDestClusterName())) {
            metaProposalDto.setDestClusterName(getClusterName(srcMhaTbl));
        }
        // 3. configure and persistent in database
        long srcReplicatorGroupId = configureReplicators(srcMhaTbl, destMhaTbl, metaProposalDto.getSrcReplicatorIps(), metaProposalDto.getDestGtidExecuted());
        long destReplicatorGroupId = configureReplicators(destMhaTbl, srcMhaTbl, metaProposalDto.getDestReplicatorIps(), metaProposalDto.getSrcGtidExecuted());
        configureAppliers(
                srcMhaTbl,
                metaProposalDto.getSrcApplierIps(),
                destReplicatorGroupId,
                metaProposalDto.getSrcApplierIncludedDbs(),
                metaProposalDto.getSrcApplierApplyMode(),
                metaProposalDto.getSrcGtidExecuted(),
                metaProposalDto.getSrcApplierNameFilter(),
                metaProposalDto.getSrcApplierNameMapping(),
                metaProposalDto.getSrcClusterName());
        configureAppliers(
                destMhaTbl,
                metaProposalDto.getDestApplierIps(),
                srcReplicatorGroupId,
                metaProposalDto.getDestApplierIncludedDbs(),
                metaProposalDto.getDestApplierApplyMode(),
                metaProposalDto.getDestGtidExecuted(),
                metaProposalDto.getDestApplierNameFilter(),
                metaProposalDto.getDestApplierNameMapping(),
                metaProposalDto.getDestClusterName());

        // 4. update status and return the configured xml from db
        MhaGroupTbl mhaGroupTbl = mhaGroupTblDao.queryByPk(mhaGroupId);
        mhaGroupTbl.setDrcEstablishStatus(EstablishStatusEnum.ESTABLISHED.getCode());
        mhaGroupTblDao.update(mhaGroupTbl);
        return metaInfoService.getXmlConfiguration(mhaGroupId);
    }

    @Override
    public String submitConfig(MessengerMetaDto dto) throws Exception {
        // 0. check 
        MhaTbl mhaTbl = mhaTblDao.queryByMhaName(dto.getMhaName(), BooleanEnum.FALSE.getCode());
        if (mhaTbl == null) {
            return "mha not record";
        }
        // 3. configure and persistent in database
        long replicatorGroupId = configureReplicators(mhaTbl, null, dto.getReplicatorIps(), dto.getGtidExecuted());
        configureMessengers(mhaTbl, replicatorGroupId, dto.getMessengerIps(), dto.getGtidExecuted());
        
        return metaInfoService.getXmlConfiguration(mhaTbl);
    }

    @Override
    public DrcBuildPreCheckVo preCheckBeforeBuild(MetaProposalDto metaProposalDto) throws SQLException {
        String srcMha = metaProposalDto.getSrcMha();
        String destMha = metaProposalDto.getDestMha();
        MhaTbl srcMhaTbl = dalUtils.getMhaTblDao().queryAll().stream()
                .filter(p -> p.getMhaName().equalsIgnoreCase(srcMha) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).findFirst().orElse(null);
        MhaTbl destMhaTbl = dalUtils.getMhaTblDao().queryAll().stream()
                .filter(p -> p.getMhaName().equalsIgnoreCase(destMha) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).findFirst().orElse(null);
        if (srcMhaTbl == null || destMhaTbl == null) return new DrcBuildPreCheckVo(null,null,DrcBuildPreCheckVo.NO_CONFLICT);
        List<GroupMappingTbl> srcGroupMappingTbls =
                dalUtils.getGroupMappingTblDao().queryAll().stream()
                        .filter(p -> p.getMhaId().equals(srcMhaTbl.getId()) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        List<GroupMappingTbl> destGroupMappingTbls =
                dalUtils.getGroupMappingTblDao().queryAll().stream()
                        .filter(p -> p.getMhaId().equals(destMhaTbl.getId()) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());

        if (srcGroupMappingTbls.size() > 1) {
            List<String> resourcesInUse = metaInfoService.getResourcesInUse(srcMha, destMha, ModuleEnum.REPLICATOR.getDescription());
            List<String> srcReplicatorIps = metaProposalDto.getSrcReplicatorIps();
            if(!resourcesCompare(resourcesInUse,srcReplicatorIps)) {
                logger.info("[preCheck before build] try to update one2many share replicators,mha is {}",srcMha);
                return new DrcBuildPreCheckVo(srcMha,resourcesInUse,DrcBuildPreCheckVo.CONFLICT);
            }
        } else if (destGroupMappingTbls.size() > 1) {
            List<String> resourcesInUse = metaInfoService.getResourcesInUse(destMha, srcMha, ModuleEnum.REPLICATOR.getDescription());
            List<String> destReplicatorIps = metaProposalDto.getDestReplicatorIps();
            if (!resourcesCompare(resourcesInUse, destReplicatorIps)) {
                logger.info("[preCheck before build] try to update one2many share replicators,mha is {}",destMha);
                return new DrcBuildPreCheckVo(destMha,resourcesInUse,DrcBuildPreCheckVo.CONFLICT);
            }
        }
        return new DrcBuildPreCheckVo(null, null, DrcBuildPreCheckVo.NO_CONFLICT);
        
    }

    @Override
    public DrcBuildPreCheckVo preCheckBeReplicatorIps(MessengerMetaDto dto) throws SQLException {
        String mhaName = dto.getMhaName();
        MhaTbl srcMhaTbl = dalUtils.getMhaTblDao().queryByMhaName(mhaName,BooleanEnum.FALSE.getCode());
        if (srcMhaTbl == null) {
            return new DrcBuildPreCheckVo(null, null, DrcBuildPreCheckVo.NO_CONFLICT);
        }
        List<GroupMappingTbl> srcGroupMappingTbls =
                dalUtils.getGroupMappingTblDao().queryAll().stream()
                        .filter(p -> p.getMhaId().equals(srcMhaTbl.getId()) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        if (srcGroupMappingTbls.size() > 1) {
            List<String> resourcesInUse = metaInfoService.getResourcesInUse(mhaName, null, ModuleEnum.REPLICATOR.getDescription());
            List<String> replicatorIps = dto.getReplicatorIps();
            if (!resourcesCompare(resourcesInUse, replicatorIps)) {
                logger.info("[preCheck before build] try to update one2many share replicators,mha is {}", mhaName);
                return new DrcBuildPreCheckVo(mhaName, resourcesInUse, DrcBuildPreCheckVo.CONFLICT);
            }
        }
        return new DrcBuildPreCheckVo(null, null, DrcBuildPreCheckVo.NO_CONFLICT);
        
    }

    @Override
    @PossibleRemote(path = "/api/drc/v1/build/preCheckMySqlConfig")
    public Map<String, Object> preCheckMySqlConfig(String mha) {
        Map<String, Object> res = new HashMap<>();
        Endpoint endpoint = dbClusterSourceProvider.getMasterEndpoint(mha);
        if (endpoint == null) {
            logger.error("[[tag=preCheck]] preCheckMySqlConfig from mha:{},db not exist", mha);
            return res;
        }
        res.put("binlogMode", MySqlUtils.checkBinlogMode(endpoint));
        res.put("binlogFormat", MySqlUtils.checkBinlogFormat(endpoint));
        res.put("binlogVersion1", MySqlUtils.checkBinlogVersion(endpoint));
        res.put("binlogTransactionDependency", MySqlUtils.checkBinlogTransactionDependency(endpoint));
        res.put("binlogTransactionDependencyHistorySize", MySqlUtils.checkBtdhs(endpoint));
        res.put("gtidMode", MySqlUtils.checkGtidMode(endpoint));
        res.put("drcTables", MySqlUtils.checkDrcTables(endpoint));
        res.put("autoIncrementStep", MySqlUtils.checkAutoIncrementStep(endpoint));
        res.put("autoIncrementOffset", MySqlUtils.checkAutoIncrementOffset(endpoint));
        res.put("binlogRowImage", MySqlUtils.checkBinlogRowImage(endpoint));
        List<Endpoint> endpoints = dbClusterSourceProvider.getMasterEndpointsInAllAccounts(mha);
        if (CollectionUtils.isEmpty(endpoints) || endpoints.size() != 3) {
            logger.error("[[tag=preCheck]] preCHeckDrcAccounts from mha:{},db not exist",mha);
            res.put("drcAccounts","no db endpoint find");
        } else {
            res.put("drcAccounts",MySqlUtils.checkAccounts(endpoints));
        }
        return res;
    }

    @Override
    @PossibleRemote(path = "/api/drc/v1/build/preCheckMySqlTables")
    public List<TableCheckVo> preCheckMySqlTables(String mha, String nameFilter) {
        List<TableCheckVo> tableVos = Lists.newArrayList();
        Endpoint endpoint = dbClusterSourceProvider.getMasterEndpoint(mha);
        if (endpoint == null) {
            logger.error("[[tag=preCheck]] preCheckMySqlTables from mha:{},db not exist",mha);
            return tableVos;
        }
        return MySqlUtils.checkTablesWithFilter(endpoint, nameFilter);
    }
    
    @Override
    @PossibleRemote(path = "/api/drc/v1/build/dataMedia/check")
    public List<MySqlUtils.TableSchemaName> getMatchTable(String namespace, String name,
                                                          String mhaName, Integer type) {
        logger.info("[[tag=matchTable]] get {}.{} from {} ",namespace,name,mhaName);
        Endpoint mySqlEndpoint = dbClusterSourceProvider.getMasterEndpoint(mhaName);
        if (mySqlEndpoint != null) {
            AviatorRegexFilter aviatorRegexFilter = new AviatorRegexFilter(namespace + "\\." +  name);
            return MySqlUtils.getTablesAfterRegexFilter(mySqlEndpoint, aviatorRegexFilter);
        } else {
            throw new IllegalArgumentException("no machine find for" + mhaName);
        }
    }

    @Override
    @PossibleRemote(path = "/api/drc/v1/build/rowsFilter/commonColumns",responseType = StringSetApiResult.class)
    public Set<String> getCommonColumnInDataMedias(String mhaName, String namespace, String name) {
        logger.info("[[tag=commonColumns]] get columns {}\\.{} from {}",namespace,name, mhaName);
        Endpoint mySqlEndpoint = dbClusterSourceProvider.getMasterEndpoint(mhaName);
        if (mySqlEndpoint != null) {
            AviatorRegexFilter aviatorRegexFilter = new AviatorRegexFilter(namespace + "\\." +  name);
            return MySqlUtils.getAllCommonColumns(mySqlEndpoint, aviatorRegexFilter);
        } else {
            throw new IllegalArgumentException("no machine find for" + mhaName);
        }
        
    }

    @Override
    public ApiResult getOrBuildSimplexDrc(String srcMha, String destMha) throws SQLException {
        if (StringUtils.isNotBlank(destMha)) {
            // if not exist add replicatorGroup[srcMha] and applierGroup[srcMha->destMha]
            Long srcMhaId = dalUtils.getId(TableEnum.MHA_TABLE, srcMha);
            Long destMhaId = dalUtils.getId(TableEnum.MHA_TABLE, destMha);
            String srcDc = metaInfoService.getDc(srcMha);
            String destDc = metaInfoService.getDc(destMha);
            Long srcReplicatorGroupId = replicatorGroupTblDao.upsertIfNotExist(srcMhaId);
            Long destApplierGroupId = applierGroupTblDao.upsertIfNotExist(srcReplicatorGroupId, destMhaId);
            SimplexDrcBuildVo simplexDrcBuildVo = new SimplexDrcBuildVo(
                    srcMha,
                    destMha,
                    srcDc,
                    destDc,
                    destApplierGroupId,
                    srcReplicatorGroupId,
                    srcMhaId
            );
            return ApiResult.getSuccessInstance(simplexDrcBuildVo);
        } else {
            Long srcMhaId = dalUtils.getId(TableEnum.MHA_TABLE, srcMha);
            Long srcReplicatorGroupId = replicatorGroupTblDao.upsertIfNotExist(srcMhaId);
            Long messengerGroupId = messengerGroupTblDao.upsertIfNotExist(srcMhaId,srcReplicatorGroupId,"");
            return ApiResult.getSuccessInstance(messengerGroupId);
        }
    }

    private String getClusterName(MhaTbl mha) throws SQLException{
        List<ClusterMhaMapTbl> clusterMhaMapTbls = dalUtils.getClusterMhaMapTblDao().
                queryByMhaIds(Lists.newArrayList(mha.getId()), BooleanEnum.FALSE.getCode());
        if (CollectionUtils.isEmpty(clusterMhaMapTbls)) {
            return null;
        } else {
            ClusterTbl clusterTbl = dalUtils.getClusterTblDao().queryByPk(clusterMhaMapTbls.get(0).getClusterId());
            ClusterMhaMapTbl clusterMhaMapTbl = dalUtils.getClusterMhaMapTblDao().queryAll().stream().filter(p -> p.getMhaId().equals(mha.getId()) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).findFirst().get();
            ClusterTbl clusterTbl1 = dalUtils.getClusterTblDao().queryAll().stream().filter(p -> p.getId().equals(clusterMhaMapTbl.getClusterId()) && p.getDeleted().equals(BooleanEnum.FALSE.getCode())).findFirst().get();
            Assert.isTrue(clusterTbl1.getClusterName().equals(clusterTbl.getClusterName()));
            return clusterTbl.getClusterName();
        }
    }

    private boolean resourcesCompare(List<String> resourcesInUse,List<String> replicatorsToBeUpdated) {
        if (resourcesInUse == null) return replicatorsToBeUpdated == null;
        else if (replicatorsToBeUpdated == null) return false;
        else if (resourcesInUse.size() != replicatorsToBeUpdated.size()) return false;
        else {
            List<String> copyResourcesInUse = Lists.newArrayList(resourcesInUse);
            copyResourcesInUse.removeAll(replicatorsToBeUpdated);
            return copyResourcesInUse.size() == 0;
        }
    }

    protected Long configureReplicators(MhaTbl mhaTbl, MhaTbl targetMhaTbl, List<String> replicatorIps, String targetGtidExecuted) throws SQLException {
        Long replicatorGroupId = configureReplicatorGroup(mhaTbl);
        configureReplicatorInstances(replicatorGroupId, mhaTbl, targetMhaTbl, replicatorIps, targetGtidExecuted);
        return replicatorGroupId;
    }

    protected Long configureReplicatorGroup(MhaTbl mhaTbl) throws SQLException {
        long mhaId = mhaTbl.getId();
        String mhaName = mhaTbl.getMhaName();
        logger.info("[[mha={}, mhaId={}]]configure or update replicator group", mhaName, mhaId);
        return dalUtils.updateOrCreateRGroup(mhaId);
    }

    protected void configureReplicatorInstances(Long replicatorGroupId, MhaTbl mhaTbl, MhaTbl targetMhaTbl, List<String> replicatorIps, String targetGtidExecuted) throws SQLException {
        String mhaName = mhaTbl.getMhaName();

        List<String> replicatorIpsInUse = Lists.newArrayList();
        List<ResourceTbl> resourceTbls = dalUtils.getResourceTblDao().queryAll().stream().filter(p -> p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        List<ReplicatorTbl> replicatorTbls = dalUtils.getReplicatorTblDao().queryAll().stream().filter(p -> p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        for (ReplicatorTbl r : replicatorTbls) {
            if(r.getDeleted().equals(BooleanEnum.FALSE.getCode()) && r.getRelicatorGroupId().equals(replicatorGroupId)) {
                resourceTbls.stream().filter(p -> p.getId().equals(r.getResourceId())).findFirst().ifPresent(resourceTbl -> replicatorIpsInUse.add(resourceTbl.getIp()));
            }
        }
        List<List<String>> addRemoveReplicatorIpsPair = getRemoveAndAddInstanceIps(replicatorIpsInUse, replicatorIps);

        if(ADD_REMOVE_PAIR_SIZE != addRemoveReplicatorIpsPair.size()) {
            logger.info("[[mha={}]] wrong add remove replicator pair size {}!={}", mhaName, addRemoveReplicatorIpsPair.size(), ADD_REMOVE_PAIR_SIZE);
            return;
        }

        List<String> replicatorIpsToBeAdded = addRemoveReplicatorIpsPair.get(0);
        List<String> replicatorIpsToBeRemoved = addRemoveReplicatorIpsPair.get(1);
        logger.info("[[mha={}]]try add replicators {}, remove replicators {}", mhaName, replicatorIpsToBeAdded, replicatorIpsToBeRemoved);

        List<String> replicatorInstancesAdded = addReplicatorInstances(replicatorIpsToBeAdded, mhaName, targetMhaTbl, replicatorGroupId, targetGtidExecuted);
        List<String> replicatorInstancesRemoved = removeReplicatorInstances(replicatorIpsToBeRemoved, mhaName, replicatorGroupId, resourceTbls, replicatorTbls);
        logger.info("added R:{}, removed R:{}", replicatorInstancesAdded, replicatorInstancesRemoved);
    }

    protected List<String> addReplicatorInstances(List<String> replicatorIpsToBeAdded, String mhaName, MhaTbl targetMhaTbl, Long replicatorGroupId, String targetGtidExecuted) {
        logger.info("[[mha={}]]try add replicators {}", mhaName, replicatorIpsToBeAdded);
        List<String> replicatorInstancesAdded = Lists.newArrayList();
        for(String ip : replicatorIpsToBeAdded) {
            try {
                logger.info("[[mha={}]]add replicator: {}", mhaName, ip);
                Long resourceId = dalUtils.getId(TableEnum.RESOURCE_TABLE, ip);
                if(null == resourceId) {
                    logger.info("[[mha={}]]UNLIKELY-replicator resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                int applierPort = metaInfoService.findAvailableApplierPort(ip);
                // todo discuss
                String gtidInit = StringUtils.isNotBlank(targetGtidExecuted) ? formatGtid(targetGtidExecuted) : getGtidInit(targetMhaTbl);
                logger.info("[[mha={}]]configure replicator instance: {}:{}", mhaName, ip, applierPort);
                dalUtils.insertReplicator(DEFAULT_REPLICATOR_PORT, applierPort, gtidInit, resourceId, replicatorGroupId, BooleanEnum.FALSE);
                replicatorInstancesAdded.add(ip+':'+applierPort);
            } catch(SQLException e) {
                logger.error("[[mha={}]]Failed add replicator ip: {}", mhaName, ip, e);
            }
        }
        return replicatorInstancesAdded;
    }

    private String formatGtid(String gtid) {
        if (gtid == null) {
            return null;
        }
        gtid = gtid.replace("#", "");
        return XmlUtils.replaceBlank(gtid);
    }

    protected List<String> removeReplicatorInstances(List<String> replicatorIpsToBeRemoved, String mhaName, Long replicatorGroupId, List<ResourceTbl> resourceTbls, List<ReplicatorTbl> replicatorTbls) {
        logger.info("[[mha={}]]try remove replicators {}", mhaName, replicatorIpsToBeRemoved);
        List<String> replicatorInstancesRemoved = Lists.newArrayList();
        if(replicatorIpsToBeRemoved.size() != 0) {
            for(String ip : replicatorIpsToBeRemoved) {
                logger.info("[[mha={}]]remove replicator: {}", mhaName, ip);
                ResourceTbl resourceTbl = resourceTbls.stream().filter(p -> ip.equalsIgnoreCase(p.getIp())).findFirst().orElse(null);
                if(null == resourceTbl) {
                    logger.info("[[mha={}]]UNLIKELY-replicator resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                // find the replicator and logically remove it
                ReplicatorTbl replicatorTbl = replicatorTbls.stream().filter(p -> (replicatorGroupId.equals(p.getRelicatorGroupId())) && resourceTbl.getId().equals(p.getResourceId())).findFirst().orElse(null);
                try {
                    assert null != replicatorTbl;
                    replicatorTbl.setDeleted(BooleanEnum.TRUE.getCode());
                    dalUtils.getReplicatorTblDao().update(replicatorTbl);
                    replicatorInstancesRemoved.add(ip+':'+replicatorTbl.getApplierPort());
                } catch (Throwable t) {
                    logger.error("[[mha={}]]Failed remove replicator {}", mhaName, ip, t);
                }
            }
        }
        return replicatorInstancesRemoved;
    }

    public Long configureAppliers(MhaTbl mhaTbl, List<String> applierIps, long replicatorGroupId, String includedDbs, int applyMode, String localGtidExecuted, String nameFilter, String nameMapping, String targetName) throws SQLException {
        Long applierGroupId = configureApplierGroup(mhaTbl, replicatorGroupId, includedDbs, applyMode, nameFilter, nameMapping, targetName);
        configureApplierInstances(mhaTbl, applierIps, applierGroupId, localGtidExecuted);
        return applierGroupId;
    }

    protected Long configureApplierGroup(MhaTbl mhaTbl, Long replicatorGroupId, String includedDbs, int applyMode, String nameFilter, String nameMapping, String targetName) throws SQLException {
        String mhaName = mhaTbl.getMhaName();
        Long mhaId = mhaTbl.getId();
        logger.info("[[mha={}, mhaId={}, includedDbs={}, applyMode={}, nameFilter={}, nameMapping={}, targetName={}, replicatorGroupId={}]]configure or update applier group", mhaName, mhaId, includedDbs, applyMode, nameFilter, nameMapping, targetName, replicatorGroupId);
        return dalUtils.updateOrCreateAGroup(replicatorGroupId, mhaId, includedDbs, applyMode, nameFilter, nameMapping, targetName);
    }

    protected void configureApplierInstances(MhaTbl mhaTbl, List<String> applierIps, Long applierGroupId, String localGtidExecuted) throws SQLException {
        // rough implementation, only for duo repl
        String mhaName = mhaTbl.getMhaName();

        List<String> applierIpsInUse = Lists.newArrayList();
        List<ResourceTbl> resourceTbls = dalUtils.getResourceTblDao().queryAll().stream().filter(p -> p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        List<ApplierTbl> applierTbls = dalUtils.getApplierTblDao().queryAll().stream().filter(p -> p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        for (ApplierTbl a : applierTbls) {
            if(a.getDeleted().equals(BooleanEnum.FALSE.getCode()) && a.getApplierGroupId().equals(applierGroupId)) {
                resourceTbls.stream().filter(p -> p.getId().equals(a.getResourceId())).findFirst().ifPresent(resourceTbl -> applierIpsInUse.add(resourceTbl.getIp()));
            }
        }
        List<List<String>> addRemoveApplierIpsPair = getRemoveAndAddInstanceIps(applierIpsInUse, applierIps);
        if(ADD_REMOVE_PAIR_SIZE != addRemoveApplierIpsPair.size()) {
            logger.info("[[mha={}]] wrong add remove applier pair size {}!={}", mhaName, addRemoveApplierIpsPair.size(), ADD_REMOVE_PAIR_SIZE);
            return;
        }

        List<String> applierIpsToBeAdded = addRemoveApplierIpsPair.get(0);
        List<String> applierIpsToBeRemoved = addRemoveApplierIpsPair.get(1);
        logger.info("[[mha={}]]try add appliers {}, remove appliers {}", mhaName, applierIpsToBeAdded, applierIpsToBeRemoved);

        List<String> applierInstancesAdded = addApplierInstances(applierIpsToBeAdded, mhaTbl, applierGroupId, localGtidExecuted);
        List<String> applierInstancesRemoved = removeApplierInstances(applierIpsToBeRemoved, mhaName, applierGroupId, resourceTbls, applierTbls);
        logger.info("added A:{}, removed A:{}", applierInstancesAdded, applierInstancesRemoved);
    }

    protected List<String> addApplierInstances(List<String> applierIpsToBeAdded, MhaTbl mhaTbl, Long applierGroupId, String localGtidExecuted) {
        logger.info("[[mha={}]]try add appliers {}", mhaTbl.getMhaName(), applierIpsToBeAdded);
        List<String> applierInstancesAdded = Lists.newArrayList();
        String mhaName = mhaTbl.getMhaName();
        for(String ip : applierIpsToBeAdded) {
            try {
                logger.info("[[mha={}]]add applier: {}", mhaName, ip);
                Long resourceId = dalUtils.getId(TableEnum.RESOURCE_TABLE, ip);
                if(null == resourceId) {
                    logger.info("[[mha={}]]UNLIKELY-applier resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                logger.info("[[mha={}]]configure applier instance: {}", mhaName, ip);
                dalUtils.insertApplier(DEFAULT_APPLIER_PORT, localGtidExecuted, resourceId, applierGroupId);
                applierInstancesAdded.add(ip);
            } catch(Throwable t) {
                logger.error("[[mha={}]]Failed add applier ip: {}", mhaName, ip, t);
            }
        }
        return applierInstancesAdded;
    }

    protected List<String> removeApplierInstances(List<String> applierIpsToBeRemoved, String mhaName, Long applierGroupId, List<ResourceTbl> resourceTbls, List<ApplierTbl> applierTbls) {
        logger.info("[[mha={}]] try remove appliers {}", mhaName, applierIpsToBeRemoved);
        List<String> applierInstancesRemoved = Lists.newArrayList();
        if(applierIpsToBeRemoved.size() != 0) {
            for(String ip : applierIpsToBeRemoved) {
                logger.info("[[mha={}]]remove applier: {}", mhaName, ip);
                ResourceTbl resourceTbl = resourceTbls.stream().filter(p -> ip.equalsIgnoreCase(p.getIp())).findFirst().orElse(null);
                if(null == resourceTbl) {
                    logger.info("[[mha={}]]UNLIKELY-applier resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                ApplierTbl applierTbl = applierTbls.stream().filter(p -> (applierGroupId.equals(p.getApplierGroupId())) && resourceTbl.getId().equals(p.getResourceId())).findFirst().orElse(null);
                try {
                    assert null != applierTbl;
                    applierTbl.setDeleted(BooleanEnum.TRUE.getCode());
                    dalUtils.getApplierTblDao().update(applierTbl);
                    applierInstancesRemoved.add(ip);
                } catch (Throwable t) {
                    logger.error("[[mha={}]]Failed remove applier {}", mhaName, ip, t);
                }
            }
        }
        return applierInstancesRemoved;
    }

    public Long configureMessengers(MhaTbl mhaTbl, 
                                    Long replicatorGroupId,
                                    List<String> messengerIps,
                                    String gtidExecuted) throws SQLException {
        Long messengerGroupId = configureMessengerGroup(mhaTbl, replicatorGroupId,gtidExecuted);
        configureMessengerInstances(mhaTbl, messengerIps, messengerGroupId);
        return messengerGroupId;
    }

    protected Long configureMessengerGroup(MhaTbl mhaTbl, Long replicatorGroupId, String gtidExecuted) throws SQLException {
        String mhaName = mhaTbl.getMhaName();
        Long mhaId = mhaTbl.getId();
        logger.info("[[mha={}, mhaId={},replicatorGroupId={}]]configure or update messenger group", mhaName, mhaId, replicatorGroupId);
        gtidExecuted = StringUtils.isBlank(gtidExecuted) ? getNativeGtid(mhaName) : formatGtid(gtidExecuted);
        return messengerGroupTblDao.upsertIfNotExist(mhaId,replicatorGroupId,gtidExecuted);
    }

    protected void configureMessengerInstances(MhaTbl mhaTbl, List<String> messengerIps, Long messengerGroupId) throws SQLException {
        String mhaName = mhaTbl.getMhaName();

        List<String> messengersInuse = Lists.newArrayList();
        List<ResourceTbl> resourceTbls = dalUtils.getResourceTblDao().queryAll().stream()
                .filter(p -> p.getDeleted().equals(BooleanEnum.FALSE.getCode())).collect(Collectors.toList());
        List<MessengerTbl> messengerTbls = messengerTblDao.queryByGroupId(messengerGroupId);
        for (MessengerTbl m : messengerTbls) {
            resourceTbls.stream().filter(p -> p.getId().equals(m.getResourceId())).findFirst()
                        .ifPresent(resourceTbl -> messengersInuse.add(resourceTbl.getIp()));
        }
        
        List<List<String>> addRemoveMessengerIpsPair = getRemoveAndAddInstanceIps(messengersInuse, messengerIps);
        if(ADD_REMOVE_PAIR_SIZE != addRemoveMessengerIpsPair.size()) {
            logger.info("[[mha={}]] wrong add remove messenger pair size {}!={}",
                    mhaName, addRemoveMessengerIpsPair.size(), ADD_REMOVE_PAIR_SIZE);
            return;
        }

        List<String> messengerIpsToBeAdded = addRemoveMessengerIpsPair.get(0);
        List<String> messengerIpsToBeRemoved = addRemoveMessengerIpsPair.get(1);
        logger.info("[[mha={}]]try add messenger {}, remove messenger {}", mhaName, messengerIpsToBeAdded, messengerIpsToBeRemoved);

        List<String> messengerInstancesAdded = addMessengerInstances(messengerIpsToBeAdded, mhaTbl, messengerGroupId);
        List<String> messengerInstancesRemoved = removeMessengerInstances(messengerIpsToBeRemoved, mhaName, messengerGroupId, resourceTbls, messengerTbls);
        logger.info("added M:{}, removed M:{}", messengerInstancesAdded, messengerInstancesRemoved);
    }

    protected List<String> addMessengerInstances(List<String> messengerIpsToBeAdded, MhaTbl mhaTbl, Long messengerGroupId) {
        logger.info("[[mha={}]]try add messengers {}", mhaTbl.getMhaName(), messengerIpsToBeAdded);
        List<String> messengerInstancesAdded = Lists.newArrayList();
        String mhaName = mhaTbl.getMhaName();
        for(String ip : messengerIpsToBeAdded) {
            try {
                logger.info("[[mha={}]]add messenger: {}", mhaName, ip);
                Long resourceId = dalUtils.getId(TableEnum.RESOURCE_TABLE, ip);
                if(null == resourceId) {
                    logger.info("[[mha={}]]UNLIKELY-messenger resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                logger.info("[[mha={}]]configure messenger instance: {}", mhaName, ip);
                messengerTblDao.insertMessenger(DEFAULT_APPLIER_PORT,resourceId,messengerGroupId);
                messengerInstancesAdded.add(ip);
            } catch(Throwable t) {
                logger.error("[[mha={}]]Failed add messenger ip: {}", mhaName, ip, t);
            }
        }
        return messengerInstancesAdded;
    }

    protected List<String> removeMessengerInstances(
            List<String> messengerIpsToBeRemoved,
            String mhaName, Long messengerGroupId,
            List<ResourceTbl> resourceTbls, 
            List<MessengerTbl> messengerTbls) {
        logger.info("[[mha={}]] try remove messengers {}", mhaName, messengerIpsToBeRemoved);
        List<String> messengerInstancesRemoved = Lists.newArrayList();
        if(messengerIpsToBeRemoved.size() != 0) {
            for(String ip : messengerIpsToBeRemoved) {
                logger.info("[[mha={}]]remove messenger: {}", mhaName, ip);
                ResourceTbl resourceTbl = resourceTbls.stream().filter(p -> ip.equalsIgnoreCase(p.getIp())).findFirst().orElse(null);
                if(null == resourceTbl) {
                    logger.info("[[mha={}]]UNLIKELY-messenger resource({}) should already be loaded", mhaName, ip);
                    continue;
                }
                MessengerTbl messengerTbl = messengerTbls.stream()
                        .filter(p -> (messengerGroupId.equals(p.getMessengerGroupId())) 
                                && resourceTbl.getId().equals(p.getResourceId()))
                        .findFirst().orElse(null);
                try {
                    assert null != messengerTbl;
                    messengerTbl.setDeleted(BooleanEnum.TRUE.getCode());
                    messengerTblDao.update(messengerTbl);
                    messengerInstancesRemoved.add(ip);
                } catch (Throwable t) {
                    logger.error("[[mha={}]]Failed remove messenger {}", mhaName, ip, t);
                }
            }
        }
        return messengerInstancesRemoved;
    }

    protected List<List<String>> getRemoveAndAddInstanceIps(List<String> ipsInUse, List<String> ipsNewConfigured) {
        List<List<String>> addRemoveReplicatorIpsPair = Lists.newArrayList();

        List<String> toBeAdded = Lists.newArrayList(ipsNewConfigured);
        toBeAdded.removeAll(Lists.newArrayList(ipsInUse));
        addRemoveReplicatorIpsPair.add(toBeAdded);

        List<String> toBeRemoved = Lists.newArrayList(ipsInUse);
        toBeRemoved.removeAll(Lists.newArrayList(ipsNewConfigured));
        addRemoveReplicatorIpsPair.add(toBeRemoved);

        return addRemoveReplicatorIpsPair;
    }

    public String getGtidInit(MhaTbl mhaTbl) throws SQLException {
        if (mhaTbl == null){
            return "";
        }
        Set<String> publicCloudRegion = consoleConfig.getPublicCloudRegion();
        String mhaDcName = dalUtils.getDcNameByDcId(mhaTbl.getDcId());
        String regionForDc = consoleConfig.getRegionForDc(mhaDcName);
        if(publicCloudRegion.contains(regionForDc.toLowerCase())) {
            return "";
        }

        Endpoint endpoint = dbClusterSourceProvider.getMasterEndpoint(mhaTbl.getMhaName());
        return MySqlUtils.getUnionExecutedGtid(endpoint);
    }
    
    public String getNativeGtid(String mhaName) {
        Endpoint endpoint = dbClusterSourceProvider.getMasterEndpoint(mhaName);
        return MySqlUtils.getExecutedGtid(endpoint);
    }

    public String submitProxyRouteConfig(RouteDto routeDto) {
        try {
            Long routeOrgId = StringUtils.isBlank(routeDto.getRouteOrgName()) ? 0L : dalUtils.getId(TableEnum.BU_TABLE, routeDto.getRouteOrgName());
            Long srcDcId = dalUtils.getId(TableEnum.DC_TABLE, routeDto.getSrcDcName());
            Long dstDcId = dalUtils.getId(TableEnum.DC_TABLE, routeDto.getDstDcName());
            List<Long> srcProxyIds = Lists.newArrayList();
            List<Long> relayProxyIds = Lists.newArrayList();
            List<Long> dstProxyIds = Lists.newArrayList();
            for(String proxyUri : routeDto.getSrcProxyUris()) {
                srcProxyIds.add(dalUtils.getId(TableEnum.PROXY_TABLE, proxyUri));
            }
            for (String proxyUri : routeDto.getRelayProxyUris()) {
                relayProxyIds.add(dalUtils.getId(TableEnum.PROXY_TABLE, proxyUri));
            }
            for(String proxyUri : routeDto.getDstProxyUris()) {
                dstProxyIds.add(dalUtils.getId(TableEnum.PROXY_TABLE, proxyUri));
            }
            dalUtils.updateOrCreateRoute(routeOrgId, srcDcId, dstDcId, StringUtils.join(srcProxyIds, ","), StringUtils.join(relayProxyIds, ","), StringUtils.join(dstProxyIds, ","), routeDto.getTag(),routeDto.getDeleted());
            return "update proxy route succeeded";
        } catch (SQLException e) {
            logger.error("update proxy route failed, ", e);
            return "update proxy route failed";
        }
    }


}
