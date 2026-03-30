/*
 * Copyright (c) 2023-2026. STIP and/or its affiliates.
 */

package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SCI 论文级基准测试套件 - 通用 List 实现综合性能对比
 *
 * 对比对象（仅选择公平可比的通用 Object 列表）：
 *  - ArrayList              标准实现（baseline）
 *  - BufferedArrayList      分块 + Gap Buffer + 插值搜索 + 批量优化（本文核心）
 *  - LinkedList             经典双向链表（插入/删除参考）
 *  - CopyOnWriteArrayList   写时复制（读多写少场景参考）
 *  - Vector                 同步 ArrayList（历史兼容性参考）
 *
 * 剔除专用集合（如 fastutil、HPPC）以保证公平性和可比性。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseParallelGC", "-Xms2g", "-Xmx8g"})
@Threads(4)
@Warmup(iterations = 8, time = 2)
@Measurement(iterations = 6, time = 3)
public class ListPerformanceBenchmark {

    // ---------------- 参数 ----------------

    @Param({"ArrayList", "BufferedArrayList", "LinkedList", "Vector"})
    private String listType;

    @Param({"10000", "100000", "1000000", "5000000"})
    private int initialSize;

    // 操作次数控制（避免超大规模时单次 benchmark 过长）
    private static final int FIXED_OPS_SMALL  = 5000;
    private static final int FIXED_OPS_MEDIUM = 10000;
    private static final int FIXED_OPS_LARGE  = 20000;

    private static final Random RANDOM = new Random(42L);

    private List<Integer> list;

    @Setup(Level.Iteration)
    public void setup() {
        list = createListInstance();
        int fillSize = Math.min(initialSize, 5_000_000); // 防止 OOM
        for (int i = 0; i < fillSize; i++) {
            list.add(i);
        }
    }

    private List<Integer> createListInstance() {
        switch (listType) {
            case "ArrayList":             return new ArrayList<>(initialSize);
            case "BufferedArrayList":     return new BufferedArrayList<>(initialSize);
            case "LinkedList":            return new LinkedList<>();
            case "Vector":                return new Vector<>(initialSize);
            default:
                throw new IllegalArgumentException("Unknown list type: " + listType);
        }
    }

    private int getOpCount() {
        if (initialSize <= 100_000)    return FIXED_OPS_SMALL;
        if (initialSize <= 1_000_000)  return FIXED_OPS_MEDIUM;
        return FIXED_OPS_LARGE;
    }

    // ──────────────────────────────────────────────
    // 1. 追加性能（从空列表开始）
    // ──────────────────────────────────────────────
    @Benchmark
    public void appendOnly(Blackhole bh) {
        List<Integer> temp = createListInstance();
        for (int i = 0; i < initialSize; i++) {
            temp.add(i);
        }
        bh.consume(temp);
    }

    // ──────────────────────────────────────────────
    // 2. 中间连续插入（热点区域）
    // ──────────────────────────────────────────────
    @Benchmark
    public void insertMiddleRegion(Blackhole bh) {
        int ops = getOpCount();
        int mid = list.size() / 2;
        for (int i = 0; i < ops; i++) {
            list.add(mid + (i & 127), i);  // 小范围扰动，避免极端偏移
        }
        bh.consume(list);
    }

    // ──────────────────────────────────────────────
    // 3. 随机位置插入
    // ──────────────────────────────────────────────
    @Benchmark
    public void randomInsert(Blackhole bh) {
        int ops = getOpCount();
        for (int i = 0; i < ops; i++) {
            int pos = RANDOM.nextInt(list.size() + 1);
            list.add(pos, i);
        }
        bh.consume(list);
    }

    // ──────────────────────────────────────────────
    // 4. 随机位置删除
    // ──────────────────────────────────────────────
    @Benchmark
    public void randomRemove(Blackhole bh) {
        int ops = Math.min(getOpCount(), list.size() / 3);
        for (int i = 0; i < ops && !list.isEmpty(); i++) {
            int pos = RANDOM.nextInt(list.size());
            list.remove(pos);
        }
        bh.consume(list);
    }

    // ──────────────────────────────────────────────
    // 5. 随机访问（读密集）
    // ──────────────────────────────────────────────
    @Benchmark
    public void randomGet(Blackhole bh) {
        long ops = Math.min(initialSize * 20L, 2_000_000L);
        for (int i = 0; i < ops; i++) {
            int pos = RANDOM.nextInt(list.size());
            bh.consume(list.get(pos));
        }
    }

    // ──────────────────────────────────────────────
    // 6. 顺序遍历（迭代器/foreach）
    // ──────────────────────────────────────────────
    @Benchmark
    public void sequentialTraversal(Blackhole bh) {
        for (Integer v : list) {
            bh.consume(v);
        }
    }

    // ──────────────────────────────────────────────
    // 7. 混合读写（最贴近真实场景）
    // ──────────────────────────────────────────────
    @Benchmark
    public void mixedReadWrite(Blackhole bh) {
        int ops = getOpCount();
        for (int i = 0; i < ops; i++) {
            int r = RANDOM.nextInt(100);
            if (r < 40) {        // 40% 随机插入
                int pos = RANDOM.nextInt(list.size() + 1);
                list.add(pos, i);
            } else if (r < 55) { // 15% 随机删除
                if (!list.isEmpty()) {
                    int pos = RANDOM.nextInt(list.size());
                    list.remove(pos);
                }
            } else {             // 45% 随机读
                if (!list.isEmpty()) {
                    int pos = RANDOM.nextInt(list.size());
                    bh.consume(list.get(pos));
                }
            }
        }
        bh.consume(list);
    }

    // ──────────────────────────────────────────────
    // 8. 批量添加 + 排序 + 打乱（常见 ETL 场景）
    // ──────────────────────────────────────────────
    @Benchmark
    public void bulkAddSortShuffle(Blackhole bh) {
        list.clear();
        int n = Math.min(initialSize, 1_000_000);
        for (int i = 0; i < n; i++) {
            list.add(RANDOM.nextInt(n * 10));
        }
        if (list instanceof ArrayList || list instanceof BufferedArrayList) {
            Collections.sort(list);
            Collections.shuffle(list, RANDOM);
        }
        bh.consume(list);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(com.stip.net.Benchmark.PerformanceBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result("list-benchmark-2026-01-full.json")
                .build();
        new Runner(opt).run();
    }
}