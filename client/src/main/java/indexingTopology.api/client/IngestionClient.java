package indexingTopology.api.client;

import indexingTopology.common.data.DataTuple;

import java.io.IOException;

/**
 * Created by robert on 9/3/17.
 */
public class IngestionClient extends ClientSkeleton implements IIngestionClient {
    public IngestionClient(String serverHost, int port) {
        super(serverHost, port);
    }

    public IResponse append(DataTuple dataTuple) throws IOException {
        objectOutputStream.writeObject(new AppendRequest(dataTuple));
//        return (Response) objectInputStream.readObject();
        return null;
    }

    @Override
    public void appendInBatch(DataTuple tuple) throws IOException {

    }

    @Override
    public void flush() throws IOException {

    }
}
