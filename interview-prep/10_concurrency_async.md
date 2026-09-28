# 10 Concurrency Async

## 一句话定位

这个项目的并发和异步设计主要围绕 Netty 的异步 I/O、`CompletableFuture` 异步转同步、`requestId` 请求响应匹配、`ConcurrentHashMap` 线程安全缓存和 Channel 连接复用展开。

面试时可以先这样说：

> 这个 RPC 项目底层使用 Netty 做异步网络通信，但对业务调用方暴露的是同步方法调用体验。客户端发送请求时会创建一个 `CompletableFuture`，然后以 `requestId` 为 key 放入 `UnprocessedRequests` 这个未完成请求表。Netty I/O 线程收到服务端响应后，根据响应中的 `requestId` 找到对应的 future 并 `complete()`，业务线程就能从 `future.get()` 中恢复并拿到结果。连接层通过 `ChannelProvider` 缓存服务地址到 Channel 的映射，避免每次 RPC 都重新建连。服务缓存、扩展缓存、未完成请求表等都用了 `ConcurrentHashMap` 保证并发安全。

## 核心类

- `simple.remoting.transport.netty.client.NettyRpcClient`
- `simple.remoting.transport.netty.client.UnprocessedRequests`
- `simple.remoting.transport.netty.client.ChannelProvider`
- `simple.remoting.transport.netty.client.NettyRpcClientHandler`
- `simple.remoting.transport.netty.server.NettyRpcServer`
- `simple.remoting.transport.netty.server.NettyRpcServerHandler`
- `simple.provider.Impl.ZKServiceProviderImpl`
- `common.extension.ExtensionLoader`
- `common.factory.SingletonFactory`
- `common.extension.Holder`
- `simple.remoting.transport.netty.codec.RpcMessageEncoder`
- `simple.serialize.kyro.KryoSerializer`
- `simple.serialize.protostuff.ProtostuffSerializer`
- `simple.loadbalance.loadbalancer.ConsistentHashLoadBalanceNew`

## 一次请求中的线程关系

一次客户端 RPC 调用涉及两类线程：

| 线程 | 职责 |
| --- | --- |
| 业务线程 | 调用代理对象方法，构造请求，等待远程调用结果 |
| Netty I/O 线程 | 建立连接、发送数据、接收响应、触发 ChannelHandler |

项目通过 `CompletableFuture` 把这两类线程串起来：

```text
业务线程创建 CompletableFuture
业务线程发送请求并阻塞等待 future.get()
Netty I/O 线程收到响应
Netty I/O 线程根据 requestId 找到 future
Netty I/O 线程 complete(future)
业务线程从 future.get() 返回
```

这就是项目里“底层异步、上层同步”的核心。

## CompletableFuture 在客户端请求中的作用

## 1. 连接建立时异步转同步

`NettyRpcClient.doConnect()` 中：

```java
CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
bootstrap.connect(inetSocketAddress).addListener((ChannelFutureListener) future -> {
    if (future.isSuccess()) {
        completableFuture.complete(future.channel());
    } else {
        throw new IllegalStateException();
    }
});
return completableFuture.get();
```

Netty 的 `bootstrap.connect()` 是异步的，调用后不会阻塞等待 TCP 连接完成，而是立即返回 `ChannelFuture`。

项目做了两件事：

1. 创建一个 `CompletableFuture<Channel>` 作为结果凭证。
2. 在 Netty 连接成功回调中调用 `complete(channel)`。
3. 调用线程通过 `completableFuture.get()` 阻塞等待 Channel。

面试表达：

> 建连本身是 Netty 异步完成的，项目用 `CompletableFuture` 把异步连接结果包装起来。业务线程调用 `get()` 阻塞等待，Netty I/O 线程连接成功后执行 `complete()`，这样业务线程就能拿到建立好的 Channel。

## 2. RPC 请求响应时异步转同步

`NettyRpcClient.sendRpcRequest()` 中：

```java
CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
```

然后：

```java
unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
```

发送请求：

```java
channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
    if (future.isSuccess()) {
        log.info("成功发送消息[{}]", rpcMessage);
    } else {
        future.channel().close();
        resultFuture.completeExceptionally(future.cause());
    }
});
```

