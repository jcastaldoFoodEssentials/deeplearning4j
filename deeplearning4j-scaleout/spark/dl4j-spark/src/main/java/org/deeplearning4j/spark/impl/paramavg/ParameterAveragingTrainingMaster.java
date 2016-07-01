package org.deeplearning4j.spark.impl.paramavg;

import lombok.Data;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.storage.StorageLevel;
import org.deeplearning4j.nn.api.Updater;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.updater.aggregate.UpdaterAggregator;
import org.deeplearning4j.nn.updater.graph.ComputationGraphUpdater;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.spark.api.Repartition;
import org.deeplearning4j.spark.api.TrainingMaster;
import org.deeplearning4j.spark.api.WorkerConfiguration;
import org.deeplearning4j.spark.api.stats.SparkTrainingStats;
import org.deeplearning4j.spark.api.worker.ExecuteWorkerFlatMap;
import org.deeplearning4j.spark.api.worker.ExecuteWorkerMultiDataSetFlatMap;
import org.deeplearning4j.spark.api.worker.NetBroadcastTuple;
import org.deeplearning4j.spark.impl.graph.SparkComputationGraph;
import org.deeplearning4j.spark.impl.graph.dataset.DataSetToMultiDataSetFn;
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer;
import org.deeplearning4j.spark.impl.paramavg.aggregator.ParameterAveragingAggregationTuple;
import org.deeplearning4j.spark.impl.paramavg.aggregator.ParameterAveragingElementAddFunction;
import org.deeplearning4j.spark.impl.paramavg.aggregator.ParameterAveragingElementCombineFunction;
import org.deeplearning4j.spark.impl.paramavg.stats.ParameterAveragingTrainingMasterStats;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;

/**
 * ParameterAveragingTrainingMaster: A {@link TrainingMaster} implementation for training networks on Spark.
 * This is standard parameter averaging with a configurable averaging period.
 *
 * @author Alex Black
 */
@Data
public class ParameterAveragingTrainingMaster implements TrainingMaster<ParameterAveragingTrainingResult, ParameterAveragingTrainingWorker> {

    private static final Logger log = LoggerFactory.getLogger(ParameterAveragingTrainingMaster.class);

    private boolean saveUpdater;
    private int numWorkers;
    private int rddDataSetNumExamples;
    private int batchSizePerWorker;
    private int averagingFrequency;
    private int prefetchNumBatches;
    private boolean collectTrainingStats;
    private ParameterAveragingTrainingMasterStats.ParameterAveragingTrainingMasterStatsHelper stats;
    private Collection<IterationListener> listeners;
    private int iterationCount = 0;
    private Repartition repartition;


    private ParameterAveragingTrainingMaster(Builder builder) {
        this.saveUpdater = builder.saveUpdater;
        this.numWorkers = builder.numWorkers;
        this.rddDataSetNumExamples = builder.rddDataSetNumExamples;
        this.batchSizePerWorker = builder.batchSizePerWorker;
        this.averagingFrequency = builder.averagingFrequency;
        this.prefetchNumBatches = builder.prefetchNumBatches;
        this.repartition = builder.repartition;
    }

    public ParameterAveragingTrainingMaster(boolean saveUpdater, int numWorkers, int rddDataSetNumExamples, int batchSizePerWorker,
                                            int averagingFrequency, int prefetchNumBatches) {
        this(saveUpdater, numWorkers, rddDataSetNumExamples, batchSizePerWorker, averagingFrequency, prefetchNumBatches, false);
    }

