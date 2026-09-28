# 02 RPC Call Flow

## 一句话总览

一次完整 RPC 调用可以概括为：

> 客户端业务代码调用 `@RpcReference` 注入的接口代理对象，代理对象把本地方法调用封装成 `RpcRequest`，通过 ZooKeeper 发现服务地址，用 Netty 按自定义协议发送到服务端；服务端解码请求后，根据服务名从本地缓存找到服务对象，反射调用目标方法，再把结果封装成 `RpcResponse` 返回；客户端收到响应后根据 `requestId` 找到对应的 `CompletableFuture`，最终把远程结果返回给业务代码。

## 调用前置条件

真正发起远程调用之前，服务端和客户端都已经完成了一些准备工作。

### 服务端已经完成服务注册

服务端启动类是 `example-server` 模块中的 `NettyServerMain`。

关键类和方法：

- `exampleServer.NettyServerMain`
- `simple.annotation.RpcScan`
- `simple.spring.SpringBeanPostProcessor.postProcessBeforeInitialization()`
- `simple.provider.Impl.ZKServiceProviderImpl.publishService()`
- `simple.provider.Impl.ZKServiceProviderImpl.addService()`
- `simple.registry.Impl.ZKServiceRegistryImpl.registerService()`
- `simple.registry.Impl.util.CuratorUtils.createPersistentNode()`

流程：

1. 服务实现类 `HelloServiceImpl` 标注了 `@RpcService(group = "test1", version = "version1")`。
2. Spring 初始化 Bean 时，`SpringBeanPostProcessor.postProcessBeforeInitialization()` 发现该 Bean 上有 `@RpcService`。
3. 框架把服务对象、group、version 封装成 `RpcServiceConfig`。
4. `ZKServiceProviderImpl.publishService()` 先调用 `addService()`，把服务对象放入本地 `serviceMap`。
5. 然后调用 `ZKServiceRegistryImpl.registerService()`，通过 Curator 在 ZooKeeper 的 `/my-rpc` 路径下注册服务节点。

这一步很关键：服务端先把服务放入本地缓存，再注册到 ZooKeeper。这样客户端发现服务并发起调用时，服务端本地已经能找到服务对象。

### 客户端已经完成代理注入

客户端调用入口在 `example-client` 模块中的 `HelloController`。

关键类和方法：

- `exampleClient.HelloController`
- `simple.annotation.RpcReference`
- `simple.spring.SpringBeanPostProcessor.postProcessAfterInitialization()`
- `simple.proxy.RpcClientProxy.getProxy()`

流程：

1. `HelloController` 中的字段 `private HelloService helloService` 标注了 `@RpcReference(version = "version1", group = "test1")`。
2. Spring 初始化 `HelloController` 后，`SpringBeanPostProcessor.postProcessAfterInitialization()` 遍历 Bean 的字段。
3. 如果字段上存在 `@RpcReference`，就读取注解中的 group 和 version。
4. 构造 `RpcServiceConfig`，保存目标服务的 group/version。
5. 创建 `RpcClientProxy`。
6. 调用 `rpcClientProxy.getProxy(declaredField.getType())`，基于接口类型创建 JDK 动态代理对象。
7. 通过反射 `declaredField.set(bean, clientProxy)` 把代理对象注入到 `HelloController.helloService` 字段中。

所以业务代码看到的是 `HelloService` 接口，实际注入进去的是代理对象。

## 完整调用链

下面按一次 `helloService.hello(new Hello(...))` 的真实执行顺序梳理。

## 1. 客户端业务代码发起本地接口调用

调用位置：

- `exampleClient.HelloController.test()`

代码行为：

```java
String hello = this.helloService.hello(new Hello("mori_nana", "森七菜"));
```

表面上看，这是一次普通的 Java 接口方法调用。实际上 `helloService` 字段已经被注入为 JDK 动态代理对象，所以这次调用不会直接执行服务端实现类，而是进入代理对象的 `invoke()` 方法。

## 2. JDK 动态代理拦截方法调用

关键类和方法：

- `simple.proxy.RpcClientProxy`
- `RpcClientProxy.invoke(Object proxy, Method method, Object[] args)`

