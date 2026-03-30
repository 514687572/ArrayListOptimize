/*
 * 优化的List性能基准测试套件（改造版）
 * 符合SCI论文发表标准的基准测试设计
 *
 * 改造原则：
 * 1. 增强可重复性：所有随机元素预先生成，使用固定种子。
 * 2. 提升公平性：统一操作方式，确保所有List实现相同测试条件。
 * 3. 增加代表性：调整操作次数与size成比例，覆盖更多场景。
 * 4. 统计严谨性：增加forks、iterations，使用CI（置信区间）输出。
 * 5. 内存测量改进：使用JMH内置GC profiler和JOL进行精确内存评估。
 * 6. 输出格式：JSON + CSV，便于论文数据分析和图表生成。
 * 7. 额外基准：分离头/中/尾插入/删除，增强比较深度。
 */
package com.stip.net.Benchmark;

import com.stip.net.optimized.BufferedArrayList;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jol.info.GraphLayout;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 优化的List基准测试套件（改造版）
 *
 * 设计原则（符合SCI标准）：
 * 1. 隔离性：每个benchmark方法独立，@State(Scope.Thread)以避免干扰。
 * 2. 公平性：所有List使用相同数据集、预分配容量（若适用）。
 * 3. 代表性：覆盖真实场景，操作次数与size成比例。
 * 4. 可重复性：固定种子，所有随机预生成，结果可复现。
 * 5. 统计可靠性：增加forks=3，warmup/measurement iterations=10，输出CI 95%。
 * 6. 内存评估：使用JOL精确计算对象图布局。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread) // 每个线程独立状态，避免并发干扰
