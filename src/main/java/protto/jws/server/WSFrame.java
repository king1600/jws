package protto.jws.server;

import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class WSFrame {
  
  private static MessageDigest SHA_1;
  public static final int MAX = 65535;
  private static final Random random = new Random();
  public static final Charset UTF8 = StandardCharsets.UTF_8;
  public static final Map<Integer, String> CLOSE_CODES = new HashMap<>();
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  
  /**
   * RFC Websocket OpCodes
   */
  public static enum OpCode {
    CONT(0x00), TEXT(0x01), BINARY(0x02),
    CLOSE(0x08), PING(0x09), PONG(0x0a);
    private final int value;
    OpCode(int v) { this.value = v; }
    public int getValue() { return this.value; }
    public static OpCode get(int value) {
      for (int i = 0; i < values().length; i++)
        if (values()[i].getValue() == value)
          return values()[i];
      return null;
    }
  };
  
  /**
   * Generate Websocket Accept Key from Client Key
   * @param key the client key used to encode with
   * @return sha-1 and base64 encoded websocket accept key
   */
  public static String CreateKey(String key) {
    SHA_1.reset();
    SHA_1.update((key + GUID).getBytes());
    return new String(Base64.getEncoder().encode(SHA_1.digest()));
  }
  
  /**
   * TODO: Generate a packet mask to use client wise
   * @return random unsigned int mask
   */
  public static int NewMask() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 8; i++)
      builder.append(Integer.toString(random.nextInt(2)));
    return Integer.parseInt(builder.toString(), 16);
  }
  
  /**
   * Initialize the close code reasons and SHA-1 Encryptor
   */
  public static void FrameInit() throws Exception {
    WSFrame.SHA_1 = MessageDigest.getInstance("SHA-1");
    WSFrame.CLOSE_CODES.put(1000, "OK");
    WSFrame.CLOSE_CODES.put(1001, "Going Away");
    WSFrame.CLOSE_CODES.put(1002, "Protocol Error");
    WSFrame.CLOSE_CODES.put(1003, "Unsupported Type");
    WSFrame.CLOSE_CODES.put(1007, "Invalid Data");
    WSFrame.CLOSE_CODES.put(1008, "Policy Violation");
    WSFrame.CLOSE_CODES.put(1009, "Message Too Big");
    WSFrame.CLOSE_CODES.put(1010, "Extension Required");
    WSFrame.CLOSE_CODES.put(1011, "Unexpected Error");
  }

  /**
   * Take network byte data and covert it into a WebsocketFrame
   * @param data the network byte[] data to parse
   * @return the parsed websocket frame
   */
  public static WSFrame parseFrame(byte[] data) throws Exception {
    WSFrame frame = new WSFrame();
    ByteBuffer buffer = ByteBuffer.wrap(data);
    
    // get fin and opcode
    byte b = buffer.get();
    frame.setFin((b & 0x80) != 0);
    for (int i = 16; i < 65; i *= 16)
      if ((b & i) != 0)
        throw new Exception("Reserved Flags must be 0");
    frame.setOpCode(WSFrame.OpCode.get(b & 0x0f));
    
    // get masked and payload length
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
  
  ////////////////////////////////////
  //         WSFrame Object         //
  ////////////////////////////////////

  private boolean fin;
  private boolean masked;
  private OpCode opcode;
  public byte[] payload;
  
  public WSFrame() {
    this(true, true, OpCode.TEXT, new byte[]{});
  }
  
  public WSFrame(boolean _fin, boolean _masked, OpCode _opcode, byte[] _payload) {
    fin = _fin;
    opcode = _opcode;
    masked = _masked;
    payload = _payload;
  }
  
  @Override
  public String toString() {
    return new String(payload);
  }
  
  /**
   * Check if FINISHED flag is set on frame
   * @return fin flag state
   */
  public boolean isFin() {
    return fin;
  }
  
  /**
   * Check if MASK flag is set on frame
   * @return mask flag state
   */
  public boolean isMasked() {
    return masked;
  }
  
  /**
   * Get the Websocket OPCODE in the fraome
   * @return the OpCode enum
   */
  public OpCode getOpCode() {
    return opcode;
  }
  
  /**
   * Calculate the final packet size of the frame
   * based on the content it currently has
   * @return the calculated frame size as network packet (byte[])
   */
  public int getFinalSize() {
    int size = 2; // initial header
    
    // payload length size
    if (payload.length <= 125) {}
    else if (payload.length >= 126 && payload.length <= MAX) size += 2;
    else size += 8;
    
    // mask key size
    if (isMasked()) size += 4;
    
    // data size
    size += payload.length;
    
    // return calculated size
    return size;
  }

  /**
   * Convert the current WebsocketFrame to a byte[] to send over network
   * @return network packet representation of the current WebsocketFrame
   */
  public byte[] toPacket() {
    int start   = -1;
    int length  = payload.length;
    int h_fin   = (isFin() ? 0x10000000 : 0) | opcode.getValue();
    int h_mask  = isMasked() ? 0x10000000 : 0;
    byte[] buffer = new byte[getFinalSize()];

    buffer[0] = (byte)(h_fin ^ 0x80); // set the first byte of fin and opcode

    // set the payload size
    if (length <= 125) {
      buffer[1] = (byte)((h_mask | length));
      start     = 2;
    } else if (length >= 126 && length <= WSFrame.MAX) {
      buffer[1] = (byte)((h_mask | 0x7E));
      buffer[2] = (byte)((length >> 8) & 0xFF);
      buffer[3] = (byte)((length     ) & 0xFF);
      start     = 4;
    } else {
      buffer[1] = (byte)((h_mask | 0x7F));
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

    // add the payload
    for (int i = 0; i < length; i++)
      buffer[start + i] = frame.payload[i];

    // return the packet
    return buffer;
  }
  
  /**
   * Set the frame's FINISHED flag
   * @param fin flag state
   */
  public void setFin(final boolean fin) {
    this.fin = fin;
  }
  
  /**
   * Set the frame's OPCODE
   * @param opcode the OpCode enum
   */
  public void setOpCode(final OpCode opcode) {
    this.opcode = opcode;
  }
  
  /**
   * Set the frame's MASK flag
   * @oaram masked flag state
   */
  public void setMasked(final boolean masked) {
    this.masked = masked;
  }
}

