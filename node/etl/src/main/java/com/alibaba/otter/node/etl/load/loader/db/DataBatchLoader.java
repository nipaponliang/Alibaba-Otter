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

package com.alibaba.otter.node.etl.load.loader.db;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.node.common.config.ConfigClientService;
import com.alibaba.otter.node.etl.OtterConstants;
import com.alibaba.otter.node.etl.load.exception.LoadException;
import com.alibaba.otter.node.etl.load.loader.LoadContext;
import com.alibaba.otter.node.etl.load.loader.OtterLoader;
import com.alibaba.otter.node.etl.load.loader.db.context.DbLoadContext;
import com.alibaba.otter.node.etl.load.loader.db.context.FileLoadContext;
import com.alibaba.otter.node.etl.load.loader.interceptor.LoadInterceptor;
import com.alibaba.otter.node.etl.load.loader.weight.WeightController;
import com.alibaba.otter.shared.common.model.config.ConfigHelper;
import com.alibaba.otter.shared.common.model.config.data.DataMedia;
import com.alibaba.otter.shared.common.model.config.data.DataMediaSource;
import com.alibaba.otter.shared.etl.model.DbBatch;
import com.alibaba.otter.shared.etl.model.EventData;
import com.alibaba.otter.shared.etl.model.FileBatch;
import com.alibaba.otter.shared.etl.model.Identity;
import com.alibaba.otter.shared.etl.model.RowBatch;
import com.google.common.base.Function;
import com.google.common.collect.OtterMigrateMap;

/**
 * ??????RowData?????????????????????
 * 
 * @author jianghang 2011-10-27 ??????11:15:48
 * @version 4.0.0
 */
public class DataBatchLoader implements OtterLoader<DbBatch, List<LoadContext>>, BeanFactoryAware {

    private static final Logger logger = LoggerFactory.getLogger(DataBatchLoader.class);
    private ExecutorService     executorService;
    private BeanFactory         beanFactory;
    private ConfigClientService configClientService;
    private LoadInterceptor     dbInterceptor;

    public List<LoadContext> load(DbBatch data) {
        final RowBatch rowBatch = data.getRowBatch();
        final FileBatch fileBatch = data.getFileBatch();
        boolean existFileBatch = (rowBatch != null && !CollectionUtils.isEmpty(fileBatch.getFiles()) && data.getRoot() != null);
        boolean existRowBatch = (rowBatch != null && !CollectionUtils.isEmpty(rowBatch.getDatas()));

        int count = 0;
        List<RowBatch> rowBatchs = null;
        if (existRowBatch) {
            rowBatchs = split(rowBatch); // ?????????????????????????????????????????????????????????????????????
            count += rowBatchs.size();
        }

        if (existFileBatch) {
            count += 1;
        }

        WeightController controller = new WeightController(count);
        List<Future> futures = new ArrayList<Future>();
        ExecutorCompletionService completionService = new ExecutorCompletionService(executorService);

        if (existFileBatch) {
            submitFileBatch(futures, completionService, fileBatch, data.getRoot(), controller);
        }

        if (existRowBatch) {
            submitRowBatch(futures, completionService, rowBatchs, controller);
        }

        // ????????????????????????????????????????????????????????????index
        List<LoadContext> processedContexts = new ArrayList<LoadContext>();
        int index = 0;
        LoadException exception = null;
        while (index < futures.size()) {
            try {
                Future future = completionService.take();// ?????????????????????
                future.get();
            } catch (InterruptedException e) {
                exception = new LoadException(e);
                break;
            } catch (ExecutionException e) {
                exception = new LoadException(e);
                break;
            }

            index++;
        }

        // ??????????????????????????????????????????????????????????????????
        if (index < futures.size()) {// ?????????????????????????????????????????????????????????cancel????????????????????????????????????????????????????????????????????????
            for (int errorIndex = 0; errorIndex < futures.size(); errorIndex++) {
                Future future = futures.get(errorIndex);
                if (future.isDone()) {
                    try {
                        LoadContext loadContext = (LoadContext) future.get();

                        if (loadContext instanceof DbLoadContext) {
                            dbInterceptor.error((DbLoadContext) loadContext);// ?????????????????????????????????store???
                        }
                    } catch (InterruptedException e) {
                        // ignore
                    } catch (ExecutionException e) {
                        // ignore
                    } catch (Exception e) {
                        logger.error("interceptor process error failed", e);
                    }

                } else {
                    future.cancel(true); // ???????????????????????????
                }
            }
        } else {
            for (int i = 0; i < futures.size(); i++) {// ???????????????????????????????????????
                Future future = futures.get(i);
                try {
                    LoadContext loadContext = (LoadContext) future.get();

                    if (loadContext instanceof DbLoadContext) {
                        processedContexts.add((DbLoadContext) loadContext);
                    }
                } catch (InterruptedException e) {
                    // ignore
                } catch (ExecutionException e) {
                    // ignore
                }
            }
        }

        if (exception != null) {
            throw exception;
        } else {
            return processedContexts;
        }
    }

