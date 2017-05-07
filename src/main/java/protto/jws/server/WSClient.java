package protto.jws.server;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class WSClient {
  private WSFrame.OpCode lastOpCode;
  private final ClientSocket socket;
  private boolean closed = false;
  private boolean handshaked = false;
  private Runnable connectionCallback;
  private ByteArrayOutputStream reader;
  private Consumer<byte[]> pingCallback;
  private Consumer<byte[]> pongCallback;
  private Consumer<Exception> errorCallback;
  private BiConsumer<Integer, String> closeCallback;
  private BiConsumer<byte[], WSFrame.OpCode> messageCallback;
  
  public WSClient(final ClientSocket sock) {
    socket = sock;
    reader = new ByteArrayOutputStream();
  }
  
  public void wrap() {
    socket.setWrite(() -> {
      if (closed) {
        socket.close();
        return;
      }
      if (!handshaked) {
        handshaked = true;
        if (connectionCallback != null)
          connectionCallback.run();
      }
    });
    socket.setRead(data -> {
      try {
        if (!handshaked) {
          handshake(data);
        } else {
          WSFrame frame = parseFrame(data);
          lastOpCode = frame.getOpCode() == null ? lastOpCode : frame.getOpCode();
          if (!frame.isMasked()) {
            close(1008);
          } else if (frame.getOpCode() == WSFrame.OpCode.CLOSE) {
            close(frame);
          } else {
            reader.write(frame.payload);
            if (frame.isFin()) {
              if (frame.getOpCode() == WSFrame.OpCode.PING) {
                if (pingCallback != null)
                  pingCallback.accept(frame.payload);
                send(new WSFrame(true, false, WSFrame.OpCode.PONG, frame.payload));
              } else if (frame.getOpCode() == WSFrame.OpCode.PONG) {
                if (pongCallback != null)
                  pongCallback.accept(frame.payload);
              } else {
               if (messageCallback != null)
                 messageCallback.accept(reader.toByteArray(), lastOpCode);
              }
              reader.reset();
            }
          }
        }
      } catch (Exception e) {
        error(e);
      }
    });
  }
  
  private void handshakeError(String message) {
    String request = new StringBuilder()
        .append("HTTP/1.1 400 Bad Request\r\n")
        .append("Server: WSClient\r\n")
        .append("Content-Type: text/plain\r\n")
        .append("Content-Length: ")
        .append(Integer.toString(message.length()))
        .append("\r\n\r\n")
        .append(message)
        .toString();
    closed = true;
    socket.write(request.getBytes());
  }
  
  private void handshake(byte[] data) throws Exception {
    String key = null, version = null, extensions = null;
    List<String> parts = split(new String(data), "\r\n");
    for (int i = 0; i < parts.size(); i++) {
      if (parts.get(i).indexOf("Sec-WebSocket-Key") > -1)
        key = split(parts.get(i),":").get(1).substring(1).trim();
      else if (parts.get(i).indexOf("Sec-WebSocket-Version") > -1)
        version = split(parts.get(i),":").get(1).substring(1).trim();
      else if (parts.get(i).indexOf("Sec-WebSocket-Extensions") > -1)
        extensions = split(parts.get(i),":").get(1).substring(1).trim();
    }
    if (key == null) {
      handshakeError("No Sec-WebSocket-Key provided");
    } else if (version == null) {
      handshakeError("No Sec-WebSocket-Version provided");
    } else if (!version.equals("13")) {
      handshakeError("Sec-WebSocket-Version 13 only supported");
    } else if (extensions != null) {
      handshakeError("Sec-WebSocket-Extensions not supported");
    } else {
      String request = new StringBuilder()
          .append("HTTP/1.1 101 Switching Protocols\r\n")
          .append("Upgrade: websocket\r\n")
          .append("Connection: Upgrade\r\n")
          .append("Sec-WebSocket-Accept: ")
          .append(WSFrame.CreateKey(key))
          .append("\r\n\r\n")
          .toString();
      socket.write(request.getBytes());
    }
  }
  
  private WSFrame parseFrame(byte[] data) throws Exception {
    WSFrame frame = new WSFrame();
    ByteBuffer buffer = ByteBuffer.wrap(data);
    
    // get if finished and opcode
    byte b = buffer.get();
    frame.setFin((b & 0x80) != 0);
    for (int i = 16; i < 65; i *= 16)
      if ((b & i) != 0)
        throw new Exception("Reserved Flags must be 0");
    frame.setOpCode(WSFrame.OpCode.get(b & 0x0f));
    
    // get if masked and payload length
    b = buffer.get();
    frame.setMasked((b & 0x80) != 0);
    int payloadLength = (byte)(0x7F & b);
    int lengthExtend  = 0;
    if (payloadLength == 0x7F)
      lengthExtend = 8;
    else if (payloadLength == 0x7E)
      lengthExtend = 2;
    while (--lengthExtend > 0) {
      b = buffer.get();
      payloadLength |= (b & 0xFF) << (8 * lengthExtend);
    }
    
    // get masked key and payload
    byte[] maskedKey = null;
    if (frame.isMasked()) {
      maskedKey = new byte[4];
      buffer.get(maskedKey, 0, maskedKey.length);
    }
    frame.payload = new byte[payloadLength];
    buffer.get(frame.payload, 0, payloadLength);
    if (frame.isMasked())
      for (int i = 0; i < payloadLength; i++)
        frame.payload[i] ^= maskedKey[i % 4];
    
    // return parsed frame
    return frame;
  }
  
  private List<String> split(String str, final String delim) {
    int index = 0;
    List<String> results = new ArrayList<String>();
    while (true) {
      index = str.indexOf(delim);
      if (index < 0) {
        results.add(str);
        break;
      }
      results.add(str.substring(0, index));
      str = str.substring(index + delim.length());
    }
    return results;
  }
  
  public void send(byte[] data) {
    send(new WSFrame(true, false, WSFrame.OpCode.BINARY, data));
  }
  
  public void send(String data) {
    send(new WSFrame(true, false, WSFrame.OpCode.TEXT, data.getBytes(WSFrame.UTF8)));
  }
  
  public void ping() {
    ping(new byte[]{});
  }
  
  public void ping(byte[] data) {
    send(new WSFrame(true, false, WSFrame.OpCode.PING, data));
  }
  
  public void ping(String data) {
    send(new WSFrame(true, false, WSFrame.OpCode.PING, data.getBytes(WSFrame.UTF8)));
  }
  
  public void send(final WSFrame frame) {
    int start   = -1;
    int length  = frame.payload.length;
    int fin     = (frame.isFin() ? 0x10000000 : 0) | frame.getOpCode().getValue();
    int mask    = frame.isMasked() ? 0x10000000 : 0;
    byte[] buffer = new byte[frame.getFinalSize()];
    buffer[0] = (byte)(fin ^ 0x80);
    if (length <= 125) {
      buffer[1] = (byte)((mask | length));
      start     = 2;
    } else if (length >= 126 && length <= WSFrame.MAX) {
      buffer[1] = (byte)((mask | 0x7E));
      buffer[2] = (byte)((length >> 8) & 0xFF);
      buffer[3] = (byte)((length     ) & 0xFF);
      start     = 4;
    } else {
      buffer[1] = (byte)((mask | 0x7F));
      buffer[2] = (byte)((length >> 56) & 0xFF);
      buffer[3] = (byte)((length >> 48) & 0xFF);
      buffer[4] = (byte)((length >> 40) & 0xFF);
      buffer[5] = (byte)((length >> 32) & 0xFF);
      buffer[6] = (byte)((length >> 24) & 0xFF);
      buffer[7] = (byte)((length >> 16) & 0xFF);
      buffer[8] = (byte)((length >> 8) & 0xFF);
      buffer[9] = (byte)((length     ) & 0xFF);
      start     = 10;
    }
    for (int i = 0; i < length; i++)
      buffer[start + i] = frame.payload[i];
    socket.write(buffer);
  }
  
  private void error(final Exception e) {
    if (errorCallback != null)
      errorCallback.accept(e);
    close(1011);
  }
  
  public void close(int code) {
    byte[] payload = Integer.toString(code).getBytes(WSFrame.UTF8);
    close(new WSFrame(true, false, WSFrame.OpCode.CLOSE, payload));
  }
  
  public void close(final WSFrame frame) {
    frame.setFin(true);
    frame.setOpCode(WSFrame.OpCode.CLOSE);
    closed = true;
    send(frame);
    if (closeCallback != null && frame.payload.length >= 4) {
      try {
        int code = Integer.parseInt(new String(frame.payload));
        if (WSFrame.CLOSE_CODES.containsKey(code))
          closeCallback.accept(code, WSFrame.CLOSE_CODES.get(code));
        else
          closeCallback.accept(code, "");
      } catch (Exception e) {
        
      }
    }
  }
  
  public ClientSocket getSock() {
    return socket;
  }
  
  public void onConnect(Runnable callback) {
    connectionCallback = callback;
  }
  
  public void onPing(Consumer<byte[]> callback) {
    pingCallback = callback;
  }
  
  public void onPong(Consumer<byte[]> callback) {
    pongCallback = callback;
  }
  
  public void onError(Consumer<Exception> callback) {
    errorCallback = callback;
  }
  
  public void onClose(BiConsumer<Integer, String> callback) {
    closeCallback = callback;
  }
  
  
  public void onMessage(BiConsumer<byte[], WSFrame.OpCode> callback) {
    messageCallback = callback;
  }
}
