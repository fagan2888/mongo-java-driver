/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoNamespace;
import com.mongodb.WriteConcern;
import com.mongodb.WriteConcernResult;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.binding.AsyncWriteBinding;
import com.mongodb.binding.WriteBinding;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.DeleteRequest;
import com.mongodb.bulk.InsertRequest;
import com.mongodb.bulk.UpdateRequest;
import com.mongodb.bulk.WriteRequest;
import com.mongodb.connection.AsyncConnection;
import com.mongodb.connection.BulkWriteBatchCombiner;
import com.mongodb.connection.Connection;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.internal.connection.IndexMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.mongodb.assertions.Assertions.isTrueArgument;
import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.bulk.WriteRequest.Type.DELETE;
import static com.mongodb.bulk.WriteRequest.Type.INSERT;
import static com.mongodb.bulk.WriteRequest.Type.REPLACE;
import static com.mongodb.bulk.WriteRequest.Type.UPDATE;
import static com.mongodb.internal.async.ErrorHandlingResultCallback.errorHandlingCallback;
import static com.mongodb.operation.OperationHelper.AsyncCallableWithConnection;
import static com.mongodb.operation.OperationHelper.CallableWithConnection;
import static com.mongodb.operation.OperationHelper.LOGGER;
import static com.mongodb.operation.OperationHelper.releasingCallback;
import static com.mongodb.operation.OperationHelper.validateWriteRequests;
import static com.mongodb.operation.OperationHelper.withConnection;
import static java.lang.String.format;
import static java.util.Collections.singletonList;

/**
 * An operation to execute a series of write operations in bulk.
 *
 * @since 3.0
 */
public class MixedBulkWriteOperation implements AsyncWriteOperation<BulkWriteResult>, WriteOperation<BulkWriteResult> {
    private final MongoNamespace namespace;
    private final List<? extends WriteRequest> writeRequests;
    private final boolean ordered;
    private final WriteConcern writeConcern;
    private Boolean bypassDocumentValidation;

    /**
     * Construct a new instance.
     *
     * @param namespace     the database and collection namespace for the operation.
     * @param writeRequests the list of writeRequests to execute.
     * @param ordered       whether the writeRequests must be executed in order.
     * @param writeConcern  the write concern for the operation.
     */
    public MixedBulkWriteOperation(final MongoNamespace namespace, final List<? extends WriteRequest> writeRequests, final boolean ordered,
                                   final WriteConcern writeConcern) {
        this.ordered = ordered;
        this.namespace = notNull("namespace", namespace);
        this.writeRequests = notNull("writes", writeRequests);
        this.writeConcern = notNull("writeConcern", writeConcern);
        isTrueArgument("writes is not an empty list", !writeRequests.isEmpty());
    }


    /**
     * Gets the namespace of the collection to write to.
     *
     * @return the namespace
     */
    public MongoNamespace getNamespace() {
        return namespace;
    }

    /**
     * Gets the write concern to apply
     *
     * @return the write concern
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    /**
     * Gets whether the writes are ordered.  If true, no more writes will be executed after the first failure.
     *
     * @return whether the writes are ordered
     */
    public boolean isOrdered() {
        return ordered;
    }

    /**
     * Gets the list of write requests to execute.
     *
     * @return the list of write requests
     */
    public List<? extends WriteRequest> getWriteRequests() {
        return writeRequests;
    }

    /**
     * Gets the the bypass document level validation flag
     *
     * @return the bypass document level validation flag
     * @since 3.2
     * @mongodb.server.release 3.2
     */
    public Boolean getBypassDocumentValidation() {
        return bypassDocumentValidation;
    }

    /**
     * Sets the bypass document level validation flag.
     *
     * @param bypassDocumentValidation If true, allows the write to opt-out of document level validation.
     * @return this
     * @since 3.2
     * @mongodb.server.release 3.2
     */
    public MixedBulkWriteOperation bypassDocumentValidation(final Boolean bypassDocumentValidation) {
        this.bypassDocumentValidation = bypassDocumentValidation;
        return this;
    }

