# 05 Netty

## 一句话定位

在这个 RPC 项目中，Netty 负责客户端和服务端之间的网络通信。客户端通过 Netty 连接服务端、发送 `RpcMessage`、接收 `RpcResponse`；服务端通过 Netty 监听端口、解码请求、交给业务处理器执行远程方法，再把结果编码返回。

面试时可以先这样说：

> 我在项目里用 Netty 实现 RPC 的传输层。服务端通过 `ServerBootstrap` 启动，使用 bossGroup 接收连接、workerGroup 处理 IO，并用独立的 `DefaultEventExecutorGroup` 执行业务逻辑；客户端通过 `Bootstrap` 建立连接，并用 `ChannelProvider` 复用 Channel。请求和响应都封装成 `RpcMessage`，通过自定义编解码器完成序列化、压缩和协议头处理。

## NettyRpcServer 启动流程

核心类：

- `simple.remoting.transport.netty.server.NettyRpcServer`
- `NettyRpcServer.start()`

服务端启动通常由 `example-server` 模块中的 `NettyServerMain.run()` 调用：

```java
nettyRpcServer.start();
```

`NettyRpcServer.start()` 的主要流程如下：

1. 注册 JVM 关闭钩子。
2. 获取本机 IP 地址。
3. 创建 bossGroup。
4. 创建 workerGroup。
5. 创建业务处理线程组 `DefaultEventExecutorGroup`。
6. 创建 `ServerBootstrap`。
7. 设置服务端 Channel 类型为 `NioServerSocketChannel`。
8. 设置 TCP 参数。
9. 配置服务端 ChannelPipeline。
10. 绑定 host 和端口。
11. 阻塞等待服务端 Channel 关闭。
12. finally 中优雅关闭线程组。

对应代码点：

- `CustomShutdownHook.getCustomShutdownHook().clearAll()`
- `EventLoopGroup bossGroup = new NioEventLoopGroup(1)`
- `EventLoopGroup workerGroup = new NioEventLoopGroup()`
- `DefaultEventExecutorGroup serviceHandlerGroup = new DefaultEventExecutorGroup(...)`
- `ServerBootstrap b = new ServerBootstrap()`
- `b.group(bossGroup, workerGroup)`
- `.channel(NioServerSocketChannel.class)`
- `.childHandler(new ChannelInitializer<SocketChannel>() {...})`
- `b.bind(host, PORT).sync()`

服务端监听端口：

```java
public static final int PORT = 9998;
```

## 服务端线程模型

### bossGroup

代码：

```java
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
```

职责：

- 接收客户端连接。
- 处理 TCP 连接建立事件。
- 将建立好的连接注册到 workerGroup。

面试表达：

> bossGroup 更像接待线程组，负责 accept 新连接。项目中设置为 1 个线程，因为接收连接本身不是主要耗时点。

### workerGroup

代码：

```java
EventLoopGroup workerGroup = new NioEventLoopGroup();
```

职责：

- 处理已建立连接的读写 IO。
- 触发 ChannelPipeline 中的入站和出站事件。
- 执行编解码等网络相关逻辑。

面试表达：

> workerGroup 负责真正的网络读写，比如读取客户端发来的字节流、触发解码器、写回响应等。

### DefaultEventExecutorGroup

代码：

```java
DefaultEventExecutorGroup serviceHandlerGroup = new DefaultEventExecutorGroup(
        RuntimeUtil.cpus() * 2,
        ThreadPoolFactoryUtil.createThreadFactory("service-handler-group", false)
);
```

职责：

- 执行 RPC 服务端业务处理逻辑。
- 执行 `NettyRpcServerHandler.channelRead()` 中的请求处理。
- 避免业务逻辑阻塞 Netty IO 线程。

为什么需要它：

服务端收到请求后，要做这些事情：

