package indexingTopology.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import indexingTopology.metadata.FileMetaData;
import indexingTopology.metadata.FilePartitionSchemaManager;
import indexingTopology.streams.Streams;
import indexingTopology.util.BalancedPartition;
import indexingTopology.util.Histogram;
import indexingTopology.util.RepartitionManager;
import javafx.util.Pair;

import java.util.*;

/**
 * Created by acelzj on 12/12/16.
 */
public class MetadataServer extends BaseRichBolt {

    private OutputCollector collector;

    private Map<Integer, Integer> intervalToPartitionMapping;

    private Double upperBound;

    private Double lowerBound;

    private FilePartitionSchemaManager filePartitionSchemaManager;

    private Map<Integer, Long> indexTaskToTimestampMapping;

    private List<Integer> indexTasks;

    private BalancedPartition balancedPartition;

    private int numberOfDispatchers;

    private int numberOfPartitions;

    private int numberOfStaticsReceived;

    private long[] partitionLoads = null;

    private Histogram histogram;

    private Thread staticsRequestSendingThread;

    private boolean repartitionEnabled;


    public MetadataServer(Double lowerBound, Double upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        collector = outputCollector;

        filePartitionSchemaManager = new FilePartitionSchemaManager();

        intervalToPartitionMapping = new HashMap<>();

        numberOfDispatchers = topologyContext.getComponentTasks("DispatcherBolt").size();

        indexTasks = topologyContext.getComponentTasks("IndexerBolt");

        numberOfPartitions = indexTasks.size();

        balancedPartition = new BalancedPartition(numberOfPartitions, lowerBound, upperBound);

        numberOfStaticsReceived = 0;

        indexTaskToTimestampMapping = new HashMap<>();

        histogram = new Histogram();

        repartitionEnabled = true;

        staticsRequestSendingThread = new Thread(new StatisticsRequestSendingRunnable());
        staticsRequestSendingThread.start();
    }

    @Override
    public void execute(Tuple tuple) {

        if (tuple.getSourceStreamId().equals(Streams.StatisticsReportStream)) {

            Histogram histogram = (Histogram) tuple.getValue(0);

            if (numberOfStaticsReceived < numberOfDispatchers) {
                partitionLoads = new long[numberOfPartitions];
                this.histogram.merge(histogram);
                ++numberOfStaticsReceived;
                if (repartitionEnabled && (numberOfStaticsReceived == numberOfDispatchers)) {
                    Double skewnessFactor = getSkewnessFactor(this.histogram);
                    if (skewnessFactor > 2) {
                        System.out.println("skewness detected!!!");
                        RepartitionManager manager = new RepartitionManager(numberOfPartitions, intervalToPartitionMapping,
                                histogram.getHistogram(), getTotalWorkLoad(histogram));
                        this.intervalToPartitionMapping = manager.getRepartitionPlan();
                        this.balancedPartition = new BalancedPartition(numberOfPartitions, lowerBound, upperBound,
                                intervalToPartitionMapping);
                        repartitionEnabled = false;
                        collector.emit(Streams.IntervalPartitionUpdateStream,
//                                new Values(this.balancedPartition.getIntervalToPartitionMapping()));
                                new Values(this.balancedPartition));
                    } else {
                        System.out.println("skewness is not detected!!!");
                    }

                    numberOfStaticsReceived = 0;
                }
            }

        } else if (tuple.getSourceStreamId().equals(Streams.FileInformationUpdateStream)) {
            String fileName = tuple.getString(0);
            Pair keyRange = (Pair) tuple.getValue(1);
            Pair timeStampRange = (Pair) tuple.getValue(2);

            filePartitionSchemaManager.add(new FileMetaData(fileName, (Double) keyRange.getKey(),
                    (Double)keyRange.getValue(), (Long) timeStampRange.getKey(), (Long) timeStampRange.getValue()));

            collector.emit(Streams.FileInformationUpdateStream,
                    new Values(fileName, keyRange, timeStampRange));
        } else if (tuple.getSourceStreamId().equals(Streams.TimeStampUpdateStream)) {
            int taskId = tuple.getSourceTask();
            Pair timestampRange = (Pair) tuple.getValueByField("timestampRange");
            Pair keyRange = (Pair) tuple.getValueByField("keyRange");
            Long endTimestamp = (Long) timestampRange.getValue();

            indexTaskToTimestampMapping.put(taskId, endTimestamp);

            collector.emit(Streams.TimeStampUpdateStream, new Values(taskId, keyRange, timestampRange));
        } else if (tuple.getSourceStreamId().equals(Streams.EableRepartitionStream)) {
            repartitionEnabled = true;
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(Streams.IntervalPartitionUpdateStream,
                new Fields("newIntervalPartition"));

        outputFieldsDeclarer.declareStream(Streams.FileInformationUpdateStream,
                new Fields("fileName", "keyRange", "timeStampRange"));

        outputFieldsDeclarer.declareStream(Streams.TimeStampUpdateStream,
                new Fields("taskId", "keyRange", "timestampRange"));

        outputFieldsDeclarer.declareStream(Streams.StaticsRequestStream,
                new Fields("Statics Request"));
    }


    private double getSkewnessFactor(Histogram histogram) {

//        System.out.println("Before repartition");
//        for (int i = 0; i < intervalLoads.length; ++i) {
//            System.out.println("bin " + i + " : " + intervalLoads[i]);
//        }

        Long sum = getTotalWorkLoad(histogram);
        Long maxWorkload = getMaxWorkLoad(histogram);
        double averageLoad = sum / (double) numberOfPartitions;

        return maxWorkload / averageLoad;
    }

    private Long getTotalWorkLoad(Histogram histogram) {
        long ret = 0;

        for(long i : histogram.histogramToList()) {
            ret += i;
        }

        return ret;
    }

    private Long getMaxWorkLoad(Histogram histogram) {
        long ret = Long.MIN_VALUE;
        for(long i : histogram.histogramToList()) {
            ret = Math.max(ret, i);
        }

        return ret;
    }


    class StatisticsRequestSendingRunnable implements Runnable {

        @Override
        public void run() {
            final int sleepTimeInSecond = 10;
            while (true) {
                try {
                    Thread.sleep(sleepTimeInSecond * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                List<Long> counts = histogram.histogramToList();
                long sum = 0;
                for(Long count: counts) {
                    sum += count;
                }

                System.out.println("statics request has been sent!!!");
//                System.out.println(String.format("Overall Throughput: %f tuple / second", sum / (double)sleepTimeInSecond));

                histogram.clear();

                collector.emit(Streams.StaticsRequestStream,
                        new Values("Statics Request"));
            }
        }

    }
}
