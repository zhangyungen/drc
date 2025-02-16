package com.ctrip.framework.drc.replicator.impl.oubound.handler;

import com.ctrip.framework.drc.core.config.RegionConfig;
import com.ctrip.framework.drc.core.driver.binlog.constant.LogEventType;
import com.ctrip.framework.drc.core.driver.binlog.gtid.GtidManager;
import com.ctrip.framework.drc.core.driver.binlog.gtid.GtidSet;
import com.ctrip.framework.drc.core.driver.binlog.impl.*;
import com.ctrip.framework.drc.core.driver.command.SERVER_COMMAND;
import com.ctrip.framework.drc.core.driver.command.ServerCommandPacket;
import com.ctrip.framework.drc.core.driver.command.handler.CommandHandler;
import com.ctrip.framework.drc.core.driver.command.packet.ResultCode;
import com.ctrip.framework.drc.core.driver.command.packet.applier.ApplierDumpCommandPacket;
import com.ctrip.framework.drc.core.driver.util.LogEventUtils;
import com.ctrip.framework.drc.core.meta.DataMediaConfig;
import com.ctrip.framework.drc.core.monitor.entity.TrafficStatisticKey;
import com.ctrip.framework.drc.core.monitor.kpi.OutboundMonitorReport;
import com.ctrip.framework.drc.core.monitor.log.Frequency;
import com.ctrip.framework.drc.core.monitor.reporter.DefaultEventMonitorHolder;
import com.ctrip.framework.drc.core.server.common.EventReader;
import com.ctrip.framework.drc.core.server.common.enums.ConsumeType;
import com.ctrip.framework.drc.core.server.common.filter.Filter;
import com.ctrip.framework.drc.core.server.common.filter.table.aviator.AviatorRegexFilter;
import com.ctrip.framework.drc.core.server.config.applier.dto.ApplyMode;
import com.ctrip.framework.drc.core.server.config.replicator.ReplicatorConfig;
import com.ctrip.framework.drc.core.server.observer.gtid.GtidObserver;
import com.ctrip.framework.drc.core.server.utils.FileUtil;
import com.ctrip.framework.drc.core.server.utils.ThreadUtils;
import com.ctrip.framework.drc.replicator.impl.oubound.channel.BinlogFileRegion;
import com.ctrip.framework.drc.replicator.impl.oubound.channel.ChannelAttributeKey;
import com.ctrip.framework.drc.replicator.impl.oubound.filter.OutboundFilterChainContext;
import com.ctrip.framework.drc.replicator.impl.oubound.filter.OutboundFilterChainFactory;
import com.ctrip.framework.drc.replicator.impl.oubound.filter.OutboundLogEventContext;
import com.ctrip.framework.drc.replicator.store.manager.file.DefaultFileManager;
import com.ctrip.framework.drc.replicator.store.manager.file.FileManager;
import com.ctrip.xpipe.api.observer.Observable;
import com.ctrip.xpipe.netty.commands.NettyClient;
import com.ctrip.xpipe.tuple.Pair;
import com.ctrip.xpipe.utils.Gate;
import com.ctrip.xpipe.utils.OffsetNotifier;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import static com.ctrip.framework.drc.core.driver.binlog.constant.LogEventHeaderLength.eventHeaderLengthVersionGt1;
import static com.ctrip.framework.drc.core.driver.binlog.constant.LogEventType.*;
import static com.ctrip.framework.drc.core.driver.command.SERVER_COMMAND.COM_APPLIER_BINLOG_DUMP_GTID;
import static com.ctrip.framework.drc.core.driver.util.LogEventUtils.isDrcGtidLogEvent;
import static com.ctrip.framework.drc.core.driver.util.LogEventUtils.isOriginGtidLogEvent;
import static com.ctrip.framework.drc.core.server.common.EventReader.releaseCompositeByteBuf;
import static com.ctrip.framework.drc.core.server.config.SystemConfig.*;
import static com.ctrip.framework.drc.replicator.store.manager.file.DefaultFileManager.LOG_EVENT_START;
import static com.ctrip.framework.drc.replicator.store.manager.file.DefaultFileManager.LOG_FILE_PREFIX;


/**
 * deal with ApplierDumpCommandPacket
 * Created by mingdongli
 * 2019/9/21 10:13
 */
public class ApplierRegisterCommandHandler extends AbstractServerCommandHandler implements CommandHandler {

