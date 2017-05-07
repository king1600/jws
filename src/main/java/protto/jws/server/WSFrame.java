package protto.jws.server;

import java.nio.charset.Charset;
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
  
  public static String CreateKey(String key) {
    SHA_1.reset();
    SHA_1.update((key + GUID).getBytes());
    return new String(Base64.getEncoder().encode(SHA_1.digest()));
  }
  
  public static int NewMask() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 8; i++)
      builder.append(Integer.toString(random.nextInt(2)));
    return Integer.parseInt(builder.toString(), 16);
  }
  
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
  
  public boolean isFin() {
    return fin;
  }
  
  public boolean isMasked() {
    return masked;
  }
  
  public OpCode getOpCode() {
    return opcode;
  }
  
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
  
  public void setFin(final boolean fin) {
    this.fin = fin;
  }
  
  public void setOpCode(final OpCode opcode) {
    this.opcode = opcode;
  }
  
  public void setMasked(final boolean masked) {
    this.masked = masked;
  }
}