最后业务线程等待：

```java
return resultFuture.get();
```

注意这里发送成功并不代表 RPC 调用完成，只代表请求字节写入 Channel 成功。

真正的响应结果是在客户端 handler 中完成的：

```java
unprocessedRequests.complete(rpcResponse);
```

面试表达：

> `CompletableFuture` 在这里相当于一个请求结果占位符。业务线程发送请求后先把 future 放入未完成请求表，然后阻塞等待。Netty I/O 线程收到响应后根据 requestId 找到这个 future 并完成它。这样底层可以异步收发，业务层仍然像同步调用一样拿结果。

## 3. CompletableFuture 的价值

在这个项目中，`CompletableFuture` 解决了三个问题：

1. 跨线程传递结果  
   业务线程等待结果，Netty I/O 线程填充结果。

2. 支持并发请求  
   多个请求可以同时发出去，每个请求都有自己的 future。

3. 支持乱序响应  
   服务端响应顺序可以和客户端发送顺序不同，只要 requestId 匹配就不会错。

## UnprocessedRequests 如何管理未完成请求

`UnprocessedRequests` 是客户端未完成请求表。

核心字段：

```java
private static final Map<String, CompletableFuture<RpcResponse<Object>>>
        UNPROCESSED_RESPONSE_FUTURES = new ConcurrentHashMap<>();
```

key 是：

```java
rpcRequest.getRequestId()
```

value 是：

```java
CompletableFuture<RpcResponse<Object>>
```

## put：发送前登记请求

客户端发送前：

```java
unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
```

对应方法：

```java
public void put(String resquestId, CompletableFuture<RpcResponse<Object>> future) {
    UNPROCESSED_RESPONSE_FUTURES.put(resquestId, future);
}
```

这一步相当于告诉客户端：

```text
requestId = xxx 的请求正在等待响应
响应回来后请完成这个 future
```

## complete：响应回来后完成请求

客户端收到响应时，在 `NettyRpcClientHandler.channelRead()` 中：

```java
RpcResponse<Object> rpcResponse = (RpcResponse<Object>) tmp.getData();
unprocessedRequests.complete(rpcResponse);
```

对应方法：

```java
public void complete(RpcResponse<Object> rpcResponse) {
    CompletableFuture<RpcResponse<Object>> future =
        UNPROCESSED_RESPONSE_FUTURES.remove(rpcResponse.getRequestId());
    if (null != future) {
        future.complete(rpcResponse);
    } else {
        throw new IllegalStateException();
    }
}
```

这里有两个关键点：

1. 用 `requestId` 找到对应 future。
2. 用 `remove()` 而不是 `get()`。

使用 `remove()` 的好处：

- 完成后立即清理，避免内存泄漏。
- 避免同一个响应重复完成。
- `ConcurrentHashMap.remove()` 是线程安全的。

## 为什么需要 requestId

因为客户端可能同时发出多个请求：

```text
请求 A -> requestId = A
请求 B -> requestId = B
请求 C -> requestId = C
```

服务端处理时间不同，响应可能按下面顺序回来：

```text
响应 B
响应 C
响应 A
```

如果没有 requestId，客户端无法知道响应属于哪个调用。

有了 requestId：

```text
响应 B -> 找到 B 对应的 future -> 唤醒等待 B 的业务线程
```

面试表达：

> `UnprocessedRequests` 是一个 requestId 到 `CompletableFuture` 的线程安全 Map。请求发送前先登记，响应回来后根据 requestId remove 出对应 future 并 complete。这样即使响应乱序回来，也能准确唤醒对应的业务线程。

## 当前未完成请求表的优化点

## 1. 缺少超时机制

当前代码使用：

```java
resultFuture.get();
```

如果服务端一直不返回，业务线程可能一直阻塞，`UNPROCESSED_RESPONSE_FUTURES` 中的 future 也不会被清理。

优化方式：

```java
resultFuture.get(3, TimeUnit.SECONDS);
```

或者：

