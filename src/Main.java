import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        try {
            Forwarder forwarder = new Forwarder(8089);
            forwarder.start();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

    }
}
