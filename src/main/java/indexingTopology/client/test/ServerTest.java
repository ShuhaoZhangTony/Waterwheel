package indexingTopology.client.test;

import indexingTopology.client.FakeServerHandle;
import indexingTopology.client.QueryClient;
import indexingTopology.client.Server;

/**
 * Created by robert on 31/5/17.
 */
public class ServerTest {
    static public void main(String[] args) {
        final Server server = new Server(10000, FakeServerHandle.class, new Class[]{int.class}, new Integer(1));
        System.out.println("start");
        server.startDaemon();
    }
}
