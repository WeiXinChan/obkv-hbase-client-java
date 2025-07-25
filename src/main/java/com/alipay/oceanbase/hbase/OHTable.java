/*-
 * #%L
 * OBKV HBase Client Framework
 * %%
 * Copyright (C) 2022 OceanBase Group
 * %%
 * OBKV HBase Client Framework  is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details.
 * #L%
 */

package com.alipay.oceanbase.hbase;

import com.alipay.oceanbase.hbase.exception.FeatureNotSupportedException;
import com.alipay.oceanbase.hbase.exception.OperationTimeoutException;
import com.alipay.oceanbase.hbase.execute.ServerCallable;
import com.alipay.oceanbase.hbase.filter.HBaseFilterUtils;
import com.alipay.oceanbase.hbase.result.ClientStreamScanner;
import com.alipay.oceanbase.hbase.util.*;
import com.alipay.oceanbase.rpc.ObGlobal;
import com.alipay.oceanbase.rpc.ObTableClient;
import com.alipay.oceanbase.rpc.exception.ObTableException;
import com.alipay.oceanbase.rpc.location.model.partition.Partition;
import com.alipay.oceanbase.rpc.exception.ObTableUnexpectedException;
import com.alipay.oceanbase.rpc.mutation.BatchOperation;
import com.alipay.oceanbase.rpc.mutation.result.BatchOperationResult;
import com.alipay.oceanbase.rpc.mutation.result.MutationResult;
import com.alipay.oceanbase.rpc.protocol.payload.ObPayload;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObObj;
import com.alipay.oceanbase.rpc.protocol.payload.impl.ObRowKey;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.*;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutate;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutateRequest;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.mutate.ObTableQueryAndMutateResult;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.query.*;
import com.alipay.oceanbase.rpc.protocol.payload.impl.execute.syncquery.ObTableQueryAsyncRequest;
import com.alipay.oceanbase.rpc.queryandmutate.QueryAndMutate;
import com.alipay.oceanbase.rpc.stream.ObTableClientQueryAsyncStreamResult;
import com.alipay.oceanbase.rpc.stream.ObTableClientQueryStreamResult;
import com.alipay.oceanbase.rpc.table.ObHBaseParams;
import com.alipay.oceanbase.rpc.table.ObKVParams;
import com.alipay.oceanbase.rpc.table.ObTableClientQueryImpl;

import com.google.protobuf.*;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.filter.*;
import org.apache.hadoop.hbase.io.TimeRange;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcChannel;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static com.alipay.oceanbase.hbase.constants.OHConstants.*;
import static com.alipay.oceanbase.hbase.util.Preconditions.checkArgument;
import static com.alipay.oceanbase.hbase.util.TableHBaseLoggerFactory.LCD;
import static com.alipay.oceanbase.rpc.mutation.MutationFactory.colVal;
import static com.alipay.oceanbase.rpc.mutation.MutationFactory.row;
import static com.alipay.oceanbase.rpc.protocol.payload.impl.execute.ObTableOperation.getInstance;
import static com.alipay.oceanbase.rpc.protocol.payload.impl.execute.ObTableOperationType.*;
import static com.alipay.sofa.common.thread.SofaThreadPoolConstants.SOFA_THREAD_POOL_LOGGING_CAPABILITY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static com.alipay.oceanbase.hbase.filter.HBaseFilterUtils.writeBytesWithEscape;
import static org.apache.hadoop.hbase.KeyValue.Type.*;

public class OHTable implements HTableInterface {

    private static final Logger   logger                 = TableHBaseLoggerFactory
                                                             .getLogger(OHTable.class);
    /**
     * the table client for oceanbase
     */
    private final ObTableClient   obTableClient;

    /**
     * the ohTable name in byte array
     */
    private final byte[]          tableName;

    /**
     * the ohTable name in string
     */
    private final String          tableNameString;

    /**
     * operation timeout whose default value is <code>Integer.MaxValue</code> decide the timeout of executing in pool.
     * <p>
     * if operation timeout is not equal to the default value mean the <code>Get</code> execute in the pool
     */
    private int                   operationTimeout;

    /**
     * timeout for each rpc request
     */
    private int                   rpcTimeout;

    /**
     * if the <code>Get</code> executing pool is specified by user cleanupPoolOnClose will be false ,
     * which means that user is responsible for the pool
     */
    private boolean               cleanupPoolOnClose     = true;

    /**
     * if the obTableClient is specified by user closeClientOnClose will be false ,
     * which means that user is responsible for obTableClient
     */
    private boolean               closeClientOnClose     = true;

    /**
     * when the operationExecuteInPool is true the <code>Get</code>
     * will be executed in the pool.
     */
    private ExecutorService       executePool;

    /**
     * decide whether the <code>Get</code> request will be executed
     * in the pool.
     */
    private boolean               operationExecuteInPool = false;

    /**
     * the buffer of put request
     */
    private final ArrayList<Put>  writeBuffer            = new ArrayList<Put>();
    /**
     * when the put request reach the write buffer size the do put will
     * flush commits automatically
     */
    private long                  writeBufferSize;

    /**
     * whether flush the put automatically
     */
    private boolean               autoFlush              = true;

    /**
     * the max size of put key value
     */
    private int                   maxKeyValueSize;

    // i.e., doPut checks the writebuffer every X Puts.

    /**
     * <code>Configuration</code> extends from hbase configuration
     */
    private final Configuration   configuration;

    private int                   scannerTimeout;

    /**
     * the bufferedMutator to execute Puts
     */
    private OHBufferedMutatorImpl mutator;
    
    private RegionLocator          regionLocator;

    /**
     * flag for whether closed
     */
    private boolean               isClosed               = false;

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.  Recommended.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @throws IllegalArgumentException if the param error
     * @throws IOException              if a remote or network exception occurs
     */
    public OHTable(Configuration configuration, String tableName) throws IOException {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(isNotBlank(tableName), "tableNameString is blank.");
        this.configuration = configuration;
        this.tableName = tableName.getBytes();
        this.tableNameString = tableName;

        int maxThreads = configuration.getInt(HBASE_HTABLE_PRIVATE_THREADS_MAX,
            DEFAULT_HBASE_HTABLE_PRIVATE_THREADS_MAX);
        long keepAliveTime = configuration.getLong(HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME,
            DEFAULT_HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME);
        this.executePool = createDefaultThreadPoolExecutor(1, maxThreads, keepAliveTime);
        OHConnectionConfiguration ohConnectionConf = new OHConnectionConfiguration(configuration);
        int numRetries = ohConnectionConf.getNumRetries();
        this.obTableClient = ObTableClientManager.getOrCreateObTableClient(setUserDefinedNamespace(
            this.tableNameString, ohConnectionConf));
        this.obTableClient.setRpcExecuteTimeout(ohConnectionConf.getRpcTimeout());
        this.obTableClient.setRuntimeRetryTimes(numRetries);
        setOperationTimeout(ohConnectionConf.getOperationTimeout());

        finishSetUp();
    }

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.  Recommended.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @throws IOException              if a remote or network exception occurs
     * @throws IllegalArgumentException if the param error
     */
    public OHTable(Configuration configuration, final byte[] tableName) throws IOException {
        this(configuration, Arrays.toString(tableName));
    }

    /**
     * Creates an object to access a HBase table.
     * Shares oceanbase table obTableClient and other resources with other OHTable instances
     * created with the same <code>configuration</code> instance.  Uses already-populated
     * region cache if one is available, populated by any other OHTable instances
     * sharing this <code>configuration</code> instance.
     * Use this constructor when the ExecutorService is externally managed.
     *
     * @param configuration Configuration object to use.
     * @param tableName     Name of the table.
     * @param executePool   ExecutorService to be used.
     * @throws IOException              if a remote or network exception occurs
     * @throws IllegalArgumentException if the param error
     */
    public OHTable(Configuration configuration, final byte[] tableName,
                   final ExecutorService executePool) throws IOException {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(tableName != null, "tableNameString is blank.");
        checkArgument(executePool != null && !executePool.isShutdown(),
            "executePool is null or executePool is shutdown");
        this.configuration = configuration;
        this.tableName = tableName;
        this.tableNameString = Bytes.toString(tableName);
        this.executePool = executePool;
        this.cleanupPoolOnClose = false;
        OHConnectionConfiguration ohConnectionConf = new OHConnectionConfiguration(configuration);
        int numRetries = ohConnectionConf.getNumRetries();
        this.obTableClient = ObTableClientManager.getOrCreateObTableClient(setUserDefinedNamespace(
            this.tableNameString, ohConnectionConf));
        this.obTableClient.setRpcExecuteTimeout(ohConnectionConf.getRpcTimeout());
        this.obTableClient.setRuntimeRetryTimes(numRetries);
        setOperationTimeout(ohConnectionConf.getOperationTimeout());

        finishSetUp();
    }

    /**
     * Creates an object to access a HBase table.
     * Shares zookeeper connection and other resources with other OHTable instances
     * created with the same <code>connection</code> instance.
     * Use this constructor when the ExecutorService and HConnection instance are
     * externally managed.
     *
     * @param tableName     Name of the table.
     * @param obTableClient Oceanbase obTableClient to be used.
     * @param executePool   ExecutorService to be used.
     * @throws IllegalArgumentException if the param error
     */
    @InterfaceAudience.Private
    public OHTable(final byte[] tableName, final ObTableClient obTableClient,
                   final ExecutorService executePool) {
        checkArgument(tableName != null, "tableNameString is blank.");
        checkArgument(executePool != null && !executePool.isShutdown(),
            "executePool is null or executePool is shutdown");
        this.tableName = tableName;
        this.tableNameString = Bytes.toString(tableName);
        this.cleanupPoolOnClose = false;
        this.closeClientOnClose = false;
        this.executePool = executePool;
        this.obTableClient = obTableClient;
        this.configuration = HBaseConfiguration.create();
        finishSetUp();
    }

    public OHTable(TableName tableName, Connection connection,
                   OHConnectionConfiguration connectionConfig, ExecutorService executePool)
                                                                                           throws IOException {
        checkArgument(connection.getConfiguration() != null, "configuration is null.");
        checkArgument(tableName != null, "tableName is null.");
        checkArgument(connection.getConfiguration() != null, "configuration is null.");
        checkArgument(tableName.getName() != null, "tableNameString is null.");
        checkArgument(connectionConfig != null, "connectionConfig is null.");
        this.tableNameString = Bytes.toString(tableName.getName());
        this.configuration = connection.getConfiguration();
        this.executePool = executePool;
        if (executePool == null) {
            int maxThreads = configuration.getInt(HBASE_HTABLE_PRIVATE_THREADS_MAX,
                DEFAULT_HBASE_HTABLE_PRIVATE_THREADS_MAX);
            long keepAliveTime = configuration.getLong(HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME,
                DEFAULT_HBASE_HTABLE_THREAD_KEEP_ALIVE_TIME);
            this.executePool = createDefaultThreadPoolExecutor(1, maxThreads, keepAliveTime);
            this.cleanupPoolOnClose = true;
        } else {
            this.cleanupPoolOnClose = false;
        }

        this.rpcTimeout = connectionConfig.getRpcTimeout();
        this.operationTimeout = connectionConfig.getOperationTimeout();
        this.operationExecuteInPool = this.configuration.getBoolean(
            HBASE_CLIENT_OPERATION_EXECUTE_IN_POOL,
            (this.operationTimeout != HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT));
        this.maxKeyValueSize = connectionConfig.getMaxKeyValueSize();
        this.writeBufferSize = connectionConfig.getWriteBufferSize();
        this.tableName = tableName.getName();
        int numRetries = connectionConfig.getNumRetries();
        this.obTableClient = ObTableClientManager.getOrCreateObTableClient(setUserDefinedNamespace(
            this.tableNameString, connectionConfig));
        this.obTableClient.setRpcExecuteTimeout(rpcTimeout);
        this.obTableClient.setRuntimeRetryTimes(numRetries);
        setOperationTimeout(operationTimeout);

        finishSetUp();
    }

