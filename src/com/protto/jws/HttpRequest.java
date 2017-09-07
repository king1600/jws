package com.protto.jws;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class HttpRequest {
  
  private URI uri;
  private final String method;
  private Map<String, String> headers;
  
  public HttpRequest(final WebsockServer server, String httpData) throws URISyntaxException {
    //
    headers = new HashMap<>();
    
    String line = null;
    int sep, pos = httpData.indexOf("\r\n");
    
    line = httpData.substring(0, pos);
    method = line.substring(0, line.indexOf(' '));
    line = line.substring(line.indexOf(' ') + 1);
    uri = new URI("http://localhost:" + server.getPort() + line.substring(0, line.indexOf(' ')));
    httpData = httpData.substring(pos + 2);
    pos = httpData.indexOf("\r\n");
    
    while (pos > 0) {
      line = httpData.substring(0, pos);
      sep = line.indexOf(':');
      headers.put(line.substring(0, sep), line.substring(sep + 2));
      httpData = httpData.substring(pos + 2);
      pos = httpData.indexOf("\r\n");
    }
    
    line = null;
    httpData = null;
  }
  
  public void dispose() {
    uri = null;
    headers.clear();
    headers = null;
  }
  
  public String getPath() {
    return uri.getPath();
  }
  
  public String getMethod() {
    return method;
  }
  
  public String getQuery() {
    return uri.getQuery();
  }
  
  public boolean hasHeader(final String key) {
    return headers.containsKey(key);
  }
  
  public String getHeader(final String key) {
    return headers.get(key);
  }
}