    /**
     * Executes a bulk write operation.
     *
     * @param binding the WriteBinding        for the operation
     * @return the bulk write result.
     * @throws com.mongodb.MongoBulkWriteException if a failure to complete the bulk write is detected based on the server response
     */
    @Override
    public BulkWriteResult execute(final WriteBinding binding) {
        return withConnection(binding, new CallableWithConnection<BulkWriteResult>() {
            @Override
            public BulkWriteResult call(final Connection connection) {
                validateWriteRequests(connection, bypassDocumentValidation, writeRequests,
                        writeConcern);
                BulkWriteBatchCombiner bulkWriteBatchCombiner = new BulkWriteBatchCombiner(connection.getDescription().getServerAddress(),
                                                                                           ordered, writeConcern);
                for (Run run : getRunGenerator(connection.getDescription())) {
                    try {
                        BulkWriteResult result = run.execute(connection);
                        if (result.wasAcknowledged()) {
                            bulkWriteBatchCombiner.addResult(result, run.indexMap);
                        }
                    } catch (MongoBulkWriteException e) {
                        bulkWriteBatchCombiner.addErrorResult(e, run.indexMap);
                        if (bulkWriteBatchCombiner.shouldStopSendingMoreBatches()) {
                            break;
                        }
                    }
                }
                return bulkWriteBatchCombiner.getResult();
            }
        });
    }

    @Override
    public void executeAsync(final AsyncWriteBinding binding, final SingleResultCallback<BulkWriteResult> callback) {
        withConnection(binding, new AsyncCallableWithConnection() {
            @Override
            public void call(final AsyncConnection connection, final Throwable t) {

                final SingleResultCallback<BulkWriteResult> errHandlingCallback = errorHandlingCallback(callback, LOGGER);

                if (t != null) {
                    errHandlingCallback.onResult(null, t);
                } else {
                    validateWriteRequests(connection, bypassDocumentValidation, writeRequests,
                            writeConcern, new AsyncCallableWithConnection() {
                                @Override
                                public void call(final AsyncConnection connection, final Throwable t) {
                                    if (t != null) {
                                        releasingCallback(errHandlingCallback, connection).onResult(null, t);
                                    } else {
                                        Iterator<Run> runs = getRunGenerator(connection.getDescription()).iterator();
                                        executeRunsAsync(runs, connection,
                                                new BulkWriteBatchCombiner(connection.getDescription().getServerAddress(), ordered,
                                                        writeConcern),
                                                errHandlingCallback);
                                    }
                                }
                            });
                }
            }
        });
    }

    private void executeRunsAsync(final Iterator<Run> runs, final AsyncConnection connection,
                                  final BulkWriteBatchCombiner bulkWriteBatchCombiner,
                                  final SingleResultCallback<BulkWriteResult> callback) {

        final Run run = runs.next();
        final SingleResultCallback<BulkWriteResult> wrappedCallback = releasingCallback(callback, connection);
        run.executeAsync(connection, new SingleResultCallback<BulkWriteResult>() {
            @Override
            public void onResult(final BulkWriteResult result, final Throwable t) {
                if (t != null) {
                    if (t instanceof MongoBulkWriteException) {
                        bulkWriteBatchCombiner.addErrorResult((MongoBulkWriteException) t, run.indexMap);
                    } else {
                        wrappedCallback.onResult(null, t);
                        return;
                    }
                } else if (result.wasAcknowledged()) {
                    bulkWriteBatchCombiner.addResult(result, run.indexMap);
                }

                // Execute next run or complete
                if (runs.hasNext() && !bulkWriteBatchCombiner.shouldStopSendingMoreBatches()) {
                    executeRunsAsync(runs, connection, bulkWriteBatchCombiner, callback);
                } else {
                    if (bulkWriteBatchCombiner.hasErrors()) {
                        wrappedCallback.onResult(null, bulkWriteBatchCombiner.getError());
                    } else {
                        wrappedCallback.onResult(bulkWriteBatchCombiner.getResult(), null);
                    }
                }
            }
        });
    }

