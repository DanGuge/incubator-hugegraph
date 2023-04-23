package com.baidu.hugegraph.store.pd;


import com.baidu.hugegraph.pd.client.PDClient;
import com.baidu.hugegraph.pd.common.PDException;
import com.baidu.hugegraph.pd.common.PartitionUtils;
import com.baidu.hugegraph.pd.grpc.MetaTask;
import com.baidu.hugegraph.pd.grpc.Metapb;
import com.baidu.hugegraph.pd.grpc.Pdpb;
import com.baidu.hugegraph.store.meta.GraphManager;
import com.baidu.hugegraph.store.options.HgStoreEngineOptions;
import com.baidu.hugegraph.store.meta.Partition;
import com.baidu.hugegraph.store.meta.Store;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 内置PD服务，用于单机部署或开发调试
 * @author liyan75
 */
@Slf4j
public class FakePdServiceProvider implements PdProvider {
    private Map<Long, Store> stores;
    private int partitionCount = 0;
    private int shardCount = 0;
    private GraphManager graphManager = null;

    public FakePdServiceProvider(HgStoreEngineOptions.FakePdOptions options) {
        stores = new LinkedHashMap<>();
        if (options != null) {
            String[] storeList = options.getStoreList().split(",");
            String[] peersList = options.getPeersList().split(",");
            for (int i = 0; i < storeList.length; i++) {
                if (!storeList[i].isEmpty()) {
                    addStore(storeList[i], peersList[i]);
                }
            }
        }
        this.partitionCount = options.getPartitionCount();
    }

    private void addStore(String storeAddr, String raftAddr){
        Store store = new Store() {{
            setId(makeStoreId(storeAddr));
            setRaftAddress(raftAddr);
            setStoreAddress(storeAddr);
        }};
        stores.put(store.getId(), store);
    }

    public void addStore(Store store) {
        stores.put(store.getId(), store);
    }

    public static long makeStoreId(String storeAddress){
        return storeAddress.hashCode();
    }

    @Override
    public long registerStore(Store store) throws PDException {
        log.info("registerStore storeId:{}, storeAddress:{}", store.getId(), store.getStoreAddress());

        // id 不匹配，禁止登录
        if ( store.getId() != 0 && store.getId() != makeStoreId(store.getStoreAddress()))
            throw  new PDException(Pdpb.ErrorType.STORE_ID_NOT_EXIST_VALUE, "Store id does not matched");

        if (!stores.containsKey(makeStoreId(store.getStoreAddress()))) {
            store.setId(makeStoreId(store.getStoreAddress()));
            stores.put(store.getId(), store);
        }
        Store s = stores.get(makeStoreId(store.getStoreAddress()));
        store.setId(s.getId());

        return store.getId();
    }

    private Map<String, Metapb.Partition> partitions = new ConcurrentHashMap<>();
    @Override
    public Partition getPartitionByID(String graph, int partId) {
        List<Store> storeList = new ArrayList(stores.values());
        int shardCount = this.shardCount;
        if (shardCount == 0 || shardCount >= stores.size())
            shardCount = stores.size();


        int storeIdx = partId % storeList.size();
        List<Metapb.Shard> shards = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            Metapb.Shard shard = Metapb.Shard.newBuilder().setStoreId(storeList.get(storeIdx).getId())
                    .setRole(i == 0 ? Metapb.ShardRole.Leader : Metapb.ShardRole.Follower) //
                    .build();
            shards.add(shard);
            storeIdx = (storeIdx + 1) >= storeList.size() ? 0 : ++storeIdx; // 顺序选择
        }

        int partLength = getPartitionLength();
        Metapb.Partition partition = Metapb.Partition.newBuilder()
                .setGraphName(graph)
                .setId(partId)
                .setStartKey(partLength * partId)
                .setEndKey(partLength * (partId + 1))
                //.addAllShards(shards)
                .build();
        return new Partition(partition);
    }

    @Override
    public Metapb.Shard getPartitionLeader(String graph, int partId) {
        return null;
    }

    private int getPartitionLength(){
        return PartitionUtils.MAX_VALUE / (partitionCount == 0 ? stores.size() : partitionCount) + 1;
    }

    @Override
    public Metapb.Partition getPartitionByCode(String graph, int code) {
        int partId = code / getPartitionLength();
        return getPartitionByID(graph, partId).getProtoObj();
    }

    @Override
    public Partition delPartition(String graph, int partId) {
        return null;
    }

    @Override
    public List<Metapb.Partition> updatePartition(List<Metapb.Partition> partitions) {
        return partitions;
    }

    @Override
    public List<Partition> getPartitionsByStore(long storeId) throws PDException {
        return new ArrayList<>();
    }

    @Override
    public void updatePartitionCache(Partition partition, Boolean changeLeader) {

    }

    @Override
    public void invalidPartitionCache(String graph, int partId) {

    }

    @Override
    public boolean startHeartbeatStream(Consumer<Throwable> onError) {
        return false;
    }

    @Override
    public boolean addPartitionInstructionListener(PartitionInstructionListener listener) {
        return false;
    }

    @Override
    public boolean partitionHeartbeat(List<Metapb.PartitionStats> statsList) {
        return true;
    }

    @Override
    public boolean isLocalPartition(long storeId, int partitionId) {
        return true;
    }

    @Override
    public Metapb.Graph getGraph(String graphName) {
        return Metapb.Graph.newBuilder().setGraphName(graphName)
                //.setId(PartitionUtils.calcHashcode(graphName.getBytes()))
                .build();
    }

    @Override
    public void reportTask(MetaTask.Task task) throws PDException {

    }

    @Override
    public PDClient getPDClient() {
        return null;
    }


    @Override
    public Store getStoreByID(Long storeId) {
        return stores.get(storeId);
    }

    @Override
    public Metapb.ClusterStats getClusterStats() {
        return Metapb.ClusterStats.newBuilder()
                .setState(Metapb.ClusterState.Cluster_OK).build();
    }

    @Override
    public Metapb.ClusterStats storeHeartbeat(Store node) {

        return getClusterStats();
    }

    @Override
    public boolean updatePartitionLeader(String graphName, int partId, long leaderStoreId) {
        return false;

    }

    /**
     * For unit test
     * @return
     */
    public static Store getDefaultStore(){
        Store store = new Store();
        store.setId(1);
        store.setStoreAddress("127.0.0.1:8501");
        store.setRaftAddress("127.0.0.1:8511");
        store.setPartitionCount(1);
        return store;
    }


    public GraphManager getGraphManager(){
        return graphManager;
    }
    public void setGraphManager(GraphManager graphManager) {
        this.graphManager = graphManager;
    }

    @Override
    public void deleteShardGroup(int groupId) {

    }
}