    private static final int END_OF_STATEMENT_FLAG = 1;

    private static final String DRC_GTID_EVENT_DB_NAME = "drc_gtid_db";

    private static final String DRC_FILTERED_DB_NAME = "drc_filtered_db";

    private GtidManager gtidManager;

    private FileManager fileManager;

    private OutboundMonitorReport outboundMonitorReport;

    private ExecutorService dumpExecutorService;

    private boolean setGitdMode;

    private String replicatorRegion;

    private ConcurrentMap<ApplierKey, NettyClient> applierKeys = Maps.newConcurrentMap();

    public ApplierRegisterCommandHandler(GtidManager gtidManager, FileManager fileManager, OutboundMonitorReport outboundMonitorReport, ReplicatorConfig replicatorConfig) {
        this.gtidManager = gtidManager;
        this.fileManager = fileManager;
        this.outboundMonitorReport = outboundMonitorReport;
        this.dumpExecutorService = ThreadUtils.newCachedThreadPool(ThreadUtils.getThreadName("ARCH", replicatorConfig.getRegistryKey()));
        this.setGitdMode = replicatorConfig.getApplyMode() == ApplyMode.set_gtid.getType();
        this.replicatorRegion = RegionConfig.getInstance().getRegion();
    }

    @Override
    public synchronized void handle(ServerCommandPacket serverCommandPacket, NettyClient nettyClient) {
        ApplierDumpCommandPacket dumpCommandPacket = (ApplierDumpCommandPacket) serverCommandPacket;
        logger.info("[Receive] command code is {}", COM_APPLIER_BINLOG_DUMP_GTID.name());
        String applierName = dumpCommandPacket.getApplierName();
        Channel channel = nettyClient.channel();
        InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        String ip = remoteAddress.getAddress().getHostAddress();
        ApplierKey applierKey = new ApplierKey(applierName, ip);
        if (!applierKeys.containsKey(applierKey)) {
            try {
                DumpTask dumpTask = new DumpTask(nettyClient.channel(), dumpCommandPacket, ip);
                dumpExecutorService.submit(dumpTask);
                DefaultEventMonitorHolder.getInstance().logEvent("DRC.replicator.applier.dump", applierName + ":" + ip);
                applierKeys.putIfAbsent(applierKey, nettyClient);
            } catch (Exception e) {
                logger.info("[DumpTask] error for applier {} and close channel {}", applierName, channel, e);
                channel.close();
            }
        } else {
            logger.info("[Duplicate] request for applier {} and close channel {}", applierName, channel);
            channel.close();
        }
    }

    @Override
    public SERVER_COMMAND getCommandType() {
        return COM_APPLIER_BINLOG_DUMP_GTID;
    }

    @Override
    public void dispose() {
        for (Map.Entry<ApplierKey, NettyClient> applierKey : applierKeys.entrySet()) {
            try {
                applierKey.getValue().channel().close();
                logger.info("[NettyClient] close for {} in ApplierRegisterCommandHandler", applierKey.getKey());
            } catch (Exception e) {
                logger.error("applierKey close NettyClient error", e);
            }
        }
        dumpExecutorService.shutdown();
    }

    class DumpTask implements Runnable, GtidObserver {

        private Gate gate;

        private Channel channel;

        private ApplierDumpCommandPacket dumpCommandPacket;

        private String applierName;

        private boolean shouldSkipEvent = false;

        private int continuousTableMapCount = 0;

        private Map<Long, String> skipTableNameMap = Maps.newHashMap();

        private LogEventType lastEventType = null;

        private AviatorRegexFilter aviatorFilter = null;

        private String applierRegion;

        private long transactionSize;

        private String sendingSchema;

        private String ip;

        private OffsetNotifier offsetNotifier = new OffsetNotifier(LOG_EVENT_START);

        private long waitEndPosition;

        private volatile boolean channelClosed = false;

        private Frequency frequencySend = new Frequency("FRE GTID SEND");

        private boolean everSeeGtid = false;

        private ResultCode resultCode;

        private ConsumeType consumeType;

        private boolean skipDrcGtidLogEvent;

        private ChannelAttributeKey channelAttributeKey;

        private Filter<OutboundLogEventContext> filterChain;

        private boolean in_exclude_group = false;