    private Iterable<Run> getRunGenerator(final ConnectionDescription connectionDescription) {
        if (ordered) {
            return new OrderedRunGenerator(connectionDescription, bypassDocumentValidation);
        } else {
            return new UnorderedRunGenerator(connectionDescription, bypassDocumentValidation);
        }
    }

    private class OrderedRunGenerator implements Iterable<Run> {
        private final int maxBatchCount;
        private final Boolean bypassDocumentValidation;

        OrderedRunGenerator(final ConnectionDescription connectionDescription, final Boolean bypassDocumentValidation) {
            this.maxBatchCount = connectionDescription.getMaxBatchCount();
            this.bypassDocumentValidation = bypassDocumentValidation;
        }

        @Override
        public Iterator<Run> iterator() {
            return new Iterator<Run>() {
                private int curIndex;

                @Override
                public boolean hasNext() {
                    return curIndex < writeRequests.size();
                }

                @Override
                public Run next() {
                    Run run = new Run(writeRequests.get(curIndex).getType(), true, bypassDocumentValidation);
                    int nextIndex = getNextIndex();
                    for (int i = curIndex; i < nextIndex; i++) {
                        run.add(writeRequests.get(i), i);
                    }
                    curIndex = nextIndex;
                    return run;
                }

                private int getNextIndex() {
                    WriteRequest.Type type = writeRequests.get(curIndex).getType();
                    for (int i = curIndex; i < writeRequests.size(); i++) {
                        if (i == curIndex + maxBatchCount || writeRequests.get(i).getType() != type) {
                            return i;
                        }
                    }
                    return writeRequests.size();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("Not implemented");
                }
            };
        }
    }


    private class UnorderedRunGenerator implements Iterable<Run> {
        private final int maxBatchCount;
        private final Boolean bypassDocumentValidation;

        UnorderedRunGenerator(final ConnectionDescription connectionDescription, final Boolean bypassDocumentValidation) {
            this.maxBatchCount = connectionDescription.getMaxBatchCount();
            this.bypassDocumentValidation = bypassDocumentValidation;
        }

        @Override
        public Iterator<Run> iterator() {
            return new Iterator<Run>() {
                private final List<Run> runs = new ArrayList<Run>();
                private int curIndex;

                @Override
                public boolean hasNext() {
                    return curIndex < writeRequests.size() || !runs.isEmpty();
                }

                @Override
                public Run next() {
                    while (curIndex < writeRequests.size()) {
                        WriteRequest writeRequest = writeRequests.get(curIndex);
                        Run run = findRunOfType(writeRequest.getType());
                        if (run == null) {
                            run = new Run(writeRequest.getType(), false, bypassDocumentValidation);
                            runs.add(run);
                        }
                        run.add(writeRequest, curIndex);
                        curIndex++;
                        if (run.size() == maxBatchCount) {
                            runs.remove(run);
                            return run;
                        }
                    }

                    return runs.remove(0);
                }

                private Run findRunOfType(final WriteRequest.Type type) {
                    for (Run cur : runs) {
                        if (cur.type == type) {
                            return cur;
                        }
                    }
                    return null;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException("Not implemented");
                }
            };
        }
    }

    private class Run {
        @SuppressWarnings("rawtypes")
        private final List runWrites = new ArrayList();
        private final WriteRequest.Type type;
        private final boolean ordered;
        private final Boolean bypassDocumentValidation;
        private IndexMap indexMap = IndexMap.create();

        Run(final WriteRequest.Type type, final boolean ordered, final Boolean bypassDocumentValidation) {
            this.type = type;
            this.ordered = ordered;
            this.bypassDocumentValidation = bypassDocumentValidation;
        }

        @SuppressWarnings("unchecked")
        void add(final WriteRequest writeRequest, final int originalIndex) {
            indexMap = indexMap.add(runWrites.size(), originalIndex);
            runWrites.add(writeRequest);
        }

