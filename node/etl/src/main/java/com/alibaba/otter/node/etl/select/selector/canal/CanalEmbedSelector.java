/*
 * Copyright (C) 2010-2101 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.otter.node.etl.select.selector.canal;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.apache.commons.lang.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.common.CanalException;
import com.alibaba.otter.canal.extend.communication.CanalConfigClient;
import com.alibaba.otter.canal.extend.ha.MediaHAController;
import com.alibaba.otter.canal.instance.core.CanalInstance;
import com.alibaba.otter.canal.instance.core.CanalInstanceGenerator;
import com.alibaba.otter.canal.instance.manager.CanalInstanceWithManager;
import com.alibaba.otter.canal.instance.manager.model.Canal;
import com.alibaba.otter.canal.instance.manager.model.CanalParameter.HAMode;
import com.alibaba.otter.canal.parse.CanalEventParser;
import com.alibaba.otter.canal.parse.ha.CanalHAController;
import com.alibaba.otter.canal.parse.inbound.mysql.MysqlEventParser;
import com.alibaba.otter.canal.parse.support.AuthenticationInfo;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.server.embedded.CanalServerWithEmbedded;
import com.alibaba.otter.canal.sink.AbstractCanalEventSink;
import com.alibaba.otter.canal.sink.CanalEventSink;
import com.alibaba.otter.node.common.config.ConfigClientService;
import com.alibaba.otter.node.etl.OtterConstants;
import com.alibaba.otter.node.etl.OtterContextLocator;
import com.alibaba.otter.node.etl.select.exceptions.SelectException;
import com.alibaba.otter.node.etl.select.selector.Message;
import com.alibaba.otter.node.etl.select.selector.MessageDumper;
import com.alibaba.otter.node.etl.select.selector.MessageParser;
import com.alibaba.otter.node.etl.select.selector.OtterSelector;
import com.alibaba.otter.shared.common.model.config.pipeline.Pipeline;
import com.alibaba.otter.shared.etl.model.EventData;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * ??????canal embed????????????????????????
 * 
 * @author jianghang 2012-7-31 ??????02:45:15
 * @version 4.1.0
 */
public class CanalEmbedSelector implements OtterSelector {

    private static final Logger     logger           = LoggerFactory.getLogger(CanalEmbedSelector.class);
    private static final String     SEP              = SystemUtils.LINE_SEPARATOR;
    private static final String     DATE_FORMAT      = "yyyy-MM-dd HH:mm:ss";
    private static final int        maxEmptyTimes    = 10;
    private int                     logSplitSize     = 50;
    private boolean                 dump             = true;
    private boolean                 dumpDetail       = true;
    private Long                    pipelineId;
    private CanalServerWithEmbedded canalServer;
    private ClientIdentity          clientIdentity;
    private MessageParser           messageParser;
    private ConfigClientService     configClientService;
    private OtterDownStreamHandler  handler;

    private String                  destination;
    private String                  filter;
    private int                     batchSize        = 10000;
    private long                    batchTimeout     = -1L;
    private boolean                 ddlSync          = true;
    private boolean                 filterTableError = false;

    private CanalConfigClient       canalConfigClient;
    private volatile boolean        running          = false;                                            // ?????????????????????
    private volatile long           lastEntryTime    = 0;

    public CanalEmbedSelector(Long pipelineId){
        this.pipelineId = pipelineId;
        canalServer = new CanalServerWithEmbedded();
    }

    public boolean isStart() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        // ??????destination/filter??????
        Pipeline pipeline = configClientService.findPipeline(pipelineId);
        filter = CanalFilterSupport.makeFilterExpression(pipeline);
        destination = pipeline.getParameters().getDestinationName();
        batchSize = pipeline.getParameters().getMainstemBatchsize();
        batchTimeout = pipeline.getParameters().getBatchTimeout();
        ddlSync = pipeline.getParameters().getDdlSync();
        final boolean syncFull = pipeline.getParameters().getSyncMode().isRow()
                                 || pipeline.getParameters().isEnableRemedy();
        // ????????????skip load??????
        filterTableError = pipeline.getParameters().getSkipSelectException();
        if (pipeline.getParameters().getDumpSelector() != null) {
            dump = pipeline.getParameters().getDumpSelector();
        }

        if (pipeline.getParameters().getDumpSelectorDetail() != null) {
            dumpDetail = pipeline.getParameters().getDumpSelectorDetail();
        }

