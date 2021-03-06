package de.hhu.bsinfo.observatory.benchmark;

import de.hhu.bsinfo.observatory.generated.BuildConfig;
import de.hhu.bsinfo.observatory.benchmark.Benchmark.Mode;
import de.hhu.bsinfo.observatory.benchmark.config.BenchmarkConfig;
import de.hhu.bsinfo.observatory.benchmark.config.OperationConfig;
import de.hhu.bsinfo.observatory.benchmark.config.IterationConfig;
import de.hhu.bsinfo.observatory.benchmark.config.OperationConfig.OperationMode;
import de.hhu.bsinfo.observatory.benchmark.result.Status;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Observatory {

    private static final Logger LOGGER = LoggerFactory.getLogger(Observatory.class);

    private final List<Benchmark> benchmarks = new ArrayList<>();

    public Observatory(BenchmarkConfig config, boolean isServer, int connectionRetries, InetSocketAddress bindAddress, InetSocketAddress remoteAddress) {
        String benchmarkName = config.getClassName().substring(config.getClassName().lastIndexOf('.') + 1);

        try {
            checkOldResults(new File("result/"), benchmarkName);
        } catch (IllegalStateException e) {
            LOGGER.error("Found old results of '{}'", benchmarkName);
            LOGGER.error("Use './observatory clean' to delete old results, if you do not need them anymore");
            return;
        }

        for (OperationConfig operationConfig : config.getOperations()) {
            for(OperationMode mode : operationConfig.getModes()) {
                String operationClassName =
                        "de.hhu.bsinfo.observatory.benchmark." + operationConfig.getName() + "Operation";

                    for (IterationConfig iterationConfig : operationConfig.getIterations()) {
                        for (int i = 0; i < operationConfig.getRepetitions(); i++) {
                        Benchmark benchmark = instantiateBenchmark(config.getClassName());

                        if (benchmark == null) {
                            return;
                        }

                        Operation operation = null;
                        String resultPath = "";

                        if (mode == OperationMode.UNIDIRECTIONAL) {
                            operation = instantiateOperation(operationClassName, benchmark,
                                    isServer ? Mode.SEND : Mode.RECEIVE, iterationConfig.getCount(),
                                    iterationConfig.getSize());

                            resultPath = ("result/Unidirectional" + operationConfig.getName() + "/" + (i + 1) + "/");
                        } else if (mode == OperationMode.BIDIRECTIONAL) {
                            Operation sendOperation = instantiateOperation(operationClassName, benchmark,
                                    Mode.SEND, iterationConfig.getCount(), iterationConfig.getSize());

                            Operation receiveOperation = instantiateOperation(operationClassName, benchmark,
                                    Mode.RECEIVE, iterationConfig.getCount(), iterationConfig.getSize());

                            if (sendOperation == null || receiveOperation == null) {
                                continue;
                            }

                            if (!(sendOperation instanceof ThroughputOperation)
                                    || !(receiveOperation instanceof ThroughputOperation)) {
                                LOGGER.error(
                                        "Invalid configuration: Only throughput operations may be executed bidirectionally");
                                return;
                            }

                            operation = new BidirectionalThroughputOperation((ThroughputOperation) sendOperation,
                                    (ThroughputOperation) receiveOperation);

                            resultPath = ("result/Bidirectional" + operationConfig.getName() + "/" + (i + 1) + "/");
                        }

                        if (operation == null) {
                            continue;
                        }

                        Arrays.stream(config.getParameters())
                                .forEach(parameter -> benchmark.setParameter(parameter.getKey(), parameter.getValue()));

                        benchmark.setServer(isServer);
                        benchmark.setConnectionRetries(connectionRetries);

                        benchmark.setDetectorConfig(config.getDetectorConfig());

                        benchmark.setBindAddress(bindAddress);
                        benchmark.setRemoteAddress(remoteAddress);

                        benchmark.setResultPath(resultPath);

                        benchmark.addBenchmarkPhase(new InitializationPhase(benchmark));
                        benchmark.addBenchmarkPhase(new ConnectionPhase(benchmark));
                        benchmark.addBenchmarkPhase(new PreparationPhase(benchmark, iterationConfig.getSize()));

                        if (operation.needsFilledReceiveQueue()) {
                            benchmark.addBenchmarkPhase(new FillReceiveQueuePhase(benchmark));
                        }

                        benchmark.addBenchmarkPhase(
                                new WarmUpPhase(benchmark, operation, iterationConfig.getWarmUpIterations()));

                        if (operation.needsFilledReceiveQueue()) {
                            benchmark.addBenchmarkPhase(new FillReceiveQueuePhase(benchmark));
                        }

                        benchmark.addBenchmarkPhase(new OperationPhase(benchmark, operation));

                        benchmark.addBenchmarkPhase(new CleanupPhase(benchmark));

                        benchmarks.add(benchmark);
                    }
                }
            }
        }
    }

    public void start()  {
        for(Benchmark benchmark : benchmarks) {
            LOGGER.info("Executing benchmark '{}'", benchmark.getClass().getSimpleName());

            if(benchmark.setup() != Status.OK) {
                return;
            }

            if(!benchmark.synchronize()) {
                return;
            }

            benchmark.executePhases();
        }
    }

    private static Benchmark instantiateBenchmark(String className) {
        try {
            Class<?> clazz = Observatory.class.getClassLoader().loadClass(className);
            return (Benchmark) clazz.getConstructor().newInstance();
        } catch (InstantiationException | InvocationTargetException | NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
            LOGGER.error("Unable to create benchmark of type '{}'", className, e);

            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Operation instantiateOperation(String className, Benchmark benchmark, Mode mode, int operationCount, int operationSize) {
        try {
            Class<? extends Operation> clazz = (Class<? extends Operation>) Class.forName(className);

            return clazz.getDeclaredConstructor(Benchmark.class, Mode.class, int.class, int.class).newInstance(benchmark, mode, operationCount, operationSize);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            LOGGER.error("Unable to create benchmark operation of type '{}'", className, e);

            return null;
        }
    }

    private static void checkOldResults(File directory, String benchmarkName) throws IllegalStateException {
        if(directory.isFile()) {
            if(directory.getName().toLowerCase().startsWith(benchmarkName.toLowerCase())) {
                throw new IllegalStateException("Found already existing result file '" + directory.getPath() + "'");
            }
        } else {
            File[] files = directory.listFiles();

            if(files != null) {
                for (File file : files) {
                    checkOldResults(file, benchmarkName);
                }
            }
        }
    }

    public static void printBanner() {
        InputStream inputStream = Observatory.class.getClassLoader().getResourceAsStream("banner.txt");

        if (inputStream == null) {
            return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String banner = reader.lines().collect(Collectors.joining(System.lineSeparator()));

        System.out.print("\n");
        System.out.printf(banner, BuildConfig.VERSION, BuildConfig.BUILD_DATE, BuildConfig.GIT_BRANCH, BuildConfig.GIT_COMMIT);
        System.out.print("\n\n");
    }
}