```java
resultFuture.orTimeout(3, TimeUnit.SECONDS);
```

超时时还应该：

```java
UNPROCESSED_RESPONSE_FUTURES.remove(requestId);
```

## 2. 发送失败时没有从表中移除

发送失败时当前逻辑：

```java
resultFuture.completeExceptionally(future.cause());
```

但是没有显式 remove `requestId`。

优化方式：

```java
unprocessedRequests.remove(rpcRequest.getRequestId());
resultFuture.completeExceptionally(future.cause());
```

## 3. complete 找不到 future 时直接抛异常

当前：

```java
throw new IllegalStateException();
```

如果响应超时后已经清理，迟到响应再回来，就可能触发这个异常。

优化方式：

- 记录 warn 日志。
- 丢弃迟到响应。
- 不影响 Netty I/O 线程继续处理后续消息。

## ChannelProvider 如何复用连接

`ChannelProvider` 负责缓存服务地址到 Channel 的映射。

核心字段：

```java
private final Map<String, Channel> channelMap;
```

构造方法中使用：

```java
channelMap = new ConcurrentHashMap<>();
```

key 是：

```java
inetSocketAddress.toString()
```

value 是：

```java
Channel
```

## get：获取可用连接

```java
public Channel get(InetSocketAddress inetSocketAddress) {
    String key = inetSocketAddress.toString();

    if (channelMap.containsKey(key)) {
        Channel channel = channelMap.get(key);
        if (channel != null && channel.isActive()) {
            return channel;
        } else {
            channelMap.remove(key);
        }
    }
    return null;
}
```

逻辑：

1. 根据服务地址查缓存。
2. 如果 Channel 存在且活跃，直接复用。
3. 如果 Channel 不存在或不可用，移除缓存并返回 null。

## set：缓存新连接

```java
public void set(InetSocketAddress inetSocketAddress, Channel channel) {
    String key = inetSocketAddress.toString();
    channelMap.put(key, channel);
}
```

`NettyRpcClient.getChannel()` 中：

```java
Channel channel = channelProvider.get(inetSocketAddress);
if (channel == null) {
    channel = doConnect(inetSocketAddress);
    channelProvider.set(inetSocketAddress, channel);
}
return channel;
```

面试表达：

> `ChannelProvider` 通过 `ConcurrentHashMap` 缓存服务地址到 Netty Channel 的映射。客户端调用远程服务时，先看是否已有 active Channel，有就直接复用，没有才重新建连。这样避免每次 RPC 都进行 TCP 握手，降低连接创建成本。

## ChannelProvider 的并发优化点

当前 `getChannel()` 是：

```java
Channel channel = channelProvider.get(inetSocketAddress);
if (channel == null) {
    channel = doConnect(inetSocketAddress);
    channelProvider.set(inetSocketAddress, channel);
}
```

在高并发下，多个线程可能同时发现 channel 为 null，于是同时创建多个连接，最后只有一个或最后一个进入 Map。

优化方向：

1. 使用 `computeIfAbsent` 控制同一地址只建一次连接。
2. 使用地址级锁，避免全局锁影响不同服务地址。
3. 缓存 `CompletableFuture<Channel>`，让并发线程共享同一个建连过程。

更合理的思路：

```java
ConcurrentHashMap<String, CompletableFuture<Channel>> connectingMap;
```

同一地址正在连接时，其他线程等待同一个 future。

## ConcurrentHashMap 在服务缓存中的使用

服务端服务提供者：

```java
simple.provider.Impl.ZKServiceProviderImpl
```

核心字段：

```java
private final Map<String, Object> serviceMap;
private final Set<String> registeredService;
```

初始化：

```java
serviceMap = new ConcurrentHashMap<>();
registeredService = ConcurrentHashMap.newKeySet();
```

## serviceMap

作用：

- key：`rpcServiceName`
- value：本地服务实现对象

注册服务时：

```java
serviceMap.put(rpcServiceName, rpcServiceConfig.getService());
```

处理请求时：

```java
Object service = serviceMap.get(rpcServiceName);
```

为什么用 `ConcurrentHashMap`：