- 从 `RpcMessage` 中取出 `RpcRequest`。
- 从本地 `serviceMap` 查找服务对象。
- 通过反射调用目标方法。
- 封装 `RpcResponse`。

这些操作可能比普通 IO 更耗时。如果直接放在 workerGroup 上执行，可能影响其他连接的读写。

面试表达：

> 我把 `NettyRpcServerHandler` 放到 `DefaultEventExecutorGroup` 中执行，是为了做线程隔离。Netty 的 workerGroup 专注处理 IO，反射调用和业务处理交给独立线程组，避免业务逻辑拖慢 IO 线程。

## 服务端 ChannelPipeline

服务端 Pipeline 在 `NettyRpcServer.start()` 的 `ChannelInitializer` 中配置：

```java
p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
p.addLast(new RpcMessageEncoder());
p.addLast(new RpcMessageDecoder());
p.addLast(serviceHandlerGroup, new NettyRpcServerHandler());
```

### IdleStateHandler

服务端配置：

```java
new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS)
```

含义：

- 30 秒没有读到客户端数据，触发 `READER_IDLE` 事件。
- 由 `NettyRpcServerHandler.userEventTriggered()` 处理。
- 如果发生读空闲，服务端关闭连接。

作用：

- 检测客户端是否长时间无数据。
- 清理可能已经失效的连接。

### RpcMessageEncoder

类：

- `simple.remoting.transport.netty.codec.RpcMessageEncoder`

作用：

- 出站 Handler。
- 将服务端要返回的 `RpcMessage` 编码成字节流。
- 写入魔数、版本、消息长度、消息类型、序列化类型、压缩类型、requestId 和 body。
- 对 body 执行序列化和压缩。

服务端返回 `RpcResponse` 时，会经过这个编码器。

### RpcMessageDecoder

类：

- `simple.remoting.transport.netty.codec.RpcMessageDecoder`

作用：

- 入站 Handler。
- 基于 `LengthFieldBasedFrameDecoder` 切出完整消息帧。
- 校验魔数和版本。
- 读取协议头字段。
- 对 body 进行解压和反序列化。
- 将请求反序列化成 `RpcRequest`，放入 `RpcMessage.data`。

服务端收到客户端请求时，会先经过这个解码器。

### NettyRpcServerHandler

类：

- `simple.remoting.transport.netty.server.NettyRpcServerHandler`

核心方法：

- `channelRead()`
- `userEventTriggered()`
- `exceptionCaught()`

作用：

1. 接收解码后的 `RpcMessage`。
2. 判断消息类型。
3. 如果是普通请求，取出 `RpcRequest`。
4. 调用 `RpcRequestHandler.handle(rpcRequest)`。
5. 根据返回结果构造 `RpcResponse`。
6. 把响应包装成 `RpcMessage`。
7. 调用 `ctx.writeAndFlush(rpcMessage)` 写回客户端。

## 客户端 NettyRpcClient 工作流程

核心类：

- `simple.remoting.transport.netty.client.NettyRpcClient`
- `simple.remoting.transport.netty.client.NettyRpcClientHandler`
- `simple.remoting.transport.netty.client.ChannelProvider`
- `simple.remoting.transport.netty.client.UnprocessedRequests`

## 客户端初始化

`NettyRpcClient` 构造方法中完成客户端基础配置：

```java
eventLoopGroup = new NioEventLoopGroup();
bootstrap = new Bootstrap();
bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
        .handler(new LoggingHandler(LogLevel.INFO))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .handler(new ChannelInitializer<SocketChannel>() {...});
```

客户端 Pipeline：

```java
p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
p.addLast(new RpcMessageDecoder());
p.addLast(new RpcMessageEncoder());
p.addLast(new NettyRpcClientHandler());
```

客户端初始化时还会创建或获取：

- `ServiceDiscovery`：服务发现器，通过 SPI 加载 ZooKeeper 实现。
- `UnprocessedRequests`：未完成请求表。
- `ChannelProvider`：Channel 缓存。

