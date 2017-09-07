package com.protto.jws;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

public class SocketClient {
  
  private class ReadEvent {
    public final Object goal;
    public final Consumer<byte[]> callback;
    
    public ReadEvent(final Object goal, final Consumer<byte[]> callback) {
      this.goal = goal;
      this.callback = callback;
    }
  }
  
  private class WriteEvent {
    public final byte[] data;
    public final Runnable callback;
    public WriteEvent(final byte[] data, final Runnable callback) {
      this.data = data;
      this.callback = callback;
    }
  }
  
  private SelectionKey key;
  private WebsockServer server;
  private Runnable closeCallback;
  private final SocketChannel channel;
  
  
  private int dataRead;
  private int dataWritten;
  private ByteBuffer readBuffer;
  private volatile boolean connected;
  private final Deque<ReadEvent> readQueue;
  private final Deque<WriteEvent> writeQueue;
  private final ByteArrayStream readStream;
  
  public SocketClient(final WebsockServer server, final SocketChannel channel) throws SocketException {
    this.server = server;
    this.channel = channel;

    connected = true;
    readStream = new ByteArrayStream();
    readQueue = new LinkedBlockingDeque<ReadEvent>();
    writeQueue = new LinkedBlockingDeque<WriteEvent>();
    readBuffer = ByteBuffer.allocate(channel.socket().getReceiveBufferSize());
  }
  
  public final SelectionKey getKey() {
    return key;
  }
  
  public final WebsockServer getServer() {
    return server;
  }
  
  public final boolean isConnected() {
    return connected;
  }
  
  public synchronized void setKey(final SelectionKey key) {
    this.key = key;
  }
  
  private synchronized void addEvent(final int event) {
    if (key != null && ((key.interestOps() & event) != event))
      key.interestOps(key.interestOps() | event);
    server.getSelector().wakeup();
  }
  
  private synchronized void removeEvent(final int event) {
    if (key != null && ((key.interestOps() & event) == event))
      key.interestOps(key.interestOps() & ~event);
    server.getSelector().wakeup();
  }
  
  private void readAndSpawn(final int amount, final Consumer<byte[]> callback) {
    final byte[] data = readStream.read(amount);
    server.getThreadPool().submit(() -> {
      callback.accept(data);
    });
  }
  
  public void write(final byte[] data) {
    write(data, null);
  }
  
  public void write(final byte[] data, final Runnable callback) {
    if (!connected)
      return;
    writeQueue.add(new WriteEvent(data, callback));
    addEvent(SelectionKey.OP_WRITE);
  }
  
  public void read(final Consumer<byte[]> callback) {
    read(Math.max(readStream.size(), 1), callback);
  }
  
  public void read(final int amount, final Consumer<byte[]> callback) {
    if (!connected)
      return;
    if (readStream.size() >= amount)
      readAndSpawn(amount, callback);
    else
      readQueue.add(new ReadEvent(new Integer(amount - readStream.size()), callback));
  }
  
  public void readUntil(final ByteArrayPattern matcher, final Consumer<byte[]> callback) {
    if (!connected)
      return;
    int index = matcher.find(readStream.data(), readStream.pos(), readStream.size());
    if (index >= 0)
      readAndSpawn(index + matcher.size(), callback);
    else
      readQueue.add(new ReadEvent(matcher, callback));
  }
  
  public void close() throws IOException {
    if (!connected)
      return;
    connected = false;
    channel.shutdownInput();
    channel.shutdownOutput();
    if (key != null)
      key.cancel();
    
    // free buffers & queues
    readBuffer.clear();
    readBuffer = null;
    readStream.clear();
    readQueue.clear();
    writeQueue.clear();
    
    // remove from connected clients
    final Iterator<WebsockClient> clients = server.getClients().iterator();
    while (clients.hasNext()) {
      if (clients.next().getSocketClient() == this) {
        clients.remove();
        break;
      }
    }
        
    // deference server, perform callback and do a GC cycle
    server = null;
    if (closeCallback != null)
      closeCallback.run();
    System.gc();
  }
  
  protected void performRead() throws IOException {
    try {
      while ((dataRead = channel.read(readBuffer)) > 0) {
        readStream.write(readBuffer.array(), dataRead);
        readBuffer.clear();
      }
    } catch (IOException ex) {
      close();
      return;
    }
    
    if (dataRead < 0)
      close();
    else if (!readQueue.isEmpty())
      handleReadTasks();
  }
  
  public void performWrite() throws IOException {
    if (writeQueue.isEmpty()) {
      removeEvent(SelectionKey.OP_WRITE);
      
    } else {
      final WriteEvent event = writeQueue.remove();
      final ByteBuffer writeBuffer = ByteBuffer.wrap(event.data);
      
      try {
        dataWritten = channel.write(writeBuffer);
        while (dataWritten > 0 && writeBuffer.hasRemaining())
          dataWritten = channel.write(writeBuffer);
      } catch (IOException ex) {
        close();
        return;
      }
      
      if (dataWritten < 1)
        close();
      else if (event.callback != null)
        server.getThreadPool().submit(event.callback);
    }
  }
  
  private void handleReadTasks() {
    ReadEvent event;
    int i, index, queueSize = readQueue.size();
    for (i = 0; i < queueSize; i++) {
      if (readQueue.isEmpty()) break;
      event = readQueue.remove();
      
      if (event.goal == null) {
        if (readStream.size() > 0)
          readAndSpawn(readStream.size(), event.callback);
        else
          readQueue.add(event);
      } else if (event.goal instanceof ByteArrayPattern) {
        index = ((ByteArrayPattern)event.goal).find(readStream.data(), readStream.pos(), readStream.size());
        if (index >= 0)
          readAndSpawn(index + ((ByteArrayPattern)event.goal).size(), event.callback);
        else
          readQueue.add(event);
      } else if (event.goal instanceof Integer) {
        if (readStream.size() >= (Integer)event.goal)
          readAndSpawn(((Integer)event.goal), event.callback);
        else
          readQueue.add(event);
      }
    }
  }
  
  
}
