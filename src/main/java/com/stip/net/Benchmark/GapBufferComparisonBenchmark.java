package com.stip.net.Benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@Warmup(iterations = 15, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 15, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(value = 7, jvmArgsAppend = {
    "-Xms4g", "-Xmx4g", "-XX:+UseG1GC",
    "-XX:MaxGCPauseMillis=200"
})
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
public class GapBufferComparisonBenchmark {

    @Param({"SIMPLE_GAP_BUFFER", "BUFFERED_ARRAY_LIST"})
    private String listType;

    @Param({"10000", "100000", "1000000"})
    private int N;

    private List<Integer> list;
    private Random random;

    @Setup(Level.Trial)
    public void setup() {
        random = new Random(42);
        list = createListInstance(listType, N);
        for (int i = 0; i < N; i++) {
            list.add(i);
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (list != null) { list.clear(); list = null; }
    }

    @Benchmark
    public void middleInsertion(Blackhole bh) {
        List<Integer> localList = createListInstance(listType, N);
        for (int i = 0; i < N; i++) {
            localList.add(i);
        }
        int operations = Math.min(10000, N / 10);
        int mid = localList.size() / 2;
        int variance = Math.max(1, localList.size() / 10);
        for (int i = 0; i < operations; i++) {
            int pos = mid + random.nextInt(variance) - variance / 2;
            pos = Math.max(0, Math.min(pos, localList.size()));
            localList.add(pos, i);
        }
        bh.consume(localList.size());
    }

    @Benchmark
    public void alternatingInsertion(Blackhole bh) {
        List<Integer> localList = createListInstance(listType, N);
        for (int i = 0; i < N; i++) {
            localList.add(i);
        }
        int operations = Math.min(10000, N / 10);
        for (int i = 0; i < operations; i++) {
            if (i % 2 == 0) {
                localList.add(0, i);
            } else {
                localList.add(localList.size() / 2, i);
            }
        }
        bh.consume(localList.size());
    }

    @Benchmark
    public void randomDeletion(Blackhole bh) {
        List<Integer> localList = createListInstance(listType, N);
        for (int i = 0; i < N; i++) {
            localList.add(i);
        }
        int operations = Math.min(N / 10, 10000);
        for (int i = 0; i < operations && !localList.isEmpty(); i++) {
            int pos = random.nextInt(localList.size());
            localList.remove(pos);
        }
        bh.consume(localList.size());
    }

    @Benchmark
    public void mixedWorkload(Blackhole bh) {
        List<Integer> localList = createListInstance(listType, N / 2);
        for (int i = 0; i < N / 2; i++) {
            localList.add(i);
        }
        int operations = Math.min(N / 5, 10000);
        for (int i = 0; i < operations; i++) {
            int op = i % 3;
            if (localList.isEmpty()) continue;
            switch (op) {
                case 0: localList.add(random.nextInt(localList.size() + 1), i); break;
                case 1: localList.remove(random.nextInt(localList.size())); break;
                case 2: bh.consume(localList.get(random.nextInt(localList.size()))); break;
            }
        }
        bh.consume(localList.size());
    }

    private List<Integer> createListInstance(String type, int initialCapacity) {
        switch (type) {
            case "SIMPLE_GAP_BUFFER":
                return new com.stip.net.optimized.SimpleGapBuffer<>(initialCapacity);
            case "BUFFERED_ARRAY_LIST":
                return new com.stip.net.optimized.BufferedArrayList<>(initialCapacity);
            default:
                throw new IllegalArgumentException("Unknown list type: " + type);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(GapBufferComparisonBenchmark.class.getSimpleName())
                .resultFormat(ResultFormatType.JSON)
                .result("gap-buffer-comparison-" + System.currentTimeMillis() + ".json")
                .shouldFailOnError(true)
                .build();
        new Runner(opt).run();
    }
}
