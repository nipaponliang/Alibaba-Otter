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

package com.alibaba.otter.shared.arbitrate.demo.multi;

import java.io.IOException;
import java.util.Arrays;

import mockit.Mock;
import mockit.Mockit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.alibaba.otter.shared.arbitrate.ArbitrateEventService;
import com.alibaba.otter.shared.arbitrate.BaseEventTest;
import com.alibaba.otter.shared.arbitrate.demo.servcie.ExtractServiceDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.LoadServiceDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.MainStemServiceDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.ProcessViewDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.SelectServiceDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.TerminProcessDemo;
import com.alibaba.otter.shared.arbitrate.demo.servcie.TransformServiceDemo;
import com.alibaba.otter.shared.arbitrate.impl.ArbitrateConstants;
import com.alibaba.otter.shared.arbitrate.impl.communication.ArbitrateCommmunicationClient;
import com.alibaba.otter.shared.arbitrate.impl.config.ArbitrateConfigUtils;
import com.alibaba.otter.shared.arbitrate.impl.manage.ChannelArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.manage.NodeArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.manage.PipelineArbitrateEvent;
import com.alibaba.otter.shared.arbitrate.impl.setl.ArbitrateFactory;
import com.alibaba.otter.shared.arbitrate.impl.setl.helper.StagePathUtils;
import com.alibaba.otter.shared.arbitrate.impl.setl.monitor.PermitMonitor;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData;
import com.alibaba.otter.shared.arbitrate.model.TerminEventData.TerminType;
import com.alibaba.otter.shared.common.model.config.channel.Channel;
import com.alibaba.otter.shared.common.model.config.node.Node;
import com.alibaba.otter.shared.common.model.config.pipeline.Pipeline;
import com.alibaba.otter.shared.common.utils.zookeeper.ZkClientx;
import com.alibaba.otter.shared.communication.core.model.Event;

/**
 * demo???????????????
 * 
 * @author jianghang 2011-9-22 ??????03:58:53
 * @version 4.0.0
 */
public class ArbitrateOppositeIntegration extends BaseEventTest {

    private MainStemServiceDemo    mainStem;
    private SelectServiceDemo      select;
    private ExtractServiceDemo     extract;
    private TransformServiceDemo   transform;
    private LoadServiceDemo        load;
    private ProcessViewDemo        view;
    private TerminProcessDemo      termin;

    // ????????????????????????
    private NodeArbitrateEvent     nodeEvent;
    private ChannelArbitrateEvent  channelEvent;
    private PipelineArbitrateEvent pipelineEvent;
    private ArbitrateEventService  arbitrateEventService;
    private final Node             one                = new Node();
    private final Node             two                = new Node();
    private final Long             oneNid             = 2L;
    private final Long             twoNid             = 1L;        // ???????????????
    private Long                   channelId          = 100L;
    private Long                   pipelineId         = 101L;      // ?????????101L?????????????????????
    private Long                   oppositePipelineId = 100L;
    private ZkClientx              zookeeper;

