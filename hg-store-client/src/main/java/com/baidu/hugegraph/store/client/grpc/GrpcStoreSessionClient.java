package com.baidu.hugegraph.store.client.grpc;

import static com.baidu.hugegraph.store.client.grpc.GrpcUtil.getHeader;
import static com.baidu.hugegraph.store.client.grpc.GrpcUtil.toKey;
import static com.baidu.hugegraph.store.client.grpc.GrpcUtil.toTk;

import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

import com.baidu.hugegraph.store.HgOwnerKey;
import com.baidu.hugegraph.store.client.HgStoreNodeSession;
import com.baidu.hugegraph.store.grpc.common.GraphMethod;
import com.baidu.hugegraph.store.grpc.common.TableMethod;
import com.baidu.hugegraph.store.grpc.session.BatchEntry;
import com.baidu.hugegraph.store.grpc.session.BatchGetReq;
import com.baidu.hugegraph.store.grpc.session.BatchReq;
import com.baidu.hugegraph.store.grpc.session.BatchWriteReq;
import com.baidu.hugegraph.store.grpc.session.CleanReq;
import com.baidu.hugegraph.store.grpc.session.FeedbackRes;
import com.baidu.hugegraph.store.grpc.session.GetReq;
import com.baidu.hugegraph.store.grpc.session.GraphReq;
import com.baidu.hugegraph.store.grpc.session.HgStoreSessionGrpc;
import com.baidu.hugegraph.store.grpc.session.HgStoreSessionGrpc.HgStoreSessionBlockingStub;
import com.baidu.hugegraph.store.grpc.session.TableReq;

import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * @author lynn.bond@hotmail.com created on 2021/11/18
 * @version 0.5.0
 */
@Slf4j
@ThreadSafe
class GrpcStoreSessionClient extends AbstractGrpcClient {

    @Override
    public HgStoreSessionBlockingStub getBlockingStub(ManagedChannel channel) {
        HgStoreSessionBlockingStub stub;
        stub = HgStoreSessionGrpc.newBlockingStub(channel);
        return stub;
    }

    private HgStoreSessionBlockingStub getBlockingStub(HgStoreNodeSession nodeSession) {
        HgStoreSessionBlockingStub stub =
                (HgStoreSessionBlockingStub) getBlockingStub(nodeSession.getStoreNode().getAddress());
        return stub;
    }

    FeedbackRes doGet(HgStoreNodeSession nodeSession, String table, HgOwnerKey ownerKey) {
        if (log.isDebugEnabled()) {
            log.debug("doGet: {}-{}-{}-{}", nodeSession, table, ownerKey, GetReq.newBuilder()
                                                                                .setHeader(getHeader(nodeSession))
                                                                                .setTk(toTk(table, ownerKey))
                                                                                .build());
        }
        return this.getBlockingStub(nodeSession)
                   .get2(GetReq.newBuilder()
                               .setHeader(getHeader(nodeSession))
                               .setTk(toTk(table, ownerKey))
                               .build()
                   );
    }

    FeedbackRes doClean(HgStoreNodeSession nodeSession, int partId) {
        return this.getBlockingStub(nodeSession)
                   .clean(CleanReq.newBuilder()
                                  .setHeader(getHeader(nodeSession))
                                  .setPartition(partId)
                                  .build()
                   );
    }

    FeedbackRes doBatchGet(HgStoreNodeSession nodeSession, String table, List<HgOwnerKey> keyList) {
        BatchGetReq.Builder builder = BatchGetReq.newBuilder();
        builder.setHeader(getHeader(nodeSession)).setTable(table);

        for (HgOwnerKey key : keyList) {
            builder.addKey(toKey(key));
        }

        if (log.isDebugEnabled()) {
            log.debug("batchGet2: {}-{}-{}-{}", nodeSession, table, keyList, builder.build());
        }
        return this.getBlockingStub(nodeSession).batchGet2(builder.build());

    }

    FeedbackRes doBatch(HgStoreNodeSession nodeSession, String batchId, List<BatchEntry> entries) {
        BatchWriteReq.Builder writeReq = BatchWriteReq.newBuilder();
        writeReq.addAllEntry(entries);
        return this.getBlockingStub(nodeSession)
                   .batch(BatchReq.newBuilder()
                                  .setHeader(getHeader(nodeSession))
                                  .setWriteReq(writeReq)
                                  .setBatchId(batchId)
                                  .build()
                   );
    }

    FeedbackRes doTable(HgStoreNodeSession nodeSession, String table, TableMethod method) {
        return this.getBlockingStub(nodeSession)
                   .table(TableReq.newBuilder()
                                  .setHeader(getHeader(nodeSession))
                                  .setTableName(table)
                                  .setMethod(method)
                                  .build()
                   );
    }

    FeedbackRes doGraph(HgStoreNodeSession nodeSession, String graph, GraphMethod method) {
        return this.getBlockingStub(nodeSession)
                   .graph(GraphReq.newBuilder()
                                  .setHeader(getHeader(nodeSession))
                                  .setGraphName(graph)
                                  .setMethod(method)
                                  .build()
                   );
    }
}