## 客户端如何建立连接

核心方法：

- `NettyRpcClient.doConnect(InetSocketAddress inetSocketAddress)`

流程：

1. 创建 `CompletableFuture<Channel>`。
2. 调用 `bootstrap.connect(inetSocketAddress)` 发起异步连接。
3. 注册 `ChannelFutureListener`。
4. 如果连接成功，执行 `completableFuture.complete(future.channel())`。
5. 当前业务线程调用 `completableFuture.get()` 等待连接完成。
6. 返回建立好的 Channel。

面试表达：

> Netty 的连接建立是异步的，`bootstrap.connect()` 会立即返回 `ChannelFuture`。我用 `CompletableFuture` 包了一层，在连接成功的监听器里 complete，业务线程通过 get 拿到连接。这样内部仍然是 Netty 异步连接，对上层方法来说可以拿到一个可用 Channel。

## 客户端如何复用 Channel

核心类和方法：

- `ChannelProvider`
- `ChannelProvider.get()`
- `ChannelProvider.set()`
- `ChannelProvider.remove()`
- `NettyRpcClient.getChannel()`

流程：

1. `NettyRpcClient.getChannel(inetSocketAddress)` 先调用 `channelProvider.get()`。
2. `ChannelProvider` 用 `ConcurrentHashMap` 保存 `服务地址 -> Channel`。
3. 如果缓存中存在 Channel 且 `channel.isActive()`，直接返回。
4. 如果不存在或不可用，移除旧 Channel。
5. 调用 `doConnect()` 新建连接。
6. 调用 `channelProvider.set()` 缓存新 Channel。

作用：

- 避免每次 RPC 请求都重新建连。
- 降低 TCP 握手开销。
- 提升重复调用同一服务节点时的性能。

可优化点：

- 当前只在 `get()` 时判断 Channel 是否可用。
- 可以在 Channel 关闭事件里主动调用 `remove()`。
- 可以扩展为连接池，支持每个服务地址多个 Channel。

## 客户端如何发送请求

核心方法：

- `NettyRpcClient.sendRpcRequest(RpcRequest rpcRequest)`

流程：

1. 创建 `CompletableFuture<RpcResponse<Object>> resultFuture`。
2. 调用 `serviceDiscovery.lookupService(rpcRequest)` 发现服务地址。
3. 调用 `getChannel(inetSocketAddress)` 获取 Channel。
4. 调用 `unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture)` 保存未完成请求。
5. 构造 `RpcMessage`，把 `RpcRequest` 放入 `data`。
6. 设置序列化方式为 Hessian。
7. 设置压缩方式为 Gzip。
8. 设置消息类型为 `REQUEST_TYPE`。
9. 调用 `channel.writeAndFlush(rpcMessage)` 发送。
10. 业务线程调用 `resultFuture.get()` 等待响应。

发送失败时：

```java
future.channel().close();
resultFuture.completeExceptionally(future.cause());
```

面试表达：

> 客户端发请求前，会先发现服务地址，再获取可用 Channel。发送前把 `requestId` 和 `CompletableFuture` 放入未完成请求表。之后构造 `RpcMessage`，调用 `writeAndFlush()` 异步发送。响应回来后由客户端 Handler 完成 Future，业务线程从 `get()` 返回。

## 客户端如何接收响应

核心类和方法：

- `NettyRpcClientHandler.channelRead()`
- `UnprocessedRequests.complete()`

流程：

1. 客户端收到服务端返回的字节流。
2. `RpcMessageDecoder` 解码成 `RpcMessage`。
3. `NettyRpcClientHandler.channelRead()` 判断消息类型。
4. 如果是心跳响应，打印心跳日志。
5. 如果是 RPC 响应，取出 `RpcResponse<Object>`。
6. 调用 `unprocessedRequests.complete(rpcResponse)`。
7. `complete()` 根据 `rpcResponse.getRequestId()` 找到 Future。
8. 调用 `future.complete(rpcResponse)` 唤醒业务线程。