- 服务注册可能发生在 Spring 初始化阶段。
- RPC 请求处理发生在 Netty 服务处理线程中。
- 多线程读写普通 `HashMap` 有风险。
- `ConcurrentHashMap` 支持高并发读写，读多写少场景性能较好。

## registeredService

作用：

- 记录哪些服务已经注册过。
- 避免重复注册。

当前逻辑：

```java
if (registeredService.contains(rpcServiceName)) {
    return;
}
registeredService.add(rpcServiceName);
```

这里有一个小的并发优化点：

`contains()` 和 `add()` 是两个操作，组合起来不是原子的。更稳妥写法是：

```java
if (!registeredService.add(rpcServiceName)) {
    return;
}
serviceMap.put(rpcServiceName, rpcServiceConfig.getService());
```

面试表达：

> 服务端本地服务缓存用 `ConcurrentHashMap`，因为服务注册和服务调用可能处在不同线程。`serviceMap` 用来根据服务名查实现对象，`registeredService` 用来避免重复发布服务。这里还能优化成直接使用 `registeredService.add()` 的返回值，保证检查和新增是一个原子动作。

## ConcurrentHashMap 在扩展缓存中的使用

## ExtensionLoader 缓存

`ExtensionLoader` 中有三个关键缓存：

```java
private static final Map<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS =
        new ConcurrentHashMap<>();

private final Map<String, Holder<Object>> cachedInstances =
        new ConcurrentHashMap<>();

private final Holder<Map<String, Class<?>>> cachedClasses =
        new Holder<>();
```

作用：

| 缓存 | 作用 |
| --- | --- |
| `EXTENSION_LOADERS` | 每个扩展接口一个加载器 |
| `cachedInstances` | 每个扩展名一个实现实例 |
| `cachedClasses` | 每个扩展接口下 name 到 Class 的映射 |

并发控制：

- `ConcurrentHashMap` 保证 Map 结构并发安全。
- `putIfAbsent()` 避免重复放入加载器或 holder。
- `synchronized (holder)` 做双重检查，避免同一个扩展实例重复创建。
- `Holder.value` 用 `volatile`，保证可见性。

## SingletonFactory 缓存

`SingletonFactory` 中：

```java
private static final Map<String, Holder<Object>> OBJECT_MAP_NEW =
        new ConcurrentHashMap<>();
```

并配合：

```java
synchronized (lock)
```

保证同一个类只创建一个单例。

面试表达：

> SPI 扩展缓存使用 `ConcurrentHashMap` 加双重检查，目标是让扩展加载在高并发下既线程安全又避免重复反射创建对象。`Holder` 中的 value 使用 volatile，是为了保证一个线程创建实例后，其他线程能及时看到。

## 扩展缓存中的线程安全风险

当前 `Holder.set()` 源码是：

```java
public void set(T instance) {
    this.value = value;
}
```

这里应该写成：

```java
this.value = instance;
```

否则缓存无法真正保存实例。它会影响：

- `ExtensionLoader.cachedInstances`
- `ExtensionLoader.cachedClasses`
- `SingletonFactory.OBJECT_MAP_NEW`

面试中可以说：

> 我复盘并发缓存时发现 `Holder.set()` 有一个赋值 bug，本意是通过 volatile 保证可见性，但实际没有把参数写入 value。这个要优先修复，否则缓存和单例都会失效。

## AtomicInteger 在请求编号中的作用

`RpcMessageEncoder` 中：

```java
private static final AtomicInteger ATOMIC_INTEGER = new AtomicInteger(0);
```

编码协议头时：

```java
out.writeInt(ATOMIC_INTEGER.getAndIncrement());
```

作用：

- 为协议头生成一个递增的 int 请求编号。
- `getAndIncrement()` 是原子操作。
- 避免多个线程同时编码消息时拿到重复编号。

为什么不能用普通 int：

```java
requestId++;
```

这不是原子操作，实际包含：

1. 读取当前值。
2. 加 1。
3. 写回。

多线程下可能两个线程读到同一个旧值，导致重复编号。

面试表达：