    private void submitFileBatch(List<Future> futures, ExecutorCompletionService completionService,
                                 final FileBatch fileBatch, final File rootDir, final WeightController controller) {
        futures.add(completionService.submit(new Callable<FileLoadContext>() {

            public FileLoadContext call() throws Exception {
                try {
                    MDC.put(OtterConstants.splitPipelineLogFileKey,
                            String.valueOf(fileBatch.getIdentity().getPipelineId()));

                    FileLoadAction fileLoadAction = (FileLoadAction) beanFactory.getBean("fileLoadAction",
                                                                                         FileLoadAction.class);
                    return fileLoadAction.load(fileBatch, rootDir, controller);
                } finally {
                    MDC.remove(OtterConstants.splitPipelineLogFileKey);
                }
            }
        }));
    }

    private void submitRowBatch(List<Future> futures, ExecutorCompletionService completionService,
                                final List<RowBatch> rowBatchs, final WeightController controller) {
        for (final RowBatch rowBatch : rowBatchs) {
            // ??????????????????????????????
            futures.add(completionService.submit(new Callable<DbLoadContext>() {

                public DbLoadContext call() throws Exception {
                    try {
                        MDC.put(OtterConstants.splitPipelineLogFileKey,
                                String.valueOf(rowBatch.getIdentity().getPipelineId()));
                        // dbLoadAction?????????pool????????????
                        DbLoadAction dbLoadAction = (DbLoadAction) beanFactory.getBean("dbLoadAction",
                                                                                       DbLoadAction.class);
                        return dbLoadAction.load(rowBatch, controller);
                    } finally {
                        MDC.remove(OtterConstants.splitPipelineLogFileKey);
                    }
                }
            }));
        }
    }

    /**
     * ???rowBatch?????????????????????????????????????????????????????????
     */
    private List<RowBatch> split(RowBatch rowBatch) {
        final Identity identity = rowBatch.getIdentity();
        Map<DataMediaSource, RowBatch> result = OtterMigrateMap.makeComputingMap(new Function<DataMediaSource, RowBatch>() {

            public RowBatch apply(DataMediaSource input) {
                RowBatch rowBatch = new RowBatch();
                rowBatch.setIdentity(identity);
                return rowBatch;
            }
        });

        for (EventData eventData : rowBatch.getDatas()) {
            // ??????????????????
            DataMedia media = ConfigHelper.findDataMedia(configClientService.findPipeline(identity.getPipelineId()),
                                                         eventData.getTableId());
            result.get(media.getSource()).merge(eventData); // ??????
        }

        return new ArrayList<RowBatch>(result.values());
    }

    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public void setConfigClientService(ConfigClientService configClientService) {
        this.configClientService = configClientService;
    }

    public void setDbInterceptor(LoadInterceptor dbInterceptor) {
        this.dbInterceptor = dbInterceptor;
    }

}
