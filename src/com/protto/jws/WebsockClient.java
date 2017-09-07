package com.protto.jws;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class WebsockClient {
  
  private enum WebsockState {
    Closed, Connecting, Open;
  }
  
  private enum WebsockOpcode {
    Continue(0x00), Text(0x01), Binary(0x02),
    Close(0x08), Ping(0x09), Pong(0x0a);
    private final int value;
    WebsockOpcode(final int value) {
      this.value = value;
    }
    public final int value() {
      return value;
    }
    public static final WebsockOpcode get(final int value) {
      switch (value) {
        case 0x00: return Continue;
        case 0x01: return Text;
        case 0x02: return Binary;
        case 0x08: return Close;
        case 0x09: return Ping;
        case 0x0a: return Pong;
        default: return null;
      }
    }
  }
  
  private class WebsockFrame {
    public boolean fin;
    public byte[] mask;
    public boolean[] rsv;
    public boolean masked;
    public byte[] payload;
    public int payloadSize;
    public WebsockOpcode opcode;
  }
  
  private class WebsockPing {
    public final long created;
    public final BiConsumer<Long, byte[]> callback;
    public WebsockPing(final BiConsumer<Long, byte[]> callback) {
      this.callback = callback;
      this.created = System.nanoTime();
    }
  }
  
  private WebsockState state;
  private final WebsockFrame frame;
  private final SocketClient client;
  private final Deque<WebsockPing> pings;
  private final ByteArrayStream fragmentBuilder;
  
  private Consumer<byte[]> pongCallback;
  private Consumer<byte[]> messageCallback;
  private Consumer<WebsockClient> connectCallback;
  private BiConsumer<Integer, String> closeCallback;
  private BiConsumer<HttpRequest, HttpUpgrade> upgradeCallback;
  
  private static ByteArrayPattern clrfPattern;
  private static final byte[] defaultPingData = new byte[] {'P','i','n','g'};
  
  public WebsockClient(final SocketClient client) {
    this.client = client;
    frame = new WebsockFrame();
    frame.rsv = new boolean[3];
    fragmentBuilder = new ByteArrayStream();
    pings = new LinkedBlockingDeque<WebsockPing>();
    
    if (clrfPattern == null)
      clrfPattern = new ByteArrayPattern(new byte[] {'\r','\n','\r','\n'});
    handshake();
  }
  
  public final SocketClient getSocketClient() {
    return client;
  }
  
  public final boolean isConnected() {
    return state == WebsockState.Open && client.isConnected();
  }
  
  public WebsockClient onPong(final Consumer<byte[]> callback) {
    pongCallback = callback;
    return this;
  }
  
  public WebsockClient onMessage(final Consumer<byte[]> callback) {
    messageCallback = callback;
    return this;
  }
  
  public WebsockClient onConnect(final Consumer<WebsockClient> callback) {
    connectCallback = callback;
    return this;
  }
  
  public WebsockClient onClose(final BiConsumer<Integer, String> callback) {
    closeCallback = callback;
    return this;
  }
  
  public WebsockClient onUpgrade(final BiConsumer<HttpRequest, HttpUpgrade> callback) {
    upgradeCallback = callback;
    return this;
  }
  
  public WebsockClient closeConnection() {
    if (!client.isConnected())
      return this;
    pings.clear();
    fragmentBuilder.clear();
    try {
      client.close();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
    state = WebsockState.Closed;
    return this;
  }
  
  public WebsockClient ping(final BiConsumer<Long, byte[]> callback) {
    return ping(defaultPingData, callback);
  }
  
  public WebsockClient ping(final byte[] data, final BiConsumer<Long, byte[]> callback) {
    pings.add(new WebsockPing(callback));
    send(data, data.length, WebsockOpcode.Ping);
    return this;
  }
  
  public WebsockClient close() {
    return close(1000, "");
  }
  
  public WebsockClient close(final String reason) {
    return close(1000, reason.getBytes(Charset.defaultCharset()));
  }
  
  public WebsockClient close(final int code, final String reason) {
    return close(code, reason.getBytes(Charset.defaultCharset()));
  }
  
  public WebsockClient close(final int code, final byte[] reason) {
    if (isConnected())
      return this;
    
    final byte[] data = new byte[reason.length + 2];
    data[0] = (byte)((code >> 8) & 0xff);
    data[1] = (byte)(code & 0xff);
    System.arraycopy(reason, 0, data, 2, reason.length);
    send(data, data.length, WebsockOpcode.Close, () -> {
      state = WebsockState.Closed;
    });
    return this;
  }
  
  public WebsockClient send(final String data) {
    return send(data.getBytes(Charset.defaultCharset()));
  }
  
  public WebsockClient send(final byte[] data) {
    return send(data, data.length, WebsockOpcode.Text, null);
  }
  
  public WebsockClient send(final byte[] data, final int amount) {
    return send(data, amount, WebsockOpcode.Text, null);
  }
  
  public WebsockClient sendBytes(final byte[] data) {
    return send(data, data.length, WebsockOpcode.Binary, null);
  }
  
  public WebsockClient sendBytes(final byte[] data, final int amount) {
    return send(data, amount, WebsockOpcode.Binary, null);
  }
  
  private WebsockClient send(final byte[] data, final int amount, final WebsockOpcode opcode) {
    return send(data, amount, opcode, null);
  }
  
  private WebsockClient send(final byte[] data, final int size, final WebsockOpcode opcode, final Runnable callback) {
    if (!isConnected())
      return this;
    
    byte[] output;
    int i, padding, offset = 0;
    
    if (size < 126)
      padding = 0;
    else if (size < 65536)
      padding = 2;
    else
      padding = 8;
    
    output = new byte[2 + padding + size];
    output[offset++] = (byte)(0x80 | opcode.value());
    
    if (size < 126) {
      output[offset++] = (byte)(size);
    } else if (size < 65536) {
      output[offset++] = 126;
      for (i = 8; i > -1; i -= 8)
        output[offset++] = (byte)((size >> i) & 0xff);
    } else {
      output[offset++] = 127;
      for (i = 56; i > -1; i -= 8)
        output[offset++] = (byte)((size >> i) & 0xff);
    }
    
    for (i = 0; i < size; i++)
      output[offset++] = data[i];

    client.write(output, callback);
    return this;
  }
  
  private void handshake() {
    state = WebsockState.Connecting;
    
    client.readUntil(clrfPattern, httpData -> {
      try {
        HttpRequest request = new HttpRequest(client.getServer(), new String(httpData, Charset.defaultCharset()));
        HttpUpgrade upgrade = new HttpUpgrade(request.getHeader("Sec-WebSocket-Key"));
        if (upgradeCallback != null)
          upgradeCallback.accept(request, upgrade);
        request.dispose();
        
        if (upgrade.hasError()) {
          upgrade.dispose();
          closeConnection();
          
        } else {
          client.write(upgrade.toString().getBytes(Charset.defaultCharset()), () -> {
            state = WebsockState.Open;
            if (connectCallback != null)
              connectCallback.accept(this);
            parseHeaders();
            System.gc();
          });
          upgrade.dispose();
        }
        
      } catch (Exception ex) {
        ex.printStackTrace();
        closeConnection();
      }
    });
  }
  
  private void parseHeaders() {
    if (!client.isConnected())
      return;
    
    client.read(2, header -> {
      frame.fin    = ((header[0] >> 7) & 1) > 0;
      frame.rsv[0] = ((header[0] >> 6) & 1) > 0;
      frame.rsv[1] = ((header[0] >> 5) & 1) > 0;
      frame.rsv[2] = ((header[0] >> 4) & 1) > 0;
      frame.opcode = WebsockOpcode.get(header[0] & 0x0f);
      
      frame.payloadSize = (int)((header[1] & 0xff) & (~0x80));
      frame.masked = ((header[1] >> 7) & 1) > 0;
      
      parsePayloadLength();
    });
  }
  
  private void parsePayloadLength() {
    
    if (frame.payloadSize < 126) {
      parseMask();
      return;
    }
    
    final int padding = frame.payloadSize < 65536 ? 2 : 8;
    client.read(padding, length -> {
      frame.payloadSize = 0;
      
      if (padding == 2) {
        for (short j = 0, i = 8; i > -1; i -= 8, j++)
          frame.payloadSize |= (length[j] & 0xff) << i;
        
      } else {
        for (short j = 0, i = 56; i > -1; i -= 8, j++)
          frame.payloadSize |= (length[j] & 0xff) << i;
      }
      
      parseMask();
    });
  }
  
  private void parseMask() {
    if (!frame.masked) {
      parsePayload();
    } else {
      client.read(4, mask -> {
        frame.mask = mask;
        parsePayload();
      });
    }
  }
  
  private void parsePayload() {
    client.read(frame.payloadSize, payload -> {
      frame.payload = payload;
      
      if (frame.masked && frame.mask != null)
        for (int i = 0; i < frame.payloadSize; i++)
          frame.payload[i] ^= frame.mask[i % 4];
  
      processFrame();
    });
  }
  
  private void processFrame() {

    if (!frame.fin || frame.opcode == WebsockOpcode.Continue) {
      fragmentBuilder.write(frame.payload);
      parseHeaders();
      return;
      
    } else {
      final int fragSize = fragmentBuilder.size();
      if (fragSize > 0) {
        byte[] combined = Arrays.copyOf(fragmentBuilder.read(fragSize), fragSize + frame.payloadSize);
        System.arraycopy(frame.payload, 0, combined, fragSize, frame.payloadSize);
        frame.payload = null;
        frame.payload = combined;
        frame.payloadSize = fragSize + frame.payloadSize;
      }
    }
    
    switch (frame.opcode) {
      case Close: {
        // parse close data
        int code = ((frame.payload[0] & 0xff) << 8) | (frame.payload[1] & 0xff);
        String reason = null;
        if (frame.payloadSize > 2) {
          byte[] reasonData = new byte[frame.payloadSize - 2];
          System.arraycopy(frame.payload, 2, reasonData, 0, reasonData.length);
          reason = new String(reasonData, Charset.defaultCharset()); 
        }
        
        // client close response
        if (state == WebsockState.Closed) {
          closeConnection();
          if (closeCallback != null)
            closeCallback.accept(code, reason);
          
        // client initialized close
        } else {
          send(frame.payload, frame.payloadSize, WebsockOpcode.Close, () -> {
            closeConnection();
          });
          state = WebsockState.Closed;
          if (closeCallback != null)
            closeCallback.accept(code, reason);
        }
        break;
      }
        
      case Ping: {
        final byte[] pingData = Arrays.copyOf(frame.payload, frame.payloadSize);
        send(frame.payload, frame.payloadSize, WebsockOpcode.Pong, () -> {
          if (pongCallback != null && isConnected())
            pongCallback.accept(pingData);
        });
        break;
      }
        
      case Pong: {
        if (!pings.isEmpty()) {
          final WebsockPing pingEvent = pings.remove();
          final Long elapsed = (long)((System.nanoTime() - pingEvent.created) / 1e6);
          pingEvent.callback.accept(elapsed, frame.payload);
        }
        break;
      }
        
      case Text:
      case Binary:
        if (messageCallback != null)
          messageCallback.accept(frame.payload);
        break;
        
      default: break;
    }
    
    parseHeaders();
  }
}
