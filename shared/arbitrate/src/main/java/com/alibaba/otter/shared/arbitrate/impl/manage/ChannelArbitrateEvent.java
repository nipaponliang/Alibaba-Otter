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

package com.alibaba.otter.shared.arbitrate.impl.manage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.I0Itec.zkclient.exception.ZkException;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.shared.arbitrate.ArbitrateViewService;
import com.alibaba.otter.shared.arbitrate.exception.ArbitrateException;
import com.alibaba.otter.shared.arbitrate.impl.ArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.config.ArbitrateConfigUtils;
import com.alibaba.otter.shared.arbitrate.impl.manage.helper.ManagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.helper.StagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.zookeeper.termin.ErrorTerminProcess;
import com.alibaba.otter.shared.arbitrate.impl.setl.zookeeper.termin.WarningTerminProcess;
import com.alibaba.otter.shared.arbitrate.impl.zookeeper.ZooKeeperClient;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData.TerminType;
import com.alibaba.otter.shared.common.model.config.channel.Channel;
import com.alibaba.otter.shared.common.model.config.channel.ChannelStatus;
import com.alibaba.otter.shared.common.model.config.node.Node;
import com.alibaba.otter.shared.common.model.config.pipeline.Pipeline;
import com.alibaba.otter.shared.common.model.statistics.stage.ProcessStat;
import com.alibaba.otter.shared.common.utils.JsonUtils;
import com.alibaba.otter.shared.common.utils.zookeeper.ZkClientx;

/**
 * ??????channel???????????????????????????
 * 
 * @author jianghang 2011-8-31 ??????07:39:26
 */
public class ChannelArbitrateEvent implements ArbitrateEvent {

    protected static final Logger logger    = LoggerFactory.getLogger(ChannelArbitrateEvent.class);
    private ZkClientx             zookeeper = ZooKeeperClient.getInstance();
    private ArbitrateViewService  arbitrateViewService;
    private NodeArbitrateEvent    nodeEvent;
    private ErrorTerminProcess    errorTerminProcess;
    private WarningTerminProcess  warningTerminProcess;
    private ExecutorService       arbitrateExecutor;

