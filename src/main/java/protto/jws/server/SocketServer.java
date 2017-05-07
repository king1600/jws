package protto.jws.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.function.Consumer;

public class SocketServer {
  private int port;
  private boolean running;
  private final Selector selector;
  private ServerSocketChannel server;
  private Consumer<ClientSocket> acceptCallback;

  /**
   * Create a TCP Server on given port
   * @param port port to host serve ron
   */
  public SocketServer(int port) throws IOException {
    // create the server socket and IO Selector
    this.port = port;
    selector = SelectorProvider.provider().openSelector();
    server = ServerSocketChannel.open();
    InetAddress addr = InetAddress.getLoopbackAddress();
    server.configureBlocking(false);
    server.socket().setReuseAddress(true);
    server.bind(new InetSocketAddress(addr, port), 1024);
    server.register(selector, SelectionKey.OP_ACCEPT);

    // add close signal hook
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){
      public void run() { close(); }
    }));
  }

  /**
   * Register a socket event to the selector
   * @param event the event to register
   */
  private void registerSocket(SelectionKey event) throws IOException {
    SocketChannel socket = server.accept();
    socket.configureBlocking(false);
    ClientSocket client = new ClientSocket(socket, null);
    client.setEvent(socket.register(selector, SelectionKey.OP_READ, client));
    if (acceptCallback != null)
      acceptCallback.accept(client);
  }

  /**
   * Get the port the server is currently hosted on
   * @return the port
   */
  public int getPort() {
    return port;
  }

  /**
   * Bind ACCEPT event to a callback
   * @param callback the event to bind to
   */
  public void onAccept(Consumer<ClientSocket> callback) {
    acceptCallback = callback;
  }

  /**
   * Close the server
   */
  public void close() {
    try {
      running = false;
      selector.keys().forEach(key -> {
        try {
          key.channel().close();
          key.cancel();
        } catch (IOException e){}
      });
      selector.wakeup();
      selector.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
    System.exit(0); // TODO: make optional
  }

  /**
   * Start the server
   */
  public void run() throws IOException {
    int active;
    SelectionKey event;
    ClientSocket socket;
    Iterator<SelectionKey> events;

    // io event loop
    running = true;
    while (running) {

      // select current IO events from registered sockets
      active = selector.select();
      if (active == 0) continue;
      events = selector.selectedKeys().iterator();

      // iterate through the found events
      while (events.hasNext()) {
        try {

          // validate the event
          event = events.next();
          events.remove();
          if (!event.isValid()) continue;

          // handle server accept event
          if (event.channel() == server) {
            if (event.isAcceptable())
              registerSocket(event);

          // handle client events
          } else {
            if (event.attachment() != null) {
              // fetch the attached socket
              socket = (ClientSocket)event.attachment();

              // validate socket again lol
              if (!socket.isOpen()) event.cancel();
              if (event.channel() != socket.getChannel()) continue;

              // handle read event
              if (event.isReadable() && socket.isOpen())
                socket.internalRead();

              // handle write event
              if (event.isWritable() && socket.isOpen())
                socket.internalWrite();
            }
          }

        // skip iteration on error
        } catch (Exception e) {
          continue;
        }
      }
    }
  }
}
