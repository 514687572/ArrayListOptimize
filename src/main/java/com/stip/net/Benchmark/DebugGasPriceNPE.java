package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import java.util.*;

public class DebugGasPriceNPE {
    public static void main(String[] args) throws Exception {
        List<EthereumDataFetcher.RealTransaction> realTransactions =
                EthereumDataFetcher.loadTransactions("real_blockchain_data/eth_transactions_100000.json");
        System.out.println("Loaded " + realTransactions.size() + " transactions");

        List<EthereumDataFetcher.RealTransaction> ordered = new BufferedArrayList<>();

        int nullCount = 0;
        for (int i = 0; i < realTransactions.size(); i++) {
            EthereumDataFetcher.RealTransaction tx = realTransactions.get(i);
            int pos = binarySearchByGasPrice(ordered, tx);
            ordered.add(pos, tx);

            if (i > 0 && i % 10000 == 0) {
                System.out.println("  " + i + " inserted, checking for nulls...");
                for (int j = 0; j < ordered.size(); j++) {
                    if (ordered.get(j) == null) {
                        System.out.println("  NULL at index " + j + " after inserting " + i + " items");
                        nullCount++;
                        if (nullCount > 5) {
                            System.out.println("Too many nulls, stopping");
                            return;
                        }
                    }
                }
            }
        }
        System.out.println("Done. Total nulls found during checks: " + nullCount);
        System.out.println("Final size: " + ordered.size());
    }

    private static int binarySearchByGasPrice(List<EthereumDataFetcher.RealTransaction> list,
                                               EthereumDataFetcher.RealTransaction tx) {
        int low = 0, high = list.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            EthereumDataFetcher.RealTransaction midTx = list.get(mid);
            if (midTx == null) {
                System.out.println("  NULL at mid=" + mid + " low=" + low + " high=" + high + " list.size=" + list.size());
                high = mid;
                continue;
            }
            if (midTx.gasPrice > tx.gasPrice) low = mid + 1;
            else high = mid;
        }
        return low;
    }
}