        canalServer.setCanalInstanceGenerator(new CanalInstanceGenerator() {

            public CanalInstance generate(String destination) {
                Canal canal = canalConfigClient.findCanal(destination);
                final OtterAlarmHandler otterAlarmHandler = new OtterAlarmHandler();
                otterAlarmHandler.setPipelineId(pipelineId);
                OtterContextLocator.autowire(otterAlarmHandler); // ????????????spring??????
                // ?????????slaveId???????????????piplineId???????????????????????????
                long slaveId = 10000;// ????????????
                if (canal.getCanalParameter().getSlaveId() != null) {
                    slaveId = canal.getCanalParameter().getSlaveId();
                }
                canal.getCanalParameter().setSlaveId(slaveId + pipelineId);
                canal.getCanalParameter().setDdlIsolation(ddlSync);
                canal.getCanalParameter().setFilterTableError(filterTableError);
                canal.getCanalParameter().setMemoryStorageRawEntry(false);

                CanalInstanceWithManager instance = new CanalInstanceWithManager(canal, filter) {

                    protected CanalHAController initHaController() {
                        HAMode haMode = parameters.getHaMode();
                        if (haMode.isMedia()) {
                            return new MediaHAController(parameters.getMediaGroup(),
                                parameters.getDbUsername(),
                                parameters.getDbPassword(),
                                parameters.getDefaultDatabaseName());
                        } else {
                            return super.initHaController();
                        }
                    }

                    protected void startEventParserInternal(CanalEventParser parser, boolean isGroup) {
                        super.startEventParserInternal(parser, isGroup);

                        if (eventParser instanceof MysqlEventParser) {
                            // ?????????????????????
                            ((MysqlEventParser) eventParser).setSupportBinlogFormats("ROW");
                            if (syncFull) {
                                ((MysqlEventParser) eventParser).setSupportBinlogImages("FULL");
                            } else {
                                ((MysqlEventParser) eventParser).setSupportBinlogImages("FULL,MINIMAL");
                            }

                            MysqlEventParser mysqlEventParser = (MysqlEventParser) eventParser;
                            mysqlEventParser.setParallel(false); // otter????????????????????????
                            CanalHAController haController = mysqlEventParser.getHaController();
                            if (haController instanceof MediaHAController) {
                                if (isGroup) {
                                    throw new CanalException("not support group database use media HA");
                                }

                                ((MediaHAController) haController).setCanalHASwitchable(mysqlEventParser);
                            }

                            if (!haController.isStart()) {
                                haController.start();
                            }

                            // ??????media???Ha????????????tddl????????????????????????
                            if (haController instanceof MediaHAController) {
                                AuthenticationInfo authenticationInfo = ((MediaHAController) haController).getAvailableAuthenticationInfo();
                                ((MysqlEventParser) eventParser).setMasterInfo(authenticationInfo);
                            }
                        }
                    }

                };
                instance.setAlarmHandler(otterAlarmHandler);

                CanalEventSink eventSink = instance.getEventSink();
                if (eventSink instanceof AbstractCanalEventSink) {
                    handler = new OtterDownStreamHandler();
                    handler.setPipelineId(pipelineId);
                    handler.setDetectingIntervalInSeconds(canal.getCanalParameter().getDetectingIntervalInSeconds());
                    OtterContextLocator.autowire(handler); // ????????????spring??????
                    ((AbstractCanalEventSink) eventSink).addHandler(handler, 0); // ???????????????
                    handler.start();
                }

                return instance;
            }
        });
        canalServer.start();

        canalServer.start(destination);
        this.clientIdentity = new ClientIdentity(destination, pipeline.getParameters().getMainstemClientId(), filter);
        canalServer.subscribe(clientIdentity);// ??????????????????

