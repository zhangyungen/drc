package com.ctrip.framework.drc.console.service.monitor.impl;

import com.ctrip.framework.drc.console.config.DefaultConsoleConfig;
import com.ctrip.framework.drc.console.dao.MhaTblDao;
import com.ctrip.framework.drc.console.dao.entity.GroupMappingTbl;
import com.ctrip.framework.drc.console.dao.entity.MhaGroupTbl;
import com.ctrip.framework.drc.console.dao.entity.MhaTbl;
import com.ctrip.framework.drc.console.enums.BooleanEnum;
import com.ctrip.framework.drc.console.monitor.delay.config.DbClusterSourceProvider;
import com.ctrip.framework.drc.console.service.impl.openapi.OpenService;
import com.ctrip.framework.drc.console.service.monitor.MonitorService;
import com.ctrip.framework.drc.core.service.utils.Constants;
import com.ctrip.framework.drc.console.utils.DalUtils;
import com.ctrip.framework.drc.core.service.utils.JsonUtils;
import com.ctrip.framework.drc.console.vo.response.MhaNamesResponseVo;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ctrip.framework.drc.console.monitor.delay.config.MonitorTableSourceProvider.SWITCH_STATUS_OFF;

/**
 * Created by jixinwang on 2021/8/2
 */
@Service
public class MonitorServiceImpl implements MonitorService {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired private DbClusterSourceProvider sourceProvider;

    @Autowired private DefaultConsoleConfig consoleConfig;

    @Autowired private OpenService openService;
    
    @Autowired private MhaTblDao mhaTblDao;

    private DalUtils dalUtils = DalUtils.getInstance();

    @Override
    public void switchMonitors(List<Long> mhaGroupIds, String status) throws SQLException {
        int monitorSwitch = status.equalsIgnoreCase(SWITCH_STATUS_OFF) ? BooleanEnum.FALSE.getCode() : BooleanEnum.TRUE.getCode();
        List<MhaGroupTbl> mhaGroupTbls = Lists.newArrayList();
        mhaGroupIds.forEach(id -> {
            MhaGroupTbl mhaGroupTbl = new MhaGroupTbl();
            mhaGroupTbl.setId(id);
            mhaGroupTbl.setMonitorSwitch(monitorSwitch);
            mhaGroupTbls.add(mhaGroupTbl);
        });
        dalUtils.getMhaGroupTblDao().batchUpdate(mhaGroupTbls);
        // TODO 
        // this.defaultCurrentMetaManager.notify(mysql)
        // 其他 不用mysqlObserver 的monitor
    }

    @Override
    public void switchMonitors(String mhaName, String status) throws SQLException {
        MhaTbl mhaTbl = mhaTblDao.queryByMhaName(mhaName, BooleanEnum.FALSE.getCode());
        int monitorSwitch = status.equalsIgnoreCase(SWITCH_STATUS_OFF) ? BooleanEnum.FALSE.getCode() : BooleanEnum.TRUE.getCode();
        mhaTbl.setMonitorSwitch(monitorSwitch);
        mhaTblDao.update(mhaTbl);
    }

    @Override
    public List<String> queryMhaNamesToBeMonitored() throws SQLException {
        // switch control by mha
        MhaTbl mhaTbl = new MhaTbl();
        mhaTbl.setDeleted(BooleanEnum.FALSE.getCode());
        mhaTbl.setMonitorSwitch(1);
        List<MhaTbl> mhaTbls = mhaTblDao.queryBy(mhaTbl);
        return mhaTbls.stream().map(MhaTbl::getMhaName).collect(Collectors.toList());
    }

    @Override
    public List<Long> queryMhaIdsToBeMonitored() throws SQLException {
        MhaGroupTbl mhaGroupTbl = new MhaGroupTbl();
        mhaGroupTbl.setDeleted(BooleanEnum.FALSE.getCode());
        mhaGroupTbl.setMonitorSwitch(BooleanEnum.TRUE.getCode());
        List<MhaGroupTbl> mhaGroupTbls = dalUtils.getMhaGroupTblDao().queryBy(mhaGroupTbl);
        List<Long> mhaGroupIdsTobeMonitored = mhaGroupTbls.stream().map(MhaGroupTbl::getId).collect(Collectors.toList());

        GroupMappingTbl groupMappingTbl = new GroupMappingTbl();
        groupMappingTbl.setDeleted(BooleanEnum.FALSE.getCode());
        List<GroupMappingTbl> groupMappingTbls = dalUtils.getGroupMappingTblDao().queryBy(groupMappingTbl);
        List<Long> mhaIdsTobeMonitored = groupMappingTbls.stream().filter(p -> mhaGroupIdsTobeMonitored
                .contains(p.getMhaGroupId())).map(GroupMappingTbl::getMhaId).collect(Collectors.toList());
        return mhaIdsTobeMonitored;
    }

    @Override
    public List<String> getMhaNamesToBeMonitored() throws SQLException {
        String region = consoleConfig.getRegion();
        Set<String> publicCloudRegion = consoleConfig.getPublicCloudRegion();
        List<String> mhaNamesToBeMonitored;

        if (publicCloudRegion.contains(region)) {
            Set<String> localConfigCloudDc = consoleConfig.getLocalConfigCloudDc();
            if (localConfigCloudDc.contains(sourceProvider.getLocalDcName())) {
                mhaNamesToBeMonitored = consoleConfig.getLocalDcMhaNamesToBeMonitored();
                logger.info("get mha name to be monitored from local config: {}", JsonUtils.toJson(mhaNamesToBeMonitored));
            } else {
                mhaNamesToBeMonitored = getRemoteMhaNamesToBeMonitored();
                logger.info("get mha name to be monitored from remote: {}", JsonUtils.toJson(mhaNamesToBeMonitored));
            }
        } else {
            mhaNamesToBeMonitored = getLocalMhaNamesToBeMonitored();
            logger.info("get mha name to be monitored from local: {}", JsonUtils.toJson(mhaNamesToBeMonitored));
        }
        
        return mhaNamesToBeMonitored;
    }

    private List<String> getRemoteMhaNamesToBeMonitored() throws IllegalStateException {
        String centerRegionUrl = consoleConfig.getCenterRegionUrl();
        
        if(!StringUtils.isEmpty(centerRegionUrl)) {
            String uri = String.format("%s/api/drc/v1/monitor/switches/on", centerRegionUrl);
            MhaNamesResponseVo mhaNamesResponseVo = openService.getMhaNamesToBeMonitored(uri);
            if (Constants.zero.equals(mhaNamesResponseVo.getStatus())) {
                return mhaNamesResponseVo.getData();
            }
        }
        logger.info("can not get remote mha names to be monitored");
        throw new IllegalStateException("get remote mha names to be monitored exception");
    }

    private List<String> getLocalMhaNamesToBeMonitored() throws SQLException {
        return queryMhaNamesToBeMonitored();
    }
}
