package com.baidu.hugegraph.store;

import com.alipay.sofa.jraft.core.Replicator;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.util.Utils;
import com.baidu.hugegraph.pd.common.PDException;
import com.baidu.hugegraph.pd.grpc.Metapb;
import com.baidu.hugegraph.pd.grpc.Pdpb;
import com.baidu.hugegraph.store.options.HgStoreEngineOptions;
import com.baidu.hugegraph.store.options.RaftRocksdbOptions;
import com.baidu.hugegraph.store.meta.*;
import com.baidu.hugegraph.store.pd.PdProvider;
import com.baidu.hugegraph.store.util.IpUtil;
import com.baidu.hugegraph.store.util.Lifecycle;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.MemoryUsageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Register and heartbeat, Keep the system online
 */
@Slf4j
public class HeartbeatService implements Lifecycle<HgStoreEngineOptions> , PartitionStateListener{

    private static final int MAX_HEARTBEAT_RETRY_COUNT = 5;     // 心跳重试次数
    private static final int REGISTER_RETRY_INTERVAL = 1;   //注册重试时间间隔，单位秒

    private HgStoreEngineOptions options;
    private PdProvider pdProvider;
    private Store storeInfo;
    private Metapb.ClusterStats clusterStats;
    private StoreMetadata storeMetadata;
    private final HgStoreEngine storeEngine;

    private List<HgStoreStateListener> stateListeners;
    private Object partitionThreadLock = new Object();
    private Object storeThreadLock = new Object();
    // 心跳失败次数
    private int heartbeatFailCount = 0;
    private int reportErrCount = 0;
    // 线程休眠时间
    private volatile int timerNextDelay = 1000;
    private boolean terminated = false;

    public HeartbeatService(HgStoreEngine storeEngine) {
        this.storeEngine = storeEngine;
        stateListeners = Collections.synchronizedList(new ArrayList());
    }