> 编码器可能被多个 Netty 线程并发调用，所以协议头 requestId 使用 `AtomicInteger.getAndIncrement()` 生成，保证递增编号的原子性。如果用普通 int 自增，会有并发覆盖和重复 ID 的风险。

## 当前 requestId 设计要注意

项目中有两个 requestId：

1. `RpcMessageEncoder` 生成的协议头 int requestId。
2. `RpcClientProxy` 生成的 body 内 String requestId。

业务响应匹配使用的是：

```java
RpcRequest.requestId
RpcResponse.requestId
```

也就是 UUID 字符串：

```java
UUID.randomUUID().toString()
```

协议头的 `AtomicInteger` requestId 当前没有参与 `UnprocessedRequests` 匹配。

优化方向：

- 统一使用协议头 requestId 做响应匹配。
- 或删除协议头 requestId，避免重复概念。
- 如果保留 `AtomicInteger`，要考虑 int 溢出后的处理。

## Netty EventLoop 线程模型

## 客户端线程模型

`NettyRpcClient` 中：

```java
eventLoopGroup = new NioEventLoopGroup();
bootstrap.group(eventLoopGroup)
        .channel(NioSocketChannel.class)
```

客户端使用一个 `NioEventLoopGroup`。

职责：

- 发起连接。
- 处理客户端 Channel 的读写事件。
- 执行客户端 Pipeline 中的 handler。
- 处理心跳写空闲事件。

客户端 Pipeline：

```java
p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
p.addLast(new RpcMessageDecoder());
p.addLast(new RpcMessageEncoder());
p.addLast(new NettyRpcClientHandler());
```

响应回来后，`NettyRpcClientHandler.channelRead()` 是由 Netty I/O 线程执行的。

## 服务端线程模型

`NettyRpcServer.start()` 中：

```java
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup();
DefaultEventExecutorGroup serviceHandlerGroup =
        new DefaultEventExecutorGroup(RuntimeUtil.cpus() * 2, ...);
```

## bossGroup

职责：

- 接收客户端连接。
- 完成 accept。
- 把连接交给 workerGroup。

项目中线程数是：

```java
new NioEventLoopGroup(1)
```

通常服务端只需要少量 boss 线程，因为 accept 本身不做复杂业务。

## workerGroup

职责：

- 处理已建立连接的 I/O 读写。
- 执行编解码器。
- 触发 ChannelPipeline 中的事件流转。

## DefaultEventExecutorGroup

项目把业务处理器放到了单独线程组：

```java
p.addLast(serviceHandlerGroup, new NettyRpcServerHandler());
```

线程数：

```java
RuntimeUtil.cpus() * 2
```

作用：

- 执行 `NettyRpcServerHandler.channelRead()`。
- 处理服务查找、反射调用、响应封装等业务逻辑。
- 避免耗时业务逻辑阻塞 Netty worker I/O 线程。

面试表达：

> 服务端用了三类线程。bossGroup 负责接收连接，workerGroup 负责网络读写和编解码，`DefaultEventExecutorGroup` 专门执行 RPC 业务 handler。这样如果服务端反射调用或业务逻辑比较耗时，不会直接阻塞 worker I/O 线程，有利于保持网络读写吞吐。

## EventLoop 的几个关键点

1. 一个 Channel 通常绑定到一个 EventLoop。
2. 同一个 Channel 的 I/O 事件在同一个 EventLoop 线程中串行执行。
3. 不同 Channel 可以分布到不同 EventLoop。
4. Netty 的 Channel 是线程安全语义较好的，可以从业务线程调用 `writeAndFlush()`，实际写操作会提交给对应 EventLoop 执行。
5. 不要在 EventLoop 线程里做阻塞操作，否则会影响这个 EventLoop 上的所有 Channel。

## 项目中哪些地方是线程安全设计

