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

package com.alibaba.otter.shared.arbitrate.impl.setl.zookeeper;

import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import com.alibaba.otter.shared.arbitrate.exception.ArbitrateException;
import com.alibaba.otter.shared.arbitrate.impl.communication.ArbitrateCommmunicationClient;
import com.alibaba.otter.shared.arbitrate.impl.config.ArbitrateConfigUtils;
import com.alibaba.otter.shared.arbitrate.impl.manage.ChannelArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.setl.ArbitrateFactory;
import com.alibaba.otter.shared.arbitrate.impl.setl.TerminArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.setl.helper.StagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.monitor.TerminMonitor;
import com.alibaba.otter.shared.arbitrate.impl.setl.zookeeper.termin.NormalTerminProcess;
import com.alibaba.otter.shared.arbitrate.impl.setl.zookeeper.termin.WarningTerminProcess;
import com.alibaba.otter.shared.arbitrate.impl.zookeeper.ZooKeeperClient;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData.TerminType;
import com.alibaba.otter.shared.common.model.config.channel.Channel;
import com.alibaba.otter.shared.common.utils.JsonUtils;
import com.alibaba.otter.shared.common.utils.zookeeper.ZkClientx;
import com.alibaba.otter.shared.communication.model.arbitrate.StopChannelEvent;

/**
 * ??????termin??????
 * 
 * @author jianghang 2011-8-9 ??????04:39:20
 */
public class TerminZooKeeperArbitrateEvent implements TerminArbitrateEvent {

    private static final Logger           logger    = LoggerFactory.getLogger(TerminZooKeeperArbitrateEvent.class);

    private ZkClientx                     zookeeper = ZooKeeperClient.getInstance();
    private ArbitrateCommmunicationClient arbitrateCommmunicationClient;
    private NormalTerminProcess           normalTerminProcess;
    private WarningTerminProcess          warningTerminProcess;
    private ChannelArbitrateEvent         channelEvent;

    /**
     * <pre>
     * ??????:
     * 1. ?????????????????????????????????processId??????????????????data??????????????????
     * </pre>
     */
    public TerminEventData await(Long pipelineId) throws InterruptedException {
        Assert.notNull(pipelineId);
        TerminMonitor terminMonitor = ArbitrateFactory.getInstance(pipelineId, TerminMonitor.class);
        Long processId = terminMonitor.waitForProcess(); // ???????????????processId
        if (logger.isDebugEnabled()) {
            logger.debug("## await pipeline[{}] processId[{}] is termin", pipelineId, processId);
        }

        // ??????pipelineId+processId???????????????path
        String path = StagePathUtils.getTermin(pipelineId, processId);

        try {
            byte[] data = zookeeper.readData(path);
            return JsonUtils.unmarshalFromByte(data, TerminEventData.class);
        } catch (ZkNoNodeException e) {
            logger.error("pipeline[{}] processId[{}] is process", pipelineId, processId);
            terminMonitor.ack(processId); // modify for 2012-09-08, ????????????????????????await???????????????????????????NoNode?????????????????????????????????
            return await(pipelineId); // ?????????????????????
        } catch (ZkException e) {
            throw new ArbitrateException("Termin_await", e);
        }
    }

    /**
     * ??????????????????termin??????
     */
    public void exhaust(Long pipelineId) {
        Assert.notNull(pipelineId);
        TerminMonitor terminMonitor = ArbitrateFactory.getInstance(pipelineId, TerminMonitor.class);
        int size = terminMonitor.size();
        try {
            for (int i = 0; i < size; i++) {
                Long processId;
                processId = terminMonitor.waitForProcess();
                TerminEventData data = new TerminEventData();
                data.setPipelineId(pipelineId);
                data.setProcessId(processId);
                ack(data);
            }
        } catch (InterruptedException e) {
            throw new ArbitrateException(e);
        }
    }

    /**
     * <pre>
     * ??????:
     * 1. ??????????????????????????????termin??????????????????????????????????????????????????????????????????S.E.T.L?????????????????????????????????????????????termin??????
     * </pre>
     */
    public void ack(TerminEventData data) {
        Assert.notNull(data);
        // ????????????select??????????????????ack????????????????????????????????????????????????????????????termin????????????????????????
        // ??????termin??????
        String path = StagePathUtils.getTermin(data.getPipelineId(), data.getProcessId());
        try {
            zookeeper.delete(path);
        } catch (ZkNoNodeException e) {
            // ignore,???????????????????????????
        } catch (ZkException e) {
            throw new ArbitrateException("Termin_ack", e);
        }

        TerminMonitor terminMonitor = ArbitrateFactory.getInstance(data.getPipelineId(), TerminMonitor.class);
        terminMonitor.ack(data.getProcessId());
    }

    /**
     * ????????????????????????termin???????????????
     */
    public int size(Long pipelineId) {
        Assert.notNull(pipelineId);

        TerminMonitor terminMonitor = ArbitrateFactory.getInstance(pipelineId, TerminMonitor.class);
        return terminMonitor.size();
    }

    /**
     * <pre>
     * ??????:
     * 1. ???????????????termin??????,??????process???????????????
     * </pre>
     */
    public void single(final TerminEventData data) {
        // ????????????
        final TerminType type = data.getType();
        if (type.isNormal()) {
            Assert.notNull(data.getProcessId());
            normalTerminProcess.process(data); // ????????????
        } else if (type.isWarning()) {
            warningTerminProcess.process(data); // warn???????????????????????????????????????pipeline
        } else {
            Channel channel = ArbitrateConfigUtils.getChannel(data.getPipelineId());

            if (data.getType().isRollback()) {
                boolean paused = channelEvent.pause(channel.getId());
                if (paused) {// ??????pause??????????????????????????????
                    warningTerminProcess.process(data);
                }
            } else if (data.getType().isShutdown()) {
                boolean shutdowned = channelEvent.stop(channel.getId());
                // ??????????????????
                if (shutdowned) {
                    warningTerminProcess.process(data);
                }
                // ?????????????????????manager
                StopChannelEvent event = new StopChannelEvent();
                event.setChannelId(channel.getId());
                arbitrateCommmunicationClient.callManager(event);
            } else if (data.getType().isRestart()) {
                boolean restarted = channelEvent.restart(channel.getId());
                // ??????????????????
                if (restarted) {
                    warningTerminProcess.process(data);
                }
            }
        }
    }

    // ================== setter / getter ===================

    public void setArbitrateCommmunicationClient(ArbitrateCommmunicationClient arbitrateCommmunicationClient) {
        this.arbitrateCommmunicationClient = arbitrateCommmunicationClient;
    }

    public void setNormalTerminProcess(NormalTerminProcess normalTerminProcess) {
        this.normalTerminProcess = normalTerminProcess;
    }

    public void setWarningTerminProcess(WarningTerminProcess warningTerminProcess) {
        this.warningTerminProcess = warningTerminProcess;
    }

    public void setChannelEvent(ChannelArbitrateEvent channelEvent) {
        this.channelEvent = channelEvent;
    }

}