        running = true;
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            handler.stop();
        } catch (Exception e) {
            logger.warn("failed destory handler", e);
        }

        handler = null;
        canalServer.stop(destination);
        canalServer.stop();
    }

    public Message<EventData> selector() throws InterruptedException {
        int emptyTimes = 0;
        com.alibaba.otter.canal.protocol.Message message = null;
        if (batchTimeout < 0) {// ??????????????????
            while (running) {
                message = canalServer.getWithoutAck(clientIdentity, batchSize);
                if (message == null || message.getId() == -1L) { // ???????????????
                    applyWait(emptyTimes++);
                } else {
                    break;
                }
            }
            if (!running) {
                throw new InterruptedException();
            }
        } else { // ??????????????????
            while (running) {
                message = canalServer.getWithoutAck(clientIdentity, batchSize, batchTimeout, TimeUnit.MILLISECONDS);
                if (message == null || message.getId() == -1L) { // ???????????????
                    continue;
                } else {
                    break;
                }
            }
            if (!running) {
                throw new InterruptedException();
            }
        }

        List<Entry> entries = null;
        if (message.isRaw()) {
            entries = new ArrayList<CanalEntry.Entry>(message.getRawEntries().size());
            for (ByteString entry : message.getRawEntries()) {
                try {
                    entries.add(CanalEntry.Entry.parseFrom(entry));
                } catch (InvalidProtocolBufferException e) {
                    throw new SelectException(e);
                }
            }
        } else {
            entries = message.getEntries();
        }

        List<EventData> eventDatas = messageParser.parse(pipelineId, entries); // ???????????????/??????????????????
        Message<EventData> result = new Message<EventData>(message.getId(), eventDatas);
        // ?????????????????????entry?????????????????????????????????
        if (!CollectionUtils.isEmpty(entries)) {
            long lastEntryTime = entries.get(entries.size() - 1).getHeader().getExecuteTime();
            if (lastEntryTime > 0) {// oracle??????????????????0
                this.lastEntryTime = lastEntryTime;
            }
        }

        if (dump && logger.isInfoEnabled()) {
            String startPosition = null;
            String endPosition = null;
            if (!CollectionUtils.isEmpty(entries)) {
                startPosition = buildPositionForDump(entries.get(0));
                endPosition = buildPositionForDump(entries.get(entries.size() - 1));
            }

            dumpMessages(result, startPosition, endPosition, entries.size());// ?????????????????????????????????
        }
        return result;
    }

    public void rollback(Long batchId) {
        canalServer.rollback(clientIdentity, batchId);
    }

    public void rollback() {
        canalServer.rollback(clientIdentity);
    }

    public void ack(Long batchId) {
        canalServer.ack(clientIdentity, batchId);
    }

    public List<Long> unAckBatchs() {
        return canalServer.listBatchIds(clientIdentity);
    }

    public Long lastEntryTime() {
        return lastEntryTime;
    }

    /**
     * ????????????message??????
     */
    private synchronized void dumpMessages(Message message, String startPosition, String endPosition, int total) {
        try {
            MDC.put(OtterConstants.splitPipelineSelectLogFileKey, String.valueOf(pipelineId));
            logger.info(SEP + "****************************************************" + SEP);
            logger.info(MessageDumper.dumpMessageInfo(message, startPosition, endPosition, total));
            logger.info("****************************************************" + SEP);
            if (dumpDetail) {// ??????????????????????????????????????????
                dumpEventDatas(message.getDatas());
                logger.info("****************************************************" + SEP);
            }
        } finally {
            MDC.remove(OtterConstants.splitPipelineSelectLogFileKey);
        }
    }

    /**
     * ????????????????????????
     */
    private void dumpEventDatas(List<EventData> eventDatas) {
        int size = eventDatas.size();
        // ????????????????????????
        int index = 0;
        do {
            if (index + logSplitSize >= size) {
                logger.info(MessageDumper.dumpEventDatas(eventDatas.subList(index, size)));
            } else {
                logger.info(MessageDumper.dumpEventDatas(eventDatas.subList(index, index + logSplitSize)));
            }
            index += logSplitSize;
        } while (index < size);
    }

    // ????????????????????????????????????????????????
    private void applyWait(int emptyTimes) {
        int newEmptyTimes = emptyTimes > maxEmptyTimes ? maxEmptyTimes : emptyTimes;
        if (emptyTimes <= 3) { // 3?????????
            Thread.yield();
        } else { // ??????3???????????????sleep 10ms
            LockSupport.parkNanos(1000 * 1000L * newEmptyTimes);
        }
    }

    private String buildPositionForDump(Entry entry) {
        long time = entry.getHeader().getExecuteTime();
        Date date = new Date(time);
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        return entry.getHeader().getLogfileName() + ":" + entry.getHeader().getLogfileOffset() + ":"
               + entry.getHeader().getExecuteTime() + "(" + format.format(date) + ")";
    }

    // ================== setter / getter ==================
    public void setMessageParser(MessageParser messageParser) {
        this.messageParser = messageParser;
    }

    public void setConfigClientService(ConfigClientService configClientService) {
        this.configClientService = configClientService;
    }

    public void setCanalConfigClient(CanalConfigClient canalConfigClient) {
        this.canalConfigClient = canalConfigClient;
    }

    public void setDump(boolean dump) {
        this.dump = dump;
    }

}
