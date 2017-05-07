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

  public SocketServer(int port) throws IOException {
    this.port = port;
    selector = SelectorProvider.provider().openSelector();
    server = ServerSocketChannel.open();
    InetAddress addr = InetAddress.getLoopbackAddress();
    server.configureBlocking(false);
    server.socket().setReuseAddress(true);
    server.bind(new InetSocketAddress(addr, port), 1024);
    server.register(selector, SelectionKey.OP_ACCEPT);

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){
      public void run() { close(); }
    }));
  }

  private void registerSocket(SelectionKey event) throws IOException {
    SocketChannel socket = server.accept();
    socket.configureBlocking(false);
    ClientSocket client = new ClientSocket(socket, null);
    client.setEvent(socket.register(selector, SelectionKey.OP_READ, client));
    if (acceptCallback != null)
      acceptCallback.accept(client);
  }

  public int getPort() {
    return port;
  }

  public void onAccept(Consumer<ClientSocket> callback) {
    acceptCallback = callback;
  }

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
    System.exit(0);
  }

  public void run() throws IOException {
    int active;
    SelectionKey event;
    ClientSocket socket;
    Iterator<SelectionKey> events;

    running = true;
    while (running) {
      active = selector.select();
      if (active == 0) continue;
      events = selector.selectedKeys().iterator();
      while (events.hasNext()) {
        try {
          event = events.next();
          events.remove();
          if (!event.isValid()) continue;
          if (event.channel() == server) {
            if (event.isAcceptable())
              registerSocket(event);
          } else {
            if (event.attachment() != null) {
              socket = (ClientSocket)event.attachment();
              if (!socket.isOpen()) event.cancel();
              if (event.channel() != socket.getChannel()) continue;
              if (event.isReadable() && socket.isOpen())
                socket.internalRead();
              if (event.isWritable() && socket.isOpen())
                socket.internalWrite();
            }
          }
        } catch (Exception e) {
          continue;
        }
      }
    }
  }
}
