import java.nio.channels.SocketChannel;

class StateInfo{
    enum State{
        UNDEFINED,
        ACCEPTED,
        NO_CONNECTED,
        CONNECTED
    }

    State state = State.UNDEFINED;
}

class Connection {

    int port;
    SocketChannel sc;

    Connection(int port, SocketChannel sc){
        this.port = port;
        this.sc = sc;
    }
}