`RpcClientProxy` 实现了 `InvocationHandler`。客户端调用 `helloService.hello(...)` 时，会被 JDK 动态代理拦截，然后进入 `invoke()`。

在这个方法里，框架能拿到本次调用的全部信息：

- 接口名：`method.getDeclaringClass().getName()`
- 方法名：`method.getName()`
- 参数值：`args`
- 参数类型：`method.getParameterTypes()`
- 服务分组：`rpcServiceConfig.getGroup()`
- 服务版本：`rpcServiceConfig.getVersion()`

这一步的意义是：把一次本地方法调用，转换成一次可以跨网络传输的 RPC 请求。

## 3. 构造 RpcRequest

关键类和方法：

- `simple.proxy.RpcClientProxy.invoke()`
- `simple.remoting.dto.RpcRequest`

代理对象在 `invoke()` 中构造 `RpcRequest`：

```java
RpcRequest rpcRequest = RpcRequest.builder()
        .methodName(method.getName())
        .parameters(args)
        .interfaceName(method.getDeclaringClass().getName())
        .paramTypes(method.getParameterTypes())
        .requestId(UUID.randomUUID().toString())
        .group(rpcServiceConfig.getGroup())
        .version(rpcServiceConfig.getVersion())
        .build();
```

`RpcRequest` 里面最重要的字段：

| 字段 | 作用 |
| --- | --- |
| `requestId` | 本次请求的唯一 ID，用于客户端匹配响应 |
| `interfaceName` | 目标接口全限定名，例如 `helloserviceapi.HelloService` |
| `methodName` | 要调用的方法名，例如 `hello` |
| `parameters` | 方法参数值 |
| `paramTypes` | 方法参数类型，服务端反射调用时需要 |
| `group` | 服务分组，用于区分同接口不同分组 |
| `version` | 服务版本，用于区分同接口不同版本 |

服务名由 `RpcRequest.getRpcServiceName()` 生成：

```java
interfaceName + group + version
```

例如：

```text
helloserviceapi.HelloServicetest1version1
```

这个服务名必须和服务端注册时生成的服务名一致，客户端才能在 ZooKeeper 中找到对应服务。

## 4. 代理对象调用传输层发送请求

关键类和方法：

- `simple.proxy.RpcClientProxy.invoke()`
- `simple.remoting.transport.RpcRequestTransport.sendRpcRequest()`
- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`

构造完 `RpcRequest` 后，代理对象调用：

```java
RpcResponse<Object> rpcResponse =
        (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);
```

这里的 `rpcRequestTransport` 是传输层接口，实际实现由自定义 SPI 加载。目前项目中配置的是 Netty 实现：

- SPI 配置文件：`META-INF/simple.remoting.transport.RpcRequestTransport`
- 配置内容：`netty=simple.remoting.transport.netty.client.NettyRpcClient`
- 实现类：`NettyRpcClient`

所以请求会进入 `NettyRpcClient.sendRpcRequest()`。

## 5. 客户端创建 CompletableFuture 保存等待结果

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `java.util.concurrent.CompletableFuture`

`NettyRpcClient.sendRpcRequest()` 一开始创建：

```java
CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
```

原因是 Netty 的网络读写是异步的。业务线程发出请求后，不知道服务端什么时候返回，也不知道响应会不会乱序回来。

所以这里用 `CompletableFuture` 表示“这个请求未来会有一个结果”。

## 6. 通过 ZooKeeper 进行服务发现

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.registry.ServiceDiscovery.lookupService()`
- `simple.registry.Impl.ZkServiceDiscoveryImpl.lookupService()`
- `simple.registry.Impl.util.CuratorUtils.getZkClient()`
- `simple.registry.Impl.util.CuratorUtils.getChildrenNodes()`
- `simple.loadbalance.LoadBalance.selectServiceAddress()`

客户端调用：

```java
InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
```

具体流程：

