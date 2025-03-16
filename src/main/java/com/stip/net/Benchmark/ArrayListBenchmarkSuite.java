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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 综合基准测试套件
 * 测试各种列表实现在不同操作场景下的性能表现
 * 生成详细的JSON格式结果，可用于进一步分析和可视化
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ArrayListBenchmarkSuite {

    /**
     * 列表实现类型
     */
    @Param({"ArrayList", "BufferedArrayList"})
    private String listType;

    /**
     * 测试数据规模
     */
    @Param({"10000","100000","1000000"})
    private int size;

    /**
     * 用于随机操作的随机数生成器
     */
    private static final Random RANDOM = new Random(42);

    /**
     * 当前测试使用的列表
     */
    private List<Integer> list;

    /**
     * 基准测试的主入口
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ArrayListBenchmarkSuite.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result("array-list-benchmark-results.json")
                .build();
        new Runner(opt).run();
    }

    /**
     * 在每个基准测试之前初始化列表
     */
    @Setup
    public void setup() {
        switch (listType) {
            case "ArrayList":
                list = new ArrayList<>(size);
                break;
            case "BufferedArrayList":
                list = new BufferedArrayList<>(size);
                break;
            default:
                throw new IllegalArgumentException("未知的列表类型: " + listType);
        }
    }

    /**
     * 测试添加到列表末尾的性能（append操作）
     */
    @Benchmark
    public void testAppend(Blackhole bh) {
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        bh.consume(list);
    }

    /**
     * 测试中间插入的性能
     */
    @Benchmark
    public void testInsertMiddle(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size / 2; i++) {
            list.add(i);
        }
        
        // 然后在中间位置插入元素
        int operations = Math.min(size / 5, 10000);
        for (int i = 0; i < operations; i++) {
            list.add(list.size() / 2, i);
        }
        bh.consume(list);
    }

    /**
     * 测试随机位置插入的性能
     */
    @Benchmark
    public void testRandomInsert(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size / 2; i++) {
            list.add(i);
        }
        
        // 然后在随机位置插入元素
        int operations = Math.min(size / 5, 10000);
        for (int i = 0; i < operations; i++) {
            int randomPos = RANDOM.nextInt(list.size() + 1);
            list.add(randomPos, i);
        }
        bh.consume(list);
    }

    /**
     * 测试随机位置删除的性能
     */
    @Benchmark
    public void testRandomRemove(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        
        // 然后随机删除元素
        int removalCount = Math.min(size / 2, 10000); // 限制删除次数
        for (int i = 0; i < removalCount && !list.isEmpty(); i++) {
            int randomPos = RANDOM.nextInt(list.size());
            list.remove(randomPos);
        }
        bh.consume(list);
    }

    /**
     * 测试获取（随机访问）的性能
     */
    @Benchmark
    public void testRandomAccess(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        
        // 然后随机访问元素
        int accessCount = Math.min(size * 10, 1000000);
        for (int i = 0; i < accessCount; i++) {
            int randomPos = RANDOM.nextInt(list.size());
            bh.consume(list.get(randomPos));
        }
    }

    /**
     * 测试顺序访问（迭代）的性能
     */
    @Benchmark
    public void testSequentialAccess(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        
        // 然后顺序访问元素
        for (Integer value : list) {
            bh.consume(value);
        }
    }

    /**
     * 测试混合操作的性能（插入、删除、获取的组合）
     */
    @Benchmark
    public void testMixedOperations(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        
        // 执行混合操作
        int operationCount = Math.min(size, 10000);
        for (int i = 0; i < operationCount; i++) {
            int operationType = i % 3; // 0: 插入, 1: 删除, 2: 获取
            switch (operationType) {
                case 0: // 插入
                    int insertPos = RANDOM.nextInt(list.size() + 1);
                    list.add(insertPos, i);
                    break;
                case 1: // 删除
                    if (!list.isEmpty()) {
                        int removePos = RANDOM.nextInt(list.size());
                        list.remove(removePos);
                    }
                    break;
                case 2: // 获取
                    if (!list.isEmpty()) {
                        int getPos = RANDOM.nextInt(list.size());
                        bh.consume(list.get(getPos));
                    }
                    break;
            }
        }
        bh.consume(list);
    }

    /**
     * 测试批量操作的性能（排序、洗牌）
     */
    @Benchmark
    public void testBulkOperations(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(RANDOM.nextInt(size * 10)); // 添加随机值
        }
        
        // 排序
        Collections.sort(list);
        bh.consume(list);
        
        // 洗牌
        Collections.shuffle(list, RANDOM);
        bh.consume(list);
    }

    /**
     * 测试中间区域的批量修改（重点测试需要大量元素移动的场景）
     */
    @Benchmark
    public void testMiddleSectionModification(Blackhole bh) {
        // 首先填充列表
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        
        // 中间部分执行批量修改
        int start = size / 4;
        int end = 3 * size / 4;
        int operations = Math.min((end - start) / 10, 1000);
        
        // 在中间部分进行插入和删除
        for (int i = 0; i < operations; i++) {
            int pos = start + RANDOM.nextInt(end - start);
            list.add(pos, i * 100); // 插入
            
            if (i % 2 == 0 && !list.isEmpty()) {
                pos = start + RANDOM.nextInt(end - start);
                if (pos < list.size()) {
                    list.remove(pos); // 删除
                }
            }
        }
        
        bh.consume(list);
    }
} 