    /**
     *
     * @param saveUpdater              If true: save (and average) the updater state when doing parameter averaging
     * @param numWorkers               Number of workers (executors * threads per executor) for the cluster
     * @param rddDataSetNumExamples    Number of examples in each DataSet object in the {@code RDD<DataSet>}
     * @param batchSizePerWorker       Number of examples to use per worker per fit
     * @param averagingFrequency       Frequency (in number of minibatches) with which to average parameters
     * @param prefetchNumBatches       Number of batches to asynchronously prefetch (0: disable)
     * @param collectTrainingStats     If true: collect training statistics for debugging/optimization purposes
     */
    public ParameterAveragingTrainingMaster(boolean saveUpdater, int numWorkers, int rddDataSetNumExamples, int batchSizePerWorker,
                                            int averagingFrequency, int prefetchNumBatches, boolean collectTrainingStats) {
        if(numWorkers <= 0) throw new IllegalArgumentException("Invalid number of workers: " + numWorkers + " (must be >= 1)");
        if(rddDataSetNumExamples <= 0) throw new IllegalArgumentException("Invalid rdd data set size: " + rddDataSetNumExamples + " (must be >= 1)");

        this.saveUpdater = saveUpdater;
        this.numWorkers = numWorkers;
        this.rddDataSetNumExamples = rddDataSetNumExamples;
        this.batchSizePerWorker = batchSizePerWorker;
        this.averagingFrequency = averagingFrequency;
        this.prefetchNumBatches = prefetchNumBatches;
        this.collectTrainingStats = collectTrainingStats;
        if (collectTrainingStats)
            stats = new ParameterAveragingTrainingMasterStats.ParameterAveragingTrainingMasterStatsHelper();
    }

    @Override
    public ParameterAveragingTrainingWorker getWorkerInstance(SparkDl4jMultiLayer network) {
        NetBroadcastTuple tuple = new NetBroadcastTuple(network.getNetwork().getLayerWiseConfigurations(),
                network.getNetwork().params(),
                network.getNetwork().getUpdater());

        if (collectTrainingStats) stats.logBroadcastStart();
        Broadcast<NetBroadcastTuple> broadcast = network.getSparkContext().broadcast(tuple);
        if (collectTrainingStats) stats.logBroadcastEnd();

        WorkerConfiguration configuration = new WorkerConfiguration(false, batchSizePerWorker, averagingFrequency, prefetchNumBatches, collectTrainingStats);
        return new ParameterAveragingTrainingWorker(broadcast, saveUpdater, configuration);
    }

    @Override
    public ParameterAveragingTrainingWorker getWorkerInstance(SparkComputationGraph graph) {
        NetBroadcastTuple tuple = new NetBroadcastTuple(graph.getNetwork().getConfiguration(),
                graph.getNetwork().params(),
                graph.getNetwork().getUpdater());

        if (collectTrainingStats) stats.logBroadcastStart();
        Broadcast<NetBroadcastTuple> broadcast = graph.getSparkContext().broadcast(tuple);
        if (collectTrainingStats) stats.logBroadcastEnd();

        WorkerConfiguration configuration = new WorkerConfiguration(true, batchSizePerWorker, averagingFrequency, prefetchNumBatches, collectTrainingStats);
        return new ParameterAveragingTrainingWorker(broadcast, saveUpdater, configuration);
    }

    private int getNumDataSetObjectsPerSplit(){
        int dataSetObjectsPerSplit;
        if(rddDataSetNumExamples == 1){
            dataSetObjectsPerSplit = numWorkers * batchSizePerWorker * averagingFrequency;
        } else {
            int reqNumExamplesEachWorker = batchSizePerWorker * averagingFrequency;
            int numDataSetsReqEachWorker = reqNumExamplesEachWorker / batchSizePerWorker;
            if(numDataSetsReqEachWorker < 1){
                //In this case: more examples in a DataSet object than we actually require
                //For example, 100 examples in DataSet, with batchSizePerWorker=50 and averagingFrequency=1
                numDataSetsReqEachWorker = 1;
            }

            dataSetObjectsPerSplit = numDataSetsReqEachWorker*numWorkers;
        }
        return dataSetObjectsPerSplit;
    }

