package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author chenjuan
 * @version 1.0
 * @data 2026/1/28 9:34
 */
@BenchmarkMode(Mode.Throughput) // 吞吐量模式
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS) // 预热
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS) // 正式测试
@Fork(2) // 派生进程数，增加统计严谨性
public class ListPerformanceTest {

    @Param({"10000", "100000", "1000000"})
    private int N;

    private List<Integer> arrayList;
    private BufferedArrayList<Integer> bufferedList;

    @Setup(Level.Trial)
    public void setup() {
        arrayList = new ArrayList<>();
        bufferedList = new BufferedArrayList<>();
        // 预填充数据以测试查询和中间插入
        for (int i = 0; i < N; i++) {
            int val = i;
            arrayList.add(val);
            bufferedList.add(val);
        }
    }

    @Benchmark
    public void testArrayListRandomInsert() {
        // 在中间位置插入，模拟 ArrayList 的最差场景
        arrayList.add(N / 2, 999);
    }

    @Benchmark
    public void testBufferedListRandomInsert() {
        // 利用 Gap Buffer 优化中间插入
        bufferedList.add(N / 2, 999);
    }

    @Benchmark
    public void testBufferedListBatchAppend() {
        // 测试你独特的 BatchScope 模式
        try (BufferedArrayList<Integer>.BatchScope batch = bufferedList.beginBatch()) {
            for (int i = 0; i < 1000; i++) {
                bufferedList.add(i);
            }
        }
    }

    @Benchmark
    public Integer testBufferedListRandomGet() {
        // 测试插值搜索和 FastIndexMap
        return bufferedList.get((int)(Math.random() * N));
    }
}
