/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.common.db;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class RangerTransactionSynchronizationAdapter extends TransactionSynchronizationAdapter {

    @Autowired
    @Qualifier(value = "transactionManager")
    PlatformTransactionManager txManager;

    private static final Log LOG = LogFactory.getLog(RangerTransactionSynchronizationAdapter.class);

    private static final ThreadLocal<List<Runnable>> RUNNABLES = new ThreadLocal<List<Runnable>>();

    public void executeOnTransactionCompletion(Runnable runnable) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Submitting new runnable {" + runnable + "} to run after completion");
        }

        /*
        From TransactionSynchronizationManager documentation:
        TransactionSynchronizationManager is a central helper that manages resources and transaction synchronizations per thread.
        Resource management code should only register synchronizations when this manager is active,
        which can be checked via isSynchronizationActive(); it should perform immediate resource cleanup else.
        If transaction synchronization isn't active, there is either no current transaction,
        or the transaction manager doesn't support transaction synchronization.

        Note: Synchronization is an Interface for transaction synchronization callbacks which is implemented by
        TransactionSynchronizationAdapter
        */

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOG.info("Transaction synchronization is NOT ACTIVE. Executing right now runnable {" + runnable + "}");
            runnable.run();
            return;
        }
        List<Runnable> threadRunnables = RUNNABLES.get();
        if (threadRunnables == null) {
            threadRunnables = new ArrayList<Runnable>();
            RUNNABLES.set(threadRunnables);
            // Register a new transaction synchronization for the current thread.
            // TransactionSynchronizationManage will call afterCompletion() when current transaction completes.
            TransactionSynchronizationManager.registerSynchronization(this);
        }
        threadRunnables.add(runnable);
    }

    @Override
    public void afterCompletion(int status) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Transaction completed with status {" + (status == STATUS_COMMITTED ? "COMMITTED" : "ROLLED_BACK") + "}");
        }
        /* Thread runnables are expected to be executed only when the status is STATUS_ROLLED_BACK. Currently, executeOnTransactionCompletion()
         * is called only for those changes that are going to be rolled-back by TransactionSynchronizationManager - such
         * as when the operation returns HttpServletResponse.SC_NOT_MODIFIED status.
         */
        //if (status == STATUS_ROLLED_BACK) {
            final List<Runnable> threadRunnables = RUNNABLES.get();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Transaction completed, executing {" + threadRunnables.size() + "} runnables");
            }
            if (threadRunnables != null) {
                try {
                    //Create new  transaction
                    TransactionTemplate txTemplate = new TransactionTemplate(txManager);
                    txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                    txTemplate.execute(new TransactionCallback<Object>() {
                        public Object doInTransaction(TransactionStatus status) {
                            for (Runnable runnable : threadRunnables) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Executing runnable {" + runnable + "}");
                                }
                                try {
                                    runnable.run();
                                } catch (RuntimeException e) {
                                    LOG.error("Failed to execute runnable " + runnable, e);
                                    break;
                                }
                            }

                            return null;
                        }
                    });
                } catch (Exception e) {
                    LOG.error("Failed to commit TransactionService transaction", e);
                    LOG.error("Ignoring...");
                }
            }

        //}
        RUNNABLES.remove();
    }

}