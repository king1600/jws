package com.protto.jws;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class HttpUpgrade {
  
  private boolean error;
  private String message;
  private String errorStatus;
  private Map<String, String> headers;
  
  private static ReentrantLock digestLock;
  private static MessageDigest sha1Digest;
  public static Map<Integer, String> closeCodes;
  private static final String WebsockGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  
  public HttpUpgrade(final String clientKey) {
    error = false;
    headers = new HashMap<>();

    headers.put("Upgrade", "WebSocket");
    headers.put("Connection", "Upgrade");
    headers.put("Server", "com.protto.jws/0.1");
    headers.put("Sec-WebSocket-Accept", generateKey(clientKey));
  }
  
  public final boolean hasError() {
    return error;
  }
  
  public HttpUpgrade removeError() {
    errorStatus = null;
    error = false;
    return this;
  }
  
  public void dispose() {
    message = null;
    headers.clear();
    headers = null;
    errorStatus = null;
  }
  
  public HttpUpgrade setHeader(final String key, final String value) {
    headers.put(key, value);
    return this;
  }
  
  public HttpUpgrade setMessage(final String message) {
    this.message = message;
    headers.put("Content-Type", "text/plain");
    headers.put("Content-Length", Integer.toString(message.length()));
    return this;
  }
  
  public HttpUpgrade setErrorStatus(final String status) {
    this.errorStatus = status;
    error = true;
    return this;
  }
  
  private final String generateKey(final String clientKey) {
    if (clientKey == null) return null;
    digestLock.lock();
    sha1Digest.reset();
    sha1Digest.update((clientKey + WebsockGUID).getBytes());
    final String key = new String(Base64.getEncoder().encode(sha1Digest.digest()));
    digestLock.unlock();
    return key;
  }
  
  public static void Initialize() throws Exception {
    sha1Digest = MessageDigest.getInstance("SHA-1");
    digestLock = new ReentrantLock();
    
    closeCodes = new HashMap<>();
    closeCodes.put(1000, "Ok");
    closeCodes.put(1001, "Going Away");
    closeCodes.put(1002, "Protocol Error");
    closeCodes.put(1003, "Unsupported Type");
    closeCodes.put(1007, "Invalid Data");
    closeCodes.put(1008, "Policy Violation");
    closeCodes.put(1009, "Message Too Big");
    closeCodes.put(1010, "Extension Required");
    closeCodes.put(1011, "UnexpectedError");
  }
  
  @Override
  public String toString() {
    final StringBuilder output = new StringBuilder();
    output.append("HTTP/1.1 ");
    if (errorStatus != null)
      output.append(errorStatus);
    else
      output.append("101 Switching Protocols");
    output.append("\r\n");
    for (final Map.Entry<String, String> entry : headers.entrySet())
      output.append(entry.getKey()).append(": ")
      .append(entry.getValue()).append("\r\n");
    output.append("\r\n");
    if (message != null)
      output.append(message);
    return output.toString();
  }
}