这一步解决了并发响应乱序问题。

## IdleStateHandler 的作用

`IdleStateHandler` 是 Netty 提供的空闲检测 Handler，可以检测三种空闲事件：

- 读空闲：一段时间没有读到数据。
- 写空闲：一段时间没有写出数据。
- 读写空闲：一段时间没有读也没有写。

### 客户端配置

代码：

```java
new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS)
```

含义：

- 5 秒没有写数据，触发 `WRITER_IDLE`。
- `NettyRpcClientHandler.userEventTriggered()` 捕获该事件。
- 客户端发送心跳请求：

```java
rpcMessage.setMessageType(RpcConstants.HEARTBEAT_REQUEST_TYPE);
rpcMessage.setData(RpcConstants.PING);
```

### 服务端配置

代码：

```java
new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS)
```

含义：

- 30 秒没有读到客户端数据，触发 `READER_IDLE`。
- `NettyRpcServerHandler.userEventTriggered()` 捕获该事件。
- 服务端主动关闭连接。

### 面试表达

> 我用 `IdleStateHandler` 做基础心跳和连接检测。客户端如果 5 秒没有写数据，就发送 PING 心跳；服务端如果 30 秒没有读到数据，就认为连接可能空闲或失效，主动关闭连接。这个实现还比较基础，后续可以加心跳失败次数、客户端重连、Channel 缓存清理等机制。

### 当前实现的小问题

当前服务端 `NettyRpcServerHandler.channelRead()` 中判断心跳时使用的是：

```java
if (messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE)
```

但客户端发送的是：

```java
RpcConstants.HEARTBEAT_REQUEST_TYPE
```

语义上服务端应该处理心跳请求并返回心跳响应。面试时如果问项目不足，可以主动说明这个点：

> 当前心跳处理是基础实现，服务端消息类型判断还需要修正为处理心跳请求，并返回心跳响应。

## 项目如何处理粘包半包

### 为什么会有粘包半包

TCP 是面向字节流的协议，不保证一次 `writeAndFlush()` 对应一次 `channelRead()`。

可能出现：

- 客户端连续发两个请求，服务端一次读到两个请求，称为粘包。
- 一个请求比较大，服务端一次只读到一部分，称为半包。

如果不处理，服务端无法知道一条完整 RPC 消息的边界。

### 项目解决方案

项目在协议头中加入 `full length` 字段，表示完整消息长度。

`RpcMessageDecoder` 继承：

```java
LengthFieldBasedFrameDecoder
```

构造参数：

```java
this(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
```

参数解释：

| 参数 | 值 | 含义 |
| --- | --- | --- |
| `maxFrameLength` | `8 * 1024 * 1024` | 最大帧长度 |
| `lengthFieldOffset` | `5` | 长度字段偏移量，4 字节魔数 + 1 字节版本号 |
| `lengthFieldLength` | `4` | 长度字段占 4 字节 |
| `lengthAdjustment` | `-9` | full length 包含整个消息长度，需要减去魔数、版本、长度字段共 9 字节 |
| `initialBytesToStrip` | `0` | 不剥离协议头，后续自己校验魔数和版本 |

完整解码流程：

1. `LengthFieldBasedFrameDecoder` 根据长度字段判断是否已有完整帧。
2. 如果数据不完整，继续等待。
3. 如果数据完整，切出一帧。
4. `decodeFrame()` 校验魔数。
5. 校验版本。
6. 读取消息类型、序列化类型、压缩类型等协议头字段。
7. 读取 body。
8. 解压缩。
9. 反序列化成 `RpcRequest` 或 `RpcResponse`。

### 面试表达

> TCP 是流式协议，会有粘包半包问题。我在协议头中设计了 full length 字段，然后让 `RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`。它会根据长度字段切出完整消息帧，只有完整帧才交给后面的 `decodeFrame()`。这样客户端和服务端就能稳定地按自定义协议解析 `RpcMessage`。