| 位置 | 设计 |
| --- | --- |
| `UnprocessedRequests` | `ConcurrentHashMap` 保存 requestId 到 future 的映射 |
| `ChannelProvider` | `ConcurrentHashMap` 保存服务地址到 Channel 的映射 |
| `ZKServiceProviderImpl.serviceMap` | `ConcurrentHashMap` 保存服务名到服务实例 |
| `ZKServiceProviderImpl.registeredService` | `ConcurrentHashMap.newKeySet()` 防重复注册 |
| `ExtensionLoader.EXTENSION_LOADERS` | `ConcurrentHashMap` 缓存扩展加载器 |
| `ExtensionLoader.cachedInstances` | `ConcurrentHashMap` 缓存扩展实例 |
| `SingletonFactory.OBJECT_MAP_NEW` | `ConcurrentHashMap` 缓存单例实例 |
| `RpcMessageEncoder.ATOMIC_INTEGER` | `AtomicInteger` 生成协议头编号 |
| `KryoSerializer` | `ThreadLocal<Kryo>` 避免共享非线程安全 Kryo |
| `ConsistentHashLoadBalanceNew.initFlag` | `volatile` 保证重建状态可见 |
| `ConsistentHashLoadBalanceNew.reBuild` | `synchronized` 控制 Hash 环重建 |

## 项目中的线程安全风险

## 1. Holder.set 赋值错误

当前：

```java
this.value = value;
```

应该：

```java
this.value = instance;
```

影响：

- 单例缓存可能失效。
- SPI 扩展实例可能重复创建。
- volatile 的可见性设计无法发挥作用。

## 2. ChannelProvider 建连存在并发重复创建风险

多个线程同时请求同一个服务地址时，都可能发现缓存为空，然后各自 `doConnect()`。

优化：

- 地址级锁。
- `computeIfAbsent`。
- 缓存 `CompletableFuture<Channel>`。

## 3. UnprocessedRequests 缺少超时清理

如果服务端不响应，`resultFuture.get()` 一直阻塞，Map 中 future 不会清理。

优化：

- `get(timeout)`。
- `orTimeout()`。
- 超时后 remove requestId。

## 4. 发送失败时未清理未完成请求

发送失败只调用：

```java
resultFuture.completeExceptionally(future.cause());
```

应同步移除 Map 中的 requestId。

## 5. Netty I/O 线程中抛出异常可能影响连接处理

`UnprocessedRequests.complete()` 找不到 future 时直接抛 `IllegalStateException`。

迟到响应、重复响应、超时清理后响应回来，都可能触发。

优化：

- 找不到 future 时记录日志并忽略。
- 不让单个异常影响 I/O 线程处理后续消息。

## 6. ProtostuffSerializer 使用 static LinkedBuffer

当前：

```java
private static final LinkedBuffer BUFFER =
        LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
```

多个线程共享同一个 `LinkedBuffer`，序列化时可能互相覆盖。

优化：

- 方法内创建局部 `LinkedBuffer`。
- 或使用 `ThreadLocal<LinkedBuffer>`。

## 7. ZK 客户端单例创建没有加锁

`CuratorUtils.getZkClient()` 中：

```java
if (zkClient != null && zkClient.getState() == CuratorFrameworkState.STARTED) {
    return zkClient;
}
zkClient = CuratorFrameworkFactory.builder()...
```

`zkClient` 是 static 字段，但创建过程没有同步控制。并发第一次调用时，可能重复创建客户端。

优化：

- synchronized。
- double-check。
- 使用 holder 单例。

## 8. ConsistentHashLoadBalanceNew 的 Hash 环读写风险

`reBuild()` 会修改：

```java
TreeMap<Long, String> virtualNodes
HashSet<String> physicalNodes
```

这些集合本身不是线程安全的。

项目用：

```java
volatile boolean initFlag
synchronized reBuild()
while (!initFlag) {}
```

来避免重建期间读取，但这种自旋等待会消耗 CPU，而且依赖所有读取都正确检查 `initFlag`。

优化：

- 使用读写锁。
- 构建新的不可变 Hash 环后用 volatile 引用整体替换。
- 使用 Copy-On-Write 思路，避免边读边改同一个 TreeMap。

## 9. ConsistentHashLoadBalanceNew 选择器可能被错误共享

当前创建选择器时：

```java
SingletonFactory.getInstance(
    () -> new ConsistentHashingLoadBalancer(...),
    ConsistentHashingLoadBalancer.class
)
```

