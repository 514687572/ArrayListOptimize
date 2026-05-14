package com.stip.net.Benchmark;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class EthereumDataFetcher {

    private static final String[] PUBLIC_RPCS = {
        "https://ethereum.publicnode.com",
        "https://rpc.ankr.com/eth",
        "https://eth.drpc.org",
        "https://1rpc.io/eth",
    };

    private static final String DATA_DIR = "real_blockchain_data";
    private static int currentRpcIndex = 0;

    public static class RealTransaction {
        public final String hash;
        public final long blockNumber;
        public final String from;
        public final String to;
        public final String value;
        public final long gasPrice;
        public final long timestamp;

        public RealTransaction(String hash, long blockNumber, String from, String to,
                               String value, long gasPrice, long timestamp) {
            this.hash = hash;
            this.blockNumber = blockNumber;
            this.from = from;
            this.to = to;
            this.value = value;
            this.gasPrice = gasPrice;
            this.timestamp = timestamp;
        }
    }

    public static void main(String[] args) throws Exception {
        int txCount = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        System.out.println("Fetching " + txCount + " real Ethereum transactions...");

        List<RealTransaction> txs = fetchAndParseBlocks(txCount);
        System.out.println("Fetched " + txs.size() + " transactions");

        Files.createDirectories(Paths.get(DATA_DIR));
        String filename = DATA_DIR + "/eth_transactions_" + txs.size() + ".json";
        saveTransactions(txs, filename);
        System.out.println("Saved to " + filename);
    }

    public static List<RealTransaction> fetchRealTransactions(int targetCount) throws Exception {
        List<RealTransaction> allTxs = new ArrayList<>();

        long latestBlock = rpcWithRetry("eth_blockNumber", new JSONArray(), -1);
        latestBlock = Long.parseUnsignedLong(String.valueOf(latestBlock).substring(2), 16);
        System.out.println("Latest block: " + latestBlock);

        long startBlock = latestBlock - 2000;
        long currentBlock = startBlock;
        int consecutiveErrors = 0;

        while (allTxs.size() < targetCount && currentBlock <= latestBlock) {
            try {
                JSONArray params = new JSONArray();
                params.add("0x" + Long.toHexString(currentBlock));
                params.add(true);

                long blockObj = rpcWithRetry("eth_getBlockByNumber", params, currentBlock);
                Thread.sleep(50);

                consecutiveErrors = 0;
            } catch (Exception e) {
                consecutiveErrors++;
                if (consecutiveErrors > 20) {
                    System.err.println("Too many consecutive errors, stopping.");
                    break;
                }
                if (e.getMessage() != null && (e.getMessage().contains("503") || e.getMessage().contains("429") || e.getMessage().contains("502"))) {
                    currentRpcIndex = (currentRpcIndex + 1) % PUBLIC_RPCS.length;
                    System.out.println("  Rate limited, switching to RPC: " + PUBLIC_RPCS[currentRpcIndex]);
                    Thread.sleep(2000);
                    continue;
                }
            }
            currentBlock++;
        }
        return allTxs;
    }

    private static long rpcWithRetry(String method, JSONArray params, long blockHint) throws Exception {
        for (int attempt = 0; attempt < PUBLIC_RPCS.length; attempt++) {
            String rpcUrl = PUBLIC_RPCS[(currentRpcIndex + attempt) % PUBLIC_RPCS.length];
            try {
                return rpcCall(rpcUrl, method, params);
            } catch (Exception e) {
                System.err.println("  RPC " + rpcUrl + " failed: " + e.getMessage());
            }
        }
        throw new RuntimeException("All RPCs failed for " + method);
    }

    public static List<RealTransaction> loadTransactions(String filename) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8);
        JSONArray arr = JSON.parseArray(content);
        List<RealTransaction> txs = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            txs.add(new RealTransaction(
                    obj.getString("hash"),
                    obj.getLongValue("blockNumber"),
                    obj.getString("from"),
                    obj.getString("to"),
                    obj.getString("value"),
                    obj.getLongValue("gasPrice"),
                    obj.getLongValue("timestamp")
            ));
        }
        return txs;
    }

    private static void saveTransactions(List<RealTransaction> txs, String filename) throws Exception {
        JSONArray arr = new JSONArray(txs.size());
        for (RealTransaction tx : txs) {
            JSONObject obj = new JSONObject();
            obj.put("hash", tx.hash);
            obj.put("blockNumber", tx.blockNumber);
            obj.put("from", tx.from);
            obj.put("to", tx.to);
            obj.put("value", tx.value);
            obj.put("gasPrice", tx.gasPrice);
            obj.put("timestamp", tx.timestamp);
            arr.add(obj);
        }
        Files.write(Paths.get(filename), arr.toJSONString().getBytes(StandardCharsets.UTF_8));
    }

    private static long rpcCall(String rpcUrl, String method, JSONArray params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", 1);

        HttpURLConnection conn = (HttpURLConnection) new URL(rpcUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("HTTP " + code + " from " + rpcUrl);
        }

        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            JSONObject resp = JSON.parseObject(sb.toString());
            Object result = resp.get("result");
            if (result == null) throw new RuntimeException("Null result from " + method);
            if (result instanceof JSONObject) return 0;
            return 0;
        } finally {
            conn.disconnect();
        }
    }

    public static List<RealTransaction> fetchAndParseBlocks(int targetCount) throws Exception {
        List<RealTransaction> allTxs = new ArrayList<>();
        int rpcIdx = 0;
        String rpcUrl = PUBLIC_RPCS[rpcIdx];

        System.out.println("Connecting to Ethereum mainnet via: " + rpcUrl);

        JSONObject resp = rawRpcCall(rpcUrl, "eth_blockNumber", new JSONArray());
        long latestBlock = Long.parseUnsignedLong(resp.getString("result").substring(2), 16);
        System.out.println("Latest block: " + latestBlock);

        long startBlock = latestBlock - 2000;
        long currentBlock = startBlock;
        int errors = 0;

        while (allTxs.size() < targetCount && currentBlock <= latestBlock) {
            try {
                JSONArray params = new JSONArray();
                params.add("0x" + Long.toHexString(currentBlock));
                params.add(true);

                JSONObject blockResp = rawRpcCall(rpcUrl, "eth_getBlockByNumber", params);
                Object result = blockResp.get("result");

                if (result instanceof JSONObject) {
                    JSONObject block = (JSONObject) result;
                    JSONArray txs = block.getJSONArray("transactions");
                    long timestamp = Long.parseLong(block.getString("timestamp").substring(2), 16);

                    if (txs != null) {
                        for (int i = 0; i < txs.size() && allTxs.size() < targetCount; i++) {
                            JSONObject tx = txs.getJSONObject(i);
                            String hash = tx.getString("hash");
                            String from = tx.getString("from");
                            String to = tx.getString("to");
                            String value = tx.getString("value");
                            long gasPrice = 0;
                            if (tx.containsKey("gasPrice") && tx.getString("gasPrice") != null) {
                                String gp = tx.getString("gasPrice");
                                if (gp.startsWith("0x") && gp.length() > 2) {
                                    try { gasPrice = Long.parseUnsignedLong(gp.substring(2), 16); } catch (NumberFormatException ignored) {}
                                }
                            }
                            allTxs.add(new RealTransaction(hash, currentBlock, from, to, value, gasPrice, timestamp));
                        }
                    }
                }

                errors = 0;
                if (allTxs.size() > 0 && allTxs.size() % 20000 < 200) {
                    System.out.println("  Block " + currentBlock + ": " + allTxs.size() + " txs collected");
                }
                Thread.sleep(50);

            } catch (Exception e) {
                errors++;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("503") || msg.contains("429") || msg.contains("502") || msg.contains("rate")) {
                    rpcIdx = (rpcIdx + 1) % PUBLIC_RPCS.length;
                    rpcUrl = PUBLIC_RPCS[rpcIdx];
                    System.out.println("  Switching RPC to: " + rpcUrl);
                    Thread.sleep(2000);
                } else if (errors > 30) {
                    System.err.println("Too many errors, stopping at block " + currentBlock);
                    break;
                }
            }
            currentBlock++;
        }
        return allTxs;
    }

    private static JSONObject rawRpcCall(String rpcUrl, String method, JSONArray params) throws Exception {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.put("params", params);
        request.put("id", 1);

        HttpURLConnection conn = (HttpURLConnection) new URL(rpcUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(request.toJSONString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }

        try (InputStream is = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return JSON.parseObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