## 为什么使用 Netty，而不是 BIO 或原生 NIO

### 相比 BIO

BIO 的问题：

- 一个连接通常对应一个线程。
- 并发连接多时线程数量暴涨。
- 线程上下文切换和内存开销大。
- 阻塞读写不适合高并发 RPC。

Netty 的优势：

- 基于 NIO，非阻塞 IO。
- 少量线程可以管理大量连接。
- 事件驱动，适合高并发请求。

### 相比原生 NIO

原生 NIO 的问题：

- API 偏底层，Selector、Channel、Buffer 编程复杂。
- 粘包半包、编解码、连接管理都要自己处理。
- 容易写出边界 bug。

Netty 的优势：

- 封装 Reactor 线程模型。
- 提供 ChannelPipeline 和 Handler 机制。
- 提供成熟编解码器，比如 `LengthFieldBasedFrameDecoder`。
- 支持空闲检测、异步事件、优雅关闭等能力。
- 社区成熟，适合做 RPC、网关、长连接服务。

面试表达：

> 我选择 Netty 是因为 RPC 的传输层需要高并发、长连接和自定义协议。BIO 在连接多的时候线程开销太大，原生 NIO 又比较复杂，很多编解码、粘包半包、连接管理都要手写。Netty 基于 NIO 做了成熟封装，有 Pipeline、Handler、EventLoop、编解码器和空闲检测，能让我把重点放在 RPC 协议和调用链路上。

## Netty 高频追问

### 1. bossGroup 和 workerGroup 有什么区别？

**高概率追问：是**

参考回答：

> bossGroup 负责接收新连接，workerGroup 负责已经建立连接上的 IO 读写。项目里 `bossGroup = new NioEventLoopGroup(1)`，只有一个线程；workerGroup 使用默认线程数，处理客户端请求的读写事件。

### 2. 为什么还要加 DefaultEventExecutorGroup？

**高概率追问：是**

参考回答：

> 因为服务端收到 RPC 请求后要执行反射调用和业务逻辑，这些操作可能耗时。如果直接在 workerGroup 的 IO 线程里执行，会影响其他连接的读写。所以我把 `NettyRpcServerHandler` 加到 `DefaultEventExecutorGroup` 中，让业务逻辑在线程池里执行，实现 IO 和业务处理隔离。

### 3. ChannelPipeline 中 Handler 的作用是什么？

**高概率追问：是**

参考回答：

> Pipeline 可以理解成处理链。入站数据先经过 Decoder，把字节流转成 `RpcMessage`，再进入业务 Handler；出站数据从业务 Handler 写出后经过 Encoder，把 `RpcMessage` 转成字节流。项目里还加了 `IdleStateHandler` 做连接空闲检测。

### 4. `writeAndFlush()` 是同步还是异步？

**高概率追问：中**

参考回答：

> `writeAndFlush()` 是异步的，它会立即返回一个 `ChannelFuture`。项目里发送请求后给 Future 加了监听器，发送成功就打印日志，失败就关闭 Channel 并让 `resultFuture.completeExceptionally()`。

### 5. 客户端为什么还要 `resultFuture.get()`？

**高概率追问：中**

参考回答：

> Netty 底层是异步的，但这个项目对业务层暴露的是同步调用体验。客户端发送请求后阻塞在 `resultFuture.get()`，等服务端响应回来后，`NettyRpcClientHandler` 根据 `requestId` 找到对应 Future 并 complete，业务线程再返回结果。

### 6. 项目如何解决 TCP 粘包半包？

**高概率追问：是**

参考回答：

> 自定义协议里有 full length 字段，`RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`，通过长度字段判断当前 ByteBuf 是否包含完整消息。完整才继续解码，否则继续等待数据。

