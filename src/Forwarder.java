import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

class Forwarder {
    private int listenPort;

    private Selector selector;
    private Map<SocketChannel, SocketChannel> connections = new HashMap<>();
    private Map<SocketChannel, StateInfo> stateOfChannels = new HashMap<>();

    private Map<Integer, Connection> dnsList = new HashMap<>();
    private Parser parser = new Parser();

    Forwarder(int listenPort){
        this.listenPort = listenPort;
    }

    void start() throws IOException {
        SelectionKey key;
        String[] dnsServers = ResolverConfig.getCurrentConfig().servers();

        selector = Selector.open();

        try (ServerSocketChannel ssc = ServerSocketChannel.open();
             DatagramChannel udpSocket = DatagramChannel.open()) {

            ssc.configureBlocking(false);
            ssc.socket().bind(new InetSocketAddress("localhost", listenPort));
            ssc.register(selector, SelectionKey.OP_ACCEPT);

            udpSocket.configureBlocking(false);
            final  int DOMAIN_PORT = 53;
            udpSocket.connect(new InetSocketAddress(dnsServers[0], DOMAIN_PORT));
            udpSocket.register(selector, SelectionKey.OP_READ);

            while (true) {
                selector.select();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    key = iterator.next();
                    iterator.remove();
                    if (key.isValid()) {
                        if (key.isAcceptable()) {
                            accept(ssc);
                        }
                        if (key.isConnectable()) {
                            ((SocketChannel) key.channel()).finishConnect();
                        }
                        if (key.isReadable()) {
                            read(key, udpSocket);
                        }
                    }
                }
            }
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    private void read(SelectionKey key, DatagramChannel udpSocket) throws IOException {
        int BUF_SIZE = 8192;
        ByteBuffer byteBuffer = ByteBuffer.allocate(BUF_SIZE);
        boolean isDNS = !(key.channel() instanceof SocketChannel);
        if (!isDNS) {
            StateInfo state = stateOfChannels.get(key.channel());
            SocketChannel sc = (SocketChannel) key.channel();

            if ((state.state == StateInfo.State.ACCEPTED) || (state.state == StateInfo.State.NO_CONNECTED)) {
                try {
                    int readRes = sc.read(byteBuffer);
                    if ( readRes < 0) {
                        stateOfChannels.remove(key.channel());
                        close(key);
                    }
                    else {
                        boolean isCorrect = parser.isCorrect(byteBuffer, state.state);
                        if (isCorrect) {
                            if (state.state == StateInfo.State.ACCEPTED) {
                                ByteBuffer outBuffer = parser.getAcceptAnswer();
                                sc.write(ByteBuffer.wrap(outBuffer.array(), 0, 2));
                                state.state = StateInfo.State.NO_CONNECTED;
                            }
                            else if (state.state == StateInfo.State.NO_CONNECTED) {
                                try {
                                    InetAddress address = parser.getAddress(byteBuffer);
                                    int port = parser.getPort(byteBuffer, readRes);
                                    if (connect(address, port, sc, key)){
                                        state.state = StateInfo.State.CONNECTED;
                                    }
                                } catch (IOException e) {
                                    Name name = org.xbill.DNS.Name.fromString(parser.getDomain(byteBuffer), Name.root);
                                    Record record = Record.newRecord(name, Type.A, DClass.IN);
                                    Message message = Message.newQuery(record);
                                    udpSocket.write(ByteBuffer.wrap(message.toWire()));
                                    int port = parser.getPort(byteBuffer, readRes);
                                    dnsList.put(message.getHeader().getID(), new Connection(port, sc));
                                }
                            }
                            byteBuffer.clear();
                        }
                    }
                }
                catch (IOException e){
                    e.printStackTrace();
                    close(key);
                }

            }
            else if (state.state == StateInfo.State.CONNECTED) {
                SocketChannel connection = connections.get(sc);

                System.out.println("SOURCE: " + sc.toString());
                System.out.println("DEST: " + connection.toString());
                if (connection.isConnected()) {
                    try {
                        int readRes = sc.read(byteBuffer);
                        if (readRes == -1) {
                            close(key);
                        }
                        else {
                            connection.write(ByteBuffer.wrap(byteBuffer.array(), 0, readRes));
                        }
                    }
                    catch (IOException e) {
                        e.printStackTrace();
                        close(key);
                    }
                }
                byteBuffer.clear();
            }
        }
        else {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            int readRes = udpSocket.read(buffer);
            if (readRes <= 0) return;
            Message msg = new Message(buffer.array());
            Record[] records = msg.getSectionArray(1);
            for (Record record : records) {
                if (record instanceof ARecord) {
                    ARecord aRecord = (ARecord) record;
                    InetAddress inetAddress = aRecord.getAddress();
                    int id = msg.getHeader().getID();
                    Connection connection = dnsList.get(id);
                    int port = connection.port;
                    if (connect(inetAddress, port, dnsList.get(id).sc, key)) {
                        stateOfChannels.get(dnsList.get(id).sc).state = StateInfo.State.CONNECTED;
                    }
                    dnsList.remove(id);
                    break;
                }
            }
        }
    }

    private void accept(ServerSocketChannel ssc) throws IOException {
        SocketChannel sc = ssc.accept();
        if (sc != null) {
            sc.configureBlocking(false);
            sc.register(selector, SelectionKey.OP_READ );

            StateInfo state = new StateInfo();
            state.state = StateInfo.State.ACCEPTED;
            stateOfChannels.put(sc, state);
        }
    }

    private void close(SelectionKey key) throws IOException {
        SocketChannel sc = connections.get(key.channel());
        if (null != sc) {
            sc.close();
            connections.remove(connections.get( key.channel()));
            connections.remove(key.channel());
        }
        key.channel().close();
    }

    private boolean connect(InetAddress address, int port, SocketChannel sc, SelectionKey key) throws IOException {
        SocketChannel connection = SocketChannel.open(new InetSocketAddress(address, port));
        ByteBuffer byteBuffer = parser.getConnectAnswer(listenPort, connection.isConnected());
        if (!connection.isConnected()) {
            close(key);
            return connection.isConnected();
        }
        try {
            sc.write(ByteBuffer.wrap(byteBuffer.array(), 0, 10));
        }
        catch (ClosedChannelException e){
            e.printStackTrace();
            return false;
        }
        connection.configureBlocking(false);
        connection.register(selector, SelectionKey.OP_READ | SelectionKey.OP_CONNECT);
        connections.put(sc, connection);
        connections.put(connection, sc);
        stateOfChannels.put(connection, new StateInfo());
        stateOfChannels.get(connection).state = StateInfo.State.CONNECTED;
        return connection.isConnected();
    }
}
