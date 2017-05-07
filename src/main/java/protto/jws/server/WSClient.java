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
  
  /**
   * Initialize a new WebsocketClient
   * @param sock the underlying socket
   */
  public WSClient(final ClientSocket sock) {
    socket = sock;
    reader = new ByteArrayOutputStream();
  }
  
  /**
   * Wrap the internal ClientSocket with callback events
   */
  public void wrap() {

    // after packet write
    socket.setWrite(() -> {
      // close connection if requested
      if (closed) { socket.close(); return; }
      // handle connection event after handshake
      if (!handshaked) {
        handshaked = true;
        if (connectionCallback != null)
          connectionCallback.run();
      }
    });

    // after packet read
    socket.setRead(data -> {
      try {
        // perform initial handshake
        if (!handshaked) {
          handshake(data);
        
        // handle websocket frames
        } else {
          WSFrame frame = WSFrame.parseFrame(data); // parse frame from data
          lastOpCode = frame.getOpCode() == null ? lastOpCode : frame.getOpCode();

          // non-masked data from client = disconnect
          if (!frame.isMasked()) {
            close(1008);

          // handle CLOSE Events
          } else if (frame.getOpCode() == WSFrame.OpCode.CLOSE) {
            close(frame);

          // handle other types of frames
          } else {
            reader.write(frame.payload); // add to internal read buffer
            if (frame.isFin()) {         // check EOF (End of Frame)

              // handle ping frames
              if (frame.getOpCode() == WSFrame.OpCode.PING) {
                if (pingCallback != null)
                  pingCallback.accept(frame.payload);
                send(new WSFrame(true, false, WSFrame.OpCode.PONG, frame.payload));

              // handle pong frames
              } else if (frame.getOpCode() == WSFrame.OpCode.PONG) {
                if (pongCallback != null)
                  pongCallback.accept(frame.payload);

              // handle data frame (OP_TEXT and OP_BINARY)
              } else {
               if (messageCallback != null)
                 messageCallback.accept(reader.toByteArray(), lastOpCode);
              }

              // on EOF (End of Frame) Reset read buffer
              reader.reset();
            }
          }
        }

      // catch any errors
      } catch (Exception e) {
        error(e);
      }
    });
  }
  
  /**
   * return Bad request on invalid handshake with message
   * @param message the message to include in the HTTP 400 response
   */
  private void handshakeError(String message) {
    String request = new StringBuilder()
        .append("HTTP/1.1 400 Bad Request\r\n")
        .append("Server: JWS/0.0.1\r\n")
        .append("Content-Type: text/plain\r\n")
        .append("Content-Length: ")
        .append(Integer.toString(message.length()))
        .append("\r\n\r\n")
        .append(message)
        .toString();
    closed = true;
    socket.write(request.getBytes());
  }
  
  /**
   * Perform Client handshake.
   * @param data the initial http request as byte[]
   */
  private void handshake(byte[] data) throws Exception {
    String key = null, version = null, extensions = null; // websocket items
    List<String> parts = split(new String(data), "\r\n"); // parsed request

    // iterate through request lines and
    // get Key, Version and Extensions from Header
    for (int i = 0; i < parts.size(); i++) {
      if (parts.get(i).indexOf("Sec-WebSocket-Key") > -1)
        key = split(parts.get(i),":").get(1).substring(1).trim();
      else if (parts.get(i).indexOf("Sec-WebSocket-Version") > -1)
        version = split(parts.get(i),":").get(1).substring(1).trim();
      else if (parts.get(i).indexOf("Sec-WebSocket-Extensions") > -1)
        extensions = split(parts.get(i),":").get(1).substring(1).trim();
    }

    // Check if it valid Websocket Request that server can serve to
    if (key == null) {
      handshakeError("No Sec-WebSocket-Key provided");
    } else if (version == null) {
      handshakeError("No Sec-WebSocket-Version provided");
    } else if (!version.equals("13")) {
      handshakeError("Sec-WebSocket-Version 13 only supported");
    } else if (extensions != null) {
      handshakeError("Sec-WebSocket-Extensions not supported");
    } else {

      // request is valid, send back response to complete the handshake
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
  
  /**
   * Faster split algorithm than String.split();
   * @param str the string to split
   * @param delim the string deliminator to split str by
   * @return the content split into a dynamic array
   */
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
  
  /**
   * Send Binary Data over websocket
   * @param data the binary data
   */
  public void send(byte[] data) {
    send(new WSFrame(true, false, WSFrame.OpCode.BINARY, data));
  }
  
  /**
   * Send UTF8 Text Data over websocket
   * @param data the text data
   */
  public void send(String data) {
    send(new WSFrame(true, false, WSFrame.OpCode.TEXT, data.getBytes(WSFrame.UTF8)));
  }

  /**
   * Sebd a Websocket frame over network
   */
  public void send(final WSFrame frame) {
    socket.write(frame.toPacket());
  }
  
  /**
   * Send a ping with no payload
   */
  public void ping() {
    ping(new byte[]{});
  }
  
  /**
   * Send a ping with binary payload
   * @param data the binary payload
   */
  public void ping(byte[] data) {
    send(new WSFrame(true, false, WSFrame.OpCode.PING, data));
  }
  
  /**
   * Send a ping with utf8 text payload
   * @param data the text payload
   */
  public void ping(String data) {
    send(new WSFrame(true, false, WSFrame.OpCode.PING, data.getBytes(WSFrame.UTF8)));
  }
  
  /**
   * Perform an error and close the connection
   * @param e the excetion to handle
   */
  private void error(final Exception e) {
    if (errorCallback != null)
      errorCallback.accept(e);
    close(1011);
  }
  
  /**
   * Closed the websocket connection
   * @param code the error code to close with
   */
  public void close(int code) {
    byte[] payload = Integer.toString(code).getBytes(WSFrame.UTF8);
    close(new WSFrame(true, false, WSFrame.OpCode.CLOSE, payload));
  }
  
  /**
   * Send the CLOSE websocket Frame and terminate the connection
   * @param frame the websocket frame to modify and send
   */
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
      } catch (Exception e) {}
    }
  }
  
  /**
   * Get the internal ClientSocket of the Websocket client
   * @return the internal ClientSocket
   */
  public ClientSocket getSock() {
    return socket;
  }
  
  /**
   * Bind CONNECT event to calback
   * @param callback event to call
   */
  public void onConnect(Runnable callback) {
    connectionCallback = callback;
  }
  
  /**
   * Bind PING event to calback
   * @param callback event to call
   */
  public void onPing(Consumer<byte[]> callback) {
    pingCallback = callback;
  }
  
  /**
   * Bind PONG event to calback
   * @param callback event to call
   */
  public void onPong(Consumer<byte[]> callback) {
    pongCallback = callback;
  }
  
  /**
   * Bind ERROR event to calback
   * @param callback event to call
   */
  public void onError(Consumer<Exception> callback) {
    errorCallback = callback;
  }
  
  /**
   * Bind CLOSE event to calback
   * @param callback event to call
   */
  public void onClose(BiConsumer<Integer, String> callback) {
    closeCallback = callback;
  }
  
  /**
   * Bind MESSAGE event to calback
   * @param callback event to call
   */
  public void onMessage(BiConsumer<byte[], WSFrame.OpCode> callback) {
    messageCallback = callback;
  }
}