        public DumpTask(Channel channel, ApplierDumpCommandPacket dumpCommandPacket, String ip) throws Exception {
            this.channel = channel;
            this.dumpCommandPacket = dumpCommandPacket;
            this.applierName = dumpCommandPacket.getApplierName();
            this.consumeType = ConsumeType.getType(dumpCommandPacket.getConsumeType());
            this.skipDrcGtidLogEvent = setGitdMode && !consumeType.requestAllBinlog();
            String properties = dumpCommandPacket.getProperties();
            DataMediaConfig dataMediaConfig = DataMediaConfig.from(applierName, properties);
            this.applierRegion = dumpCommandPacket.getRegion();
            this.ip = ip;
            logger.info("[ConsumeType] is {}, [properties] is {}, [replicatorRegion] is {}, [applierRegion] is {}, for {} from {}", consumeType.name(), properties, replicatorRegion, applierRegion, applierName, ip);
            channelAttributeKey = channel.attr(ReplicatorMasterHandler.KEY_CLIENT).get();
            if (!consumeType.shouldHeartBeat()) {
                channelAttributeKey.setHeartBeat(false);
                HEARTBEAT_LOGGER.info("[HeartBeat] stop due to replicator slave for {}:{}", applierName, channel.remoteAddress().toString());
            }
            this.gate = channelAttributeKey.getGate();

            String filter = dumpCommandPacket.getNameFilter();
            logger.info("[Filter] before init name filter, applier name is: {}, filter is: {}", applierName, filter);
            if (StringUtils.isNotBlank(filter)) {
                this.aviatorFilter = new AviatorRegexFilter(filter);
                logger.info("[Filter] init name filter, applier name is: {}, filter is: {}", applierName, filter);
            }

            filterChain = new OutboundFilterChainFactory().createFilterChain(
                    OutboundFilterChainContext.from(
                            this.channel,
                            this.consumeType,
                            dataMediaConfig,
                            outboundMonitorReport
                    )
            );
        }

        private boolean check(GtidSet excludedSet) {
            GtidSet executedGtids = gtidManager.getExecutedGtids();
            GtidSet purgedGtids = gtidManager.getPurgedGtids();
            logger.info("[GtidSet] check : filteredExcludedSet {}, executedGtids {}, purgedGtids {}", excludedSet, executedGtids, purgedGtids);
            if (excludedSet != null && excludedSet.isContainedWithin(executedGtids)) {
                return true;
            }
            return false;
        }

        private File getFirstFile(GtidSet excludedSet, boolean onlyLocalUuids) {
            return gtidManager.getFirstLogNotInGtidSet(excludedSet, onlyLocalUuids);
        }

        private boolean skipEvent(GtidSet excludedSet, LogEventType eventType, String gtid) {
            if (eventType == gtid_log_event) {
                return new GtidSet(gtid).isContainedWithin(excludedSet);
            }

            if (eventType == drc_gtid_log_event) {
                return skipDrcGtidLogEvent || new GtidSet(gtid).isContainedWithin(excludedSet);
            }
            return in_exclude_group;
        }

        private void addListener() {
            this.channel.closeFuture().addListener((ChannelFutureListener) future -> {
                Throwable throwable = future.cause();
                if (throwable != null) {
                    logger.error("DumpTask closeFuture", throwable);
                }
                channelClosed = true;
                gate.open();
                removeListener();
                logger.info("closeFuture Listener invoke open gate {} and set channelClosed", gate);
            });
            fileManager.addObserver(this);
        }

        private void removeListener() {
            removeObserver(this);
            NettyClient nettyClient = applierKeys.remove(new ApplierKey(applierName, ip));
            if (nettyClient != null) {
                nettyClient.channel().close();
            }
            filterChain.release();
        }

        private File blankUuidSets() {
            if (isIntegrityTest()) {
                return fileManager.getFirstLogFile();
            } else {
                resultCode = ResultCode.APPLIER_GTID_ERROR;
                logger.warn("[GTID SET] is blank for {}", dumpCommandPacket.getApplierName());
                return null;
            }
        }

