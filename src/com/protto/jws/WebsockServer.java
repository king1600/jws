package com.protto.jws;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class WebsockServer {
  
  private final int port;
  private final Selector selector;
  private volatile boolean running;
  private final ExecutorService pool;
  private final ServerSocketChannel server;
  private Consumer<WebsockClient> acceptCallback;
  private final BlockingQueue<WebsockClient> clients;
  private BiConsumer<HttpRequest, HttpUpgrade> upgradeCallback;
  
  public WebsockServer() throws Exception {
    this(8080);
  }
  
  public WebsockServer(final int sport) throws Exception {
    this(sport, Runtime.getRuntime().availableProcessors() * 10 / 8);
  }
  
  public WebsockServer(final int sport, final int threads) throws Exception {
    port = sport;
    running = false;
    pool = Executors.newFixedThreadPool(threads);
    clients = new LinkedBlockingQueue<WebsockClient>();
    selector = SelectorProvider.provider().openSelector();
    
    server = ServerSocketChannel.open();
    server.socket().setReuseAddress(true);
    server.socket().setPerformancePreferences(2, 1, 0);
    server.socket().bind(new InetSocketAddress(port));
    server.configureBlocking(false);
    server.register(selector, SelectionKey.OP_ACCEPT);
    
    if (HttpUpgrade.closeCodes == null)
      HttpUpgrade.Initialize();
  }
  
  public void stop() {
    running = false;
    selector.wakeup();
  }
  
  public int getPort() {
    return port;
  }
  
  public final Selector getSelector() {
    return selector;
  }
  
  public final ExecutorService getThreadPool() {
    return pool;
  }
  
  public final BlockingQueue<WebsockClient> getClients() {
    return clients;
  }
  
  public final SocketAddress getAddress() throws IOException {
    return server.getLocalAddress();
  }
  
  public WebsockServer onUpgrade(final BiConsumer<HttpRequest, HttpUpgrade> callback) {
    upgradeCallback = callback;
    return this;
  }
  
  public WebsockServer onConnection(final Consumer<WebsockClient> callback) {
    acceptCallback = callback;
    return this;
  }
  
  private void acceptClient() throws IOException, InterruptedException {
    SocketChannel channel = server.accept();
    channel.socket().setTcpNoDelay(true);
    channel.socket().setPerformancePreferences(0, 2, 1);
    channel.configureBlocking(false);
    
    final WebsockClient client = new WebsockClient(new SocketClient(this, channel));
    client.getSocketClient().setKey(channel.register(
      selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ, client));
    client.onUpgrade(upgradeCallback);
    client.onConnect(acceptCallback);
    clients.put(client);
  }
  
  private void dispose() {
    try {
      while (!clients.isEmpty()) {
        try {
          clients.take().getSocketClient().close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
      
      for (final SelectionKey key : selector.keys()) {
        try {
          key.cancel();
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }
      
      selector.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }
  
  public void start() throws IOException {
    SelectionKey event;
    int selectedEvents;
    WebsockClient client;
    Iterator<SelectionKey> events;
    
    running = true;
    System.out.printf("Server started on %s\n", getAddress().toString());
    while (running) {
      
      selectedEvents = selector.select();
      if (selectedEvents < 1) continue;
      events = selector.selectedKeys().iterator();
      
      while (events.hasNext()) {
        
        event = events.next();
        events.remove();

        try {
          if (event.channel() == server && event.isValid()) {
            acceptClient();
          } else {
            
            if (!event.isValid()) {
              if (event.attachment() instanceof WebsockClient)
                ((WebsockClient)event.attachment()).getSocketClient().close();
              continue;
            }
            
            client = (WebsockClient)event.attachment();
            client.getSocketClient().setKey(event);
            if (event.isWritable() && event.isValid())
              client.getSocketClient().performWrite();
            if (event.isReadable() && event.isValid())
              client.getSocketClient().performRead();
          }
          
        } catch (CancelledKeyException ex) {
          
        } catch (Exception ex) {
          ex.printStackTrace();
        }
 
      }
    }
    
    dispose();
  }
}