1. `ZkServiceDiscoveryImpl.lookupService()` 调用 `rpcRequest.getRpcServiceName()` 得到服务名。
2. 调用 `CuratorUtils.getZkClient()` 获取 ZooKeeper 客户端。
3. 调用 `CuratorUtils.getChildrenNodes(zkClient, rpcServiceName)` 获取该服务下的所有服务地址节点。
4. 如果本地 `SERVICE_ADDRESS_MAP` 中有缓存，则直接返回缓存。
5. 如果没有缓存，则从 ZooKeeper 的 `/my-rpc/{rpcServiceName}` 路径下读取子节点，并注册节点变化监听器。
6. 拿到服务地址列表后，调用 `loadBalance.selectServiceAddress(serviceUrlList, rpcRequest)` 选择一个目标地址。
7. 把字符串地址拆成 host 和 port，返回 `InetSocketAddress`。

这一步的结果是：客户端知道这次请求要发往哪个服务端 IP 和端口。

## 7. 获取或建立 Netty Channel

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.getChannel()`
- `simple.remoting.transport.netty.client.ChannelProvider.get()`
- `simple.remoting.transport.netty.client.NettyRpcClient.doConnect()`
- `simple.remoting.transport.netty.client.ChannelProvider.set()`

服务发现拿到地址后，客户端调用：

```java
Channel channel = getChannel(inetSocketAddress);
```

流程：

1. `ChannelProvider.get(inetSocketAddress)` 根据服务地址从 `ConcurrentHashMap` 中查询已有 Channel。
2. 如果 Channel 存在并且 `channel.isActive()`，直接复用。
3. 如果不存在或不可用，则调用 `doConnect(inetSocketAddress)` 建立新连接。
4. `doConnect()` 内部调用 `bootstrap.connect(inetSocketAddress)`，这是 Netty 异步连接。
5. 连接成功后，在监听器中执行 `completableFuture.complete(future.channel())`。
6. 当前业务线程通过 `completableFuture.get()` 等待并拿到 Channel。
7. 新 Channel 会通过 `ChannelProvider.set(inetSocketAddress, channel)` 缓存起来，后续请求可以复用。

这一步的意义是：避免每次 RPC 调用都重新建立 TCP 连接。

## 8. 保存未完成请求

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.remoting.transport.netty.client.UnprocessedRequests.put()`

拿到可用 Channel 后，客户端先把请求 ID 和 Future 保存起来：

```java
unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);
```

`UnprocessedRequests` 内部维护：

```java
Map<String, CompletableFuture<RpcResponse<Object>>> UNPROCESSED_RESPONSE_FUTURES
```

它的 key 是 `RpcRequest.requestId`，value 是等待响应的 `CompletableFuture`。

为什么要这样做：

- 客户端可能同时发送多个 RPC 请求。
- 服务端处理速度不同，响应返回顺序不一定等于请求发送顺序。
- 客户端收到响应时，必须根据 `requestId` 找到对应的业务线程和 Future。

## 9. 构造 RpcMessage 并发送

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.remoting.dto.RpcMessage`
- `io.netty.channel.Channel.writeAndFlush()`

客户端把 `RpcRequest` 包装成 `RpcMessage`：

```java
RpcMessage rpcMessage = RpcMessage.builder()
        .data(rpcRequest)
        .codec(SerializationTypeEnum.HESSIAN.getCode())
        .compress(CompressTypeEnum.GZIP.getCode())
        .messageType(RpcConstants.REQUEST_TYPE)
        .build();