        public int size() {
            return runWrites.size();
        }

        @SuppressWarnings("unchecked")
        BulkWriteResult execute(final Connection connection) {
            final BulkWriteResult nextWriteResult;

            if (type == UPDATE || type == REPLACE) {
                nextWriteResult = getUpdatesRunExecutor((List<UpdateRequest>) runWrites, bypassDocumentValidation, connection).execute();
            } else if (type == INSERT) {
                nextWriteResult = getInsertsRunExecutor((List<InsertRequest>) runWrites, bypassDocumentValidation, connection).execute();
            } else if (type == DELETE) {
                nextWriteResult = getDeletesRunExecutor((List<DeleteRequest>) runWrites, connection).execute();
            } else {
                throw new UnsupportedOperationException(format("Unsupported write of type %s", type));
            }
            return nextWriteResult;
        }

        @SuppressWarnings("unchecked")
        void executeAsync(final AsyncConnection connection, final SingleResultCallback<BulkWriteResult> callback) {
            if (type == UPDATE || type == REPLACE) {
                getUpdatesRunExecutor((List<UpdateRequest>) runWrites, bypassDocumentValidation, connection).executeAsync(callback);
            } else if (type == INSERT) {
                getInsertsRunExecutor((List<InsertRequest>) runWrites, bypassDocumentValidation, connection).executeAsync(callback);
            } else if (type == DELETE) {
                getDeletesRunExecutor((List<DeleteRequest>) runWrites, connection).executeAsync(callback);
            } else {
                callback.onResult(null, new UnsupportedOperationException(format("Unsupported write of type %s", type)));
            }
        }

        RunExecutor getDeletesRunExecutor(final List<DeleteRequest> deleteRequests, final Connection connection) {
            return new RunExecutor() {

                @Override
                void executeWriteProtocol(final int index) {
                    connection.delete(namespace, ordered, writeConcern, singletonList(deleteRequests.get(index)));
                }

                @Override
                BulkWriteResult executeWriteCommandProtocol() {
                    return connection.deleteCommand(namespace, ordered, writeConcern, deleteRequests);
                }

                @Override
                WriteRequest.Type getType() {
                    return DELETE;
                }
            };
        }

        @SuppressWarnings("unchecked")
        RunExecutor getInsertsRunExecutor(final List<InsertRequest> insertRequests, final Boolean bypassDocumentValidation,
                                          final Connection connection) {
            return new RunExecutor() {

                @Override
                void executeWriteProtocol(final int index) {
                    connection.insert(namespace, ordered, writeConcern, singletonList(insertRequests.get(index)));
                }

                @Override
                BulkWriteResult executeWriteCommandProtocol() {
                    return connection.insertCommand(namespace, ordered, writeConcern, bypassDocumentValidation, insertRequests);
                }

                @Override
                WriteRequest.Type getType() {
                    return INSERT;
                }
            };
        }

        RunExecutor getUpdatesRunExecutor(final List<UpdateRequest> updates, final Boolean bypassDocumentValidation,
                                          final Connection connection) {
            return new RunExecutor() {

                @Override
                void executeWriteProtocol(final int index) {
                    connection.update(namespace, ordered, writeConcern, singletonList(updates.get(index)));
                }

                @Override
                BulkWriteResult executeWriteCommandProtocol() {
                    return connection.updateCommand(namespace, ordered, writeConcern, bypassDocumentValidation, updates);
                }

                @Override
                WriteRequest.Type getType() {
                    return UPDATE;
                }

            };
        }

        AsyncRunExecutor getDeletesRunExecutor(final List<DeleteRequest> deleteRequests, final AsyncConnection connection) {
            return new AsyncRunExecutor() {

                @Override
                void executeWriteProtocolAsync(final int index, final SingleResultCallback<WriteConcernResult> callback) {
                    connection.deleteAsync(namespace, ordered, writeConcern, singletonList(deleteRequests.get(index)), callback);
                }

                @Override
                void executeWriteCommandProtocolAsync(final SingleResultCallback<BulkWriteResult> callback) {
                    connection.deleteCommandAsync(namespace, ordered, writeConcern, deleteRequests, callback);
                }

                @Override
                WriteRequest.Type getType() {
                    return DELETE;
                }
            };
        }