        private File calculateGtidSet(GtidSet excludedSet) {
            // 1、clone gtid
            GtidSet clonedExcludedSet = excludedSet.clone();
            GtidSet filteredExcludedSet = excludedSet.filterGtid(gtidManager.getUuids());
            logger.info("[GtidSet] filter : excludedSet {}, filteredExcludedSet {}", excludedSet, filteredExcludedSet);

            // 2、check gtid
            if (!check(filteredExcludedSet)) {
                logger.warn("[GTID SET] {} not valid for {}", dumpCommandPacket.getGtidSet(), applierName);
                resultCode = ResultCode.APPLIER_GTID_ERROR;
                return null;
            }

            // 3、find first file
            return consumeType.isSlave() ? getFirstFile(clonedExcludedSet, !consumeType.isSlave()) : getFirstFile(filteredExcludedSet, !consumeType.isSlave());
        }

        private File firstFileToSend() {
            GtidSet excludedSet = dumpCommandPacket.getGtidSet();
            Collection<GtidSet.UUIDSet> uuidSets = excludedSet.getUUIDSets();
            return (uuidSets == null || uuidSets.isEmpty()) ? blankUuidSets() : calculateGtidSet(excludedSet);
        }

        private void sendResultCode() {
            if (resultCode == null) {
                logger.warn("[Replicator] not ready to serve {}", applierName);
                resultCode = ResultCode.REPLICATOR_NOT_READY;
            }
            resultCode.sendResultCode(channel, logger);
        }

        @Override
        public void run() {
            try {
                GtidSet excludedSet = dumpCommandPacket.getGtidSet();
                addListener();
                File file = firstFileToSend();
                if (file == null) {
                    sendResultCode();
                    return;
                }

                checkFileGaps(file);

                logger.info("[Serving] {} begin, first file name {}", applierName, file.getName());
                // 3、open file，send every file
                while (loop()) {
                    if (sendBinlog(file, excludedSet) == 1) {
                        if (channelClosed) {
                            logger.info("[Inactive] for {}", applierName);
                            return;
                        }
                        ResultCode.REPLICATOR_SEND_BINLOG_ERROR.sendResultCode(channel, logger);
                        logger.info("[Send] binlog error for {}", applierName);
                        return;
                    }

                    // 4、get next file
                    do {
                        String previousFileName = file.getName();
                        file = fileManager.getNextLogFile(file);
                        String currentFileName = file.getName();
                        logger.info("[Transfer] binlog file from {} to {} for {}", previousFileName, currentFileName, applierName);
                    } while (fileManager.gtidExecuted(file, excludedSet));
                }
                logger.info("{} exit loop with channelClosed {}", applierName, channelClosed);
            } catch (Throwable e) {
                logger.error("dump thread error and close channel {}", channel.remoteAddress().toString(), e);
                channel.close();
            }
        }

        private void checkFileGaps(File file) {
            try {
                File currentFile = fileManager.getCurrentLogFile();
                long firstSendFileNum = FileUtil.getFileNumFromName(file.getName(), LOG_FILE_PREFIX);
                long currentFileNum = FileUtil.getFileNumFromName(currentFile.getName(), LOG_FILE_PREFIX);
                if (currentFileNum - firstSendFileNum > 10) {
                    DefaultEventMonitorHolder.getInstance().logEvent("DRC.replicator.applier.gap", applierName + ":" + ip);
                }
            } catch (Exception e) {
                logger.info("checkFileHasGaps error for {}", applierName, e);
            }
        }

        private void removeObserver(DumpTask dumpTask) {
            fileManager.removeObserver(dumpTask);
            logger.info("[Remove] observer of DumpTask {}:{} from fileManager", applierName, ip);
        }

        private boolean sendEvents(FileChannel fileChannel, GtidSet excludedSet, long endPos) throws Exception {

            String gtidForLog = StringUtils.EMPTY;

            while (endPos > fileChannel.position() && !channelClosed) {  //read event in while
                gate.tryPass();
                if (channelClosed) {
                    logger.info("channelClosed and return sendEvents");
                    return false;
                }

                ByteBuf headByteBuf = EventReader.readHeader(fileChannel);
                long eventSize = LogEventUtils.parseNextLogEventSize(headByteBuf);
                if (!checkEventSize(fileChannel, headByteBuf, eventSize)) {
                    continue;
                }

                LogEventType eventType = LogEventUtils.parseNextLogEventType(headByteBuf);
                boolean isIndexLogEvent = LogEventUtils.isIndexEvent(eventType);
                if (!checkDrcIndex(fileChannel, isIndexLogEvent, eventSize, headByteBuf, excludedSet)) {
                    continue;
                }

                Pair<GtidLogEvent, CompositeByteBuf> eventPair = checkGtidEvent(fileChannel, LogEventUtils.isGtidLogEvent(eventType), eventSize, headByteBuf);

                boolean isSlaveConcerned = LogEventUtils.isSlaveConcerned(eventType);

                if (!isSlaveConcerned && (isIndexLogEvent || (excludedSet != null && (in_exclude_group = skipEvent(excludedSet, eventType, eventPair.getKey() != null ? eventPair.getKey().getGtid() : null))))) {
                    gtidForLog = handleNotSend(fileChannel, eventPair.getKey(), eventSize, eventType, gtidForLog);
                    channelAttributeKey.handleEvent(false);
                } else {
                    gtidForLog = handleSend(fileChannel, eventPair.getKey(), eventSize, eventType, gtidForLog, headByteBuf);
                    channelAttributeKey.handleEvent(true);
                }

                releaseCompositeByteBuf(eventPair.getValue());
                endPos = fileChannel.size();
            }

            return true;
        }

