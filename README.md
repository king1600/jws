# jws
Java Websocket Server (modeled after Node.js "ws" package)

## Example

```java
// create the server
WSServer server = new WSServer(8080);

// handle connections
server.onConnection(client -> {
  System.out.println("Client connected!");

  // handle messages from client
  client.onMessage((data, opcode) -> {
    String resp = new String(data);
    System.out.println("Received: " + resp);
    client.send(resp); // echo back the data
  });

  // other events
  // client.onPing(data -> {});
  // client.onPong(data -> {});
  // client.onError(err -> {});
  // client.onClose(() -> {});
});

// start server
server.run();
```