    @Override
    public boolean init(HgStoreEngineOptions opts) {
        this.options = opts;
        storeInfo = storeMetadata.getStore();
        if (storeInfo == null)
            storeInfo = new Store();
        storeInfo.setStoreAddress(options.getGrpcAddress());
        storeInfo.setPdAddress(options.getPdAddress());
        storeInfo.setRaftAddress(options.getRaftAddress());
        storeInfo.setState(Metapb.StoreState.Unknown);
        storeInfo.setLabels(options.getLabels());
        storeInfo.setCores(Runtime.getRuntime().availableProcessors());
        storeInfo.setDeployPath(HeartbeatService.class.getResource("/").getPath());
        storeInfo.setDataPath(options.getDataPath());
        this.pdProvider = options.getPdProvider();

        new Thread(new Runnable() {
            @Override
            public void run() {
                doStoreHeartbeat();
            }
        }, "heartbeat").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                doPartitionHeartbeat();
            }
        }, " partition-hb").start();
        return true;
    }

    public HeartbeatService addStateListener(HgStoreStateListener stateListener) {
        stateListeners.add(stateListener);
        return this;
    }


    public Store getStoreInfo() {
        return storeInfo;
    }
    public void setStoreMetadata(StoreMetadata storeMetadata){
        this.storeMetadata = storeMetadata;
    }

    // 集群是否准备就绪
    public boolean isClusterReady() { return clusterStats.getState() == Metapb.ClusterState.Cluster_OK;}
    /**
     * 服务状态有四种
     * 就绪，在线、离线、死亡（从集群排除）
     */
    protected void doStoreHeartbeat() {
        while (!terminated) {
            try {
                switch (storeInfo.getState()) {
                    case Unknown:
                    case Offline:
                        registerStore();
                        break;
                    case Up:
                        storeHeartbeat();
                        monitorMemory();
                        break;
                    case Tombstone:
                        break;

                }
                synchronized (storeThreadLock){
                    storeThreadLock.wait(timerNextDelay);
                }
            } catch (Throwable e) {
                log.error("heartbeat error: ", e);
            }
        }
    }

    protected void doPartitionHeartbeat() {
        while (!terminated) {
            try {
                partitionHeartbeat();

            } catch (Exception e) {
                log.error("doPartitionHeartbeat error: ", e);
            } finally {

            }
            try {
                synchronized (partitionThreadLock) {
                    partitionThreadLock.wait(options.getPartitionHBInterval() * 1000);
                }
            } catch (InterruptedException e) {
                log.error("doPartitionHeartbeat error: ", e);
            }
        }
    }

    protected void registerStore() {
        try {
            // 注册 store，初次注册PD产生id，自动给storeinfo赋值
            this.storeInfo.setStoreAddress(IpUtil.getNearestAddress(options.getGrpcAddress()));
            this.storeInfo.setRaftAddress(IpUtil.getNearestAddress(options.getRaftAddress()));

            long storeId = pdProvider.registerStore(this.storeInfo);
            if (storeId != 0) {
                storeInfo.setId(storeId);
                storeMetadata.save(storeInfo);
                this.clusterStats = pdProvider.getClusterStats();
                if (clusterStats.getState() == Metapb.ClusterState.Cluster_OK) {
                    timerNextDelay = options.getStoreHBInterval() * 1000;
                }
                else {
                    timerNextDelay = REGISTER_RETRY_INTERVAL * 1000;
                }
                log.info("Register Store id= {} successfully. store = {}, clusterStats {}",
                        storeInfo.getId(), storeInfo, this.clusterStats);
                // 监听partition消息
                pdProvider.startHeartbeatStream(error->{
                    onStateChanged(Metapb.StoreState.Offline);
                    timerNextDelay = REGISTER_RETRY_INTERVAL * 1000;
                    wakeupHeartbeatThread();
                    log.error("Connection closed. The store state changes to {}", Metapb.StoreState.Offline);
                });
                onStateChanged(Metapb.StoreState.Up);
            } else {
                timerNextDelay = REGISTER_RETRY_INTERVAL * 1000 / 2;
            }
        } catch (PDException e) {
            int exceptCode = e.getErrorCode();
            if (exceptCode == Pdpb.ErrorType.STORE_ID_NOT_EXIST_VALUE) {
                log.error("The store ID {} does not match the PD. Check that the correct PD is connected, " +
                                "and then delete the store ID!!!",
                        storeInfo.getId());
                System.exit(-1);
            } else if (exceptCode == Pdpb.ErrorType.STORE_HAS_BEEN_REMOVED_VALUE) {
                log.error("The store ID {} has been removed, please delete all data and restart!",
                        storeInfo.getId());
                System.exit(-1);
            } else if (exceptCode == Pdpb.ErrorType.STORE_PROHIBIT_DUPLICATE_VALUE) {
                log.error("The store ID {} maybe duplicated, please check out store raft address and restart later!",
                        storeInfo.getId());
                System.exit(-1);
            }
        }
    }

    protected void storeHeartbeat() {
        if (log.isDebugEnabled()) {
            log.debug("storeHeartbeat ... ");
        }
        Metapb.ClusterStats clusterStats = null;
        try {
            clusterStats = pdProvider.storeHeartbeat(this.storeInfo);
        } catch (PDException e) {
            int exceptCode = e.getErrorCode();
            if (exceptCode == Pdpb.ErrorType.STORE_ID_NOT_EXIST_VALUE) {
                log.error("The store ID {} does not match the PD. Check that the correct PD is " +
                                "connected, and then delete the store ID!!!", storeInfo.getId());
                System.exit(-1);
            } else if (exceptCode == Pdpb.ErrorType.STORE_HAS_BEEN_REMOVED_VALUE) {
                log.error("The store ID {} has been removed, please delete all data and restart!",
                        storeInfo.getId());
                System.exit(-1);
            }
        }
        if (clusterStats.getState().getNumber() >= Metapb.ClusterState.Cluster_Fault.getNumber()) {
            if (reportErrCount == 0)
                log.info("The cluster is abnormal, {}", clusterStats);
            reportErrCount = (++reportErrCount) % 30;
        }

        if ( clusterStats.getState() == Metapb.ClusterState.Cluster_OK)
            timerNextDelay = options.getStoreHBInterval() * 1000;
        else
            timerNextDelay = REGISTER_RETRY_INTERVAL * 1000;

        if (clusterStats.getState() == Metapb.ClusterState.Cluster_Fault) {
            heartbeatFailCount++;
        } else {
            heartbeatFailCount = 0;
            this.clusterStats = clusterStats;
        }
        if (heartbeatFailCount > MAX_HEARTBEAT_RETRY_COUNT) {
            onStateChanged(Metapb.StoreState.Offline);
            timerNextDelay = REGISTER_RETRY_INTERVAL * 1000;
            this.clusterStats = clusterStats;
            log.error("Store heart beat failure. The store state changes to {}", Metapb.StoreState.Offline);
        }
    }

    protected synchronized void onStateChanged(Metapb.StoreState newState) {
        Utils.runInThread(() -> {
            Metapb.StoreState oldState = this.storeInfo.getState();
            this.storeInfo.setState(newState);
            stateListeners.forEach((e) ->
                    e.stateChanged(this.storeInfo, oldState, newState));
        });
    }


    protected void partitionHeartbeat() {
        if ( storeEngine == null) return;

        List<PartitionEngine> partitions = storeEngine.getLeaderPartition();
        final List<Metapb.PartitionStats> statsList = new ArrayList<>(partitions.size());

        Metapb.Shard localLeader = Metapb.Shard.newBuilder()
                .setStoreId(storeEngine.getPartitionManager().getStore().getId())
                .setRole(Metapb.ShardRole.Leader)
                .build();
        // 获取各个shard信息.
        for(PartitionEngine partition : partitions) {
            Metapb.PartitionStats.Builder stats = Metapb.PartitionStats.newBuilder();
            stats.setId(partition.getGroupId());
            stats.addAllGraphName(partition.getPartitions().keySet());
            stats.setLeaderTerm(partition.getLeaderTerm());
            stats.setConfVer(partition.getShardGroup().getConfVersion());
            stats.setLeader(localLeader);

            stats.addAllShard(partition.getShardGroup().getMetaPbShard());

            // shard 状态
            List<Metapb.ShardStats> shardStats = new ArrayList<>();
            Map<Long, PeerId> aliveShards = partition.getAlivePeers();
            // 统计shard状态
            partition.getShardGroup().getShards().forEach(shard -> {
                Metapb.ShardState state = Metapb.ShardState.SState_Normal;
                if (aliveShards.containsKey(shard.getStoreId())) {
                    Replicator.State s = partition.getRaftNode().getReplicatorState(aliveShards.get(shard.getStoreId()));
                    if (s == Replicator.State.Snapshot)
                        state = Metapb.ShardState.SState_Snapshot;
                } else
                    state = Metapb.ShardState.SState_Offline;

                shardStats.add(Metapb.ShardStats.newBuilder()
                        .setStoreId(shard.getStoreId())
                        .setRole(shard.getRole())
                        .setState(state).build());
            });
            stats.addAllShardStats(shardStats);
            stats.setTimestamp(System.currentTimeMillis());

            statsList.add(stats.build());
        }
        // 发送心跳
        if ( statsList.size() > 0 ) {
            pdProvider.partitionHeartbeat(statsList);
        }

    }

    public void monitorMemory() {

        try {
            Map<MemoryUsageType, Long> mems =
                    storeEngine.getBusinessHandler().getApproximateMemoryUsageByType(null);

            if (mems.get(MemoryUsageType.kCacheTotal) > RaftRocksdbOptions.getWriteCacheCapacity() * 0.9 &&
                    mems.get(MemoryUsageType.kMemTableUnFlushed) > RaftRocksdbOptions.getWriteCacheCapacity() * 0.1) {
               // storeEngine.getBusinessHandler().flushAll();
                log.warn("Less memory, start flush dbs, {}", mems);
            }
        } catch (Exception e) {
            log.error("MonitorMemory exception {}", e);
        }
    }

    @Override
    public void shutdown() {
        log.info("HeartbeatService shutdown");
        terminated = true;
        synchronized (partitionThreadLock) {
            partitionThreadLock.notify();
        }
    }

    @Override
    public void partitionRoleChanged(Partition partition, PartitionRole newRole) {
        if ( newRole == PartitionRole.LEADER ){
            // leader发生改变，激活心跳
            synchronized (partitionThreadLock) {
                partitionThreadLock.notifyAll();
            }
        }
    }

    @Override
    public void partitionShardChanged(Partition partition, List<Metapb.Shard> oldShards, List<Metapb.Shard> newShards) {
        if ( partition.isLeader()){
            synchronized (partitionThreadLock) {
                partitionThreadLock.notifyAll();
            }
        }
    }

    private void wakeupHeartbeatThread(){
        synchronized (storeThreadLock) {
            storeThreadLock.notifyAll();
        }
    }
}