        private boolean checkEventSize(FileChannel fileChannel, ByteBuf headByteBuf, long eventSize) throws IOException {
            if (fileChannel.position() + eventSize - eventHeaderLengthVersionGt1 > fileChannel.size()) {
                headByteBuf.release();
                fileChannel.position(fileChannel.position() - eventHeaderLengthVersionGt1);
                return false;
            }
            return true;
        }

        private boolean checkDrcIndex(FileChannel fileChannel, boolean isIndexLogEvent, long eventSize, ByteBuf headByteBuf, GtidSet excludedSet) throws IOException {
            if (!everSeeGtid && isIndexLogEvent) { //first file and skip to first previous gtid event
                trySkip(fileChannel, eventSize, headByteBuf, excludedSet);
                return false;
            }
            return true;
        }

        // first file start with non gtid event, for example gtid in binlog.00001, and tablemap in binlog.00002
        private boolean checkPartialTransaction(FileChannel fileChannel, long eventSize, LogEventType eventType) throws IOException {
            if (!everSeeGtid && !LogEventUtils.isDrcEvent(eventType)) {
                fileChannel.position(fileChannel.position() + eventSize - eventHeaderLengthVersionGt1);
                return true;
            }
            return false;
        }

        private Pair<GtidLogEvent, CompositeByteBuf> checkGtidEvent(FileChannel fileChannel, boolean isGtidLogEvent, long eventSize, ByteBuf headByteBuf) {
            if (isGtidLogEvent) {
                everSeeGtid = true;
                GtidLogEvent gtidLogEvent = new GtidLogEvent();
                CompositeByteBuf compositeByteBuf = EventReader.readEvent(fileChannel, eventSize, gtidLogEvent, headByteBuf);
                return Pair.from(gtidLogEvent, compositeByteBuf);
            }
            return Pair.from(null, null);
        }

        private String handleSend(FileChannel fileChannel, GtidLogEvent gtidLogEvent, long eventSize, LogEventType eventType, String previousGtidLogEvent, ByteBuf headByteBuf) throws Exception {
            if (gtidLogEvent != null) {
                channel.writeAndFlush(new BinlogFileRegion(fileChannel, fileChannel.position() - eventSize, eventSize).retain());  //read all
                previousGtidLogEvent = gtidLogEvent.getGtid();
                transactionSize = 0;
                if (drc_gtid_log_event == eventType && !consumeType.requestAllBinlog()) {
                    in_exclude_group = true;
                    outboundMonitorReport.updateTrafficStatistic(new TrafficStatisticKey(DRC_GTID_EVENT_DB_NAME, replicatorRegion, applierRegion, consumeType.name()), eventSize);
                } else {
                    transactionSize += eventSize;
                    outboundMonitorReport.addOutboundGtid(applierName, previousGtidLogEvent);
                    outboundMonitorReport.addOneCount();
                }
            } else {  // two cases: partial transaction and filtered db
                if (!LogEventUtils.isDrcEvent(eventType) && (checkPartialTransaction(fileChannel, eventSize, eventType)
                        || processNameFilter(fileChannel, eventSize, eventType, headByteBuf))) {
                    lastEventType = eventType;
                    return previousGtidLogEvent;
                }

                // read header already
                OutboundLogEventContext logEventContext = new OutboundLogEventContext(fileChannel, fileChannel.position(), eventType, eventSize, previousGtidLogEvent);
                filterChain.doFilter(logEventContext);
                transactionSize += logEventContext.getFilteredEventSize();
                if (logEventContext.getCause() != null) {
                    throw logEventContext.getCause();
                }

                if (xid_log_event == eventType) {
                    outboundMonitorReport.updateTrafficStatistic(new TrafficStatisticKey(sendingSchema, replicatorRegion, applierRegion, consumeType.name()), transactionSize);
                }

                fileChannel.position(fileChannel.position() + eventSize - eventHeaderLengthVersionGt1);
            }

            logGtid(previousGtidLogEvent, eventType);
            lastEventType = eventType;
            return previousGtidLogEvent;
        }