    @Override
    public void executeTraining(SparkDl4jMultiLayer network, JavaRDD<DataSet> trainingData) {
        if (collectTrainingStats) stats.logFitStart();
        //For "vanilla" parameter averaging training, we need to split the full data set into batches of size N, such that we can process the specified
        // number of minibatches between averagings
        //But to do that, wee need to know: (a) the number of examples, and (b) the number of workers
        trainingData.persist(StorageLevel.MEMORY_ONLY());

        long totalDataSetObjectCount = trainingData.count();
        int dataSetObjectsPerSplit = getNumDataSetObjectsPerSplit();

        if (collectTrainingStats) stats.logSplitStart();
        JavaRDD<DataSet>[] splits = randomSplit((int)totalDataSetObjectCount, dataSetObjectsPerSplit, trainingData);
        if (collectTrainingStats) stats.logSplitEnd();

        int splitNum = 1;
        for (JavaRDD<DataSet> split : splits) {
            log.info("Starting training of split {} of {}. workerMiniBatchSize={}, averagingFreq={}, dataSetTotalExamples={}. Configured for {} executors",
                    splitNum, splits.length, batchSizePerWorker, averagingFrequency, totalDataSetObjectCount, numWorkers);
            if (collectTrainingStats) stats.logMapPartitionsStart();

            JavaRDD<DataSet> splitData = split;

            splitData = repartitionIfRequired(splitData);
            int nPartitions = split.partitions().size();

            FlatMapFunction<Iterator<DataSet>, ParameterAveragingTrainingResult> function = new ExecuteWorkerFlatMap<>(getWorkerInstance(network));
            JavaRDD<ParameterAveragingTrainingResult> result = splitData.mapPartitions(function);
            processResults(network, null, result, splitNum, splits.length);

            splitNum++;
            if (collectTrainingStats) stats.logMapPartitionsEnd(nPartitions);
        }

        if (collectTrainingStats) stats.logFitEnd((int) totalDataSetObjectCount);
    }

    @Override
    public void executeTraining(SparkComputationGraph graph, JavaRDD<DataSet> trainingData) {
        JavaRDD<MultiDataSet> mdsTrainingData = trainingData.map(new DataSetToMultiDataSetFn());

        executeTrainingMDS(graph, mdsTrainingData);
    }

    @Override
    public void executeTrainingMDS(SparkComputationGraph graph, JavaRDD<MultiDataSet> trainingData) {
        if (collectTrainingStats) stats.logFitStart();
        //For "vanilla" parameter averaging training, we need to split the full data set into batches of size N, such that we can process the specified
        // number of minibatches between averagings
        //But to do that, wee need to know: (a) the number of examples, and (b) the number of workers
        trainingData.persist(StorageLevel.MEMORY_ONLY());

        long totalDataSetObjectCount = trainingData.count();
        int dataSetObjectsPerSplit = getNumDataSetObjectsPerSplit();

        JavaRDD<MultiDataSet>[] splits = randomSplit((int) totalDataSetObjectCount, dataSetObjectsPerSplit, trainingData);


        int splitNum = 1;
        for (JavaRDD<MultiDataSet> split : splits) {
            log.info("Starting graph training of split {} of {}. workerMiniBatchSize={}, averagingFreq={}, dataSetTotalExamples={}. Configured for {} executors",
                    splitNum, splits.length, batchSizePerWorker, averagingFrequency, totalDataSetObjectCount, numWorkers);
            if (collectTrainingStats) stats.logMapPartitionsStart();

            JavaRDD<MultiDataSet> splitData = split;

            splitData = repartitionIfRequired(splitData);
            int nPartitions = split.partitions().size();

            FlatMapFunction<Iterator<MultiDataSet>, ParameterAveragingTrainingResult> function = new ExecuteWorkerMultiDataSetFlatMap<>(getWorkerInstance(graph));
            JavaRDD<ParameterAveragingTrainingResult> result = splitData.mapPartitions(function);
            processResults(null, graph, result, splitNum, splits.length);

            splitNum++;
            if (collectTrainingStats) stats.logMapPartitionsEnd(nPartitions);
        }

        if (collectTrainingStats) stats.logFitEnd((int) totalDataSetObjectCount);
    }

    private <T> JavaRDD<T>[] randomSplit(int totalObjectCount, int numObjectsPerSplit, JavaRDD<T> data) {
        JavaRDD<T>[] splits;
        if (collectTrainingStats) stats.logSplitStart();
        if (totalObjectCount <= numObjectsPerSplit) {
            splits = (JavaRDD<T>[]) Array.newInstance(JavaRDD.class, 1);
            splits[0] = data;
        } else {
            int numSplits = totalObjectCount / numObjectsPerSplit; //Intentional round down
            double[] weights = new double[numSplits];
            for (int i = 0; i < weights.length; i++) weights[i] = 1.0 / numSplits;
            splits = data.randomSplit(weights);
        }
        if (collectTrainingStats) stats.logSplitEnd();
        return splits;
    }