@Fork(value = 3, jvmArgs = {
        "-server",
        "-XX:+UseG1GC",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+PrintAssembly",  // 打印热点代码的汇编
        "-XX:+LogCompilation",
        "-XX:+PrintGCDetails",
        "-XX:+PrintGCTimeStamps",
        "-XX:+PrintGCDateStamps",
        "-Xloggc:target/gc-%t.log",
        "-Xms4g",
        "-Xmx4g",
        "-XX:MaxGCPauseMillis=50"
})
@Threads(1)
@Warmup(iterations = 5, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Timeout(time = 20, timeUnit = TimeUnit.MINUTES)
public class PerformanceBenchmark {

    // 基准参数配置
    @Param({"1000", "10000", "100000", "1000000"})
    private int size;

    @Param({
            "ArrayList",
            "BufferedArrayList",
            "LinkedList",
            "Vector"
    })
    private String listType;

    // 测试数据集（预先生成）
    private int[] testData;
    private int[] randomIndices;
    private int[] insertionPositions;
    private int[] removalPositions;
    private int[] searchValues;

    // 随机数生成器（固定种子）
    private static final Random RANDOM = new Random(42L);

    @Setup(Level.Trial)
    public void trialSetup() {
        // 生成测试数据
        testData = new int[size];
        for (int i = 0; i < size; i++) {
            testData[i] = RANDOM.nextInt(size * 10);
        }

        // 生成随机索引（用于访问/搜索），添加上限防止OOM
        int maxOps = (int) Math.min((long) size * 10, 5_000_000L); // 操作次数上限
        randomIndices = new int[maxOps];
        insertionPositions = new int[maxOps];
        removalPositions = new int[maxOps];
        searchValues = new int[maxOps];

        for (int i = 0; i < maxOps; i++) {
            randomIndices[i] = RANDOM.nextInt(size);
            insertionPositions[i] = RANDOM.nextInt(size + 1); // +1 for add at end
            removalPositions[i] = RANDOM.nextInt(size);
            searchValues[i] = testData[RANDOM.nextInt(size)];
        }
    }

    // 创建List实例的工厂方法（统一预分配容量）
    private List<Integer> createList(int initialCapacity) {
        switch (listType) {
            case "ArrayList":
                return new ArrayList<>(initialCapacity);
            case "BufferedArrayList":
                return new BufferedArrayList<>(initialCapacity);
            case "LinkedList":
                return new LinkedList<>(); // 无容量预分配，但公平对待
            case "Vector":
                return new Vector<>(initialCapacity);
            default:
                throw new IllegalArgumentException("Unknown list type: " + listType);
        }
    }

    // -----------------------------------------------------------------
    // 基准测试方法（每个方法独立）
    // -----------------------------------------------------------------

    /**
     * 基准1: 从空列表开始顺序添加
     * 场景：初始化填充数据
     */
    @Benchmark
    public void sequentialAdd(Blackhole bh) {
        List<Integer> list = createList(size);
        for (int i = 0; i < size; i++) {
            list.add(testData[i]);
        }
        bh.consume(list);
    }

    /**
     * 基准2: 从空列表开始批量添加
     * 场景：批量数据导入（统一使用addAll）
     */
    @Benchmark
    public void bulkAdd(Blackhole bh) {
        List<Integer> list = createList(size);
        List<Integer> tempList = Arrays.stream(testData).boxed().collect(Collectors.toList());
        list.addAll(tempList);
        bh.consume(list);
    }

    /**
     * 基准3: 随机访问（读密集型）
     * 场景：缓存查找、随机抽样（操作次数与size成比例）
     */
    @Benchmark
    public void randomAccess(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        int accessCount = size * 10; // 与size成比例
        for (int i = 0; i < accessCount; i++) {
            int idx = randomIndices[i % randomIndices.length];
            bh.consume(list.get(idx % size));
        }
    }

    /**
     * 基准4: 顺序遍历
     * 场景：数据处理、序列化
     */
    @Benchmark
    public void sequentialTraversal(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        for (Integer value : list) {
            bh.consume(value);
        }
    }

    /**
     * 基准5: 随机位置插入
     * 场景：动态更新、中间插入（使用预生成位置）
     */
    @Benchmark
    public void randomInsert(Blackhole bh) {
        List<Integer> list = createList(size);
        int initialSize = size * 2 / 3;
        for (int i = 0; i < initialSize; i++) {
            list.add(testData[i]);
        }
        int insertCount = size / 10;
        for (int i = 0; i < insertCount; i++) {
            int pos = insertionPositions[i] % (list.size() + 1);
            list.add(pos, testData[(initialSize + i) % size]);
        }
        bh.consume(list);
    }

    /**
     * 基准6: 随机位置删除
     * 场景：数据清理、元素移除（使用预生成位置）
     */
    @Benchmark
    public void randomRemove(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        int removeCount = size / 20;
        for (int i = 0; i < removeCount; i++) {
            if (list.isEmpty()) break;
            int pos = removalPositions[i] % list.size();
            bh.consume(list.remove(pos));
        }
        bh.consume(list);
    }

    /**
     * 基准7: 查找元素
     * 场景：搜索、存在性检查（操作次数与size成比例）
     */
    @Benchmark
    public void containsSearch(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        int searchCount = size;
        for (int i = 0; i < searchCount; i++) {
            int searchValue = searchValues[i % searchValues.length];
            bh.consume(list.contains(searchValue));
        }
    }

    /**
     * 基准8: 排序性能
     * 场景：数据排序、预处理（无size限制）
     */
    @Benchmark
    public void sortPerformance(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        List<Integer> copy = new ArrayList<>(list); // 统一使用ArrayList副本排序
        Collections.sort(copy);
        bh.consume(copy);
    }

    /**
     * 基准9: 批量删除
     * 场景：批量清理、范围删除
     */
    @Benchmark
    public void bulkRemove(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        int removeFrom = size / 2;
        if (removeFrom < list.size()) {
            list.subList(removeFrom, list.size()).clear();
        }
        bh.consume(list);
    }

    /**
     * 基准10: 混合操作（真实场景模拟）
     * 场景：典型CRUD操作混合（使用预生成操作序列）
     */
    @Benchmark
    public void mixedOperations(Blackhole bh) {
        List<Integer> list = createList(size);
        int operationCount = size * 2;
        for (int i = 0; i < operationCount; i++) {
            int operation = randomIndices[i] % 100; // 复用randomIndices作为操作选择
            if (operation < 40) {
                list.add(testData[i % size]);
            } else if (operation < 70) {
                if (!list.isEmpty()) {
                    int idx = randomIndices[i] % list.size();
                    bh.consume(list.get(idx));
                }
            } else if (operation < 90) {
                if (!list.isEmpty()) {
                    int pos = insertionPositions[i] % list.size();
                    list.add(pos, testData[i % size]);
                }
            } else {
                if (!list.isEmpty()) {
                    int pos = removalPositions[i] % list.size();
                    bh.consume(list.remove(pos));
                }
            }
        }
        bh.consume(list);
    }

    /**
     * 基准11: 内存使用效率
     * 场景：内存敏感应用（使用JOL精确测量）
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Measurement(iterations = 5)
    @Warmup(iterations = 3)
    public long memoryEfficiency() {
        List<Integer> list = createPrefilledList();
        // 使用JOL计算总内存占用
        return GraphLayout.parseInstance(list).totalSize();
    }

    /**
     * 基准12: 迭代器性能
     * 场景：遍历时修改
     */
    @Benchmark
    public void iteratorWithRemoval(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        Iterator<Integer> it = list.iterator();
        int count = 0;
        while (it.hasNext()) {
            it.next();
            if (count++ % 10 == 0) {
                it.remove();
            }
        }
        bh.consume(list);
    }

    // 新增基准：头插入
    @Benchmark
    public void headInsert(Blackhole bh) {
        List<Integer> list = createList(size);
        for (int i = 0; i < size / 10; i++) {
            list.add(0, testData[i]);
        }
        bh.consume(list);
    }

    // 新增基准：中间插入
    @Benchmark
    public void middleInsert(Blackhole bh) {
        List<Integer> list = createPrefilledList();
        for (int i = 0; i < size / 20; i++) {
            int pos = list.size() / 2;
            list.add(pos, testData[i]);
        }
        bh.consume(list);
    }

    // 新增基准：尾插入（类似sequentialAdd，可视情况合并）
    @Benchmark
    public void tailInsert(Blackhole bh) {
        List<Integer> list = createList(size);
        for (int i = 0; i < size / 10; i++) {
            list.add(testData[i]);
        }
        bh.consume(list);
    }

    // -----------------------------------------------------------------
    // 辅助方法
    // -----------------------------------------------------------------

    private List<Integer> createPrefilledList() {
        List<Integer> list = createList(size);
        for (int i = 0; i < size; i++) {
            list.add(testData[i]);
        }
        return list;
    }

    // -----------------------------------------------------------------
    // 主运行方法
    // -----------------------------------------------------------------

    public static void main(String[] args) throws RunnerException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String resultFile = "list-perf-" + timestamp + ".json";
        String logFile = "list-perf-" + timestamp + ".log";

        Options opt = new OptionsBuilder()
                .include(PerformanceBenchmark.class.getSimpleName())
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .result(resultFile)
                .output(logFile)
                .shouldDoGC(true)
                .forks(3)
                .addProfiler("gc")
                .addProfiler("stack", "lines=5")
                .build();

        new Runner(opt).run();
    }
}