        private boolean processNameFilter(FileChannel fileChannel, long eventSize, LogEventType eventType, ByteBuf headByteBuf) throws IOException {
            shouldSkipEvent = false;

            if (xid_log_event == eventType) {
                continuousTableMapCount = 0;
                skipTableNameMap.clear();
                return false;
            }

            if (table_map_log_event == eventType) {
                if (lastEventType == table_map_log_event) {
                    continuousTableMapCount++;
                } else {
                    continuousTableMapCount = 1;
                    skipTableNameMap.clear();
                }
                handNameFilterTableMapEvent(fileChannel, eventSize, headByteBuf);
            } else {
                if (skipTableNameMap.isEmpty()) {
                    return false;
                }
                if (continuousTableMapCount == 1) {
                    fileChannel.position(fileChannel.position() + (eventSize - eventHeaderLengthVersionGt1));  // forward body size
                    GTID_LOGGER.info("[Skip] rows event {} for name filter", skipTableNameMap.toString());
                    return true;
                } else {
                    switch (eventType) {
                        case write_rows_event_v2:
                            handNameFilterRowsEvent(fileChannel, eventSize, headByteBuf, new WriteRowsEvent());
                            break;
                        case update_rows_event_v2:
                            handNameFilterRowsEvent(fileChannel, eventSize, headByteBuf, new UpdateRowsEvent());
                            break;
                        case delete_rows_event_v2:
                            handNameFilterRowsEvent(fileChannel, eventSize, headByteBuf, new DeleteRowsEvent());
                            break;
                    }
                }
            }
            return shouldSkipEvent;
        }

        private void handNameFilterTableMapEvent(FileChannel fileChannel, long eventSize, ByteBuf headByteBuf) throws IOException {
            TableMapLogEvent tableMapLogEvent = new TableMapLogEvent();
            CompositeByteBuf compositeByteBuf = EventReader.readEvent(fileChannel, eventSize, tableMapLogEvent, headByteBuf);
            if (aviatorFilter != null && !aviatorFilter.filter(tableMapLogEvent.getSchemaNameDotTableName())) {
                sendingSchema = DRC_FILTERED_DB_NAME;
                shouldSkipEvent = true;
                skipTableNameMap.put(tableMapLogEvent.getTableId(), tableMapLogEvent.getSchemaNameDotTableName());
                GTID_LOGGER.info("[Skip] table map event {} for name filter", tableMapLogEvent.getSchemaNameDotTableName());
            } else {
                sendingSchema = tableMapLogEvent.getSchemaName();
                fileChannel.position(fileChannel.position() - (eventSize - eventHeaderLengthVersionGt1));  // back body size
            }
            releaseCompositeByteBuf(compositeByteBuf);
        }

        private void handNameFilterRowsEvent(FileChannel fileChannel, long eventSize, ByteBuf headByteBuf, AbstractRowsEvent rowsEvent) throws IOException {
            CompositeByteBuf compositeByteBuf = EventReader.readEvent(fileChannel, eventSize, rowsEvent, headByteBuf);
            rowsEvent.loadPostHeader();
            String tableName = skipTableNameMap.get(rowsEvent.getRowsEventPostHeader().getTableId());

            if (tableName != null) {
                shouldSkipEvent = true;
                GTID_LOGGER.info("[Skip] rows event {} for name filter", tableName);
            } else {
                fileChannel.position(fileChannel.position() - (eventSize - eventHeaderLengthVersionGt1));  // back body size
            }

            if (rowsEvent.getRowsEventPostHeader().getFlags() == END_OF_STATEMENT_FLAG) {
                continuousTableMapCount = 0;
                skipTableNameMap.clear();
            }
            releaseCompositeByteBuf(compositeByteBuf);
        }

