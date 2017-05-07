package protto.jws.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public class ClientSocket {
  private boolean open = true;
  private  SelectionKey event;
  private ByteBuffer dataBuffer;
  private  SocketChannel channel;
  private Runnable writeCallback;
  private Queue<byte[]> writeQueue;
  private Consumer<byte[]> readCallback;
  private static final int READ_BUFFER = 4*1024;

  public ClientSocket(final SocketChannel chan, final SelectionKey ev) {
    event = ev;
    channel = chan;
    writeQueue = new LinkedList<byte[]>();
  }

  /**
   * Internal function called by event loop to write queued data
   */
  public void internalWrite() {
    try {
      while (!writeQueue.isEmpty()) {
        dataBuffer = ByteBuffer.wrap(writeQueue.remove());
        int written = channel.write(dataBuffer);
        while (written > 0 && dataBuffer.hasRemaining())
          written = channel.write(dataBuffer);
      }
      removeFlag(SelectionKey.OP_WRITE);
      onWrite();
    } catch (Exception e) {
      e.printStackTrace();
      close();
    }
  }

  /**
   * Internal function called by event loop to read data
   */
  public void internalRead() {
    try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
      int read = 0;
      dataBuffer = ByteBuffer.allocate(READ_BUFFER);
      while ((read = channel.read(dataBuffer)) > 0) {
        stream.write(dataBuffer.array());
        dataBuffer.clear();
      }
      if (read == -1) close();
      else onRead(stream.toByteArray());
    } catch (Exception e) {
      e.printStackTrace();
      close();
    }
  }

  /**
   * Write a byte array to the stream
   * @param data the data to write
   */
  public void write(byte[] data) {
    writeQueue.add(data);
    addFlag(SelectionKey.OP_WRITE);
  }

  
  public void addFlag(int flag) {
    event.interestOps(event.interestOps() | flag);
  }

  public void removeFlag(int flag) {
    event.interestOps(event.interestOps() & ~flag);
  }

  public boolean isOpen() {
    return open;
  }
  
  public Socket getSocket() {
    return channel.socket();
  }

  public SelectionKey getEvent() {
    return event;
  }

  public SocketChannel getChannel() {
    return channel;
  }

  public void setRead(Consumer<byte[]> cb) {
    readCallback = cb;
  }

  public void setWrite(Runnable cb) {
    writeCallback = cb;
  }

  public void setEvent(SelectionKey event) {
    this.event = event;
  }

  public void close() {
    try {
      event.cancel();
      channel.close();
      System.out.println("Internal close");
    } catch (IOException e) {
    }
    open = false;
  }

  public void onWrite() {
    if (writeCallback != null)
      writeCallback.run();
  }

  public void onRead(byte[] data) {
    if (readCallback != null)
      readCallback.accept(data);
  }
}
