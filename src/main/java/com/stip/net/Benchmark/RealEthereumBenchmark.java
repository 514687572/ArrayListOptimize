package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import org.apache.commons.collections4.list.TreeList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 10, time = 5)
@Fork(value = 3, jvmArgs = {"-Xmx4g", "-Xms2g"})
@State(Scope.Benchmark)
public class RealEthereumBenchmark {

    @Param({"ARRAY_LIST", "TREE_LIST", "BUFFERED_ARRAY_LIST"})
    private String listType;

    private List<EthereumDataFetcher.RealTransaction> realTransactions;
    private int outOfOrderCount;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String dataFile = "real_blockchain_data/eth_transactions_100000.json";
        File f = new File(dataFile);

        if (f.exists()) {
            System.out.println("Loading real Ethereum data from " + dataFile);
            realTransactions = EthereumDataFetcher.loadTransactions(dataFile);
        } else {
            System.out.println("Real data file not found. Fetching from Ethereum mainnet...");
            realTransactions = EthereumDataFetcher.fetchRealTransactions(100000);
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get("real_blockchain_data"));
            EthereumDataFetcher.main(new String[]{"100000"});
            realTransactions = EthereumDataFetcher.loadTransactions(dataFile);
        }

        outOfOrderCount = countOutOfOrder(realTransactions);
        System.out.println("Loaded " + realTransactions.size() + " real transactions");
        System.out.println("Out-of-order count: " + outOfOrderCount + " (" +
                String.format("%.1f%%", 100.0 * outOfOrderCount / realTransactions.size()) + ")");
    }

    @Benchmark
    public void transactionOrderByTimestamp(Blackhole bh) {
        List<EthereumDataFetcher.RealTransaction> ordered = createList();
        for (EthereumDataFetcher.RealTransaction tx : realTransactions) {
            int pos = binarySearchByTimestamp(ordered, tx);
            ordered.add(pos, tx);
        }
        bh.consume(ordered.size());
    }

    @Benchmark
    public void transactionOrderByGasPrice(Blackhole bh) {
        List<EthereumDataFetcher.RealTransaction> ordered = createList();
        for (EthereumDataFetcher.RealTransaction tx : realTransactions) {
            int pos = binarySearchByGasPrice(ordered, tx);
            ordered.add(pos, tx);
        }
        bh.consume(ordered.size());
    }

    @Benchmark
    public void mempoolSimulation(Blackhole bh) {
        List<EthereumDataFetcher.RealTransaction> mempool = createList();
        Random random = new Random(42);
        int ops = Math.min(realTransactions.size(), 10000);

        for (int i = 0; i < ops; i++) {
            EthereumDataFetcher.RealTransaction tx = realTransactions.get(i);
            double action = random.nextDouble();
            if (action < 0.7) {
                int pos = binarySearchByGasPrice(mempool, tx);
                mempool.add(pos, tx);
            } else if (action < 0.9 && !mempool.isEmpty()) {
                mempool.remove(0);
            } else if (!mempool.isEmpty()) {
                mempool.remove(random.nextInt(mempool.size()));
            }
        }
        bh.consume(mempool.size());
    }

    @Benchmark
    public void chainReorgSimulation(Blackhole bh) {
        List<EthereumDataFetcher.RealTransaction> chain = createList();
        Random random = new Random(123);
        int mainSize = realTransactions.size() * 80 / 100;

        for (int i = 0; i < mainSize; i++) {
            chain.add(realTransactions.get(i));
        }

        for (int i = mainSize; i < realTransactions.size(); i++) {
            if (random.nextDouble() < 0.1 && chain.size() > 10) {
                for (int j = 0; j < 5 && !chain.isEmpty(); j++) {
                    chain.remove(chain.size() - 1);
                }
            }
            chain.add(realTransactions.get(i));
        }
        bh.consume(chain.size());
    }

    private int countOutOfOrder(List<EthereumDataFetcher.RealTransaction> txs) {
        int count = 0;
        for (int i = 1; i < txs.size(); i++) {
            if (txs.get(i).timestamp < txs.get(i - 1).timestamp) count++;
        }
        return count;
    }

    private int binarySearchByTimestamp(List<EthereumDataFetcher.RealTransaction> list,
                                         EthereumDataFetcher.RealTransaction tx) {
        int low = 0, high = list.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            EthereumDataFetcher.RealTransaction midTx = list.get(mid);
            if (midTx == null) {
                high = mid;
                continue;
            }
            if (midTx.timestamp < tx.timestamp) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private int binarySearchByGasPrice(List<EthereumDataFetcher.RealTransaction> list,
                                        EthereumDataFetcher.RealTransaction tx) {
        int low = 0, high = list.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            EthereumDataFetcher.RealTransaction midTx = list.get(mid);
            if (midTx == null) {
                high = mid;
                continue;
            }
            if (midTx.gasPrice > tx.gasPrice) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> createList() {
        switch (listType) {
            case "ARRAY_LIST": return new ArrayList<>();
            case "TREE_LIST": return new TreeList<>();
            case "BUFFERED_ARRAY_LIST": return new BufferedArrayList<>();
            default: throw new IllegalArgumentException("Unknown: " + listType);
        }
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