如果单例工厂按 class 缓存，那么不同服务可能拿到同一个选择器，和外层 `selectors` 按 `rpcServiceName` 缓存的意图冲突。

优化：

- 直接 `new ConsistentHashingLoadBalancer(...)`。
- 或者按 `rpcServiceName` 作为缓存 key。

## 10. 服务对象本身的线程安全依赖业务实现

`serviceMap` 线程安全，只能保证服务对象引用的存取安全。

但真正执行：

```java
method.invoke(service, rpcRequest.getParameters());
```

时，如果服务对象内部有共享可变状态，线程安全要由业务服务自己保证。

面试表达：

> 框架能保证服务缓存 Map 的并发安全，但不能自动保证业务服务对象内部状态安全。如果服务实现类里有共享变量，就需要业务侧自己加锁、使用并发集合，或者尽量设计成无状态服务。

## 高频面试问题

## 1. 这个项目的客户端是同步调用还是异步调用？

参考回答：

> 底层是 Netty 异步 I/O，但对业务调用方表现为同步调用。业务线程发送请求后会创建 `CompletableFuture`，然后调用 `get()` 等待结果。真正的网络响应由 Netty I/O 线程接收，收到后根据 requestId 找到对应 future 并 complete，业务线程才继续执行。

高概率追问：

- 为什么不直接全异步返回 `CompletableFuture`？
- 这样做会不会阻塞业务线程？

## 2. CompletableFuture 在项目中起什么作用？

参考回答：

> 它主要用于跨线程传递异步结果。发送请求的是业务线程，接收响应的是 Netty I/O 线程。业务线程先创建 future 并放到未完成请求表，然后等待；I/O 线程收到响应后 complete 这个 future。这样就完成了异步网络响应到同步方法返回的转换。

高概率追问：

- `complete()` 之后 `get()` 会发生什么？
- `completeExceptionally()` 用在什么场景？

## 3. 客户端如何保证响应不会匹配错请求？

参考回答：

> 每个 `RpcRequest` 都有一个唯一 `requestId`，客户端发送前把 `requestId -> CompletableFuture` 放进 `UnprocessedRequests`。服务端处理完成后把同一个 requestId 放进 `RpcResponse` 返回。客户端 handler 收到响应后，根据 requestId 从 Map 中 remove 出对应 future 并 complete。所以即使响应乱序回来，也能匹配到正确请求。

高概率追问：

- requestId 是在哪里生成的？
- 为什么 complete 时要 remove 而不是 get？

## 4. UnprocessedRequests 为什么要用 ConcurrentHashMap？

参考回答：

> 因为它会被多个线程同时访问。业务线程发送请求时 put future，Netty I/O 线程收到响应时 remove future 并 complete。并发读写普通 HashMap 不安全，所以这里使用 `ConcurrentHashMap`。

高概率追问：

- 如果响应一直不回来怎么办？
- 超时后响应又回来了怎么办？

## 5. ChannelProvider 如何实现连接复用？

参考回答：

> `ChannelProvider` 内部维护一个服务地址到 Channel 的 `ConcurrentHashMap`。客户端发请求时先根据目标地址查缓存，如果 Channel 存在并且 active，就直接复用；如果不存在或不可用，就重新建立连接并放入缓存。这样可以避免每次 RPC 都创建 TCP 连接。

高概率追问：

- 高并发下会不会重复建连？
- Channel 失效后怎么处理？

## 6. 服务端为什么要用 ConcurrentHashMap 缓存服务？

参考回答：

> 服务端 `ZKServiceProviderImpl` 用 `serviceMap` 保存服务名到服务对象的映射，用 `registeredService` 记录已经注册过的服务。RPC 请求处理是多线程的，多个请求可能同时查服务对象，所以用 `ConcurrentHashMap` 保证并发安全，同时读性能也比整张表加 synchronized 更好。

高概率追问：

- `registeredService.contains()` 再 `add()` 是原子的吗？
- serviceMap 线程安全是否代表服务对象也线程安全？

## 7. Netty 服务端线程模型是什么？

参考回答：

