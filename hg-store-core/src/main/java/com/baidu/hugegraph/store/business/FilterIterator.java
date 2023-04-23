package com.baidu.hugegraph.store.business;

import org.apache.commons.lang3.ArrayUtils;

import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.serializer.BinaryBackendEntry;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.rocksdb.access.RocksDBSession.BackendColumn;
import com.baidu.hugegraph.rocksdb.access.ScanIterator;
import com.baidu.hugegraph.structure.HugeElement;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FilterIterator<T extends BackendColumn> extends
                                                     AbstractSelectIterator
        implements ScanIterator {

    private final ConditionQuery query;
    T current = null;

    public FilterIterator(ScanIterator iterator, ConditionQuery query) {
        super();
        this.iterator = iterator;
        this.query = query;
        // log.info("operator sinking is used to filter data:{}",
        //         query.toString());
    }

    @Override
    public boolean hasNext() {
        boolean match = false;
        if (this.query.resultType().isVertex() ||
            this.query.resultType().isEdge()) {
            BackendEntry entry = null;
            while (iterator.hasNext()) {
                current = iterator.next();
                BackendEntry.BackendColumn column =
                        BackendEntry.BackendColumn.of(
                                current.name, current.value);
                BackendEntry.BackendColumn[] columns =
                        new BackendEntry.BackendColumn[]{column};
                if (entry == null || !belongToMe(entry, column) ||
                    this.query.resultType().isEdge()) {
                    entry = new BinaryBackendEntry(query.resultType(),
                                                   current.name);
                    entry.columns(columns);
                } else {
                    // 有可能存在包含多个column的情况
                    entry.columns(columns);
                    continue;
                }
                HugeElement element = this.parseEntry(entry,
                                                      this.query.resultType()
                                                                .isVertex());
                match = query.test(element);
                if (match) break;
            }
        } else {
            boolean has = iterator.hasNext();
            if (has) {
                current = iterator.next();
            }
            return has;
        }
        return match;
    }

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public <T> T next() {
        return (T) current;
    }

    @Override
    public long count() {
        return iterator.count();
    }

    @Override
    public byte[] position() {
        return iterator.position();
    }

    @Override
    public void seek(byte[] position) {
        this.iterator.seek(position);
    }

    @Override
    public void close() {
        iterator.close();
    }

    public static ScanIterator of(ScanIterator it, byte[] conditionQuery) {
        if (ArrayUtils.isEmpty(conditionQuery)) return it;
        ConditionQuery query = ConditionQuery.fromBytes(conditionQuery);
        return new FilterIterator(it, query);
    }
}