        private String handleNotSend(FileChannel fileChannel, GtidLogEvent gtidLogEvent, long eventSize, LogEventType eventType, String previousGtidLogEvent) throws IOException {
            if (gtidLogEvent == null) {  // no need to read body
                fileChannel.position(fileChannel.position() + eventSize - eventHeaderLengthVersionGt1);
                if (xid_log_event == eventType) {  //skip all transaction, clear in_exclude_group
                    GTID_LOGGER.info("[Reset] in_exclude_group to false, gtid:{}", previousGtidLogEvent);
                    in_exclude_group = false;
                }
            } else {
                String newGtidForLog = gtidLogEvent.getGtid();
                GTID_LOGGER.info("[Skip] gtid log event, gtid:{}, lastCommitted:{}, sequenceNumber:{}, type:{}", newGtidForLog, gtidLogEvent.getLastCommitted(), gtidLogEvent.getSequenceNumber(), eventType);
                DefaultEventMonitorHolder.getInstance().logEvent("DRC.replicator.outbound.gtid.skip", applierName);
                long nextTransactionOffset = gtidLogEvent.getNextTransactionOffset();
                if (nextTransactionOffset > 0) {
                    fileChannel.position(fileChannel.position() + nextTransactionOffset);
                    in_exclude_group = false;
                    previousGtidLogEvent = newGtidForLog;
                }
            }

            return previousGtidLogEvent;
        }

        private void trySkip(FileChannel fileChannel, long eventSize, ByteBuf headByteBuf, GtidSet excludedSet) throws IOException {
            CompositeByteBuf compositeByteBuf = PooledByteBufAllocator.DEFAULT.compositeDirectBuffer();
            DrcIndexLogEvent indexLogEvent = new DrcIndexLogEvent();
            try {
                ByteBuf bodyByteBuf = EventReader.readBody(fileChannel, eventSize);
                long currentPosition = fileChannel.position();
                compositeByteBuf.addComponents(true, headByteBuf, bodyByteBuf);
                indexLogEvent.read(compositeByteBuf);
                List<Long> indices = indexLogEvent.getIndices();
                if (indices.size() > 1) {
                    GtidSet firstGtidSet = readPreviousGtids(fileChannel, indices.get(0));
                    for (int i = 1; i < indices.size(); ++i) {
                        if (indices.get(i) == indices.get(i - 1)) {
                            restorePosition(fileChannel, indices.get(i - 1), currentPosition);
                            break;
                        }
                        GtidSet secondGtidSet = readPreviousGtids(fileChannel, indices.get(i));
                        GtidSet stepGtidSet = secondGtidSet.subtract(firstGtidSet);
                        if (stepGtidSet.isContainedWithin(excludedSet)) {
                            logger.info("[GtidSet] update from {} to {}", firstGtidSet, secondGtidSet);
                            firstGtidSet = secondGtidSet;
                        } else {  // restore to last position
                            restorePosition(fileChannel, indices.get(i - 1), currentPosition);
                            break;
                        }
                    }
                }
            } finally {
                indexLogEvent.release();
                releaseCompositeByteBuf(compositeByteBuf);
            }
        }

        private void restorePosition(FileChannel fileChannel, long restorePosition, long currentPosition) throws IOException {
            logger.info("restorePosition is {} and currentPosition is {}", restorePosition, currentPosition);
            restorePosition = Math.max(restorePosition, currentPosition);
            fileChannel.position(restorePosition);
            logger.info("[restorePosition] set to {} finally", restorePosition);
        }

        private GtidSet readPreviousGtids(FileChannel fileChannel, long position) throws IOException {
            PreviousGtidsLogEvent previousGtidsLogEvent = new PreviousGtidsLogEvent();
            try {
                fileChannel.position(position);
                logger.info("[Update] position of fileChannel to {}", position);
                EventReader.readEvent(fileChannel, previousGtidsLogEvent);
                return previousGtidsLogEvent.getGtidSet();
            } finally {
                previousGtidsLogEvent.release();
            }
        }