    private <T> JavaRDD<T> repartitionIfRequired(JavaRDD<T> rdd){
        int nPartitions = rdd.partitions().size();
        switch (repartition) {
            case Never:
                return rdd;
            case NumPartitionsExecutorsDiffers:
                if (nPartitions == numWorkers) return rdd;
            case Always:
                //Repartition: either always, or nPartitions != numWorkers
                JavaRDD<T> temp;
                if (collectTrainingStats) stats.logRepartitionStart();
                temp = rdd.repartition(numWorkers);
                if (collectTrainingStats) stats.logRepartitionEnd();
                return temp;
            default:
                throw new RuntimeException("Unknown setting for repartition: " + repartition);
        }
    }


    @Override
    public void setCollectTrainingStats(boolean collectTrainingStats) {
        this.collectTrainingStats = collectTrainingStats;
        if (collectTrainingStats) {
            if (this.stats == null)
                this.stats = new ParameterAveragingTrainingMasterStats.ParameterAveragingTrainingMasterStatsHelper();
        } else {
            this.stats = null;
        }
    }

    @Override
    public boolean getIsCollectTrainingStats() {
        return collectTrainingStats;
    }

    @Override
    public SparkTrainingStats getTrainingStats() {
        if (stats != null) return stats.build();
        return null;
    }


    private void processResults(SparkDl4jMultiLayer network, SparkComputationGraph graph, JavaRDD<ParameterAveragingTrainingResult> results, int splitNum, int totalSplits) {
        //Need to do parameter averaging, and where necessary also do averaging of the updaters

        //Let's do all of this in ONE step, such that we don't have extra synchronization costs

        if (collectTrainingStats) stats.logAggregateStartTime();
        ParameterAveragingAggregationTuple tuple = results.aggregate(null,
                new ParameterAveragingElementAddFunction(),
                new ParameterAveragingElementCombineFunction());
        INDArray params = tuple.getParametersSum();
        int aggCount = tuple.getAggregationsCount();
        SparkTrainingStats aggregatedStats = tuple.getSparkTrainingStats();
        if (collectTrainingStats) stats.logAggregationEndTime();


        if (collectTrainingStats) stats.logProcessParamsUpdaterStart();
        params.divi(aggCount);
        if (network != null) {
            MultiLayerNetwork net = network.getNetwork();
            UpdaterAggregator updaterAg = tuple.getUpdaterAggregator();
            Updater updater = (updaterAg != null ? updaterAg.getUpdater() : null);
            net.setParameters(params);
            net.setUpdater(updater);

            network.setScore(tuple.getScoreSum() / tuple.getAggregationsCount());
        } else {
            ComputationGraph g = graph.getNetwork();
            ComputationGraphUpdater.Aggregator updaterAg = tuple.getUpdaterAggregatorGraph();
            ComputationGraphUpdater updater = (updaterAg != null ? updaterAg.getUpdater() : null);
            g.setParams(params);
            g.setUpdater(updater);

            graph.setScore(tuple.getScoreSum() / tuple.getAggregationsCount());
        }

        if (collectTrainingStats) {
            stats.logProcessParamsUpdaterEnd();
            stats.addWorkerStats(aggregatedStats);
        }

        log.info("Completed training of split {} of {}", splitNum, totalSplits);

        if (listeners != null) {
            if (network != null) {
                MultiLayerNetwork net = network.getNetwork();
                net.setScore(network.getScore());
                for (IterationListener il : listeners) {
                    il.iterationDone(net, iterationCount);
                }
            } else {
                ComputationGraph g = graph.getNetwork();
                g.setScore(graph.getScore());
                for (IterationListener il : listeners) {
                    il.iterationDone(g, iterationCount);
                }
            }
        }

        iterationCount++;
    }


    public static class Builder {