    /**
     * ??????????????????channel??????,????????????
     */
    public void init(Long channelId) {
        String path = ManagePathUtils.getChannelByChannelId(channelId);
        byte[] data = JsonUtils.marshalToByte(ChannelStatus.STOP);// ????????????????????????

        try {
            zookeeper.create(path, data, CreateMode.PERSISTENT);
        } catch (ZkNodeExistsException e) {
            // ??????????????????????????????????????????
            // ignore
        } catch (ZkNoNodeException e) {
            zookeeper.createPersistent(path, data, true);//???????????????
        } catch (ZkException e) {
            throw new ArbitrateException("Channel_init", channelId.toString(), e);
        }
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public void start(Long channelId) {
        updateStatus(channelId, ChannelStatus.START);
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public boolean pause(Long channelId) {
        return pause(channelId, true);
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public boolean pause(Long channelId, boolean needTermin) {
        ChannelStatus currstatus = status(channelId);
        boolean status = false;
        boolean result = !needTermin;
        if (currstatus.isStart()) { // stop??????????????????pause??????????????????start????????????????????????
            updateStatus(channelId, ChannelStatus.PAUSE);
            status = true; // ??????stop?????????rollback??????
        }

        if (needTermin) {
            try {
                // ??????termin????????????
                result |= termin(channelId, TerminType.ROLLBACK);
            } catch (Throwable e) {
                updateStatus(channelId, ChannelStatus.PAUSE); // ????????????????????????
                throw new ArbitrateException(e);
            }
        }

        return result && status;
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public boolean stop(Long channelId) {
        return stop(channelId, true);
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public boolean stop(Long channelId, boolean needTermin) {
        // stop???????????????pause
        updateStatus(channelId, ChannelStatus.STOP);

        boolean result = !needTermin;

        if (needTermin) {
            try {
                result |= termin(channelId, TerminType.SHUTDOWN);
            } catch (Throwable e) {
                updateStatus(channelId, ChannelStatus.STOP); // ????????????????????????
                throw new ArbitrateException(e);
            }
        }

        return result;
    }

    public boolean restart(final Long channelId) {
        return restart(channelId, true);
    }

    /**
     * ???????????????channel??????,??????????????????
     */
    public boolean restart(final Long channelId, boolean needTermin) {
        boolean result = !needTermin;
        boolean status = false;
        if (status(channelId).isStop() == false) { // stop??????????????????pause
            updateStatus(channelId, ChannelStatus.PAUSE);
            status = true;
        }

        if (needTermin) {
            try {
                result |= termin(channelId, TerminType.RESTART);
            } catch (Throwable e) {
                updateStatus(channelId, ChannelStatus.PAUSE); // ????????????????????????
                throw new ArbitrateException(e);
            }

        }

        // ????????????????????????????????????pause??????
        if (status || result) {
            // ????????????
            arbitrateExecutor.submit(new Runnable() {

                public void run() {
                    // sleep?????????????????????rollback??????????????????????????????????????????
                    try {
                        Thread.sleep(5000L + RandomUtils.nextInt(2000));
                    } catch (InterruptedException e) {
                        // ignore
                    }

                    Channel channel = ArbitrateConfigUtils.getChannelByChannelId(channelId);
                    ChannelStatus status = status(channel.getId());
                    if (status.isStop()) {
                        // stop???????????????????????????????????????
                        logger.info("channel[{}] is already stop , restart is ignored", channel.getId());
                    } else if (canStart(channel)) { // ??????stop????????????????????????????????????stop???????????????
                        start(channelId);
                    }
                }
            });
        }

        return result && status;
    }

    /**
     * ????????????channel????????????????????????????????????
     */
    public ChannelStatus status(Long channelId) {
        String path = StagePathUtils.getChannelByChannelId(channelId);
        byte[] data = null;
        try {
            data = zookeeper.readData(path);
        } catch (ZkNoNodeException e) {
            // ?????????????????????????????????????????????
            // ignore
            return null;
        } catch (ZkException e) {
            throw new ArbitrateException("Channel_status", channelId.toString(), e);
        }

        return JsonUtils.unmarshalFromByte(data, ChannelStatus.class);
    }

    /**
     * ???????????????channel??????,????????????
     */
    public void destory(Long channelId) {
        String path = ManagePathUtils.getChannelByChannelId(channelId);
        try {
            zookeeper.delete(path); // ??????????????????????????????
        } catch (ZkNoNodeException e) {
            // ?????????????????????????????????????????????
            // ignore
        } catch (ZkException e) {
            throw new ArbitrateException("Channel_destory", channelId.toString(), e);
        }
    }

    // ===================== help method =================

    /**
     * ??????????????????????????????
     */
    private Boolean termin(Long channelId, final TerminType type) throws Exception {
        Channel channel = ArbitrateConfigUtils.getChannelByChannelId(channelId);
        List<Pipeline> pipelines = channel.getPipelines();
        List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();
        for (final Pipeline pipeline : pipelines) {
            futures.add(arbitrateExecutor.submit(new Callable<Boolean>() {

                public Boolean call() {
                    TerminEventData data = new TerminEventData();
                    data.setPipelineId(pipeline.getId());
                    data.setType(type);
                    data.setCode("channel");
                    data.setDesc(type.toString());
                    return errorTerminProcess.process(data); // ????????????
                }
            }));

        }

        boolean result = false;
        Exception exception = null;
        int index = 0;
        for (Future<Boolean> future : futures) {
            try {
                result |= future.get(); // ????????????
            } catch (InterruptedException e) {
                // ignore
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                sendWarningMessage(pipelines.get(index).getId(), e);
                exception = e;
            }

            index++;
        }

        if (exception != null) {
            throw exception;
        } else {
            return result;
        }
    }

    private void sendWarningMessage(Long pipelineId, Exception e) {
        sendWarningMessage(pipelineId, ExceptionUtils.getFullStackTrace(e));
    }

    private void sendWarningMessage(Long pipelineId, String message) {
        TerminEventData eventData = new TerminEventData();
        eventData.setPipelineId(pipelineId);
        eventData.setType(TerminType.WARNING);
        eventData.setCode("channel");
        eventData.setDesc(message);
        warningTerminProcess.process(eventData);
    }

    private boolean canStart(Channel channel) {
        // ??????????????????????????????????????????
        // ??????????????????????????????node???????????????channel?????????????????????cache?????????
        List<Long> liveNodes = nodeEvent.liveNodes();
        for (Pipeline pipeline : channel.getPipelines()) {
            // ??????select
            List<Long> nids = getNids(pipeline.getSelectNodes());
            if (!CollectionUtils.containsAny(liveNodes, nids)) {
                logger.error("current live nodes:{} , but select nids:{} , result:{}", new Object[] { liveNodes, nids,
                        CollectionUtils.containsAny(liveNodes, nids) });
                sendWarningMessage(pipeline.getId(), "can't restart by no select live node");
                return false;
            }

            // ??????extract
            nids = getNids(pipeline.getExtractNodes());
            if (!CollectionUtils.containsAny(liveNodes, nids)) {
                logger.error("current live nodes:{} , but extract nids:{} , result:{}", new Object[] { liveNodes, nids,
                        CollectionUtils.containsAny(liveNodes, nids) });
                sendWarningMessage(pipeline.getId(), "can't restart by no extract live node");
                return false;
            }

            // ??????transform/load
            nids = getNids(pipeline.getLoadNodes());
            if (!CollectionUtils.containsAny(liveNodes, nids)) {
                logger.error("current live nodes:{} , but transform nids:{} , result:{}", new Object[] { liveNodes,
                        nids, CollectionUtils.containsAny(liveNodes, nids) });
                sendWarningMessage(pipeline.getId(), "can't restart by no transform live node");
                return false;
            }

            // ??????????????????????????????process
            List<ProcessStat> stats = arbitrateViewService.listProcesses(channel.getId(), pipeline.getId());
            if (!stats.isEmpty() && !status(channel.getId()).isStart()) {
                List<Long> processIds = new ArrayList<Long>();
                for (ProcessStat stat : stats) {
                    processIds.add(stat.getProcessId());
                }
                sendWarningMessage(pipeline.getId(),
                                   "can't restart by exist process[" + StringUtils.join(processIds, ',') + "]");
                return false;
            }
        }

        return true;
    }

    private List<Long> getNids(List<Node> nodes) {
        List<Long> nids = new ArrayList<Long>();
        for (Node node : nodes) {
            nids.add(node.getId());
        }

        return nids;
    }

    private void updateStatus(Long channelId, ChannelStatus status) {
        String path = ManagePathUtils.getChannelByChannelId(channelId);
        byte[] data = JsonUtils.marshalToByte(status);// ????????????????????????
        try {
            zookeeper.writeData(path, data);
        } catch (ZkException e) {
            throw new ArbitrateException("Channel_init", channelId.toString(), e);
        }
    }

    // ====================== setter / getter =================

    public void setErrorTerminProcess(ErrorTerminProcess errorTerminProcess) {
        this.errorTerminProcess = errorTerminProcess;
    }

    public void setWarningTerminProcess(WarningTerminProcess warningTerminProcess) {
        this.warningTerminProcess = warningTerminProcess;
    }

    public void setArbitrateExecutor(ExecutorService arbitrateExecutor) {
        this.arbitrateExecutor = arbitrateExecutor;
    }

    public void setArbitrateViewService(ArbitrateViewService arbitrateViewService) {
        this.arbitrateViewService = arbitrateViewService;
    }

    public void setNodeEvent(NodeArbitrateEvent nodeEvent) {
        this.nodeEvent = nodeEvent;
    }

}
