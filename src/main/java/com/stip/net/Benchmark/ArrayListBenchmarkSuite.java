/*
 * Copyright (c) 2023. STIP and/or its affiliates.
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
 * 综合基准测试套件 - 多种List实现性能对比
 *
 * 测试的数据结构：
 * - ArrayList          标准连续数组实现
 * - BufferedArrayList  分块 + Gap Buffer 优化（针对中间频繁插入/删除）
 * - LinkedList         双向链表
 * - Vector             线程安全的 ArrayList（synchronized）
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Threads(4)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ArrayListBenchmarkSuite {

    /**
     * 列表实现类型
     */
    @Param({"ArrayList", "BufferedArrayList"})
    private String listType;

    /**
     * 测试数据规模
     * 注意：较大规模下 LinkedList 可能非常慢或耗内存较多
     */
    @Param({"10000", "100000", "1000000", "10000000"})
    private int size;

    /**
     * 用于随机操作的随机数生成器（固定种子保证可重复）
     */
    private static final Random RANDOM = new Random(42);

    /**
     * 当前测试使用的列表（预填充好的）
     */
    private List<Integer> list;

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ArrayListBenchmarkSuite.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result("list-benchmark-results2026-01-21.json")
                .jvmArgs("-XX:+UseParallelGC")
                .build();
        new Runner(opt).run();
    }

    /**
     * 每次迭代前重新初始化并预填充列表
     */
    @Setup(Level.Iteration)
    public void setup() {
        switch (listType) {
            case "ArrayList":
                list = new ArrayList<>(size);
                break;
            case "BufferedArrayList":
                list = new BufferedArrayList<>(size);
                break;
            case "LinkedList":
                list = new LinkedList<>();
                break;
            case "Vector":
                list = new Vector<>(size);
                break;
            default:
                throw new IllegalArgumentException("未知的列表类型: " + listType);
        }

        // 预填充（除了 testAppend 外的大多数场景使用）
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
    }

    /**
     * 创建一个新的空列表（主要给 testAppend 使用）
     */
    private List<Integer> createEmptyList() {
        switch (listType) {
            case "ArrayList":
                return new ArrayList<>(size);
            case "BufferedArrayList":
                return new BufferedArrayList<>(size);
            case "LinkedList":
                return new LinkedList<>();
            case "Vector":
                return new Vector<>(size);
            default:
                throw new IllegalArgumentException("未知的列表类型: " + listType);
        }
    }

    @Benchmark
    public void testAppend(Blackhole bh) {
        List<Integer> appendList = createEmptyList();
        for (int i = 0; i < size; i++) {
            appendList.add(i);
        }
        bh.consume(appendList);
    }

    @Benchmark
    public void testInsertMiddle(Blackhole bh) {
        int operations = Math.min(size / 5, 10000);
        for (int i = 0; i < operations; i++) {
            list.add(list.size() / 2, i);
        }
        bh.consume(list);
    }

    @Benchmark
    public void testRandomInsert(Blackhole bh) {
        int operations = Math.min(size / 5, 10000);
        for (int i = 0; i < operations; i++) {
            int randomPos = RANDOM.nextInt(list.size() + 1);
            list.add(randomPos, i);
        }
        bh.consume(list);
    }

    @Benchmark
    public void testRandomRemove(Blackhole bh) {
        int removalCount = Math.min(size / 2, 10000);
        for (int i = 0; i < removalCount && !list.isEmpty(); i++) {
            int randomPos = RANDOM.nextInt(list.size());
            list.remove(randomPos);
        }
        bh.consume(list);
    }

    @Benchmark
    public void testRandomAccess(Blackhole bh) {
        int accessCount = Math.min(size * 10, 1_000_000);
        for (int i = 0; i < accessCount; i++) {
            int randomPos = RANDOM.nextInt(list.size());
            bh.consume(list.get(randomPos));
        }
    }

    @Benchmark
    public void testSequentialAccess(Blackhole bh) {
        for (Integer value : list) {
            bh.consume(value);
        }
    }

    @Benchmark
    public void testMixedOperations(Blackhole bh) {
        int operationCount = Math.min(size, 10000);
        for (int i = 0; i < operationCount; i++) {
            int op = i % 3;
            switch (op) {
                case 0: // insert
                    int pos = RANDOM.nextInt(list.size() + 1);
                    list.add(pos, i);
                    break;
                case 1: // remove
                    if (!list.isEmpty()) {
                        pos = RANDOM.nextInt(list.size());
                        list.remove(pos);
                    }
                    break;
                case 2: // get
                    if (!list.isEmpty()) {
                        pos = RANDOM.nextInt(list.size());
                        bh.consume(list.get(pos));
                    }
                    break;
            }
        }
        bh.consume(list);
    }

    @Benchmark
    public void testBulkOperations(Blackhole bh) {
        list.clear();
        for (int i = 0; i < size; i++) {
            list.add(RANDOM.nextInt(size * 10));
        }
        Collections.sort(list);
        bh.consume(list);
        Collections.shuffle(list, RANDOM);
        bh.consume(list);
    }

    @Benchmark
    public void testMiddleSectionModification(Blackhole bh) {
        int start = size / 4;
        int end = 3 * size / 4;
        int ops = Math.min((end - start) / 10, 1000);

        for (int i = 0; i < ops; i++) {
            int pos = start + RANDOM.nextInt(end - start);
            list.add(pos, i * 100);

            if (i % 2 == 0 && !list.isEmpty()) {
                pos = start + RANDOM.nextInt(end - start);
                if (pos < list.size()) {
                    list.remove(pos);
                }
            }
        }
        bh.consume(list);
    }
}