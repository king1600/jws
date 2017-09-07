package com.protto.jws;

import java.util.Arrays;

public class ByteArrayStream {
  
  private volatile int length;
  private volatile int maximum;
  private volatile int position;
  private volatile byte[] stream;
  public static int DefaultSize = 1024;
  
  public ByteArrayStream() {
    this(1024);
  }
  
  public ByteArrayStream(final int max) {
    stream = new byte[max];
    length = 0;
    position = 0;
    maximum = max;
  }
  
  public byte[] data() {
    return stream;
  }
  
  public int capacity() {
    return maximum;
  }
  
  public int size() {
    return Math.max(length - position, 0);
  }
  
  public int pos() {
    return position;
  }
  
  public void clear() {
    stream = null;
    length = 0;
    maximum = 0;
    position = 0;
  }
  
  public void write(final byte[] data) {
    write(data, data.length);
  }
  
  public byte[] read(int amount) {
    if (amount < 1 || size() < 1)
      return null;
    amount = Math.min(amount, size());
    byte[] output = new byte[amount];
    System.arraycopy(stream, position, output, 0, amount);
    position += amount;
    return output;
  }
  
  public void write(final byte[] data, final int amount) {
    if (amount < 1)
      return;
    if (position != 0) {
      System.arraycopy(stream, position, stream, 0, size());
      length -= position;
      position = 0;
    }
    if (length + amount > position) {
      maximum = (length + amount) * 11 / 10;
      stream = Arrays.copyOf(stream, maximum);
    }
    System.arraycopy(data, 0, stream, length, amount);
    length += amount;
  }
}
