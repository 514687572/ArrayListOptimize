package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms4g", "-Xmx4g", "-XX:+UseG1GC"})
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 5, time = 3)
@Threads(1)
@State(Scope.Benchmark)
public class ChunkSizeSensitivityBenchmark {

    @Param({"1024", "2048", "4096", "8192", "16384"})
    int chunkSize;

    @Param({"1000000"})
    int N;

    @Benchmark
    public void middleInsertion(Blackhole bh) {
        List<Integer> list = new BufferedArrayList<>(N / 2, chunkSize);
        for (int i = 0; i < N; i++) {
            list.add(list.size() / 2, i);
        }
        bh.consume(list.size());
    }

    @Benchmark
    public void randomDeletion(Blackhole bh) {
        List<Integer> list = new BufferedArrayList<>(N, chunkSize);
        for (int i = 0; i < N; i++) list.add(i);
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < N / 2; i++) {
            int idx = rng.nextInt(list.size());
            list.remove(idx);
        }
        bh.consume(list.size());
    }

    @Benchmark
    public void mixedWorkload(Blackhole bh) {
        List<Integer> list = new BufferedArrayList<>(N / 2, chunkSize);
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < N; i++) {
            int op = rng.nextInt(3);
            if (op == 0 || list.isEmpty()) {
                list.add(rng.nextInt(list.size() + 1), i);
            } else if (op == 1) {
                list.remove(rng.nextInt(list.size()));
            } else {
                bh.consume(list.get(rng.nextInt(list.size())));
            }
        }
        bh.consume(list.size());
    }
}