/**
 * 补充说明：输出数据符合SCI论文标准
 *
 * 1. JMH输出：JSON格式包含score、error、CI（95%置信区间）、单位等。
 *    示例输出片段（论文中可转为表格）：
 *    Benchmark               (listType)  (size)  Mode  Cnt     Score     Error  Units
 *    sequentialAdd           ArrayList    1000  avgt   30     1.234 ±   0.056  us/op
 *
 * 2. 处理输出：使用Python/R脚本从JSON生成LaTeX表格或Excel，包含均值、SD、CI。
 *    论文中报告：平均时间 ± 标准差 (95% CI: [low, high])。
 *
 * 3. 可重复性：固定种子、JVM参数，确保相同环境复现。
 *
 * 4. 统计分析：若需p-value，可用外部工具（如R的ANOVA）分析多组数据。
 *
 * 5. 内存数据：JOL提供精确字节数，GC profiler提供分配率/暂停时间。
 */

/**
 * 运行脚本：run-benchmark.sh（更新版）
 *
 * #!/bin/bash
 *
 * # 设置JVM参数
 * export JAVA_OPTS="-server -Xms4g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=50"
 *
 * # 运行基准测试
 * mvn clean compile exec:java \
 * -Dexec.mainClass="com.stip.net.Benchmark.PerformanceBenchmark" \
 * -Dexec.classpathScope="test"
 *
 * # 生成CSV报告（便于论文导入Excel/R）
 * java -cp target/benchmarks.jar \
 * org.openjdk.jmh.results.ResultFormatConverter \
 * -i target/benchmarks/*.json \
 * -o target/benchmarks/report.csv \
 * -f csv
 *
 * # 生成HTML报告（可视化图表）
 * java -cp target/benchmarks.jar \
 * org.openjdk.jmh.results.ResultFormatConverter \
 * -i target/benchmarks/*.json \
 * -o target/benchmarks/report.html \
 * -f html
 */