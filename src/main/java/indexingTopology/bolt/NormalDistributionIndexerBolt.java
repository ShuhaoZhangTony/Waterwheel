package indexingTopology.bolt;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import indexingTopology.config.TopologyConfig;
import indexingTopology.DataSchema;
import indexingTopology.filesystem.FileSystemHandler;
import indexingTopology.filesystem.HdfsFileSystemHandler;
import indexingTopology.filesystem.LocalFileSystemHandler;
import indexingTopology.streams.Streams;
import indexingTopology.exception.UnsupportedGenericException;
import indexingTopology.util.*;
import javafx.util.Pair;

import java.io.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by acelzj on 7/27/16.
 */
public class NormalDistributionIndexerBolt extends BaseRichBolt {
    private final static int numberOfIndexingThreads = 1;

    private final DataSchema schema;

    private final int btreeOrder;
    private final int bytesLimit;

    private final String indexField;

    private OutputCollector collector;

    private BTree<Double, Integer> indexedData;
    private BTree<Double, Integer> copyOfIndexedData;

    private int numTuples;
    private int chunkId;

    private boolean isTreeBuilt;

    private MemChunk chunk;

    private TimingModule tm;
    private SplitCounterModule sm;

    private long processingTime;

    private long totalTime;

    private TemplateUpdater templateUpdater;

    private Double minIndexValue = Double.MAX_VALUE;
    private Double maxIndexValue = Double.MIN_VALUE;

    private Long minTimeStamp = Long.MAX_VALUE;
    private Long maxTimeStamp = Long.MIN_VALUE;

    private File file;
    private File inputFile;
    private File outputFile;

    private FileOutputStream fop;
    private FileOutputStream queryFileOutPut;

    private LinkedBlockingQueue<Pair> queue;

    private List<Thread> indexingThreads = new ArrayList<Thread>();

    private IndexingRunnable indexingRunnable;

    private TopologyContext context;

    private Kryo kryo;

    public NormalDistributionIndexerBolt(String indexField, DataSchema schema, int btreeOrder, int bytesLimit) {
        this.schema = schema;
        this.btreeOrder = btreeOrder;
        this.bytesLimit = bytesLimit;
        this.indexField = indexField;
    }
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        collector = outputCollector;

        this.tm = TimingModule.createNew();
        this.sm = SplitCounterModule.createNew();

        indexedData = new BTree(btreeOrder,tm, sm);
        copyOfIndexedData = indexedData;

        chunk = MemChunk.createNew(this.bytesLimit);

        kryo = new Kryo();
        kryo.register(BTree.class, new KryoTemplateSerializer());
        kryo.register(BTreeLeafNode.class, new KryoLeafNodeSerializer());

        this.numTuples = 0;
        this.processingTime = 0;
        this.chunkId = 0;

        this.context = topologyContext;

        this.isTreeBuilt = false;

        this.templateUpdater = new TemplateUpdater(btreeOrder, tm, sm);