    /**
     * 创建默认的线程池
     * Using the "direct handoff" approach, new threads will only be created
     * if it is necessary and will grow unbounded. This could be bad but in HCM
     * we only create as many Runnables as there are region servers. It means
     * it also scales when new region servers are added.
     * @param coreSize core size
     * @param maxThreads max threads
     * @param keepAliveTime keep alive time
     * @return ThreadPoolExecutor
     */
    @InterfaceAudience.Private
    public static ThreadPoolExecutor createDefaultThreadPoolExecutor(int coreSize, int maxThreads,
                                                                     long keepAliveTime) {
        // NOTE: when SOFA_THREAD_POOL_LOGGING_CAPABILITY is set to true or not set,
        // the static instance ThreadPoolGovernor will start a non-daemon thread pool
        // monitor thread in the function ThreadPoolMonitorWrapper.startMonitor,
        // which will prevent the client process from normal exit
        if (System.getProperty(SOFA_THREAD_POOL_LOGGING_CAPABILITY) == null) {
            System.setProperty(SOFA_THREAD_POOL_LOGGING_CAPABILITY, "false");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(coreSize, maxThreads,
                keepAliveTime, SECONDS, new SynchronousQueue<>(), Threads.newDaemonThreadFactory("ohtable"));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 参数校验，防止在执行的时候才发现配置不正确
     */
    private void finishSetUp() {
        checkArgument(configuration != null, "configuration is null.");
        checkArgument(tableName != null, "tableNameString is null.");
        checkArgument(tableNameString != null, "tableNameString is null.");
        this.scannerTimeout = HBaseConfiguration.getInt(configuration,
            HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD,
            HConstants.HBASE_REGIONSERVER_LEASE_PERIOD_KEY,
            HConstants.DEFAULT_HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD);
        this.rpcTimeout = configuration.getInt(HConstants.HBASE_RPC_TIMEOUT_KEY,
            HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
        this.operationTimeout = this.configuration.getInt(
            HConstants.HBASE_CLIENT_OPERATION_TIMEOUT,
            HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT);
        this.operationExecuteInPool = this.configuration.getBoolean(
            HBASE_CLIENT_OPERATION_EXECUTE_IN_POOL,
            (this.operationTimeout != HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT));
        this.maxKeyValueSize = this.configuration.getInt(MAX_KEYVALUE_SIZE_KEY,
            MAX_KEYVALUE_SIZE_DEFAULT);
        this.writeBufferSize = this.configuration.getLong(WRITE_BUFFER_SIZE_KEY,
            WRITE_BUFFER_SIZE_DEFAULT);
    }

    public static OHConnectionConfiguration setUserDefinedNamespace(String tableNameString,
                                                              OHConnectionConfiguration ohConnectionConf) {
        if (tableNameString.indexOf(':') != -1) {
            String[] params = tableNameString.split(":");
            if (params.length != 2) {
                throw new IllegalArgumentException("Please check the format of self-defined "
                                                   + "namespace and qualifier: { "
                                                   + tableNameString + " }");
            }
            String database = params[0];
            checkArgument(isNotBlank(database), "self-defined namespace cannot be blank or null { "
                                                + tableNameString + " }");
            if (ohConnectionConf.isOdpMode()) {
                ohConnectionConf.setDatabase(database);
            } else {
                String databaseSuffix = "database=" + database;
                String paramUrl = ohConnectionConf.getParamUrl();
                int databasePos = paramUrl.indexOf("database");
                if (databasePos == -1) {
                    paramUrl += "&" + databaseSuffix;
                } else {
                    paramUrl = paramUrl.substring(0, databasePos) + databaseSuffix;
                }
                ohConnectionConf.setParamUrl(paramUrl);
            }
        }
        return ohConnectionConf;
    }

    @Override
    public byte[] getTableName() {
        return tableName;
    }

    @Override
    public TableName getName() {
        return TableName.valueOf(this.tableNameString);
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public HTableDescriptor getTableDescriptor() throws IOException {
        OHTableDescriptorExecutor executor = new OHTableDescriptorExecutor(tableNameString, obTableClient);
        return executor.getTableDescriptor();
    }

    /**
     * Test for the existence of columns in the table, as specified in the Get.
     *
     * This will return true if the Get matches one or more keys, false if not.
     *
     * Be carefully ,this is not a server-side call in real. May be optimized later
     *
     * @param get the Get
     * @return true if the specified Get matches one or more keys, false if not
     * @throws IOException e
     */
    @Override
    public boolean exists(Get get) throws IOException {
        Get newGet = new Get(get);
        newGet.setCheckExistenceOnly(true);
        return this.get(newGet).getExists();
    }

    @Override
    public boolean[] existsAll(List<Get> gets) throws IOException {
        boolean[] ret = new boolean[gets.size()];
        List<Get> newGets = new ArrayList<>();
        // if just checkExistOnly, batch get will not return any result or row count
        // therefore we have to set checkExistOnly as false and so the result can be returned
        // TODO: adjust ExistOnly in server when using batch get
        for (Get get : gets) {
            Get newGet = new Get(get);
            newGet.setCheckExistenceOnly(false);
            newGets.add(newGet);
        }
        Result[] results = get(newGets);
        for (int i = 0; i < results.length; ++i) {
            ret[i] = !results[i].isEmpty();
        }
        return ret;
    }

    @Override
    public Boolean[] exists(List<Get> gets) throws IOException {
        boolean[] results = existsAll(gets);
        Boolean[] objectResults = new Boolean[results.length];
        for (int i = 0; i < results.length; ++i) {
            objectResults[i] = results[i];
        }
        return objectResults;
    }

    private BatchOperation compatOldServerPut(final List<? extends Row> actions, final Object[] results, BatchError batchError, int i, List<Row> puts)
            throws Exception {
        BatchOperationResult tmpResults;
        List<Integer> resultMapSingleOp = new LinkedList<>();
        if (((Put)actions.get(i)).getFamilyCellMap().size() == 1) {
            puts.clear();
            Put put = (Put) actions.get(i++);
            String family = Bytes.toString(put.getFamilyCellMap().firstKey());
            // aggregate put
            puts.add(put);
            while (i < actions.size()) {
                if (actions.get(i) instanceof Put && ((Put)actions.get(i)).getFamilyCellMap().size() == 1 && Objects.equals(
                        Bytes.toString(((Put) actions.get(i)).getFamilyCellMap().firstKey()),
                        family)) {
                    puts.add(actions.get(i++));
                } else {
                    break;
                }
            }
            String realTableName = getTargetTableName(tableNameString, family,
                    configuration);
            BatchOperation batch = buildBatchOperation(realTableName, puts, false,
                    resultMapSingleOp);
            tmpResults = batch.execute();
            int index = 0;
            for (int k = 0; k < resultMapSingleOp.size(); k++) {
                if (tmpResults.getResults().get(index) instanceof ObTableException) {
                    if (results != null) {
                        results[i - puts.size() + k] = tmpResults.getResults().get(index);
                    }
                    batchError.add(
                            (ObTableException) tmpResults.getResults().get(index),
                            actions.get(i - puts.size() + k), null);
                } else {
                    if (results != null) {
                        results[i - puts.size() + k] = new Result();
                    }
                }
                index += resultMapSingleOp.get(k);
            }
            return null;
        } else {
            // multi cf put
            Put put = (Put) actions.get(i);
            String realTableName = getTargetTableName(tableNameString);
            return buildBatchOperation(realTableName,
                    Collections.singletonList(put),
                    true, resultMapSingleOp);

        }
    }

    private BatchOperation compatOldServerDel(final List<? extends Row> actions, final Object[] results, BatchError batchError, int i)
            throws Exception {
        Delete delete = (Delete)actions.get(i);
        List<Integer> resultMapSingleOp = new LinkedList<>();
        if (delete.isEmpty()) {
            return buildBatchOperation(tableNameString,
                    Collections.singletonList(delete), true,
                    resultMapSingleOp);
        } else if (delete.getFamilyCellMap().size() > 1) {
            boolean has_delete_family = delete.getFamilyMap().entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()).anyMatch(
                            kv -> KeyValue.Type.codeToType(
                                    kv.getType()) == DeleteFamily || KeyValue.Type.codeToType(
                                    kv.getType()) == DeleteFamilyVersion);
            if (!has_delete_family) {
                return buildBatchOperation(tableNameString,
                        Collections.singletonList(delete), true,
                        resultMapSingleOp);
            } else {
                for (Map.Entry<byte[], List<Cell>> entry : delete.getFamilyCellMap()
                        .entrySet()) {
                    byte[] family = entry.getKey();
                    String realTableName = getTargetTableName(tableNameString,
                            Bytes.toString(family), configuration);
                    // split delete
                    List<Cell> cells = entry.getValue();
                    Delete del = new Delete(delete.getRow());
                    del.getFamilyCellMap().put(family, cells);
                    BatchOperation batch = buildBatchOperation(realTableName,
                            Collections.singletonList(del), false,
                            resultMapSingleOp);
                    BatchOperationResult tmpResults = batch.execute();
                    if (tmpResults.getResults().get(0) instanceof ObTableException) {
                        if (results != null) {
                            results[i] = tmpResults.getResults().get(0);
                        }
                        batchError.add((ObTableException) tmpResults.getResults().get(0), actions.get(i), null);
                    }
                }
                if (results != null && results[i] == null) {
                    results[i] = new Result();
                }
                return null;
            }
        } else {
            byte[] family = delete.getFamilyCellMap().firstKey();
            String realTableName = getTargetTableName(tableNameString, Bytes.toString(family), configuration);
            return buildBatchOperation(realTableName,
                    Collections.singletonList(delete),
                    false, resultMapSingleOp);
        }

    }

    private void compatOldServerBatch(final List<? extends Row> actions, final Object[] results, BatchError batchError)
            throws Exception {
        for (Row row : actions) {
            if (!(row instanceof Put) && !(row instanceof Delete)) {
                throw new FeatureNotSupportedException(
                        "not supported other type in batch yet,only support put and delete");
            }
        }
        List<Row> puts = new LinkedList<>();
        BatchOperation batch;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i) instanceof Put) {
                batch = compatOldServerPut(actions, results, batchError, i, puts);
                if (!puts.isEmpty()) {
                    i = i + puts.size() - 1;
                }
                puts.clear();
            } else {
                batch = compatOldServerDel(actions, results, batchError, i);
            }
            if (batch != null) {
                BatchOperationResult tmpResults = batch.execute();
                if (tmpResults.getResults().get(0) instanceof ObTableException) {
                    if (results != null) {
                        results[i] = tmpResults.getResults().get(0);
                    }
                    batchError.add((ObTableException) tmpResults.getResults().get(0), actions.get(i), null);
                } else {
                    if (results != null) {
                        results[i] = new Result();
                    }
                }
            }
        }
    }

    @Override
    public void batch(final List<? extends Row> actions, final Object[] results) throws IOException {
        if (actions == null) {
            return;
        }
        if (results != null) {
            if (results.length != actions.size()) {
                throw new AssertionError("results.length");
            }
        }
        BatchError batchError = new BatchError();
        obTableClient.setRuntimeBatchExecutor(executePool);
        List<Integer> resultMapSingleOp = new LinkedList<>();
        if (!ObGlobal.isHBaseBatchSupport()) {
            try {
                compatOldServerBatch(actions, results, batchError);
            } catch (Exception e) {
                throw new IOException(tableNameString + " table occurred unexpected error." , e);
            }
        } else {
            String realTableName = getTargetTableName(actions);
            BatchOperation batch = buildBatchOperation(realTableName, actions,
                    tableNameString.equals(realTableName), resultMapSingleOp);
            BatchOperationResult tmpResults;
            try {
                tmpResults = batch.execute();
            } catch (Exception e) {
                throw new IOException(tableNameString + " table occurred unexpected error." , e);
            }
            int index = 0;
            for (int i = 0; i != actions.size(); ++i) {
                if (tmpResults.getResults().get(index) instanceof ObTableException) {
                    if (results != null) {
                        results[i] = tmpResults.getResults().get(index);
                    }
                    batchError.add((ObTableException) tmpResults.getResults().get(index), actions.get(i), null);
                } else if (actions.get(i) instanceof Get) {
                    if (results != null) {
                        // get results have been wrapped in MutationResult, need to fetch it
                        if (tmpResults.getResults().get(index) instanceof MutationResult) {
                            MutationResult mutationResult = (MutationResult) tmpResults.getResults().get(index);
                            ObPayload innerResult = mutationResult.getResult();
                            if (innerResult instanceof ObTableSingleOpResult) {
                                ObTableSingleOpResult singleOpResult = (ObTableSingleOpResult) innerResult;
                                List<Cell> cells = generateGetResult(singleOpResult);
                                results[i] = Result.create(cells);
                            } else {
                                throw new ObTableUnexpectedException("Unexpected type of result in MutationResult");
                            }
                        } else {
                            throw new ObTableUnexpectedException("Unexpected type of result in batch");
                        }
                    }
                } else {
                    if (results != null) {
                        results[i] = new Result();
                    }
                }
                index += resultMapSingleOp.get(i);
            }
        }
        if (batchError.hasErrors()) {
            throw batchError.makeException();
        }
    }

