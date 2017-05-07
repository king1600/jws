package protto.jws.server;

import java.io.IOException;
import java.util.function.Consumer;

public class WSServer extends SocketServer {
  private Consumer<WSClient> connectCallback;
  
  public WSServer(int port) throws Exception {
    super(port);
    WSFrame.FrameInit();
    wrap();
  }
  
  @Override
  public void run() throws IOException {
    System.out.println(" === Server started on http://localhost:" + getPort() + " === ");
    super.run();
  }
  
  public void onConnection(final Consumer<WSClient> callback) {
    connectCallback = callback;
  }
  
  private void wrap() {
    onAccept(socket -> {
      WSClient client = new WSClient(socket);
      if (connectCallback != null)
        client.onConnect(() -> {
          connectCallback.accept(client);
        });
      client.wrap();
    });
  }
}