        @SuppressWarnings("unchecked")
        AsyncRunExecutor getInsertsRunExecutor(final List<InsertRequest> insertRequests, final Boolean bypassDocumentValidation,
                                               final AsyncConnection connection) {
            return new AsyncRunExecutor() {

                @Override
                void executeWriteProtocolAsync(final int index, final SingleResultCallback<WriteConcernResult> callback) {
                    connection.insertAsync(namespace, ordered, writeConcern, singletonList(insertRequests.get(index)), callback);
                }

                @Override
                void executeWriteCommandProtocolAsync(final SingleResultCallback<BulkWriteResult> callback) {
                    connection.insertCommandAsync(namespace, ordered, writeConcern, bypassDocumentValidation, insertRequests, callback);
                }

                @Override
                WriteRequest.Type getType() {
                    return INSERT;
                }
            };
        }

        AsyncRunExecutor getUpdatesRunExecutor(final List<UpdateRequest> updates, final Boolean bypassDocumentValidation,
                                               final AsyncConnection connection) {
            return new AsyncRunExecutor() {

                @Override
                void executeWriteProtocolAsync(final int index, final SingleResultCallback<WriteConcernResult> callback) {
                    connection.updateAsync(namespace, ordered, writeConcern, singletonList(updates.get(index)), callback);
                }

                @Override
                void executeWriteCommandProtocolAsync(final SingleResultCallback<BulkWriteResult> callback) {
                    connection.updateCommandAsync(namespace, ordered, writeConcern, bypassDocumentValidation, updates, callback);
                }

                @Override
                WriteRequest.Type getType() {
                    return UPDATE;
                }

            };
        }

        private abstract class BaseRunExecutor {

            abstract WriteRequest.Type getType();

        }

        private abstract class RunExecutor extends BaseRunExecutor {

            abstract void executeWriteProtocol(int index);

            abstract BulkWriteResult executeWriteCommandProtocol();

            BulkWriteResult execute() {
                if (writeConcern.isAcknowledged()) {
                    return executeWriteCommandProtocol();
                } else {
                    for (int i = 0; i < runWrites.size(); i++) {
                        IndexMap indexMap = IndexMap.create(i, 1);
                        indexMap = indexMap.add(0, i);
                        executeWriteProtocol(i);
                    }
                    return BulkWriteResult.unacknowledged();
                }
            }
        }

        private abstract class AsyncRunExecutor extends BaseRunExecutor {

            abstract void executeWriteProtocolAsync(int index, SingleResultCallback<WriteConcernResult> callback);

            abstract void executeWriteCommandProtocolAsync(SingleResultCallback<BulkWriteResult> callback);

            void executeAsync(final SingleResultCallback<BulkWriteResult> callback) {
                if (writeConcern.isAcknowledged()) {
                    executeWriteCommandProtocolAsync(callback);
                } else {
                    executeRunWritesAsync(runWrites.size(), 0, callback);
                }
            }

            private void executeRunWritesAsync(final int numberOfRuns, final int currentPosition,
                                               final SingleResultCallback<BulkWriteResult> callback) {

                executeWriteProtocolAsync(currentPosition, new SingleResultCallback<WriteConcernResult>() {

                    @Override
                    public void onResult(final WriteConcernResult result, final Throwable t) {
                        final int nextRunPosition = currentPosition + 1;
                        if (t != null) {
                            callback.onResult(null, t);
                            return;
                        }

                        // Execute next run or complete
                        if (numberOfRuns != nextRunPosition) {
                            executeRunWritesAsync(numberOfRuns, nextRunPosition, callback);
                        } else {
                            callback.onResult(BulkWriteResult.unacknowledged(), null);
                        }
                    }
                });
            }
       }
    }
}