### 7. `LengthFieldBasedFrameDecoder` 参数为什么是 `(MAX, 5, 4, -9, 0)`？

**高概率追问：中**

参考回答：

> 因为协议开头是 4 字节魔数和 1 字节版本号，所以长度字段偏移是 5；长度字段本身是 4 字节；full length 表示整条消息长度，而解码器读到长度字段时已经经过了魔数、版本和长度字段共 9 字节，所以 lengthAdjustment 是 -9；initialBytesToStrip 是 0，因为我后续还要手动校验魔数和版本。

### 8. ChannelProvider 有什么作用？

**高概率追问：中**

参考回答：

> 它用 `ConcurrentHashMap` 缓存服务地址到 Channel 的映射。客户端调用同一个服务节点时，先查缓存，如果 Channel 仍然 active，就复用；否则移除旧连接并重新建立。这样可以避免每次 RPC 都重新 TCP 握手。

### 9. 心跳机制是怎么做的？

**高概率追问：中**

参考回答：

> 客户端使用 `IdleStateHandler(0, 5, 0)`，5 秒没有写数据就触发写空闲，在 `NettyRpcClientHandler.userEventTriggered()` 中发送 PING 心跳。服务端使用 `IdleStateHandler(30, 0, 0)`，30 秒没有读数据就触发读空闲，在 `NettyRpcServerHandler.userEventTriggered()` 中关闭连接。

### 10. 这个 Netty 实现还有哪些不足？

**高概率追问：是**

参考回答：

> 当前实现已经跑通了核心通信链路，但还有不少生产级优化点。第一，请求等待使用 `resultFuture.get()`，没有超时控制；第二，心跳处理比较基础，服务端心跳请求和响应类型判断需要修正；第三，ChannelProvider 只是简单缓存 Channel，还没有连接池和重连机制；第四，解码器里读取了 `compressType`，但获取压缩器时用了 `codecType`，这里需要修复；第五，异常处理和监控指标还不够完善。

## 面试话术：我在项目中如何使用 Netty

可以直接背这一段：

> 我在这个 RPC 项目里主要用 Netty 实现客户端和服务端之间的传输层。服务端通过 `NettyRpcServer.start()` 启动，里面使用 `ServerBootstrap`，bossGroup 负责接收客户端连接，workerGroup 负责网络 IO，另外我还用了 `DefaultEventExecutorGroup` 执行 RPC 业务处理，避免反射调用阻塞 Netty IO 线程。服务端 Pipeline 中配置了 `IdleStateHandler`、自定义编码器 `RpcMessageEncoder`、自定义解码器 `RpcMessageDecoder` 和业务处理器 `NettyRpcServerHandler`。
>
> 客户端通过 `NettyRpcClient` 使用 `Bootstrap` 和 `NioSocketChannel` 建立连接。每次发送 RPC 请求前，客户端会先通过 ZooKeeper 找到服务地址，再通过 `ChannelProvider` 查询是否已有可用 Channel，如果有就复用，没有就通过 `bootstrap.connect()` 新建连接。请求会被封装成 `RpcMessage`，通过 `channel.writeAndFlush()` 异步发送。发送前还会把 `requestId` 和 `CompletableFuture` 放入 `UnprocessedRequests`，响应回来后客户端 Handler 根据 `requestId` 完成对应 Future，业务线程就能拿到结果。
>
> 协议层我用 `RpcMessageEncoder` 和 `RpcMessageDecoder` 处理。编码器负责写入魔数、版本、消息长度、消息类型、序列化方式、压缩方式等协议头，并对 body 做序列化和压缩；解码器继承 `LengthFieldBasedFrameDecoder`，通过长度字段解决 TCP 粘包半包，然后再校验魔数和版本，反序列化出 `RpcRequest` 或 `RpcResponse`。整体上，Netty 在项目中承担了长连接管理、异步收发、编解码、心跳检测和请求响应传输这些职责。