    private List<Cell> generateGetResult(ObTableSingleOpResult getResult) throws IOException {
        List<Cell> cells = new ArrayList<>();
        ObTableSingleOpEntity singleOpEntity = getResult.getEntity();
        // all values queried by this get are contained in properties
        // qualifier in batch get result is always appended after family
        List<ObObj> propertiesValues = singleOpEntity.getPropertiesValues();
        int valueIdx = 0;
        while (valueIdx < propertiesValues.size()) {
            // values in propertiesValues like: [ K, Q, T, V, K, Q, T, V ... ]
            // we need to retrieve K Q T V and construct them to cells: [ cell_0, cell_1, ... ]
            byte[][] familyAndQualifier = new byte[2][];
            try {
                // split family and qualifier
                familyAndQualifier = OHBaseFuncUtils
                        .extractFamilyFromQualifier((byte[]) propertiesValues.get(valueIdx + 1).getValue());
            } catch (Exception e) {
                throw new IOException(e);
            }
            KeyValue kv = new KeyValue((byte[]) propertiesValues.get(valueIdx).getValue(),//K
                    familyAndQualifier[0], // family
                    familyAndQualifier[1], // qualifiermat
                    (Long) propertiesValues.get(valueIdx + 2).getValue(), // T
                    (byte[]) propertiesValues.get(valueIdx + 3).getValue()//  V
            );
            cells.add(kv);
            valueIdx += 4;
        }
        return cells;
    }

    private String getTargetTableName(List<? extends Row> actions) {
        byte[] family = null;
        for (Row action : actions) {
            if (action instanceof RowMutations || action instanceof RegionCoprocessorServiceExec) {
                throw new FeatureNotSupportedException("not supported yet'");
            } else {
                Set<byte[]> familySet;
                if (action instanceof Get) {
                    Get get = (Get) action;
                    familySet = get.familySet();
                } else {
                    Mutation mutation = (Mutation) action;
                    familySet = mutation.getFamilyCellMap().keySet();
                }
                if (familySet == null) {
                    throw new ObTableUnexpectedException("Fail to get family set in action");
                }
                if (familySet.size() != 1) {
                    return getTargetTableName(tableNameString);
                } else {
                    byte[] nextFamily = familySet.iterator().next();
                    if (family != null && !Arrays.equals(family, nextFamily)) {
                        return getTargetTableName(tableNameString);
                    } else if (family == null) {
                        family = nextFamily;
                    }
                }
            }
        }
        return getTargetTableName(tableNameString, Bytes.toString(family), configuration);
    }

    @Override
    public Object[] batch(List<? extends Row> actions) throws IOException {
        Object[] results = new Object[actions.size()];
        batch(actions, results);
        return results;
    }

    @Override
    public <R> void batchCallback(List<? extends Row> actions, Object[] results,
                                  Batch.Callback<R> callback) throws IOException,
                                                             InterruptedException {
        try {
            batch(actions, results);
        } finally {
            if (results != null) {
                for (int i = 0; i < results.length; i++) {
                    if (!(results[i] instanceof ObTableException)) {
                        callback.update(null, actions.get(i).getRow(), (R) results[i]);
                    }
                }
            }
        }
    }

    @Override
    public <R> Object[] batchCallback(final List<? extends Row> actions,
                                      final Batch.Callback<R> callback) throws IOException,
                                                                       InterruptedException {
        Object[] results = new Object[actions.size()];
        batchCallback(actions, results, callback);
        return results;
    }

    public static int compareByteArray(byte[] bt1, byte[] bt2) {
        int minLength = Math.min(bt1.length, bt2.length);
        for (int i = 0; i < minLength; i++) {
            if (bt1[i] != bt2[i]) {
                return bt1[i] - bt2[i];
            }
        }
        return bt1.length - bt2.length;
    }

    private void getMaxRowFromResult(AbstractQueryStreamResult clientQueryStreamResult,
                                     List<KeyValue> keyValueList, boolean isTableGroup,
                                     byte[] family, boolean checkExistenceOnly) throws Exception {
        byte[][] familyAndQualifier = new byte[2][];
        KeyValue kv = null;
        while (clientQueryStreamResult.next()) {
            if (checkExistenceOnly) {
                // Currently, checkExistOnly is set, and if the row exists, it returns an empty row.
                keyValueList.add(new KeyValue());
                return;
            } else {
                List<ObObj> row = clientQueryStreamResult.getRow();
                if (kv == null
                    || compareByteArray(kv.getRow(), (byte[]) row.get(0).getValue()) <= 0) {
                    if (kv != null
                        && compareByteArray(kv.getRow(), (byte[]) row.get(0).getValue()) != 0) {
                        keyValueList.clear();
                    }
                    if (isTableGroup) {
                        // split family and qualifier
                        familyAndQualifier = OHBaseFuncUtils
                            .extractFamilyFromQualifier((byte[]) row.get(1).getValue());
                    } else {
                        familyAndQualifier[0] = family;
                        familyAndQualifier[1] = (byte[]) row.get(1).getValue();
                    }
                    kv = new KeyValue((byte[]) row.get(0).getValue(),//K
                        familyAndQualifier[0], // family
                        familyAndQualifier[1], // qualifiermat
                        (Long) row.get(2).getValue(), // T
                        (byte[]) row.get(3).getValue() // V
                    );
                    keyValueList.add(kv);
                }
            }
        }
    }

    private String getTargetTableName(String tableNameString) {
        if (configuration.getBoolean(HBASE_HTABLE_TEST_LOAD_ENABLE, false)) {
            return tableNameString
                   + configuration.get(HBASE_HTABLE_TEST_LOAD_SUFFIX,
                       DEFAULT_HBASE_HTABLE_TEST_LOAD_SUFFIX);
        }
        return tableNameString;
    }

