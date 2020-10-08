package com.amazon.situp.plugins.processor.state;

import com.google.common.primitives.SignedBytes;
import com.amazon.situp.processor.state.ProcessorState;
import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.mapdb.BTreeMap;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerByteArray;

public class MapDbProcessorState<V> implements ProcessorState<byte[], V> {


    private static class SignedByteArraySerializer extends SerializerByteArray {
        @Override
        public int compare(byte[] o1, byte[] o2) {
            return SignedBytes.lexicographicalComparator().compare(o1, o2);
        }
    }

    private static final SignedByteArraySerializer SIGNED_BYTE_ARRAY_SERIALIZER = new SignedByteArraySerializer();

    private final BTreeMap<byte[], V> map;

    public MapDbProcessorState(final File dbPath, final String dbName) {
        map =
                (BTreeMap<byte[], V>) DBMaker.fileDB(String.join("/", dbPath.getPath(), dbName))
                        .fileDeleteAfterClose()
                        .fileMmapEnable() //MapDB uses the (slower) Random Access Files by default
                        .make()
                        .treeMap(dbName)
                        .counterEnable() //Treemap doesnt keep size counter by default
                        .keySerializer(SIGNED_BYTE_ARRAY_SERIALIZER)
                        .valueSerializer(Serializer.JAVA).create();
    }


    @Override
    public void put(byte[] key, V value) {
        map.put(key, value);
    }

    public void putAll(final Map<byte[], V> data) {
        map.putAll(data);
    }

    @Override
    public V get(byte[] key) {
        return map.get(key);
    }

    @Override
    public Map<byte[], V> getAll() {
        return map;
    }

    @Override
    public <R> List<R> iterate(BiFunction<byte[], V, R> fn) {
        final List<R> returnList = new ArrayList<>();
        map.entryIterator().forEachRemaining(
                entry -> returnList.add(fn.apply(entry.getKey(), entry.getValue()))
        );
        return returnList;
    }

    public <R> List<R> iterate(BiFunction<byte[], V, R> fn, final int segments, final int index) {
        if(map.size() == 0) {
            return Collections.EMPTY_LIST;
        }
        final byte[][]iterationEndpoints = getIterationEndpoints(segments, index);
        final List<R> returnList = new ArrayList<>();
        map.entryIterator(iterationEndpoints[0], true, iterationEndpoints[1], false).forEachRemaining(
                entry -> returnList.add(fn.apply(entry.getKey(), entry.getValue()))
        );
        return returnList;
    }

    /**
     * Gets iteration endpoints by taking the lowest and highest key and splitting the keyrange into segments.
     * These endpoints are an approximation of segments, and segments are guaranteed to cover the entire key range,
     * but there is no guarantee that all segments contain an equal number of elements.
     * @param segments Number of segments
     * @param index Index to find segment endpoints for
     * @return byte[][] containing the two endpoints
     */
    private byte[][] getIterationEndpoints(final int segments, final int index) {
        final BigInteger lowEnd = new BigInteger(map.firstKey());
        final BigInteger highEnd = new BigInteger(map.lastKey());
        final BigInteger step = highEnd.subtract(lowEnd).divide(new BigInteger(String.valueOf(segments)));
        final byte[] lowIndex = lowEnd.add(step.multiply(new BigInteger(String.valueOf(index)))).toByteArray();
        final byte[] highIndex =
                index == segments - 1 ?
                        highEnd.add(new BigInteger("1")).toByteArray() :
                        lowEnd.add(step.multiply(new BigInteger(String.valueOf(index+1)))).toByteArray();
        return new byte[][]{lowIndex, highIndex};
    }

    @Override
    public long size() {
        return map.size();
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public void close() {
        map.close();
    }
}