        private boolean saveUpdater;
        private int numWorkers;
        private int rddDataSetNumExamples;
        private int batchSizePerWorker = 16;
        private int averagingFrequency = 5;
        private int prefetchNumBatches = 0;
        private Repartition repartition = Repartition.Never;

        /**
         * Create a builder, where the following number of workers (Spark executors * number of threads per executor) are
         * being used.<br>
         * Note: this should match the configuration of the cluster.<br>
         *
         * It is also necessary to specify how many examples are in each DataSet that appears in the {@code RDD<DataSet>}
         * or {@code JavaRDD<DataSet>} used for training.<br>
         * Two most common cases here:<br>
         * (a) Preprocessed data pipelines will often load binary DataSet objects with N > 1 examples in each; in this case,
         *     rddDataSetNumExamples should be set to N <br>
         * (b) "In line" data pipelines (for example, CSV String -> record reader -> DataSet just before training) will
         *     typically have exactly 1 example in each DataSet object. In this case, rddDataSetNumExamples should be set to 1
         *
         *
         * @param numWorkers Number of Spark execution threads in the cluster
         * @param rddDataSetNumExamples Number of examples in each DataSet object in the {@code RDD<DataSet>}
         */
        public Builder(int numWorkers, int rddDataSetNumExamples) {
            if(numWorkers <= 0) throw new IllegalArgumentException("Invalid number of workers: " + numWorkers + " (must be >= 1)");
            if(rddDataSetNumExamples <= 0) throw new IllegalArgumentException("Invalid rdd data set size: " + rddDataSetNumExamples + " (must be >= 1)");
            this.numWorkers = numWorkers;
            this.rddDataSetNumExamples = rddDataSetNumExamples;
        }

        /**
         * Batch size (in number of examples) per worker, for each fit(DataSet) call.
         *
         * @param batchSizePerWorker Size of each minibatch to use for each worker
         * @return
         */
        public Builder batchSizePerWorker(int batchSizePerWorker) {
            this.batchSizePerWorker = batchSizePerWorker;
            return this;
        }

        /**
         * Frequency with which to average worker parameters.<br>
         * <b>Note</b>: Too high or too low can be bad for different reasons.<br>
         * - Too low (such as 1) can result in a lot of network traffic<br>
         * - Too high (>> 20 or so) can result in accuracy issues or problems with network convergence
         *
         * @param averagingFrequency Frequency (in number of minibatches of size 'batchSizePerWorker') to average parameters
         */
        public Builder averagingFrequency(int averagingFrequency) {
            if (averagingFrequency <= 0)
                throw new IllegalArgumentException("Ivalid input: averaging frequency must be >= 1");
            this.averagingFrequency = averagingFrequency;
            return this;
        }

        /**
         * Set the number of minibatches to asynchronously prefetch in the worker.
         * <p>
         * Default: 0 (no prefetching)
         *
         * @param prefetchNumBatches Number of minibatches (DataSets of size batchSizePerWorker) to fetch
         */
        public Builder workerPrefetchNumBatches(int prefetchNumBatches) {
            this.prefetchNumBatches = prefetchNumBatches;
            return this;
        }

        /**
         * Set whether the updater (i.e., historical state for momentum, adagrad, etc should be saved).
         * <b>NOTE</b>: This can <b>double</b> (or more) the amount of network traffic in each direction, but might
         * improve network training performance (and can be more stable for certain updaters such as adagrad).<br>
         * <p>
         * This is <b>enabled</b> by default.
         *
         * @param saveUpdater If true: retain the updater state (default). If false, don't retain (updaters will be
         *                    reinitalized in each worker after averaging).
         */
        public Builder saveUpdater(boolean saveUpdater) {
            this.saveUpdater = saveUpdater;
            return this;
        }

        /**
         * Set if and how repartitioning should be conducted for the training data.<br>
         * Default value: no repartitioning.
         *
         * @param repartition Setting for repartitioning
         */
        public Builder repartionData(Repartition repartition) {
            this.repartition = repartition;
            return this;
        }

        public ParameterAveragingTrainingMaster build() {
            return new ParameterAveragingTrainingMaster(this);
        }
    }


}