        this.queue = new LinkedBlockingQueue<Pair>(1024);
        this.outputFile = new File("/home/acelzj/IndexTopology_experiment/NormalDistribution/query_latency_with_nothing_4");
//        this.outputFile = new File("/home/lzj/IndexTopology_experiment/NormalDistribution/query_latency_without_rebuild_but_split_256");
//        this.outputFile = new File("/home/lzj/IndexTopology_experiment/NormalDistribution/query_latency_with_rebuild_and_split_4");
        try {
            if (!outputFile.exists()) {
                outputFile.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            queryFileOutPut = new FileOutputStream(outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

//        file = new File("/home/lzj/IndexTopology_experiment/NormalDistribution/specific_time_with_rebuild_and_split_with_query_4_64M");
//        file = new File("/home/lzj/IndexTopology_experiment/NormalDistribution/specific_time_without_rebuild_but_split_with_query_256_64M");
        file = new File("/home/acelzj/IndexTopology_experiment/NormalDistribution/specific_time_with_nothing_4_64M");
        try {
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            fop = new FileOutputStream(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

        createIndexingThread();

    }

    @Override
    public void cleanup() {
        super.cleanup();
    }

    public void execute(Tuple tuple) {
        if (tuple.getSourceStreamId().equals(Streams.IndexStream)) {
            Double indexValue = tuple.getDoubleByField(indexField);
            Long timeStamp = tuple.getLong(8);
            try {
                if (numTuples < TopologyConfig.NUMBER_TUPLES_OF_A_CHUNK) {

                    if (indexValue < minIndexValue) {
                        minIndexValue = indexValue;
                    }
                    if (indexValue > maxIndexValue) {
                        maxIndexValue = indexValue;
                    }

                    if (timeStamp < minTimeStamp) {
                        minTimeStamp = timeStamp;
                    }
                    if (timeStamp > maxTimeStamp) {
                        maxTimeStamp = timeStamp;
                    }

                    byte[] serializedTuple = schema.serializeTuple(tuple);

                    Pair pair = new Pair(indexValue, serializedTuple);
                    queue.put(pair);

                    ++numTuples;

                } else {

                    while (!queue.isEmpty()) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    terminateIndexingThreads();

                    double percentage = (double) sm.getCounter() * 100 / (double) numTuples;


                    writeTreeIntoChunk();
//                    chunk.changeToLeaveNodesStartPosition();
//                    indexedData.writeLeavesIntoChunk(chunk);
//                    chunk.changeToStartPosition();

//                    byte[] serializedTree = SerializationHelper.serializeTree(indexedData);
//                    chunk.write(serializedTree);

                    indexedData.clearPayload();

                    FileSystemHandler fileSystemHandler = null;
                    String fileName = null;
                    try {
                        if (TopologyConfig.HDFSFlag) {
                            fileSystemHandler = new HdfsFileSystemHandler(TopologyConfig.dataDir);
                        } else {
                            fileSystemHandler = new LocalFileSystemHandler(TopologyConfig.dataDir);
                        }

                        int taskId = context.getThisTaskId();
                        fileName = "taskId" + taskId + "chunk" + chunkId;
                        System.out.println("file name " + fileName);
                        fileSystemHandler.writeToFileSystem(chunk, "/", fileName);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Pair keyRange = new Pair(minIndexValue, maxIndexValue);
                    Pair timeStampRange = new Pair(minTimeStamp, maxTimeStamp);

                    collector.emit(Streams.FileInformationUpdateStream,
                            new Values(fileName, keyRange, timeStampRange));

                    collector.emit(Streams.TimeStampUpdateStream, new Values(maxTimeStamp));

                    numTuples = 0;

                    chunk = MemChunk.createNew(bytesLimit);
                    sm.resetCounter();
                    byte[] serializedTuple = schema.serializeTuple(tuple);
                    Pair pair = new Pair(indexValue, serializedTuple);
                    queue.put(pair);
                    createIndexingThread();
                    ++numTuples;
                    ++chunkId;

                    minIndexValue = Double.MAX_VALUE;
                    maxIndexValue = Double.MIN_VALUE;
                    if (indexValue < minIndexValue) {
                        minIndexValue = indexValue;
                    }
                    if (indexValue > maxIndexValue) {
                        maxIndexValue = indexValue;
                    }

                    minTimeStamp = Long.MAX_VALUE;
                    maxTimeStamp = Long.MIN_VALUE;
                    if (timeStamp < minTimeStamp) {
                        minTimeStamp = timeStamp;
                    }
                    if (timeStamp > maxTimeStamp) {
                        maxTimeStamp = timeStamp;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {

                collector.ack(tuple);
            }
        } else {
            Long queryId = tuple.getLong(0);
            Double key = tuple.getDouble(1);

            List<byte[]> serializedTuples = indexedData.searchTuples(key);

            collector.emit(Streams.BPlusTreeQueryStream, new Values(queryId, serializedTuples));
        }
    }

    private void copyTemplate(int chunkId) throws CloneNotSupportedException {
        if (chunkId == 0) {
            copyOfIndexedData = (BTree) indexedData.clone(indexedData);
        } else {
            indexedData = (BTree) copyOfIndexedData.clone(copyOfIndexedData);
        }
    }

    private void createEmptyTree() {
        indexedData = new BTree<Double,Integer>(btreeOrder,tm, sm);
    }


    private void createNewTemplate() {
        indexedData = templateUpdater.createTreeWithBulkLoading(indexedData);
        indexedData.clearPayload();
    }


    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(Streams.FileInformationUpdateStream,
                new Fields("fileName", "keyRange", "timestampRange"));

        outputFieldsDeclarer.declareStream(Streams.BPlusTreeQueryStream,
                new Fields("queryId", "serializedTuples"));

        outputFieldsDeclarer.declareStream(Streams.TimeStampUpdateStream,
                new Fields("timestamp"));
    }

    class IndexingRunnable implements Runnable {

        boolean inputExhausted = false;

        public void setInputExhausted() {
            inputExhausted = true;
        }

        AtomicLong executed;
        Long startTime;
        AtomicInteger threadIndex = new AtomicInteger(0);

        Object syn = new Object();
        public void run() {
            boolean first = false;
            synchronized (syn) {
                if (startTime == null) {
                    startTime = System.currentTimeMillis();
                    first = true;
                }
                if (executed == null)
                    executed = new AtomicLong(0);
            }
            long localCount = 0;
            ArrayList<Pair> drainer = new ArrayList<Pair>();
            while (true) {
                try {
//                        Pair pair = queue.poll(1, TimeUnit.MILLISECONDS);
//                        if (pair == null) {
//                        if(!first)
//                            Thread.sleep(100);
                    queue.drainTo(drainer,256);
//                        Pair pair = queue.poll(10, TimeUnit.MILLISECONDS);
                    if(drainer.size() == 0) {
                        if(inputExhausted)
                            break;
                        else
                            continue;
                    }
                    for(Pair pair: drainer) {
                        localCount++;
                        final Double indexValue = (Double) pair.getKey();
                        final byte[] serializedTuple = (byte[]) pair.getValue();
                        indexedData.insert(indexValue, serializedTuple); // for testing
                    }
                    executed.getAndAdd(drainer.size());
                    drainer.clear();
                } catch (UnsupportedGenericException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if(first) {
                System.out.println(String.format("Index throughput = %f tuple / s", executed.get() / (double) (System.currentTimeMillis() - startTime) * 1000));
                System.out.println("Thread execution time: " + (System.currentTimeMillis() - startTime) + " ms.");
            }
//                System.out.println("Indexing thread " + Thread.currentThread().getId() + " is terminated with " + localCount + " tuples processed!");
        }
    }




    private void createIndexingThread() {
        createIndexingThread(numberOfIndexingThreads);
    }

    private void createIndexingThread(int n) {
        if(indexingRunnable == null) {
            indexingRunnable = new IndexingRunnable();
        }
        for(int i = 0; i < n; i++) {
            Thread indexThread = new Thread(indexingRunnable);
            indexThread.start();
            System.out.println(String.format("Thread %d is created!", indexThread.getId()));
            indexingThreads.add(indexThread);
        }
    }

    public void terminateIndexingThreads() {
        try {
            indexingRunnable.setInputExhausted();
            for (Thread thread : indexingThreads) {
                thread.join();
            }
            indexingThreads.clear();
            indexingRunnable = new IndexingRunnable();
            System.out.println("All the indexing threads are terminated!");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void writeTreeIntoChunk() {
        Output output = new Output(65000000);

        byte[] leavesInBytes = indexedData.serializeLeaves();

        kryo.writeObject(output, indexedData);

        byte[] bytes = output.toBytes();

        int lengthOfTemplate = bytes.length;

        output = new Output(4);

        output.writeInt(lengthOfTemplate);

        byte[] lengthInBytes = output.toBytes();

        chunk.write(lengthInBytes );

        chunk.write(bytes);

        chunk.write(leavesInBytes);
    }

}