```

这里的含义：

| 字段 | 说明 |
| --- | --- |
| `data` | 真实业务请求，也就是 `RpcRequest` |
| `codec` | 序列化方式，目前请求使用 Hessian |
| `compress` | 压缩方式，目前使用 Gzip |
| `messageType` | 消息类型，`REQUEST_TYPE` 表示 RPC 请求 |

然后调用：

```java
channel.writeAndFlush(rpcMessage)
```

这会把消息写入 Netty Channel，后续进入客户端的编码器。

## 10. 客户端编码 RpcMessage

关键类和方法：

- `simple.remoting.transport.netty.codec.RpcMessageEncoder.encode()`
- `simple.serialize.Serializer.serialize()`
- `simple.compress.Compress.compress()`

`RpcMessageEncoder` 会把 `RpcMessage` 编码成字节流。

编码流程：

1. 写入魔数 `RpcConstants.MAGIC_NUMBER`，用于识别协议。
2. 写入协议版本 `RpcConstants.VERSION`。
3. 预留 4 字节消息总长度。
4. 写入消息类型 `messageType`。
5. 写入序列化方式 `codec`。
6. 写入压缩方式 `compress`。
7. 写入协议头中的 int 类型 requestId。
8. 如果不是心跳消息，则根据 `codec` 通过 `ExtensionLoader` 获取序列化器。
9. 将 `rpcMessage.getData()` 序列化成字节数组。
10. 根据 `compress` 获取压缩器，对字节数组进行压缩。
11. 计算完整消息长度 `fullLength`。
12. 写入 body。
13. 回填消息总长度字段。

注意：项目里有两个“请求 ID”概念。

- `RpcRequest.requestId`：字符串 UUID，用于业务请求和响应匹配，这是客户端真正用来匹配响应的 ID。
- `RpcMessage.requestId`：协议头中的 int，目前由 `RpcMessageEncoder` 的 `AtomicInteger` 生成，主要属于协议头字段。

客户端响应匹配使用的是 `RpcResponse.requestId`，它来自原始 `RpcRequest.requestId`。

## 11. 服务端解码请求

关键类和方法：

- `simple.remoting.transport.netty.codec.RpcMessageDecoder`
- `RpcMessageDecoder.decode()`
- `RpcMessageDecoder.decodeFrame()`
- `LengthFieldBasedFrameDecoder`

服务端 `NettyRpcServer` 的 Pipeline 中添加了：

- `RpcMessageEncoder`
- `RpcMessageDecoder`
- `NettyRpcServerHandler`

当服务端收到字节流后，先进入 `RpcMessageDecoder`。

解码流程：

1. `LengthFieldBasedFrameDecoder` 根据长度字段切出完整消息帧，解决 TCP 粘包半包问题。
2. `checkMagicNumber()` 校验魔数，判断是否为当前 RPC 协议。
3. `checkVersion()` 校验协议版本。
4. 读取 `fullLength`。
5. 读取 `messageType`、`codecType`、`compressType`、协议头 requestId。
6. 构造 `RpcMessage`。
7. 如果 body 长度大于 0，则读取 body 字节数组。
8. 通过压缩器解压。
9. 根据 `codec` 获取序列化器。
10. 如果是 `REQUEST_TYPE`，反序列化为 `RpcRequest`，放入 `rpcMessage.data`。
11. 如果是 `RESPONSE_TYPE`，反序列化为 `RpcResponse`。

注意：当前 `RpcMessageDecoder` 中获取压缩器时使用了 `CompressTypeEnum.getName(codecType)`，从语义上看这里更应该使用 `compressType`。这不影响我们梳理主链路，但如果面试官问项目不足，这是一个可以主动说明并优化的点。

## 12. 服务端 Handler 处理 RpcRequest

关键类和方法：

- `simple.remoting.transport.netty.server.NettyRpcServerHandler.channelRead()`
- `simple.remoting.handler.RpcRequestHandler.handle()`

解码完成后，Netty 会把 `RpcMessage` 传给 `NettyRpcServerHandler.channelRead()`。

处理流程：

1. 判断 `msg instanceof RpcMessage`。
2. 读取 `messageType`。
3. 如果是心跳消息，封装心跳响应。
4. 如果是普通请求，从 `rpcMessage.getData()` 中取出 `RpcRequest`。
5. 调用 `rpcRequestHandler.handle(rpcRequest)` 执行业务方法。

`NettyRpcServer` 中把 `NettyRpcServerHandler` 放到了 `DefaultEventExecutorGroup` 中执行，避免业务处理逻辑直接阻塞 Netty IO 线程。

## 13. 根据服务名找到本地服务对象

关键类和方法：

- `simple.remoting.handler.RpcRequestHandler.handle()`
- `simple.remoting.dto.RpcRequest.getRpcServiceName()`
- `simple.provider.Impl.ZKServiceProviderImpl.getService()`

`RpcRequestHandler.handle()` 中先执行：

```java
Object service = serviceProvider.getService(rpcRequest.getRpcServiceName());
```

服务名仍然由以下内容拼接：

```text
interfaceName + group + version
```

服务端本地的 `ZKServiceProviderImpl.serviceMap` 保存了：

```text
rpcServiceName -> serviceObject
```

这个 `serviceMap` 是服务端启动注册服务时写入的。比如 `HelloServiceImpl` 被 `@RpcService` 扫描到之后，会被放入这个 Map。

如果 Map 中找不到服务对象，则抛出 `SERVICE_CAN_NOT_BE_FOUND` 对应的 `RpcException`。

## 14. 服务端反射调用目标方法

关键类和方法：

- `simple.remoting.handler.RpcRequestHandler.invokeTargetMethod()`
- `java.lang.reflect.Method.invoke()`

找到服务对象后，服务端通过反射调用目标方法：

```java
Method method = service.getClass().getMethod(
        rpcRequest.getMethodName(),
        rpcRequest.getParamTypes()
);
result = method.invoke(service, rpcRequest.getParameters());
```

为什么 `RpcRequest` 里要保存 `paramTypes`：

- Java 支持方法重载。
- 只有方法名不够准确。
- 服务端需要方法名和参数类型一起定位目标方法。

例如：

```text
methodName = hello
paramTypes = [Hello.class]
parameters = [new Hello(...)]
```

服务端最终会调用：

```java
HelloServiceImpl.hello(Hello hello)
```

得到真实业务结果。

## 15. 服务端封装 RpcResponse

关键类和方法：

- `simple.remoting.transport.netty.server.NettyRpcServerHandler.channelRead()`
- `simple.remoting.dto.RpcResponse.success()`
- `simple.remoting.dto.RpcMessage`

服务端拿到反射调用结果后，封装响应：

```java
RpcResponse<Object> rpcResponse =
        RpcResponse.success(result, rpcRequest.getRequestId());