    @BeforeMethod
    public void setUp() {
        // mock ??????????????????
        Mockit.setUpMock(ArbitrateConfigUtils.class, new Object() {

            @Mock
            public Channel getChannelByChannelId(Long channelId) {
                Channel channel = new Channel();
                channel.setId(channelId);
                Pipeline pipeline = new Pipeline();
                pipeline.setId(pipelineId);
                pipeline.setSelectNodes(Arrays.asList(one));
                pipeline.setExtractNodes(Arrays.asList(one));
                pipeline.setLoadNodes(Arrays.asList(two));
                channel.setPipelines(Arrays.asList(pipeline));
                return channel;
            }

            @Mock
            public Pipeline getPipeline(Long pipelineId) {
                Pipeline pipeline = new Pipeline();
                pipeline.setId(pipelineId);
                pipeline.setSelectNodes(Arrays.asList(one));
                pipeline.setExtractNodes(Arrays.asList(one));
                pipeline.setLoadNodes(Arrays.asList(two));
                return pipeline;
            }

            @Mock
            public Long getCurrentNid() {
                return oneNid;
            }

            @Mock
            public int getParallelism(Long pipelineId) {
                return 3;// ?????????
            }

            @Mock
            public Pipeline getOppositePipeline(Long pipelineId) {
                Pipeline pipeline = new Pipeline();
                pipeline.setId(oppositePipelineId);
                pipeline.setSelectNodes(Arrays.asList(two));
                pipeline.setExtractNodes(Arrays.asList(two));
                pipeline.setLoadNodes(Arrays.asList(one));
                return pipeline;
            }

            @Mock
            public Channel getChannel(Long pipelineId) {
                Channel channel = new Channel();
                channel.setId(channelId);

                Pipeline pipeline = new Pipeline();
                pipeline.setId(pipelineId);

                Pipeline oppositePipeline = new Pipeline();
                oppositePipeline.setId(oppositePipelineId);
                channel.setPipelines(Arrays.asList(pipeline, oppositePipeline));
                return channel;
            }

        });

        Mockit.setUpMock(ArbitrateCommmunicationClient.class, new Object() {

            @Mock
            public Object callManager(final Event event) {
                // do nothing
                return null;
            }
        });

        zookeeper = getZookeeper();

        one.setId(oneNid);
        two.setId(twoNid);
        nodeEvent = new NodeArbitrateEvent();
        channelEvent = new ChannelArbitrateEvent();// ??????channel
        pipelineEvent = new PipelineArbitrateEvent();

        // ??????node??????
        nodeEvent.init(one.getId());

        // ??????pipeline??????
        try {
            channelEvent.init(channelId);
        } catch (Exception e) {
            // ignore
        }

        try {
            pipelineEvent.init(channelId, pipelineId);
        } catch (Exception e) {
            // ignore
        }

        arbitrateEventService = (ArbitrateEventService) getBeanFactory().getBean("arbitrateEventService");
        mainStem = new MainStemServiceDemo();
        autowire(mainStem);
        select = new SelectServiceDemo();
        autowire(select);
        extract = new ExtractServiceDemo();
        autowire(extract);
        transform = new TransformServiceDemo();
        autowire(transform);
        load = new LoadServiceDemo();
        autowire(load);
        view = new ProcessViewDemo();
        autowire(view);
        termin = new TerminProcessDemo();
        autowire(termin);
    }

    @AfterMethod
    public void tearDown() {
        // ??????mainStem??????
        String path = StagePathUtils.getPipeline(pipelineId) + "/" + ArbitrateConstants.NODE_MAINSTEM;

        zookeeper.delete(path);
        nodeEvent.destory(one.getId());
        // ??????pipeline
        pipelineEvent.destory(channelId, pipelineId);
        // channelEvent.destory(channelId);
    }

    @Test
    public void testDemo() {
        // ??????????????????
        // channelEvent.start(channelId);
        // sleep(); //????????????

        // ????????????????????????
        mainStem.submit(pipelineId);

        PermitMonitor permit = ArbitrateFactory.getInstance(pipelineId, PermitMonitor.class);
        try {
            permit.waitForPermit();// ??????????????????
        } catch (InterruptedException e1) {
            want.fail();
        }

        // ??????
        select.submit(pipelineId);
        extract.submit(pipelineId);
        view.submit(pipelineId);
        this.termin.submit(pipelineId);

        transform.submit(oppositePipelineId);// ?????????????????????
        load.submit(oppositePipelineId);// ?????????????????????

        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // ??????????????????
        TerminEventData termin = new TerminEventData();
        termin.setPipelineId(pipelineId);
        termin.setType(TerminType.SHUTDOWN);
        arbitrateEventService.terminEvent().single(termin);
        sleep(5 * 1000L);// ????????????????????????termin??????

        // ??????
        select.destory(pipelineId);
        extract.destory(pipelineId);
        view.destory(pipelineId);
        this.termin.destory(pipelineId);

        transform.destory(oppositePipelineId);
        load.destory(oppositePipelineId);
        ArbitrateFactory.destory(pipelineId);
    }
}
