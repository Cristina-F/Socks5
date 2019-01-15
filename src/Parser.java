import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

class Parser {
    private final byte VERSION = 0x05;
    private final byte AUTH = 0x00;
    private final byte IPv4 = 0x01;

    boolean isCorrect(ByteBuffer message, StateInfo.State state){
        byte[] msg = message.array();
        if (state == StateInfo.State.ACCEPTED) {
            if (msg[0] != VERSION) {
                return false;
            }
            else {
                int numMethods = message.get(1);
                for (int i = 2; i < numMethods + 2; ++i){
                    if (msg[i] == AUTH){
                        return true;
                    }
                }
                return false;
            }
        }
        else if (state == StateInfo.State.NO_CONNECTED){
            byte COMMAND = 0x01;
            if (msg[1] != COMMAND){
                return false;
            }
            else{
                byte DOMAIN = 0x03;
                return (msg[3] == IPv4) || (msg[3] == DOMAIN);
            }
        }
        return false;
    }

    InetAddress getAddress(ByteBuffer message) throws IOException {
        byte[] msg = message.array();
        if (msg[3] == IPv4) {
            byte[] addr = new byte[]{msg[4], msg[5], msg[6], msg[7]};
            return InetAddress.getByAddress(addr);
        }
        throw new IOException();
    }

    int getPort(ByteBuffer message, int length){
        byte[] msg = message.array();
        return (((0xFF & msg[length-2]) << 8) + (0xFF & msg[length-1]));
    }

    String getDomain(ByteBuffer message){
        int length = message.array()[4];
        byte[] domainBytes = Arrays.copyOfRange(message.array(), 5, length + 5);
        return new String(domainBytes);
    }

    ByteBuffer getAcceptAnswer(){
        ByteBuffer byteBuffer = ByteBuffer.allocate(2);
        byteBuffer.put(VERSION);
        byteBuffer.put(AUTH);
        return byteBuffer;
    }

    ByteBuffer getConnectAnswer(int listenPort, boolean isConnected){
        ByteBuffer byteBuffer = ByteBuffer.allocate(10);
        byte[] tmp;
        if (isConnected)
            tmp = new byte[]{
                    VERSION,
                    AUTH, //access granted
                    AUTH, //reserved
                    IPv4,
                    0x7F,
                    0x00,
                    0x00,
                    0x01, //localhost
                    (byte) ((listenPort >> 8) & 0xFF), (byte) (listenPort & 0xFF)};
        else
            tmp = new byte[]{
                    VERSION,
                    0x01, //SOCKS issue
                    AUTH, //reserved
                    IPv4,
                    0x7F,
                    0x00,
                    0x00,
                    0x01, //localhost
                    (byte) ((listenPort >> 8) & 0xFF), (byte) (listenPort & 0xFF)};
        byteBuffer.put(tmp);
        return byteBuffer;
    }
}
