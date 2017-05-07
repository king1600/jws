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
      // write any data in write queue to socket
      while (!writeQueue.isEmpty()) {
        dataBuffer = ByteBuffer.wrap(writeQueue.remove());
        int written = channel.write(dataBuffer);
        while (written > 0 && dataBuffer.hasRemaining())
          written = channel.write(dataBuffer);
      }
      removeFlag(SelectionKey.OP_WRITE); // done writing stop listening for write
      onWrite();                         // do callback

    // close on error
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

      // read data from socket
      int read = 0;
      dataBuffer = ByteBuffer.allocate(READ_BUFFER);
      while ((read = channel.read(dataBuffer)) > 0) {
        stream.write(dataBuffer.array());
        dataBuffer.clear();
      }
      
      // handle read event after effects
      if (read == -1) close();
      else onRead(stream.toByteArray());

    // close on error
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

  /**
   * add a SELECT event flag to the socket event
   * @param flag the flag to add
   */
  public void addFlag(int flag) {
    event.interestOps(event.interestOps() | flag);
  }

  /**
   * remove a SELECT event flag from the socket event
   * @param flag the flag to remove
   */
  public void removeFlag(int flag) {
    event.interestOps(event.interestOps() & ~flag);
  }

  /**
   * Check if connection is open
   * @return connection state
   */
  public boolean isOpen() {
    return open;
  }
  
  /**
   * Get the underlying java Socket
   * @return java Socket
   */
  public Socket getSocket() {
    return channel.socket();
  }

  /**
   * Get the IO Event Key
   * @return the event key
   */
  public SelectionKey getEvent() {
    return event;
  }

  /**
   * Get the nio SocketChannel
   * @return the socket channel
   */
  public SocketChannel getChannel() {
    return channel;
  }

  /**
   * Set the READ event callback
   * @param cb the callback
   */
  public void setRead(Consumer<byte[]> cb) {
    readCallback = cb;
  }

  /**
   * Set the WRITE event callback
   * @param cb the callback
   */
  public void setWrite(Runnable cb) {
    writeCallback = cb;
  }

  /**
   * Set the IO Event Key
   * @param event the io event key
   */
  public void setEvent(SelectionKey event) {
    this.event = event;
  }

  /**
   * Close the socket connection and detach from io loop
   */
  public void close() {
    try {
      event.cancel();
      channel.close();
      System.out.println("Internal close");
    } catch (IOException e) {
    }
    open = false;
  }

  /**
   * Execute the write callback
   */
  public void onWrite() {
    if (writeCallback != null)
      writeCallback.run();
  }

  /**
   * Evexute the read callback
   */
  public void onRead(byte[] data) {
    if (readCallback != null)
      readCallback.accept(data);
  }
}