rpcMessage.setData(rpcResponse);
```

这里必须把原请求中的 `rpcRequest.getRequestId()` 放入响应。

原因是客户端等待响应时，是用这个字符串 requestId 存入 `UnprocessedRequests` 的。响应带着同一个 requestId 回来，客户端才能找到对应的 `CompletableFuture`。

服务端同时设置：

```java
rpcMessage.setMessageType(RpcConstants.RESPONSE_TYPE);
rpcMessage.setCodec(SerializationTypeEnum.HESSIAN.getCode());
rpcMessage.setCompress(CompressTypeEnum.GZIP.getCode());
```

最后调用：

```java
ctx.writeAndFlush(rpcMessage)
```

响应消息再次经过服务端 `RpcMessageEncoder` 编码后，通过 Netty Channel 返回客户端。

## 16. 客户端解码响应

关键类和方法：

- `simple.remoting.transport.netty.codec.RpcMessageDecoder.decode()`
- `simple.remoting.transport.netty.codec.RpcMessageDecoder.decodeFrame()`
- `simple.remoting.dto.RpcResponse`

客户端收到服务端返回的字节流后，也会进入 `RpcMessageDecoder`。

解码流程和服务端类似：

1. 根据长度字段拆完整帧。
2. 校验魔数和版本。
3. 读取消息头。
4. 解压 body。
5. 反序列化 body。
6. 如果 `messageType == RESPONSE_TYPE`，则反序列化为 `RpcResponse`。
7. 把 `RpcResponse` 放入 `RpcMessage.data`。

解码完成后，消息继续传给 `NettyRpcClientHandler`。

## 17. 客户端根据 requestId 匹配响应

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClientHandler.channelRead()`
- `simple.remoting.transport.netty.client.UnprocessedRequests.complete()`
- `java.util.concurrent.CompletableFuture.complete()`

客户端 Handler 收到 `RpcMessage` 后：

1. 判断消息类型是否为 `RESPONSE_TYPE`。
2. 从 `RpcMessage.data` 取出 `RpcResponse<Object>`。
3. 调用 `unprocessedRequests.complete(rpcResponse)`。

`UnprocessedRequests.complete()` 内部执行：