> 服务端有 bossGroup、workerGroup 和 serviceHandlerGroup。bossGroup 负责接收连接，workerGroup 负责已连接 Channel 的网络读写和编解码，serviceHandlerGroup 是 `DefaultEventExecutorGroup`，专门处理 RPC 业务逻辑，比如服务查找和反射调用。这样可以避免业务处理阻塞 Netty I/O 线程。

高概率追问：

- 为什么业务 handler 要放到单独线程池？
- 如果在 EventLoop 里执行耗时任务会怎样？

## 8. AtomicInteger 在协议编码里有什么用？

参考回答：

> `RpcMessageEncoder` 中用 `AtomicInteger.getAndIncrement()` 生成协议头 requestId。因为编码器可能被多个线程并发调用，普通 int 自增不是原子操作，可能产生重复编号。AtomicInteger 可以保证递增操作的原子性。

高概率追问：

- 项目真正用哪个 requestId 匹配响应？
- AtomicInteger 会不会溢出？

## 9. 项目里有哪些线程安全风险？

参考回答：

> 比较明显的有几个。第一，`Holder.set()` 当前赋值写错，会导致单例和扩展缓存失效。第二，`UnprocessedRequests` 缺少超时清理，服务端不返回可能造成阻塞和内存泄漏。第三，`ChannelProvider` 在高并发下可能重复建连。第四，`ProtostuffSerializer` 使用 static `LinkedBuffer`，多线程序列化有风险。第五，业务服务对象本身是否线程安全，需要业务实现自己保证。

高概率追问：

- 你会优先修哪个？
- 怎么给 RPC 调用加超时？

## 10. Kryo 为什么要用 ThreadLocal？

参考回答：

> Kryo 实例不是线程安全的，如果多个线程共享同一个 Kryo，可能出现序列化数据错乱。项目用 `ThreadLocal<Kryo>`，让每个线程持有自己的 Kryo 实例，从而避免并发问题，同时也减少频繁创建 Kryo 的开销。

高概率追问：

- ThreadLocal 有内存泄漏风险吗？
- Protostuff 为什么当前实现不安全？

## 可背诵面试回答

> 这个 RPC 项目在并发和异步处理上，核心是 Netty 异步 I/O 加 `CompletableFuture` 异步转同步。客户端调用远程方法时，业务线程会构造 `RpcRequest`，创建一个 `CompletableFuture`，然后以 requestId 为 key 放入 `UnprocessedRequests`。请求通过 Netty Channel 异步发送出去，业务线程调用 `future.get()` 等待结果。服务端处理完成后返回 `RpcResponse`，客户端的 `NettyRpcClientHandler` 由 Netty I/O 线程执行，它会从响应中取出 requestId，到未完成请求表中 remove 对应 future 并 complete，业务线程就能恢复执行。连接复用方面，`ChannelProvider` 用 `ConcurrentHashMap` 缓存服务地址到 Channel 的映射，有 active Channel 就复用，没有才重新建连。服务端本地服务缓存、SPI 扩展缓存、单例缓存也大量使用 `ConcurrentHashMap`，配合 `AtomicInteger`、`volatile`、`synchronized`、`ThreadLocal` 保证关键并发场景下的安全。整体上，这个项目体现了 RPC 框架里请求响应匹配、异步转同步、连接复用和线程安全缓存这些核心设计。

## 面试加分回答

如果面试官问优化，可以继续说：

> 当前实现还有一些可以优化的并发点。第一，RPC 调用应该加超时，避免 `future.get()` 无限等待，同时超时后要清理 `UnprocessedRequests`。第二，发送失败时也要从未完成请求表移除 requestId。第三，`ChannelProvider` 可以用地址级锁或缓存 `CompletableFuture<Channel>`，避免高并发下重复建连。第四，`Holder.set()` 的赋值 bug 要修复，否则扩展缓存和单例缓存会失效。第五，Protostuff 的静态 `LinkedBuffer` 要改成局部变量或 ThreadLocal。第六，业务 handler 放在独立线程组是对的，但后续还可以对业务线程池做队列、拒绝策略和监控，避免服务端被慢请求拖垮。
