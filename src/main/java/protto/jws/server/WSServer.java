package protto.jws.server;

import java.io.IOException;
import java.util.function.Consumer;

public class WSServer extends SocketServer {
  private Consumer<WSClient> connectCallback;
  
  /**
   * Websocket Wrapper around the async TCP Server
   * @param port the port to run the server on
   */
  public WSServer(int port) throws Exception {
    super(port);
    WSFrame.FrameInit();
    wrap();
  }

  /**
   * Print running when run
   */ 
  @Override
  public void run() throws IOException {
    System.out.println(" === Server started on http://localhost:" + getPort() + " === ");
    super.run();
  }
  
  /**
   * Bind ACCEPT event to wrapped callback
   * @param callback the callback to websocket wrap
   */
  public void onConnection(final Consumer<WSClient> callback) {
    connectCallback = callback;
  }
  
  /**
   * Wrap the server callback events with websocket ones
   */
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
