# jws
Somewhat optimized Java Websocket Server

## Example

```java
// create the server
int optionalThreads = 4;
int optionalPort = 8080; // defaults to 8080
WebsockServer server = new WebsockServer(optionalPort, optionalThreads);

// handle http upgrade messages from client handshake
server.onUpgrade((request, upgrade) -> {
  System.out.println(request.getHeader("Sec-WebSocket-Key"));
  // if deemed incorrect request
  // upgrade.setErrorStatus("404 Bad request");
  upgrade.setMessage("Noice");
});

// handle socket connections (before handshake)
server.onConnection(client -> {
  
  // handle handshaked client
  client.onConnect(self -> {
    System.out.println("Client connected!");
  });

  // handle messages from client
  client.onMessage(byteData -> {
    String resp = new String(byteData);
    System.out.println("Received: " + resp);
    client.send(resp); // echo back the data
    // client.sendBytes(); Binary data
  });
  
  // perform pings with measured time and responses
  client.ping(byteData, (pingTime, pingByteData) -> {
    System.out.println("Ping is: " + pingTime + "ms");
  });
  
  // close websocket connection
  client.close(1001, "Bye");
  
  // other events
  // client.onPong(data -> {});
  // client.onClose((code, reason) -> {});
});

// start server
server.start();
```