```java
CompletableFuture<RpcResponse<Object>> future =
        UNPROCESSED_RESPONSE_FUTURES.remove(rpcResponse.getRequestId());

future.complete(rpcResponse);
```

也就是说：

- 用响应中的 `requestId` 找到发送请求时保存的 Future。
- 从未完成请求 Map 中移除，避免内存泄漏。
- 调用 `future.complete(rpcResponse)` 唤醒正在等待的业务线程。

## 18. 业务线程拿到响应并返回真实结果

关键类和方法：

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.proxy.RpcClientProxy.check()`
- `simple.proxy.RpcClientProxy.invoke()`

之前业务线程在 `NettyRpcClient.sendRpcRequest()` 里阻塞等待：

```java
return resultFuture.get();
```

当客户端 Handler 调用 `future.complete(rpcResponse)` 后，`resultFuture.get()` 返回。

然后回到 `RpcClientProxy.invoke()`：

```java
this.check(rpcResponse, rpcRequest);
return rpcResponse.getData();
```

`check()` 会校验：

- 响应不能为 null。
- 请求 ID 和响应 ID 必须一致。
- 响应码必须是成功状态。

校验通过后，代理对象返回 `rpcResponse.getData()`，业务代码最终拿到远程方法的返回值。

到这里，一次完整 RPC 调用结束。

## 完整时序表

| 顺序 | 阶段 | 核心类 | 核心方法 |
| --- | --- | --- | --- |
| 1 | 客户端字段注入代理 | `SpringBeanPostProcessor` | `postProcessAfterInitialization()` |
| 2 | 创建 JDK 动态代理 | `RpcClientProxy` | `getProxy()` |
| 3 | 业务代码调用接口 | `HelloController` | `test()` |
| 4 | 代理拦截调用 | `RpcClientProxy` | `invoke()` |
| 5 | 构造请求对象 | `RpcRequest` | `builder()` |
| 6 | 进入传输层 | `NettyRpcClient` | `sendRpcRequest()` |
| 7 | 服务发现 | `ZkServiceDiscoveryImpl` | `lookupService()` |
| 8 | 读取 ZK 节点 | `CuratorUtils` | `getChildrenNodes()` |
| 9 | 负载均衡选择地址 | `LoadBalance` | `selectServiceAddress()` |
| 10 | 获取或建立连接 | `NettyRpcClient` / `ChannelProvider` | `getChannel()` / `doConnect()` |
| 11 | 保存未完成请求 | `UnprocessedRequests` | `put()` |
| 12 | 包装 RPC 消息 | `RpcMessage` | `builder()` |
| 13 | 发送消息 | `Channel` | `writeAndFlush()` |
| 14 | 客户端编码 | `RpcMessageEncoder` | `encode()` |
| 15 | 服务端解码 | `RpcMessageDecoder` | `decode()` / `decodeFrame()` |
| 16 | 服务端处理请求 | `NettyRpcServerHandler` | `channelRead()` |
| 17 | 查找本地服务 | `ZKServiceProviderImpl` | `getService()` |
| 18 | 反射调用方法 | `RpcRequestHandler` | `invokeTargetMethod()` |
| 19 | 封装响应 | `RpcResponse` | `success()` |
| 20 | 服务端写回响应 | `ChannelHandlerContext` | `writeAndFlush()` |
| 21 | 客户端解码响应 | `RpcMessageDecoder` | `decodeFrame()` |
| 22 | 匹配请求响应 | `NettyRpcClientHandler` / `UnprocessedRequests` | `channelRead()` / `complete()` |
| 23 | 返回业务结果 | `RpcClientProxy` | `check()` / `invoke()` |

## 面试官可能追问的点

### 为什么要用动态代理？

因为客户端只有接口，没有远程服务实现类。动态代理可以拦截接口方法调用，把方法调用信息封装成网络请求。这样业务代码只需要面向接口编程，不需要关心服务发现、网络通信、序列化这些 RPC 细节。

### 为什么 RpcRequest 要保存 paramTypes？

因为服务端要通过反射定位方法。Java 允许方法重载，只靠方法名可能无法唯一确定方法，所以要同时传方法名和参数类型。

### 客户端为什么要维护 UnprocessedRequests？

因为 Netty 是异步通信，请求和响应可能乱序。客户端发送请求前，把 `requestId` 和 `CompletableFuture` 存起来；收到响应后，根据响应中的 `requestId` 找到对应 Future 并完成它。

### 服务端为什么要有本地 serviceMap？

ZooKeeper 只负责告诉客户端服务在哪，不负责真正执行服务。服务端收到请求后，还需要根据服务名从本地 `serviceMap` 找到真实服务对象，然后才能反射调用。

### 这个调用是同步还是异步？

底层 Netty 网络通信是异步的，但当前对业务调用方暴露的是同步调用体验。客户端发送请求后通过 `CompletableFuture.get()` 等待响应，所以业务代码看起来像普通同步方法调用。

## 完整调用链面试回答

可以直接背这一段：

> 这个项目里一次 RPC 调用是从客户端的 `@RpcReference` 字段开始的。客户端启动时，`SpringBeanPostProcessor` 会在 Bean 初始化后扫描字段，如果发现 `@RpcReference`，就读取注解里的 group 和 version，然后通过 `RpcClientProxy` 为接口创建 JDK 动态代理对象，并注入到业务 Bean 中。所以业务代码调用 `helloService.hello()` 时，实际进入的是代理对象的 `invoke()` 方法。
>
> 在 `invoke()` 中，框架会把本地方法调用信息封装成 `RpcRequest`，里面包括接口名、方法名、参数、参数类型、requestId、group 和 version。然后代理对象调用 `NettyRpcClient.sendRpcRequest()` 发送请求。客户端会先根据 `RpcRequest.getRpcServiceName()` 生成服务名，通过 `ZkServiceDiscoveryImpl` 到 ZooKeeper 查询服务地址列表，再通过负载均衡选择一个目标服务地址。
>
> 拿到服务地址后，客户端会通过 `ChannelProvider` 查询是否已经有可用的 Netty Channel。如果有就复用，没有就通过 `Bootstrap.connect()` 建立新连接。发送请求前，客户端会创建一个 `CompletableFuture`，并把 `requestId` 和这个 Future 放入 `UnprocessedRequests`，用于后续响应回来时匹配。之后把 `RpcRequest` 包装成 `RpcMessage`，设置消息类型、序列化方式和压缩方式，再调用 `channel.writeAndFlush()` 发出去。
>
> 消息发送前会经过 `RpcMessageEncoder` 编码。编码器会写入魔数、版本号、消息长度、消息类型、序列化类型、压缩类型等协议头，然后把请求体序列化、压缩后写入 body。服务端收到字节流后，`RpcMessageDecoder` 会用 `LengthFieldBasedFrameDecoder` 根据长度字段切出完整消息，解决粘包半包问题，然后校验魔数和版本，解压并反序列化出 `RpcRequest`。
>
> 解码后的请求进入 `NettyRpcServerHandler.channelRead()`。服务端从 `RpcMessage` 中取出 `RpcRequest`，交给 `RpcRequestHandler.handle()`。这个方法会根据 `rpcRequest.getRpcServiceName()` 从 `ZKServiceProviderImpl` 的本地 `serviceMap` 中找到真实服务对象，然后根据方法名和参数类型反射调用目标方法。执行完成后，服务端把结果封装成 `RpcResponse`，并且把原请求的 `requestId` 放到响应里，再包装成 `RpcMessage` 通过 Netty 写回客户端。
>
> 客户端收到响应后，同样先解码成 `RpcMessage`，再由 `NettyRpcClientHandler.channelRead()` 取出里面的 `RpcResponse`。然后调用 `UnprocessedRequests.complete()`，根据响应里的 `requestId` 找到之前保存的 `CompletableFuture`，把响应结果写进去。业务线程从 `resultFuture.get()` 返回，代理对象校验响应状态和 requestId 后，最终把 `rpcResponse.getData()` 返回给业务代码。这样就完成了一次从本地接口调用到远程服务执行再返回结果的完整 RPC 调用。