        private void logGtid(String gtidForLog, LogEventType eventType) {
            if (xid_log_event == eventType) {
                GTID_LOGGER.debug("[S] X, {}", gtidForLog);
            } else if (isOriginGtidLogEvent(eventType)) {
                frequencySend.addOne();
                if (StringUtils.isNotBlank(gtidForLog)) {
                    GTID_LOGGER.info("[S] G, {}", gtidForLog);
                }
            }  else if (isDrcGtidLogEvent(eventType)) {
                frequencySend.addOne();
                if (StringUtils.isNotBlank(gtidForLog)) {
                    GTID_LOGGER.info("[S] drc G, {}", gtidForLog);
                }
            } else if (LogEventUtils.isDrcTableMapLogEvent(eventType)) {
                GTID_LOGGER.info("[S] drc table map, {}", gtidForLog);
            } else if (LogEventUtils.isDrcDdlLogEvent(eventType)) {
                GTID_LOGGER.info("[S] drc ddl, {}", gtidForLog);
            }
        }

        /**
         * 0 mean reaching the end, 1 mean fail, otherwise endPos
         *
         * @param fileChannel
         * @param file
         * @return
         * @throws IOException
         */
        private long getBinlogEndPos(FileChannel fileChannel, File file) throws IOException {
            do {
                long logPos = fileChannel.position();
                long endPos = fileChannel.size();
                String fileName = file.getName();
                File currentFile = fileManager.getCurrentLogFile();
                if (fileName != null && currentFile != null && !fileName.equalsIgnoreCase(currentFile.getName())) {  //file rolled
                    if (logPos == endPos) {  //read to the tail
                        logger.info("[Reaching] {} end position and write empty msg to close fileChannel", fileName);
                        channel.writeAndFlush(new BinlogFileRegion(fileChannel, endPos, 0, applierName, fileName));
                        return 0;
                    } else {
                        return endPos;
                    }
                }

                if (logPos < endPos) {
                    return endPos;
                }

                if (!waitNewEvents(endPos)) {  //== to wait
                    return 1;
                }
            } while (loop());

            return 1;
        }

        private boolean waitNewEvents(long endPos) {
            waitEndPosition = endPos + 1;
            boolean acquired = false;
            do {
                try {
                    acquired = offsetNotifier.await(waitEndPosition, 500);
                    if (acquired) {
                        logger.debug("offsetNotifier acquired for {}", waitEndPosition);
                        return acquired;
                    }
                } catch (InterruptedException e) {
                    logger.error("[Read] error", e);
                    Thread.currentThread().interrupt();
                }
            } while (loop());
            return acquired;
        }

        private long sendBinlog(File file, GtidSet excludedSet) throws Exception {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            FileChannel fileChannel = raf.getChannel();
            if (fileChannel.position() == 0) {
                fileChannel.position(DefaultFileManager.LOG_EVENT_START);
            }

            while (loop()) {
                long endPosition = getBinlogEndPos(fileChannel, file);
                if (endPosition <= 1) {
                    return endPosition;
                }
                if (!sendEvents(fileChannel, excludedSet, endPosition)) {
                    return 1;
                }
            }
            return 1;
        }

        private boolean loop() {
            return !Thread.currentThread().isInterrupted() && !channelClosed;
        }

        @Override
        public void update(Object args, Observable observable) {
            long position = (Long) args;
            if (logger.isDebugEnabled()) {
                logger.debug("[OffsetNotifier] update position {}, waitEndPosition {}", position, waitEndPosition);
            }
            if ((Long) args < waitEndPosition) {  //file rolled
                offsetNotifier.offsetIncreased(waitEndPosition + (Long) args);
            } else {
                offsetNotifier.offsetIncreased((Long) args);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DumpTask dumpTask = (DumpTask) o;
            return Objects.equals(channel, dumpTask.channel) &&
                    Objects.equals(dumpCommandPacket, dumpTask.dumpCommandPacket);
        }

        @Override
        public int hashCode() {

            return Objects.hash(channel, dumpCommandPacket);
        }
    }

    private static class ApplierKey {

        private String applierName;

        private String ip;

        public ApplierKey(String applierName, String ip) {
            this.applierName = applierName;
            this.ip = ip;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ApplierKey that = (ApplierKey) o;
            return Objects.equals(applierName, that.applierName) &&
                    Objects.equals(ip, that.ip);
        }

        @Override
        public int hashCode() {

            return Objects.hash(applierName, ip);
        }

        @Override
        public String toString() {
            return "ApplierKey{" +
                    "applierName='" + applierName + '\'' +
                    ", ip='" + ip + '\'' +
                    '}';
        }
    }

}