    // To enable the server to identify the column family to which a qualifier belongs,
    // the client writes the column family name into the qualifier.
    // The server then parses this information to determine the table that needs to be operated on.
    private void processColumnFilters(NavigableSet<byte[]> columnFilters,
                                      Map<byte[], NavigableSet<byte[]>> familyMap) {
        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : familyMap.entrySet()) {
            if (entry.getValue() != null) {
                for (byte[] columnName : entry.getValue()) {
                    byte[] family = entry.getKey();
                    byte[] newQualifier = new byte[family.length + 1/* length of "." */ + columnName.length];
                    System.arraycopy(family, 0, newQualifier, 0, family.length);
                    newQualifier[family.length] = 0x2E; // 0x2E in utf-8 is "."
                    System.arraycopy(columnName, 0, newQualifier, family.length + 1, columnName.length);
                    columnFilters.add(newQualifier);
                }
            } else {
                byte[] family = entry.getKey();
                byte[] newQualifier = new byte[family.length + 1/* length of "."*/];
                System.arraycopy(family, 0, newQualifier, 0, family.length);
                newQualifier[family.length] = 0x2E; // 0x2E in utf-8 is "."
                columnFilters.add(newQualifier);
            }
        }
    }

    @Override
    public Result get(final Get get) throws IOException {
        if (get.getFamilyMap().keySet().isEmpty()) {
            if (!FeatureSupport.isEmptyFamilySupported()) {
                throw new FeatureNotSupportedException("empty family get not supported yet within observer version: " + ObGlobal.obVsnString());
            }
            // check nothing, use table group;
        } else {
            checkFamilyViolation(get.getFamilyMap().keySet(), false);
        }

        ServerCallable<Result> serverCallable = new ServerCallable<Result>(configuration,
            obTableClient, tableNameString, get.getRow(), get.getRow(), operationTimeout) {
            public Result call() throws IOException {
                List<KeyValue> keyValueList = new ArrayList<>();
                byte[] family = new byte[] {};
                ObTableQuery obTableQuery;
                try {
                    if (get.getFamilyMap().keySet().isEmpty()
                            || get.getFamilyMap().size() > 1) {
                        // In a Get operation where the family map is greater than 1 or equal to 0,
                        // we handle this by appending the column family to the qualifier on the client side.
                        // The server can then use this information to filter the appropriate column families and qualifiers.
                        if (!get.getColumnFamilyTimeRange().isEmpty()) {
                            throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                        }
                        NavigableSet<byte[]> columnFilters = new TreeSet<>(Bytes.BYTES_COMPARATOR);
                        processColumnFilters(columnFilters, get.getFamilyMap());
                        obTableQuery = buildObTableQuery(get, columnFilters);
                        ObTableQueryAsyncRequest request = buildObTableQueryAsyncRequest(obTableQuery,
                            getTargetTableName(tableNameString));

                        ObTableClientQueryAsyncStreamResult clientQueryStreamResult = (ObTableClientQueryAsyncStreamResult) obTableClient
                            .execute(request);
                        getMaxRowFromResult(clientQueryStreamResult, keyValueList, true, family, get.isCheckExistenceOnly());
                    } else {
                        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : get.getFamilyMap()
                            .entrySet()) {
                            family = entry.getKey();
                            if (!get.getColumnFamilyTimeRange().isEmpty()) {
                                Map<byte[], TimeRange> colFamTimeRangeMap = get.getColumnFamilyTimeRange();
                                if (colFamTimeRangeMap.size() > 1) {
                                    throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                                } else if (colFamTimeRangeMap.get(family) == null) {
                                    throw new IllegalArgumentException("Get family is not matched in ColumnFamilyTimeRange");
                                } else {
                                    TimeRange tr = colFamTimeRangeMap.get(family);
                                    get.setTimeRange(tr.getMin(), tr.getMax());
                                }
                            }
                            obTableQuery = buildObTableQuery(get, entry.getValue());
                            ObTableQueryRequest request = buildObTableQueryRequest(obTableQuery,
                                getTargetTableName(tableNameString, Bytes.toString(family),
                                    configuration));
                            ObTableClientQueryStreamResult clientQueryStreamResult = (ObTableClientQueryStreamResult) obTableClient
                                .execute(request);
                            getMaxRowFromResult(clientQueryStreamResult, keyValueList, false,
                                family, get.isCheckExistenceOnly());
                        }
                    }
                } catch (Exception e) {
                    logger.error(LCD.convert("01-00002"), tableNameString, Bytes.toString(family),
                        e);
                    throw new IOException("query table:" + tableNameString + " family "
                                          + Bytes.toString(family) + " error.", e);
                }
                if (get.isCheckExistenceOnly()) {
                    return Result.create(null, !keyValueList.isEmpty());
                }
                return new Result(keyValueList);
            }
        };
        return executeServerCallable(serverCallable);
    }

    @Override
    public Result[] get(List<Get> gets) throws IOException {
        Result[] results = new Result[gets.size()];
        if (ObGlobal.isHBaseBatchGetSupport()) { // get only supported in BatchSupport version
            batch(gets, results);
        } else {
            List<Future<Result>> futures = new LinkedList<>();
            for (int i = 0; i < gets.size(); i++) {
                int index = i;
                Future<Result> future = executePool.submit(() -> get(gets.get(index)));
                futures.add(future);
            }
            for (int i = 0; i < gets.size(); i++) {
                try {
                    results[i] = futures.get(i).get();
                } catch (Exception e) {
                    throw new RuntimeException("gets occur error. index:{" + i + "}", e);
                }
            }
        }
        return results;
    }

    /**
     * hbase的获取这个rowkey，没有的话就前一个rowkey，不支持
     * @param row row
     * @param family family
     * @return Result
     */
    @Override
    public Result getRowOrBefore(byte[] row, byte[] family) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    @Override
    public ResultScanner getScanner(final Scan scan) throws IOException {
        if (scan.getFamilyMap().keySet().isEmpty()) {
            if (!FeatureSupport.isEmptyFamilySupported()) {
                throw new FeatureNotSupportedException("empty family scan not supported yet within observer version: " + ObGlobal.obVsnString());
            }
            // check nothing, use table group;
        } else {
            checkFamilyViolation(scan.getFamilyMap().keySet(), false);
        }

        //be careful about the packet size ,may the packet exceed the max result size ,leading to error
        ServerCallable<ResultScanner> serverCallable = new ServerCallable<ResultScanner>(
            configuration, obTableClient, tableNameString, scan.getStartRow(), scan.getStopRow(),
            operationTimeout) {
            public ResultScanner call() throws IOException {
                byte[] family = new byte[] {};
                ObTableClientQueryAsyncStreamResult clientQueryAsyncStreamResult;
                ObTableQueryAsyncRequest request;
                ObTableQuery obTableQuery;
                ObHTableFilter filter;
                try {
                    if (scan.getFamilyMap().keySet().isEmpty()
                        || scan.getFamilyMap().size() > 1) {
                        // In a Scan operation where the family map is greater than 1 or equal to 0,
                        // we handle this by appending the column family to the qualifier on the client side.
                        // The server can then use this information to filter the appropriate column families and qualifiers.
                        if (!scan.getColumnFamilyTimeRange().isEmpty()) {
                            throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                        }
                        NavigableSet<byte[]> columnFilters = new TreeSet<>(Bytes.BYTES_COMPARATOR);
                        processColumnFilters(columnFilters, scan.getFamilyMap());
                        filter = buildObHTableFilter(scan.getFilter(), scan.getTimeRange(),
                            scan.getMaxVersions(), columnFilters);
                        obTableQuery = buildObTableQuery(filter, scan);

                        request = buildObTableQueryAsyncRequest(obTableQuery,
                            getTargetTableName(tableNameString));
                        clientQueryAsyncStreamResult = (ObTableClientQueryAsyncStreamResult) obTableClient
                            .execute(request);
                        return new ClientStreamScanner(clientQueryAsyncStreamResult,
                            tableNameString, family, true);
                    } else {
                        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : scan.getFamilyMap()
                            .entrySet()) {
                            family = entry.getKey();
                            if (!scan.getColumnFamilyTimeRange().isEmpty()) {
                                Map<byte[], TimeRange> colFamTimeRangeMap = scan.getColumnFamilyTimeRange();
                                if (colFamTimeRangeMap.size() > 1) {
                                    throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                                } else if (colFamTimeRangeMap.get(family) == null) {
                                    throw new IllegalArgumentException("Scan family is not matched in ColumnFamilyTimeRange");
                                } else {
                                    TimeRange tr = colFamTimeRangeMap.get(family);
                                    scan.setTimeRange(tr.getMin(), tr.getMax());
                                }
                            }
                            filter = buildObHTableFilter(scan.getFilter(), scan.getTimeRange(),
                                scan.getMaxVersions(), entry.getValue());
                            obTableQuery = buildObTableQuery(filter, scan);

                            request = buildObTableQueryAsyncRequest(
                                obTableQuery,
                                getTargetTableName(tableNameString, Bytes.toString(family),
                                    configuration));
                            clientQueryAsyncStreamResult = (ObTableClientQueryAsyncStreamResult) obTableClient
                                .execute(request);
                            return new ClientStreamScanner(clientQueryAsyncStreamResult,
                                tableNameString, family, false);
                        }
                    }
                } catch (Exception e) {
                    logger.error(LCD.convert("01-00003"), tableNameString, Bytes.toString(family),
                        e);
                    throw new IOException("scan table:" + tableNameString + " family "
                                          + Bytes.toString(family) + " error.", e);
                }

                throw new IOException("scan table:" + tableNameString + "has no family");
            }
        };
        return executeServerCallable(serverCallable);
    }

    public List<ResultScanner> getScanners(final Scan scan) throws IOException {

        if (scan.getFamilyMap().keySet().isEmpty()) {
            // check nothing, use table group;
        } else {
            checkFamilyViolation(scan.getFamilyMap().keySet(), false);
        }

        //be careful about the packet size ,may the packet exceed the max result size ,leading to error
        ServerCallable<List<ResultScanner>> serverCallable = new ServerCallable<List<ResultScanner>>(
            configuration, obTableClient, tableNameString, scan.getStartRow(), scan.getStopRow(),
            operationTimeout) {
            public List<ResultScanner> call() throws IOException {
                byte[] family = new byte[] {};
                ObTableClientQueryAsyncStreamResult clientQueryAsyncStreamResult;
                ObTableQueryAsyncRequest request;
                ObTableQuery obTableQuery;
                ObHTableFilter filter;
                try {
                    if (scan.getFamilyMap().keySet().isEmpty()
                            || scan.getFamilyMap().size() > 1) {
                        // In a Scan operation where the family map is greater than 1 or equal to 0,
                        // we handle this by appending the column family to the qualifier on the client side.
                        // The server can then use this information to filter the appropriate column families and qualifiers.
                        NavigableSet<byte[]> columnFilters = new TreeSet<>(Bytes.BYTES_COMPARATOR);
                        processColumnFilters(columnFilters, scan.getFamilyMap());
                        filter = buildObHTableFilter(scan.getFilter(), scan.getTimeRange(),
                                scan.getMaxVersions(), columnFilters);
                        obTableQuery = buildObTableQuery(filter, scan);
                        List<ResultScanner> resultScanners = new ArrayList<ResultScanner>();

                        request = buildObTableQueryAsyncRequest(obTableQuery,
                            getTargetTableName(tableNameString));
                        request.setAllowDistributeScan(false);
                        String phyTableName = obTableClient.getPhyTableNameFromTableGroup(
                            request.getObTableQueryRequest(), tableNameString);
                        List<Partition> partitions = obTableClient.getPartition(phyTableName, false);
                        for (Partition partition : partitions) {
                            request.getObTableQueryRequest().setTableQueryPartId(
                                partition.getPartId());
                            clientQueryAsyncStreamResult = (ObTableClientQueryAsyncStreamResult) obTableClient
                                .execute(request);
                            ClientStreamScanner clientScanner = new ClientStreamScanner(
                                clientQueryAsyncStreamResult, tableNameString, family, true);
                            resultScanners.add(clientScanner);
                        }
                        return resultScanners;
                    } else {
                        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : scan.getFamilyMap()
                            .entrySet()) {
                            family = entry.getKey();
                            filter = buildObHTableFilter(scan.getFilter(), scan.getTimeRange(),
                                    scan.getMaxVersions(), entry.getValue());
                            obTableQuery = buildObTableQuery(filter, scan);

                            List<ResultScanner> resultScanners = new ArrayList<ResultScanner>();
                            String targetTableName = getTargetTableName(tableNameString, Bytes.toString(family),
                                    configuration);
                            request = buildObTableQueryAsyncRequest(obTableQuery, targetTableName);
                            request.setAllowDistributeScan(false);
                            List<Partition> partitions = obTableClient
                                .getPartition(targetTableName, false);
                            for (Partition partition : partitions) {
                                request.getObTableQueryRequest().setTableQueryPartId(
                                    partition.getPartId());
                                clientQueryAsyncStreamResult = (ObTableClientQueryAsyncStreamResult) obTableClient
                                    .execute(request);
                                ClientStreamScanner clientScanner = new ClientStreamScanner(
                                    clientQueryAsyncStreamResult, tableNameString, family, false);
                                resultScanners.add(clientScanner);
                            }
                            return resultScanners;
                        }
                    }
                } catch (Exception e) {
                    logger.error(LCD.convert("01-00003"), tableNameString, Bytes.toString(family),
                        e);
                    throw new IOException("scan table:" + tableNameString + " family "
                                          + Bytes.toString(family) + " error.", e);
                }

                throw new IOException("scan table:" + tableNameString + "has no family");
            }
        };
        return executeServerCallable(serverCallable);
    }

    @Override
    public ResultScanner getScanner(final byte[] family) throws IOException {
        Scan scan = new Scan();
        scan.addFamily(family);
        return getScanner(scan);
    }

    @Override
    public ResultScanner getScanner(byte[] family, byte[] qualifier) throws IOException {
        Scan scan = new Scan();
        scan.addColumn(family, qualifier);
        return getScanner(scan);
    }

    @Override
    public void put(Put put) throws IOException {
        getBufferedMutator().mutate(put);
        if (autoFlush) {
            flushCommits();
        }
    }

    @Override
    public void put(List<Put> puts) throws IOException {
        getBufferedMutator().mutate(puts);
        if (autoFlush) {
            flushCommits();
        }
    }

    /**
     * 校验 put 里的参数是否合法，需要传入 family ，并且 keyvalue 的 size 不能太大
     * @param put the put
     */
    public static void validatePut(Put put, int maxKeyValueSize) {
        if (put.isEmpty()) {
            throw new IllegalArgumentException("No columns to insert");
        }
        if (maxKeyValueSize > 0) {
            for (Map.Entry<byte[], List<KeyValue>> entry : put.getFamilyMap().entrySet()) {
                if (entry.getKey() == null || entry.getKey().length == 0) {
                    throw new IllegalArgumentException("family is empty");
                }
                for (KeyValue kv : entry.getValue()) {
                    if (kv.getLength() > maxKeyValueSize) {
                        throw new IllegalArgumentException("KeyValue size too large");
                    }
                }
            }
        }
    }

    private ObKVParams buildOBKVParams(final Scan scan) {
        ObKVParams obKVParams = new ObKVParams();
        ObHBaseParams obHBaseParams = new ObHBaseParams();
        if (scan != null) {
            obHBaseParams.setCaching(scan.getCaching());
            obHBaseParams.setCallTimeout(scannerTimeout);
            obHBaseParams.setCacheBlock(scan.isGetScan());
            obHBaseParams.setAllowPartialResults(scan.getAllowPartialResults());
            obHBaseParams.setHbaseVersion(VersionInfo.getVersion());
        }
        obKVParams.setObParamsBase(obHBaseParams);
        return obKVParams;
    }

    private ObKVParams buildOBKVParams(final Get get) {
        ObKVParams obKVParams = new ObKVParams();
        ObHBaseParams obHBaseParams = new ObHBaseParams();
        obHBaseParams.setCheckExistenceOnly(get.isCheckExistenceOnly());
        obHBaseParams.setCacheBlock(get.getCacheBlocks());
        obHBaseParams.setHbaseVersion(VersionInfo.getVersion());
        obKVParams.setObParamsBase(obHBaseParams);
        return obKVParams;
    }

    /**
     * 例如当 key="key001", family = "family", c1="a" 时，才执行 put 操作，该命令是原子的
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param value value
     * @param put put
     * @return boolean
     * @throws IOException if failed
     */
    @Override
    public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier, byte[] value, Put put)
                                                                                                  throws IOException {
        return checkAndPut(row, family, qualifier, CompareFilter.CompareOp.EQUAL, value, put);
    }

    @Override
    public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier,
                               CompareFilter.CompareOp compareOp, byte[] value, Put put)
                                                                                        throws IOException {
        RowMutations rowMutations = new RowMutations(row);
        rowMutations.add(put);
        try {
            return checkAndMutation(row, family, qualifier, compareOp, value, rowMutations);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00005"), put, tableNameString, e);
            throw new IOException("checkAndPut type table:" + tableNameString + " e.msg:"
                                  + e.getMessage() + " error.", e);
        }
    }

    private void innerDelete(Delete delete) throws IOException {
        checkArgument(delete.getRow() != null, "row is null");
        try {
            batch(Collections.singletonList(delete));
        } catch (Exception e) {
            logger.error(LCD.convert("01-00004"), tableNameString, e);
            throw e;
        }
    }

    @Override
    public void delete(Delete delete) throws IOException {
        checkFamilyViolation(delete.getFamilyMap().keySet(), false);
        innerDelete(delete);
    }

    @Override
    public void delete(List<Delete> deletes) throws IOException {
        for (Delete delete : deletes) {
            innerDelete(delete);
        }
    }

    /**
     * 例如当 key="key001", family = "family", c1="a" 时，才执行 delete 操作，该命令是原子的
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param value value
     * @param delete delete
     * @return boolean
     * @throws IOException if failed
     */
    @Override
    public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier, byte[] value,
                                  Delete delete) throws IOException {
        return checkAndDelete(row, family, qualifier, CompareFilter.CompareOp.EQUAL, value, delete);
    }

    @Override
    public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier,
                                  CompareFilter.CompareOp compareOp, byte[] value, Delete delete)
                                                                                                 throws IOException {
        RowMutations rowMutations = new RowMutations(row);
        rowMutations.add(delete);
        try {
            return checkAndMutation(row, family, qualifier, compareOp, value, rowMutations);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00005"), delete, tableNameString, e);
            throw new IOException("checkAndDelete type table:" + tableNameString + " e.msg:"
                                  + e.getMessage() + " error.", e);
        }
    }

    @Override
    public boolean checkAndMutate(byte[] row, byte[] family, byte[] qualifier,
                                  CompareFilter.CompareOp compareOp, byte[] value,
                                  RowMutations rowMutations) throws IOException {
        try {
            return checkAndMutation(row, family, qualifier, compareOp, value, rowMutations);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00005"), rowMutations, tableNameString, e);
            throw new IOException("checkAndMutate type table:" + tableNameString + " e.msg:"
                                  + e.getMessage() + " error.", e);
        }
    }

    private boolean checkAndMutation(byte[] row, byte[] family, byte[] qualifier,
                                     CompareFilter.CompareOp compareOp, byte[] value,
                                     RowMutations rowMutations) throws Exception {
        checkArgument(row != null, "row is null");
        checkArgument(isNotBlank(Bytes.toString(family)), "family is blank");
        checkArgument(!rowMutations.getMutations().isEmpty(), "mutation is empty");
        List<Mutation> mutations = rowMutations.getMutations();
        // only one family operation is allowed
        for (Mutation mutation : mutations) {
            if (!(mutation instanceof Put || mutation instanceof Delete)) {
                throw new DoNotRetryIOException("RowMutations supports only put and delete, not "
                                                + mutation.getClass().getName());
            }
            checkFamilyViolationForOneFamily(mutation.getFamilyCellMap().keySet());
            checkArgument(Arrays.equals(family, mutation.getFamilyCellMap().firstEntry().getKey()),
                "mutation family is not equal check family");
        }
        byte[] filterString = buildCheckAndMutateFilterString(family, qualifier, compareOp, value);
        ObHTableFilter filter = buildObHTableFilter(filterString, null, 1, qualifier);
        ObTableQuery obTableQuery = buildObTableQuery(filter, row, true, row, true, false,
            new TimeRange());
        ObTableBatchOperation batch = buildObTableBatchOperation(mutations, null);

        ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
            batch, getTargetTableName(tableNameString, Bytes.toString(family), configuration));
        ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
            .execute(request);
        return result.getAffectedRows() > 0;
    }

    @Override
    public void mutateRow(RowMutations rm) {
        throw new FeatureNotSupportedException("not supported yet.");
    }

    /**
     * 例如 key = "key", c1="a"，在c1后面append，使c1="aaa"
     * 原子操作
     * @param append append
     * @return Result
     * @throws IOException if failed
     */
    @Override
    public Result append(Append append) throws IOException {
        checkArgument(!append.isEmpty(), "Invalid arguments to %s, zero columns specified",
            append.toString());
        checkFamilyViolationForOneFamily(append.getFamilyCellMap().keySet());
        try {
            byte[] r = append.getRow();
            Map.Entry<byte[], List<KeyValue>> entry = append.getFamilyMap().entrySet().iterator()
                .next();
            byte[] f = entry.getKey();
            List<byte[]> qualifiers = new ArrayList<byte[]>();
            ObTableBatchOperation batchOperation = buildObTableBatchOperation(
                Collections.singletonList(append), qualifiers);
            // the later hbase has supported timeRange
            ObHTableFilter filter = buildObHTableFilter(null, null, 1, qualifiers);
            ObTableQuery obTableQuery = buildObTableQuery(filter, r, true, r, true, false,
                new TimeRange());
            ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
            queryAndMutate.setTableQuery(obTableQuery);
            queryAndMutate.setMutations(batchOperation);
            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batchOperation,
                getTargetTableName(tableNameString, Bytes.toString(f), configuration));
            request.setReturningAffectedEntity(append.isReturnResults());
            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            if (!append.isReturnResults()) {
                return null;
            }
            ObTableQueryResult queryResult = result.getAffectedEntity();
            List<KeyValue> keyValues = new ArrayList<KeyValue>();
            for (List<ObObj> row : queryResult.getPropertiesRows()) {
                byte[] k = (byte[]) row.get(0).getValue();
                byte[] q = (byte[]) row.get(1).getValue();
                long t = (Long) row.get(2).getValue();
                byte[] v = (byte[]) row.get(3).getValue();
                KeyValue kv = new KeyValue(k, f, q, t, v);

                keyValues.add(kv);
            }
            return new Result(keyValues);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00006"), tableNameString, e);
            throw new IOException("append table " + tableNameString + " error.", e);
        }
    }

    /**
     * 例如 key = "key", c2=1，在c2后面increment，在c2后面加2，变成 c2=3
     * 原子操作
     * @param increment increment
     * @return Result
     * @throws IOException if failed
     */
    @Override
    public Result increment(Increment increment) throws IOException {
        checkArgument(!increment.isEmpty(), "Invalid arguments to %s, zero columns specified", increment.toString());
        checkFamilyViolationForOneFamily(increment.getFamilyCellMap().keySet());

        try {
            byte[] rowKey = increment.getRow();
            Map.Entry<byte[], List<Cell>> entry = increment.getFamilyCellMap().entrySet()
                .iterator().next();

            byte[] f = entry.getKey();
            List<byte[]> qualifiers = new ArrayList<>();
            ObTableBatchOperation batch = buildObTableBatchOperation(Collections.singletonList(increment), qualifiers);

            ObHTableFilter filter = buildObHTableFilter(null, increment.getTimeRange(), 1,
                qualifiers);

            ObTableQuery obTableQuery = buildObTableQuery(filter, rowKey, true, rowKey, true, false, increment.getTimeRange());

            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batch, getTargetTableName(tableNameString, Bytes.toString(f), configuration));
            request.setReturningAffectedEntity(increment.isReturnResults());
            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            if (!increment.isReturnResults()) {
                return null;
            }
            ObTableQueryResult queryResult = result.getAffectedEntity();
            List<KeyValue> keyValues = new ArrayList<KeyValue>();
            for (List<ObObj> row : queryResult.getPropertiesRows()) {
                byte[] k = (byte[]) row.get(0).getValue();
                byte[] q = (byte[]) row.get(1).getValue();
                long t = (Long) row.get(2).getValue();
                byte[] v = (byte[]) row.get(3).getValue();
                KeyValue kv = new KeyValue(k, f, q, t, v);
                keyValues.add(kv);
            }
            return new Result(keyValues);
        } catch (Exception e) {
            logger.error(LCD.convert("01-00007"), tableNameString, e);
            throw new IOException("increment table " + tableNameString + " error.", e);
        }
    }

    /**
     * 直接通过 column 名进行 increment 操作
     * @param row row
     * @param family family
     * @param qualifier qualifier
     * @param amount amount
     * @return long
     * @throws IOException if failed
     */
    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                                                                                              throws IOException {
        try {
            List<byte[]> qualifiers = new ArrayList<byte[]>();
            qualifiers.add(qualifier);

            ObTableBatchOperation batch = new ObTableBatchOperation();
            batch.addTableOperation(getInstance(INCREMENT, new Object[] { row, qualifier,
                    Long.MAX_VALUE }, V_COLUMNS, new Object[] { Bytes.toBytes(amount) }));

            ObHTableFilter filter = buildObHTableFilter(null, null, 1, qualifiers);

            ObTableQuery obTableQuery = buildObTableQuery(filter, row, true, row, true, false,
                new TimeRange());
            ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
            queryAndMutate.setMutations(batch);
            queryAndMutate.setTableQuery(obTableQuery);

            ObTableQueryAndMutateRequest request = buildObTableQueryAndMutateRequest(obTableQuery,
                batch, getTargetTableName(tableNameString, Bytes.toString(family), configuration));
            request.setReturningAffectedEntity(true);
            ObTableQueryAndMutateResult result = (ObTableQueryAndMutateResult) obTableClient
                .execute(request);
            ObTableQueryResult queryResult = result.getAffectedEntity();
            if (queryResult.getPropertiesRows().size() != 1) {
                throw new IllegalStateException("the increment result size illegal "
                                                + queryResult.getPropertiesRows().size());
            }
            return Bytes.toLong((byte[]) queryResult.getPropertiesRows().get(0).get(3).getValue());
        } catch (Exception e) {
            logger.error(LCD.convert("01-00007"), tableNameString, e);
            throw new IOException("increment table " + tableNameString + " error.", e);
        }
    }

    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount,
                                     Durability durability) throws IOException {
        return incrementColumnValue(row, family, qualifier, amount);
    }

    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount,
                                     boolean writeToWAL) throws IOException {
        // WAL ignored
        return incrementColumnValue(row, family, qualifier, amount);
    }

    @Override
    public boolean isAutoFlush() {
        return autoFlush;
    }

    @Override
    public void flushCommits() throws IOException {
        if (mutator == null) {
            return;
        }
        mutator.flush();
    }

    @Override
    public void close() throws IOException {
        if (isClosed) {
            return;
        }
        flushCommits();
        if (cleanupPoolOnClose) {
            executePool.shutdown();
        }
        this.isClosed = true;
    }

    @Override
    public CoprocessorRpcChannel coprocessorService(byte[] row) {
        throw new FeatureNotSupportedException("not supported yet'");
    }

    @Override
    public <T extends Service, R> Map<byte[], R> coprocessorService(Class<T> service,
                                                                    byte[] startKey, byte[] endKey,
                                                                    Batch.Call<T, R> callable)
                                                                                              throws ServiceException,
                                                                                              Throwable {
        throw new FeatureNotSupportedException("not supported yet'");
    }

    @Override
    public <T extends Service, R> void coprocessorService(Class<T> service, byte[] startKey,
                                                          byte[] endKey, Batch.Call<T, R> callable,
                                                          Batch.Callback<R> callback)
                                                                                     throws ServiceException,
                                                                                     Throwable {
        throw new FeatureNotSupportedException("not supported yet'");
    }

    /**
     * See {@link #setAutoFlush(boolean, boolean)}
     *
     * @param autoFlush Whether or not to enable 'auto-flush'.
     */
    @Override
    public void setAutoFlush(boolean autoFlush) {
        setAutoFlush(autoFlush, autoFlush);
    }

    /**
     * Turns 'auto-flush' on or off.
     * <p>
     * When enabled (default), {@link Put} operations don't get buffered/delayed
     * and are immediately executed. Failed operations will be retried in batch. This is
     * slower but safer.
     * <p>
     * Turning off {@link #autoFlush} means that multiple {@link Put}s will be
     * accepted before any RPC is actually sent to do the write operations. If the
     * application dies before pending writes get flushed to HBase, data will be
     * lost.
     * <p>
     * By default, asynchronous {@link Put}
     * requests will be retried on failure until successful. However, this can
     * pollute the writeBuffer and slow down batching performance. Additionally,
     * you may want to issue a number of Put requests and call
     * {@link #flushCommits()} as a barrier.
     *
     * @param autoFlush         Whether or not to enable 'auto-flush'.
     * @param clearBufferOnFail Whether to keep Put failures in the writeBuffer (UNUSED for this version)
     * @see #flushCommits
     */
    @Override
    public void setAutoFlush(boolean autoFlush, boolean clearBufferOnFail) {
        this.autoFlush = autoFlush;
    }

    @Override
    public void setAutoFlushTo(boolean autoFlush) {
        this.autoFlush = autoFlush;
    }

    /**
     * Returns the maximum size in bytes of the write buffer for this HTable.
     * <p>
     * The default value comes from the configuration parameter
     * {@code hbase.client.write.buffer}.
     *
     * @return The size of the write buffer in bytes.
     */
    @Override
    public long getWriteBufferSize() {
        if (mutator == null) {
            return writeBufferSize;
        } else {
            return mutator.getWriteBufferSize();
        }
    }

    /**
     * Sets the size of the buffer in bytes.
     * <p>
     * If the new size is less than the current amount of data in the
     * write buffer, the buffer gets flushed.
     *
     * @param writeBufferSize The new write buffer size, in bytes.
     * @throws IOException if a remote or network exception occurs.
     */
    @Override
    public void setWriteBufferSize(long writeBufferSize) throws IOException {
        this.writeBufferSize = writeBufferSize;
        if (this.mutator == null) {
            getBufferedMutator();
        }
        this.mutator.setWriteBufferSize(writeBufferSize);
    }

    @Override
    public <R extends Message> Map<byte[], R> batchCoprocessorService(Descriptors.MethodDescriptor methodDescriptor,
                                                                      Message request,
                                                                      byte[] startKey,
                                                                      byte[] endKey,
                                                                      R responsePrototype)
                                                                                          throws ServiceException,
                                                                                          Throwable {
        throw new FeatureNotSupportedException("not supported yet'");
    }

    @Override
    public <R extends Message> void batchCoprocessorService(Descriptors.MethodDescriptor methodDescriptor,
                                                            Message request, byte[] startKey,
                                                            byte[] endKey, R responsePrototype,
                                                            Batch.Callback<R> callback)
                                                                                       throws ServiceException,
                                                                                       Throwable {
        throw new FeatureNotSupportedException("not supported yet'");
    }

    @Override
    public void setOperationTimeout(int operationTimeout) {
        this.operationTimeout = operationTimeout;
        this.obTableClient.setRuntimeMaxWait(operationTimeout);
        this.obTableClient.setRuntimeBatchMaxWait(operationTimeout);
        this.operationExecuteInPool = this.configuration.getBoolean(
            HBASE_CLIENT_OPERATION_EXECUTE_IN_POOL,
            (this.operationTimeout != HConstants.DEFAULT_HBASE_CLIENT_OPERATION_TIMEOUT));
    }

    @Override
    public int getOperationTimeout() {
        return operationTimeout;
    }

    //todo
    @Override
    public void setRpcTimeout(int rpcTimeout) {
        this.rpcTimeout = rpcTimeout;
        obTableClient.setRpcExecuteTimeout(rpcTimeout);
    }

    // todo
    @Override
    public int getRpcTimeout() {
        return this.rpcTimeout;
    }

    public void setRuntimeBatchExecutor(ExecutorService runtimeBatchExecutor) {
        this.obTableClient.setRuntimeBatchExecutor(runtimeBatchExecutor);
    }

    <T> T executeServerCallable(final ServerCallable<T> serverCallable) throws IOException {

        if (!this.operationExecuteInPool) {
            return serverCallable.withRetries();
        }

        long startTime = System.currentTimeMillis();
        Future<T> future = this.executePool.submit(serverCallable);
        try {
            return future.get(this.operationTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw new IOException("Interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() != null && e.getCause() instanceof IOException) {
                IOException ie = ((IOException) e.getCause());
                // In order to show the stack of this call, add a exception to the
                // root cause
                Throwable rootCause = ie;
                while (rootCause.getCause() != null) {
                    rootCause = rootCause.getCause();
                }
                throw ie;
            }
            throw new IOException(e);
        } catch (TimeoutException e) {
            future.cancel(true);
            String hostPort = "unknown";
            String regionEncodedName = "unknown";
            throw new OperationTimeoutException("Failed executing operation for table '"
                                                + Bytes.toString(this.tableName) + "' on server "
                                                + hostPort + ",region=" + regionEncodedName
                                                + ",operationTimeout=" + this.operationTimeout
                                                + ",waitTime="
                                                + (System.currentTimeMillis() - startTime));
        }
    }

    public static String getTargetTableName(String tableNameString, String familyString,
                                            Configuration conf) {
        checkArgument(tableNameString != null, "tableNameString is null");
        checkArgument(familyString != null, "familyString is null");
        if (conf.getBoolean(HBASE_HTABLE_TEST_LOAD_ENABLE, false)) {
            return getTestLoadTargetTableName(tableNameString, familyString, conf);
        }
        return getNormalTargetTableName(tableNameString, familyString);
    }

    private static String getNormalTargetTableName(String tableNameString, String familyString) {
        return tableNameString + "$" + familyString;
    }

    private static String getTestLoadTargetTableName(String tableNameString, String familyString,
                                                     Configuration conf) {
        String suffix = conf.get(HBASE_HTABLE_TEST_LOAD_SUFFIX,
            DEFAULT_HBASE_HTABLE_TEST_LOAD_SUFFIX);
        return tableNameString + suffix + "$" + familyString;
    }

    private ObHTableFilter buildObHTableFilter(Filter filter, TimeRange timeRange, int maxVersion,
                                               Collection<byte[]> columnQualifiers)
                                                                                   throws IOException {
        ObHTableFilter obHTableFilter = new ObHTableFilter();

        if (filter != null) {
            obHTableFilter.setFilterString(HBaseFilterUtils.toParseableByteArray(filter));
        }

        if (timeRange != null) {
            obHTableFilter.setMaxStamp(timeRange.getMax());
            obHTableFilter.setMinStamp(timeRange.getMin());
        }

        obHTableFilter.setMaxVersions(maxVersion);

        if (columnQualifiers != null) {
            for (byte[] columnQualifier : columnQualifiers) {
                if (columnQualifier == null) {
                    obHTableFilter.addSelectColumnQualifier(new byte[0]);
                } else {
                    obHTableFilter.addSelectColumnQualifier(columnQualifier);
                }
            }
        }
        return obHTableFilter;
    }

    private byte[] buildCheckAndMutateFilterString(byte[] family, byte[] qualifier,
                                                   CompareFilter.CompareOp compareOp, byte[] value)
                                                                                                   throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        byteStream.write("CheckAndMutateFilter(".getBytes());
        byteStream.write(HBaseFilterUtils.toParseableByteArray(compareOp));
        byteStream.write(", 'binary:".getBytes());
        writeBytesWithEscape(byteStream, value);
        byteStream.write("', '".getBytes());
        writeBytesWithEscape(byteStream, family);
        byteStream.write("', '".getBytes());
        writeBytesWithEscape(byteStream, qualifier);
        if (value != null) {
            byteStream.write("', false)".getBytes());
        } else {
            byteStream.write("', true)".getBytes());
        }
        return byteStream.toByteArray();
    }

    private ObHTableFilter buildObHTableFilter(byte[] filterString, TimeRange timeRange,
                                               int maxVersion, byte[]... columnQualifiers) {
        ObHTableFilter obHTableFilter = new ObHTableFilter();

        if (filterString != null) {
            obHTableFilter.setFilterString(filterString);
        }

        if (timeRange != null) {
            obHTableFilter.setMaxStamp(timeRange.getMax());
            obHTableFilter.setMinStamp(timeRange.getMin());
        }

        obHTableFilter.setMaxVersions(maxVersion);

        if (columnQualifiers != null) {
            for (byte[] columnQualifier : columnQualifiers) {
                if (columnQualifier == null) {
                    obHTableFilter.addSelectColumnQualifier(new byte[0]);
                } else {
                    obHTableFilter.addSelectColumnQualifier(columnQualifier);
                }
            }
        }
        return obHTableFilter;
    }

    private ObTableQuery buildObTableQuery(ObHTableFilter filter, byte[] start,
                                           boolean includeStart, byte[] stop, boolean includeStop,
                                           boolean isReversed, TimeRange ts) {
        ObNewRange obNewRange = new ObNewRange();
        ObBorderFlag obBorderFlag = new ObBorderFlag();
        Object left_ts = ObObj.getMin();
        Object right_ts = ObObj.getMax();
        if (!ts.isAllTime()) {
            left_ts = -ts.getMax();
            right_ts = -ts.getMin();
        }
        if (Arrays.equals(start, HConstants.EMPTY_BYTE_ARRAY)) {
            obNewRange.setStartKey(ObRowKey.getInstance(ObObj.getMin(), ObObj.getMin(), left_ts));
        } else if (includeStart) {
            obNewRange.setStartKey(ObRowKey.getInstance(start, ObObj.getMin(), left_ts));
            obBorderFlag.setInclusiveStart();
        } else {
            obNewRange.setStartKey(ObRowKey.getInstance(start, ObObj.getMax(), left_ts));
            obBorderFlag.unsetInclusiveStart();
        }

        if (Arrays.equals(stop, HConstants.EMPTY_BYTE_ARRAY)) {
            obNewRange.setEndKey(ObRowKey.getInstance(ObObj.getMax(), ObObj.getMax(), right_ts));
        } else if (includeStop) {
            obNewRange.setEndKey(ObRowKey.getInstance(stop, ObObj.getMax(), right_ts));
            obBorderFlag.setInclusiveEnd();
        } else {
            obNewRange.setEndKey(ObRowKey.getInstance(stop, ObObj.getMin(), right_ts));
            obBorderFlag.unsetInclusiveEnd();
        }
        ObTableQuery obTableQuery = new ObTableQuery();
        obNewRange.setBorderFlag(obBorderFlag);
        if (isReversed) {
            obTableQuery.setScanOrder(ObScanOrder.Reverse);
        }
        obTableQuery.setIndexName("PRIMARY");
        obTableQuery.sethTableFilter(filter);
        obTableQuery.addKeyRange(obNewRange);
        obTableQuery.setScanRangeColumns("K", "Q", "T");
        return obTableQuery;
    }

    private ObTableQuery buildObTableQuery(ObHTableFilter filter, final Scan scan) {
        ObTableQuery obTableQuery;
        TimeRange ts = scan.getTimeRange();
        if (scan.getMaxResultsPerColumnFamily() > 0) {
            filter.setLimitPerRowPerCf(scan.getMaxResultsPerColumnFamily());
        }
        if (scan.getRowOffsetPerColumnFamily() > 0) {
            filter.setOffsetPerRowPerCf(scan.getRowOffsetPerColumnFamily());
        }
        if (scan.isReversed()) {
            obTableQuery = buildObTableQuery(filter, scan.getStopRow(), false, scan.getStartRow(),
                true, true, ts);
        } else {
            obTableQuery = buildObTableQuery(filter, scan.getStartRow(), true, scan.getStopRow(),
                false, false, ts);
        }
        if (scan.getBatch() > 0) {
            obTableQuery.setBatchSize(scan.getBatch());
        }
        obTableQuery.setMaxResultSize(scan.getMaxResultSize() > 0 ? scan.getMaxResultSize()
            : configuration.getLong(HConstants.HBASE_CLIENT_SCANNER_MAX_RESULT_SIZE_KEY,
                HConstants.DEFAULT_HBASE_CLIENT_SCANNER_MAX_RESULT_SIZE));
        obTableQuery.setObKVParams(buildOBKVParams(scan));
        obTableQuery.setScanRangeColumns("K", "Q", "T");
        return obTableQuery;
    }

    private ObTableQuery buildObTableQuery(final Get get, Collection<byte[]> columnQualifiers)
                                                                                              throws IOException {
        ObTableQuery obTableQuery;
        if (get.isClosestRowBefore()) {
            PageFilter pageFilter = new PageFilter(1);
            FilterList filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL);
            filterList.addFilter(pageFilter);
            if (null != get.getFilter()) {
                filterList.addFilter(get.getFilter());
            }
            get.setFilter(filterList);
        }
        ObHTableFilter filter = buildObHTableFilter(get.getFilter(), get.getTimeRange(),
            get.getMaxVersions(), columnQualifiers);
        if (get.isClosestRowBefore()) {
            obTableQuery = buildObTableQuery(filter, HConstants.EMPTY_BYTE_ARRAY, true,
                get.getRow(), true, true, get.getTimeRange());
        } else {
            obTableQuery = buildObTableQuery(filter, get.getRow(), true, get.getRow(), true, false,
                get.getTimeRange());
        }
        obTableQuery.setObKVParams(buildOBKVParams(get));
        obTableQuery.setScanRangeColumns("K", "Q", "T");
        return obTableQuery;
    }

    public static ObTableBatchOperation buildObTableBatchOperation(List<Mutation> rowList,
                                                                   List<byte[]> qualifiers) {
        ObTableBatchOperation batch = new ObTableBatchOperation();
        ObTableOperationType opType;
        Map<String, Integer> indexMap = new HashMap<>();
        for (Mutation row : rowList) {
            if (row instanceof Put) {
                opType = INSERT_OR_UPDATE;
            } else if (row instanceof Delete) {
                opType = DEL;
            } else if (row instanceof Increment) {
                opType = INCREMENT;
            } else if (row instanceof Append) {
                opType = APPEND;
            } else {
                throw new FeatureNotSupportedException("not supported other type");
            }
            Set<Map.Entry<byte[], List<KeyValue>>> familyCellMap = row.getFamilyMap().entrySet();
            for (Map.Entry<byte[], List<KeyValue>> familyWithCells : familyCellMap) {
                if (opType == INCREMENT || opType == APPEND) {
                    indexMap.clear();
                    for (int i = 0; i < familyWithCells.getValue().size(); i++) {
                        Cell cell = familyWithCells.getValue().get(i);
                        String qualifier = Bytes.toString(CellUtil.cloneQualifier(cell));
                        indexMap.put(qualifier, i);
                    }
                    for (Map.Entry<String, Integer> entry : indexMap.entrySet()) {
                        qualifiers.add(entry.getKey().getBytes());
                        batch.addTableOperation(buildObTableOperation(
                                familyWithCells.getValue().get(entry.getValue()), opType,
                                row.getTTL()));
                    }
                } else {
                    for (KeyValue cell : familyWithCells.getValue()) {
                        batch.addTableOperation(
                                buildObTableOperation(cell, opType, row.getTTL()));
                    }
                }
            }
        }
        batch.setSamePropertiesNames(true);
        return batch;
    }

    private QueryAndMutate buildDeleteQueryAndMutate(KeyValue kv,
                                                     ObTableOperationType operationType,
                                                     boolean isTableGroup, Long TTL) {
        KeyValue.Type kvType = KeyValue.Type.codeToType(kv.getType());
        com.alipay.oceanbase.rpc.mutation.Mutation tableMutation = buildMutation(kv, operationType,
            isTableGroup, TTL);
        ObNewRange range = new ObNewRange();
        ObTableQuery tableQuery = new ObTableQuery();
        tableQuery.setObKVParams(buildOBKVParams((Scan) null));
        ObHTableFilter filter = null;
        switch (kvType) {
            case Delete:
                if (kv.getTimestamp() == Long.MAX_VALUE) {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    filter = buildObHTableFilter(null, null, 1, kv.getQualifier());
                } else {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    filter = buildObHTableFilter(null,
                        new TimeRange(kv.getTimestamp(), kv.getTimestamp() + 1), 1,
                        kv.getQualifier());
                }
                break;
            case DeleteColumn:
                if (kv.getTimestamp() == Long.MAX_VALUE) {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    filter = buildObHTableFilter(null, null, Integer.MAX_VALUE, kv.getQualifier());
                } else {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    filter = buildObHTableFilter(null, new TimeRange(0, kv.getTimestamp() + 1),
                        Integer.MAX_VALUE, kv.getQualifier());
                }
                break;
            case DeleteFamily:
                if (kv.getTimestamp() == Long.MAX_VALUE) {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    if (!isTableGroup) {
                        filter = buildObHTableFilter(null, null, Integer.MAX_VALUE);
                    } else {
                        filter = buildObHTableFilter(null, null, Integer.MAX_VALUE, kv.getQualifier());
                    }
                } else {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    if (!isTableGroup) {
                        filter = buildObHTableFilter(null, new TimeRange(0, kv.getTimestamp() + 1),
                            Integer.MAX_VALUE);
                    } else {
                        filter = buildObHTableFilter(null, new TimeRange(0, kv.getTimestamp() + 1),
                            Integer.MAX_VALUE, kv.getQualifier());
                    }
                }
                break;
            case DeleteFamilyVersion:
                if (kv.getTimestamp() == Long.MAX_VALUE) {
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    // [MAX_VALUE, MAX_VALUE), delete nothing
                    filter = buildObHTableFilter(null, new TimeRange(Long.MAX_VALUE), Integer.MAX_VALUE);
                } else {
                    TimeRange timeRange = new TimeRange(kv.getTimestamp(), kv.getTimestamp() + 1);
                    range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(),
                        ObObj.getMin()));
                    range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(),
                        ObObj.getMax()));
                    if (!isTableGroup) {
                        filter = buildObHTableFilter(null,
                                timeRange,
                            Integer.MAX_VALUE);
                    } else {
                        filter = buildObHTableFilter(null,
                                timeRange,
                            Integer.MAX_VALUE, kv.getQualifier());
                    }
                }
                break;
            default:
                return null;
        }
        tableQuery.sethTableFilter(filter);
        tableQuery.addKeyRange(range);
        return new QueryAndMutate(tableQuery, tableMutation);
    }

    private com.alipay.oceanbase.rpc.mutation.Mutation buildMutation(KeyValue kv,
                                                                     ObTableOperationType operationType,
                                                                     boolean isTableGroup, Long TTL) {
        KeyValue.Type kvType = KeyValue.Type.codeToType(kv.getType());
        switch (kvType) {
            case Put:
                String[] property_columns = V_COLUMNS;
                Object[] property = new Object[] { CellUtil.cloneValue(kv) };
                if (TTL != Long.MAX_VALUE) {
                    property_columns = PROPERTY_COLUMNS;
                    property = new Object[] { CellUtil.cloneValue(kv), TTL };
                }
                return com.alipay.oceanbase.rpc.mutation.Mutation.getInstance(operationType,
                    ROW_KEY_COLUMNS,
                    new Object[] { kv.getRow(), kv.getQualifier(), kv.getTimestamp() },
                    property_columns, property);
            case Delete:
                return com.alipay.oceanbase.rpc.mutation.Mutation.getInstance(DEL, ROW_KEY_COLUMNS,
                    new Object[] { kv.getRow(), kv.getQualifier(), kv.getTimestamp() }, null, null);
            case Maximum:
                return com.alipay.oceanbase.rpc.mutation.Mutation.getInstance(DEL, ROW_KEY_COLUMNS,
                    new Object[] { kv.getRow(), null, -kv.getTimestamp() }, null, null);
            case DeleteColumn:
                return com.alipay.oceanbase.rpc.mutation.Mutation
                    .getInstance(DEL, ROW_KEY_COLUMNS,
                        new Object[] { kv.getRow(), kv.getQualifier(), -kv.getTimestamp() }, null,
                        null);
            case DeleteFamily:
                return com.alipay.oceanbase.rpc.mutation.Mutation.getInstance(
                    DEL,
                    ROW_KEY_COLUMNS,
                    new Object[] { kv.getRow(), isTableGroup ? kv.getQualifier() : null,
                            -kv.getTimestamp() }, null, null);
            case DeleteFamilyVersion:
                return com.alipay.oceanbase.rpc.mutation.Mutation.getInstance(
                    DEL,
                    ROW_KEY_COLUMNS,
                    new Object[] { kv.getRow(), isTableGroup ? kv.getQualifier() : null,
                            kv.getTimestamp() }, null, null);
            default:
                throw new IllegalArgumentException("illegal mutation type " + operationType);
        }
    }

    private KeyValue modifyQualifier(KeyValue original, byte[] newQualifier) {
        // Extract existing components
        byte[] row = original.getRow();
        byte[] family = original.getFamily();
        byte[] value = original.getValue();
        long timestamp = original.getTimestamp();
        byte type = original.getTypeByte();
        // Create a new KeyValue with the modified qualifier
        return new KeyValue(row, family, newQualifier, timestamp, KeyValue.Type.codeToType(type),
            value);
    }

    private BatchOperation buildBatchOperation(String tableName, List<? extends Row> actions,
                                               boolean isTableGroup, List<Integer> resultMapSingleOp)
                                                                                                     throws FeatureNotSupportedException,
                                                                                                     IllegalArgumentException,
                                                                                                     IOException {
        BatchOperation batch = obTableClient.batchOperation(tableName);
        int posInList = -1;
        int singleOpResultNum;
        for (Row row : actions) {
            singleOpResultNum = 0;
            posInList++;
            if (row instanceof Get) {
                if (!ObGlobal.isHBaseBatchGetSupport()) {
                    throw new FeatureNotSupportedException("server does not support batch get");
                }
                ++singleOpResultNum;
                Get get = (Get) row;
                ObTableQuery obTableQuery;
                // In a Get operation in ls batch, we need to determine whether the get is a table-group operation or not,
                // we handle this by appending the column family to the qualifier on the client side.
                // The server can then use this information to filter the appropriate column families and qualifiers.
                if ((get.getFamilyMap().keySet().isEmpty()
                        || get.getFamilyMap().size() > 1) &&
                        !get.getColumnFamilyTimeRange().isEmpty()) {
                    throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                } else if (get.getFamilyMap().size() == 1 && !get.getColumnFamilyTimeRange().isEmpty()) {
                    byte[] family = get.getFamilyMap().keySet().iterator().next();
                    Map<byte[], TimeRange> colFamTimeRangeMap = get.getColumnFamilyTimeRange();
                    if (colFamTimeRangeMap.size() > 1) {
                        throw new FeatureNotSupportedException("setColumnFamilyTimeRange is only supported in single column family for now");
                    } else if (colFamTimeRangeMap.get(family) == null) {
                        throw new IllegalArgumentException("Get family is not matched in ColumnFamilyTimeRange");
                    } else {
                        TimeRange tr = colFamTimeRangeMap.get(family);
                        get.setTimeRange(tr.getMin(), tr.getMax());
                    }
                }
                NavigableSet<byte[]> columnFilters = new TreeSet<>(Bytes.BYTES_COMPARATOR);
                // in batch get, we need to carry family in qualifier to server even this get is a single-cf operation
                // because the entire batch may be a multi-cf batch so do not carry family
                // family in qualifier helps us to know which table to query
                processColumnFilters(columnFilters, get.getFamilyMap());
                obTableQuery = buildObTableQuery(get, columnFilters);
                ObTableClientQueryImpl query = new ObTableClientQueryImpl(tableName, obTableQuery, obTableClient);
                try {
                    query.setRowKey(row(colVal("K", Bytes.toString(get.getRow())), colVal("Q", null), colVal("T", Integer.MAX_VALUE)));
                } catch (Exception e) {
                    logger.error("unexpected error occurs when set row key", e);
                    throw new IOException(e);
                }
                batch.addOperation(query);
            } else if (row instanceof Put) {
                Put put = (Put) row;
                if (put.isEmpty()) {
                    throw new IllegalArgumentException("No columns to insert for #"
                            + (posInList + 1) + " item");
                }
                for (Map.Entry<byte[], List<KeyValue>> entry : put.getFamilyMap().entrySet()) {
                    byte[] family = entry.getKey();
                    List<KeyValue> keyValueList = entry.getValue();
                    for (KeyValue kv : keyValueList) {
                        singleOpResultNum++;
                        if (isTableGroup) {
                            byte[] oldQualifier = kv.getQualifier();
                            byte[] newQualifier = new byte[family.length + 1/* length of "." */ + oldQualifier.length];
                            System.arraycopy(family, 0, newQualifier, 0, family.length);
                            newQualifier[family.length] = 0x2E; // 0x2E in utf-8 is "."
                            System.arraycopy(oldQualifier, 0, newQualifier, family.length + 1, oldQualifier.length);
                            KeyValue newKV = modifyQualifier(kv, newQualifier);
                            batch
                                .addOperation(buildMutation(newKV, INSERT_OR_UPDATE, true, put.getTTL()));
                        } else {
                            batch.addOperation(buildMutation(kv, INSERT_OR_UPDATE, false, put.getTTL()));
                        }
                    }
                }
            } else if (row instanceof Delete) {
                boolean disExec = obTableClient.getServerCapacity().isSupportDistributedExecute();
                Delete delete = (Delete) row;
                if (delete.isEmpty()) {
                    singleOpResultNum++;
                    if (disExec) {
                        KeyValue kv = new KeyValue(delete.getRow(), delete.getTimeStamp(),
                                KeyValue.Type.Maximum);
                        com.alipay.oceanbase.rpc.mutation.Mutation tableMutation = buildMutation(kv, DEL, isTableGroup, Long.MAX_VALUE);
                        ObNewRange range = new ObNewRange();
                        ObTableQuery tableQuery = new ObTableQuery();
                        ObHTableFilter filter;
                        tableQuery.setObKVParams(buildOBKVParams((Scan) null));
                        range.setStartKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMin(), ObObj.getMin()));
                        range.setEndKey(ObRowKey.getInstance(kv.getRow(), ObObj.getMax(), ObObj.getMax()));
                        if (kv.getTimestamp() == Long.MAX_VALUE) {
                            filter = buildObHTableFilter(null, null, Integer.MAX_VALUE);
                        } else {
                            filter = buildObHTableFilter(null, new TimeRange(0, kv.getTimestamp() + 1), Integer.MAX_VALUE);
                        }
                        tableQuery.sethTableFilter(filter);
                        tableQuery.addKeyRange(range);

                        tableMutation.setTable(tableName);
                        batch.addOperation(new QueryAndMutate(tableQuery, tableMutation));
                    } else {
                        KeyValue kv = new KeyValue(delete.getRow(), delete.getTimeStamp(),
                                KeyValue.Type.Maximum);
                        batch.addOperation(buildMutation(kv, DEL, isTableGroup, Long.MAX_VALUE));
                    }
                } else {
                    for (Map.Entry<byte[], List<KeyValue>> entry : delete.getFamilyMap().entrySet()) {
                        byte[] family = entry.getKey();
                        List<KeyValue> keyValueList = entry.getValue();
                        for (KeyValue kv : keyValueList) {
                            singleOpResultNum++;
                            if (isTableGroup) {
                                byte[] oldQualifier = kv.getQualifier();
                                byte[] newQualifier = new byte[family.length + 1/* length of "." */ + oldQualifier.length];
                                System.arraycopy(family, 0, newQualifier, 0, family.length);
                                newQualifier[family.length] = 0x2E; // 0x2E in utf-8 is "."
                                System.arraycopy(oldQualifier, 0, newQualifier, family.length + 1, oldQualifier.length);
                                KeyValue newKV = modifyQualifier(kv, newQualifier);
                                if (disExec) {
                                    batch.addOperation(buildDeleteQueryAndMutate(newKV, DEL, true, Long.MAX_VALUE));
                                } else {
                                    batch.addOperation(buildMutation(newKV, DEL, true, Long.MAX_VALUE));
                                }
                            } else {
                                if (disExec) {
                                    batch.addOperation(buildDeleteQueryAndMutate(kv, DEL, false, Long.MAX_VALUE));
                                } else {
                                    batch.addOperation(buildMutation(kv, DEL, false, Long.MAX_VALUE));
                                }
                            }
                        }
                    }
                }
            } else {
                throw new FeatureNotSupportedException(
                    "not supported other type in batch yet,only support get, put and delete");
            }
            resultMapSingleOp.add(singleOpResultNum);
        }
        batch.setEntityType(ObTableEntityType.HKV);
        return batch;
    }

    public static ObTableOperation buildObTableOperation(KeyValue kv,
                                                         ObTableOperationType operationType,
                                                         Long TTL) {
        KeyValue.Type kvType = KeyValue.Type.codeToType(kv.getType());
        String[] property_columns = V_COLUMNS;
        Object[] property = new Object[] { CellUtil.cloneValue(kv) };
        if (TTL != Long.MAX_VALUE) {
            property_columns = PROPERTY_COLUMNS;
            property = new Object[] { CellUtil.cloneValue(kv), TTL };
        }
        switch (kvType) {
            case Put:
                return getInstance(
                    operationType,
                    new Object[] { CellUtil.cloneRow(kv), CellUtil.cloneQualifier(kv),
                            kv.getTimestamp() }, property_columns, property);
            case Delete:
                return getInstance(
                    DEL,
                    new Object[] { CellUtil.cloneRow(kv), CellUtil.cloneQualifier(kv),
                            kv.getTimestamp() }, null, null);
            case DeleteColumn:
                return getInstance(
                    DEL,
                    new Object[] { CellUtil.cloneRow(kv), CellUtil.cloneQualifier(kv),
                            -kv.getTimestamp() }, null, null);
            case DeleteFamily:
                return getInstance(DEL, new Object[] { kv.getRow(), null, -kv.getTimestamp() },
                    null, null);
            default:
                throw new IllegalArgumentException("illegal mutation type " + operationType);
        }
    }

    private ObTableQueryRequest buildObTableQueryRequest(ObTableQuery obTableQuery,
                                                         String targetTableName) {
        ObTableQueryRequest request = new ObTableQueryRequest();
        request.setEntityType(ObTableEntityType.HKV);
        request.setTableQuery(obTableQuery);
        request.setTableName(targetTableName);
        return request;
    }

    private ObTableQueryAsyncRequest buildObTableQueryAsyncRequest(ObTableQuery obTableQuery,
                                                                   String targetTableName) {
        ObTableQueryRequest request = new ObTableQueryRequest();
        request.setEntityType(ObTableEntityType.HKV);
        request.setTableQuery(obTableQuery);
        request.setTableName(targetTableName);
        ObTableQueryAsyncRequest asyncRequest = new ObTableQueryAsyncRequest();
        asyncRequest.setEntityType(ObTableEntityType.HKV);
        asyncRequest.setTableName(targetTableName);
        asyncRequest.setObTableQueryRequest(request);
        return asyncRequest;
    }

    private ObTableQueryAndMutateRequest buildObTableQueryAndMutateRequest(ObTableQuery obTableQuery,
                                                                           ObTableBatchOperation obTableBatchOperation,
                                                                           String targetTableName) {
        ObTableQueryAndMutate queryAndMutate = new ObTableQueryAndMutate();
        queryAndMutate.setTableQuery(obTableQuery);
        queryAndMutate.setMutations(obTableBatchOperation);
        ObTableQueryAndMutateRequest request = new ObTableQueryAndMutateRequest();
        request.setTableName(targetTableName);
        request.setTableQueryAndMutate(queryAndMutate);
        request.setEntityType(ObTableEntityType.HKV);
        request.setReturningAffectedEntity(true);
        return request;
    }

    public static void checkFamilyViolation(Collection<byte[]> families, boolean check_empty_family) {
        if (check_empty_family && (families == null || families.isEmpty())) {
            throw new FeatureNotSupportedException("family is empty");
        }

        for (byte[] family : families) {
            if (family == null || family.length == 0) {
                throw new IllegalArgumentException("family is empty");
            } else if (isBlank(Bytes.toString(family))) {
                throw new IllegalArgumentException("family is blank");
            }
        }
    }

    // This method is currently only used for append and increment operations.
    // It restricts these two methods to use multi-column family operations.
    // Note: After completing operations on multiple column families, they are deleted using the method described above.
    public static void checkFamilyViolationForOneFamily(Collection<byte[]> families) {
        if (families == null || families.size() == 0) {
            throw new FeatureNotSupportedException("family is empty.");
        }

        if (families.size() > 1) {
            throw new FeatureNotSupportedException("multi family is not supported yet.");
        }
        for (byte[] family : families) {
            if (family == null || family.length == 0) {
                throw new IllegalArgumentException("family is empty");
            } else if (isBlank(Bytes.toString(family))) {
                throw new IllegalArgumentException("family is blank");
            }
        }
    }

    public void refreshTableEntry(String familyString, boolean hasTestLoad) throws Exception {
        if (this.obTableClient.isOdpMode()) {
            return;
        }
        this.obTableClient.getOrRefreshTableEntry(
            getNormalTargetTableName(tableNameString, familyString), true);
        if (hasTestLoad) {
            this.obTableClient.getOrRefreshTableEntry(
                getTestLoadTargetTableName(tableNameString, familyString, configuration), true);
        }
    }

    public byte[][] getStartKeys() throws IOException {
        byte[][] startKeys = new byte[0][];
        if (ObGlobal.isHBaseAdminSupport()) {
            // 4.3.5.3 and above, OCP and ODP mode use regionLocator to getStartKeys
            if (regionLocator == null) {
                OHRegionLocatorExecutor executor = new OHRegionLocatorExecutor(tableNameString, obTableClient);
                regionLocator = executor.getRegionLocator(tableNameString);
            }
            startKeys = regionLocator.getStartKeys();
        } else if (!obTableClient.isOdpMode()) {
            // before 4.3.5.3 only support in OCP mode
            try {
                startKeys = obTableClient.getHBaseTableStartKeys(tableNameString);
            } catch (Exception e) {
                throw new IOException("Fail to get start keys of HBase Table: " + tableNameString, e);
            }
        } else {
            throw new IOException("not supported yet in odp mode, only support in ObServer 4.3.5.3 and above");
        }

        return startKeys;
    }

    public byte[][] getEndKeys() throws IOException {
        byte[][] endKeys = new byte[0][];
        if (ObGlobal.isHBaseAdminSupport()) {
            // 4.3.5.3 and above, OCP and ODP mode use regionLocator to getEndKeys
            if (regionLocator == null) {
                OHRegionLocatorExecutor executor = new OHRegionLocatorExecutor(tableNameString, obTableClient);
                regionLocator = executor.getRegionLocator(tableNameString);
            }
            endKeys = regionLocator.getEndKeys();
        } else if (!obTableClient.isOdpMode()) {
            // before 4.3.5.3 only support in OCP mode
            try {
                endKeys = obTableClient.getHBaseTableEndKeys(tableNameString);
            } catch (Exception e) {
                throw new IOException("Fail to get start keys of HBase Table: " + tableNameString, e);
            }
        } else {
            throw new IOException("not supported yet in odp mode, only support in ObServer 4.3.5.3 and above");
        }

        return endKeys;
    }

    public Pair<byte[][], byte[][]> getStartEndKeys() throws IOException {
        if (ObGlobal.isHBaseAdminSupport()) {
            // 4.3.5.3 and above, OCP and ODP mode use regionLocator to getStartEndKeys
            if (regionLocator == null) {
                OHRegionLocatorExecutor executor = new OHRegionLocatorExecutor(tableNameString, obTableClient);
                regionLocator = executor.getRegionLocator(tableNameString);
            }
            return regionLocator.getStartEndKeys();
        } else if (!obTableClient.isOdpMode()) {
            // before 4.3.5.3 only support in OCP mode
            return new Pair<>(getStartKeys(), getEndKeys());
        } else {
            throw new IOException("not supported yet in odp mode, only support in ObServer 4.3.5.3 and above");
        }
    }

    private BufferedMutator getBufferedMutator() throws IOException {
        if (this.mutator == null) {
            this.mutator = new OHBufferedMutatorImpl(this.configuration, new BufferedMutatorParams(
                TableName.valueOf(this.tableNameString)).pool(this.executePool)
                .writeBufferSize(this.writeBufferSize).maxKeyValueSize(this.maxKeyValueSize), this);
        }
        return this.mutator;
    }
}