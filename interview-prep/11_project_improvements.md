# 11 Project Improvements

## 一句话定位

这份资料从面试官和代码评审视角，专门分析当前 RPC 项目的真实不足、风险点和优化方案。面试时不要回避问题，应该把它们讲成“我知道项目边界，也知道如何继续演进”。

面试时可以先这样定调：

> 这个项目作为学习型 RPC 框架，已经覆盖了 RPC 的核心链路，比如动态代理、Netty 通信、自定义协议、ZooKeeper 注册发现、序列化压缩、负载均衡和 SPI。但如果按生产级框架标准来看，还需要补充超时、重试、熔断限流、连接治理、服务下线、监控、配置化和测试压测等能力。我会把这些不足当成后续优化方向，而不是说这个项目已经完全生产可用。

## 总体评审结论

当前项目亮点是主链路完整，但工程化能力还偏基础。

可以概括成：

- 核心 RPC 调用链已经跑通。
- 扩展点设计有雏形。
- 并发缓存和 Netty 线程模型有基本考虑。
- 生产级治理能力不足。
- 部分实现存在真实 bug 或风险点。

下面按面试常问角度逐项分析。

## 1. 超时控制

## 当前项目现状

客户端连接时：

```java
return completableFuture.get();
```

位置：

```text
simple.remoting.transport.netty.client.NettyRpcClient#doConnect
```

客户端等待 RPC 响应时：

```java
return resultFuture.get();
```

位置：

```text
simple.remoting.transport.netty.client.NettyRpcClient#sendRpcRequest
```

Netty 连接参数中配置了连接超时：

```java
.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
```

但是 RPC 调用本身没有响应超时控制。

## 存在风险

如果服务端没有返回响应，或者响应在网络中丢失，业务线程会一直阻塞在 `resultFuture.get()`。

直接后果：

- 业务线程被长期占用。
- `UnprocessedRequests` 中的 future 无法清理。
- 高并发下可能导致内存增长和线程资源耗尽。
- 调用方无法快速失败。

## 优化方案

1. 给 RPC 响应等待增加超时：

```java
resultFuture.get(timeout, TimeUnit.MILLISECONDS);
```

2. 超时后清理未完成请求：

```java
unprocessedRequests.remove(rpcRequest.getRequestId());
```

3. 支持配置化超时时间：

```text
rpc.request.timeout=3000
rpc.connect.timeout=5000
```

4. 区分连接超时、请求超时、服务发现超时。

5. 超时后返回明确异常，例如：

```text
RpcTimeoutException
```

## 面试时如何回答

> 当前项目配置了 Netty 建连超时，但 RPC 调用等待响应时使用的是 `CompletableFuture.get()`，没有设置超时时间。如果服务端一直不返回，业务线程会一直阻塞，未完成请求表也可能堆积。我的优化思路是给每次 RPC 调用增加 request timeout，使用 `get(timeout)` 或 `orTimeout()`，超时后从 `UnprocessedRequests` 移除 requestId，并抛出明确的超时异常。

## 2. 重试机制

## 当前项目现状

ZooKeeper 客户端连接使用了 Curator 的重试策略：

```java
RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME, MAX_RETRIES);
```

位置：

```text
simple.registry.Impl.util.CuratorUtils#getZkClient
```

但是 RPC 调用本身没有重试机制。

在 `NettyRpcClient#sendRpcRequest` 中，如果发送失败，只是：

```java
future.channel().close();
resultFuture.completeExceptionally(future.cause());
```

没有重新选择节点，也没有重新发送请求。

## 存在风险

- 某个服务节点短暂网络抖动时，请求直接失败。
- 连接失效后没有自动换节点重试。
- 服务发现得到多个地址时，没有失败转移能力。
- 如果盲目重试，又可能导致非幂等接口重复执行。

## 优化方案

1. 只对幂等请求默认重试。

2. 支持重试次数配置：

```text
rpc.retry.count=2
rpc.retry.interval=100
```

3. 发送失败或超时时重新服务发现，避开失败节点。

4. 记录失败节点短期黑名单，避免马上再次选中。

5. 区分异常类型：

- 连接失败：可以重试。
- 请求超时：谨慎重试。
- 服务端业务异常：不应该重试。

6. 支持注解级配置：

```java
@RpcReference(retries = 2, timeout = 3000)
```

## 面试时如何回答

> 当前项目只有 ZooKeeper 客户端连接层的重试，RPC 请求本身没有重试。如果发送失败或调用超时，当前是直接异常返回。后续我会在客户端调用层加可配置重试，并且只默认重试幂等接口。重试时重新服务发现和负载均衡，尽量避开失败节点，同时要避免对非幂等方法造成重复写入。

## 3. 熔断限流

## 当前项目现状

项目没有熔断、限流、降级机制。

客户端：

- 请求失败后直接抛异常。
- 没有统计某个服务或某个节点的失败率。

服务端：

- 没有限制单个客户端请求速率。
- 没有拒绝策略。
- 没有根据业务线程池压力做保护。

## 存在风险

- 某个服务节点异常时，客户端仍然持续请求它。
- 下游慢响应会拖垮上游业务线程。
- 突发流量可能把服务端业务处理线程打满。
- 故障可能在服务间级联扩散。

## 优化方案

1. 客户端熔断：

- 按服务名或服务地址统计失败率。
- 失败率超过阈值打开熔断。
- 熔断期间快速失败。
- 半开状态放少量请求探测恢复。

2. 服务端限流：

- 按接口限流。
- 按 IP 限流。
- 按服务维度限流。

3. 可接入成熟组件：

- Resilience4j
- Sentinel

4. 简化版实现：

- 使用滑动窗口统计 QPS 和失败率。
- 使用令牌桶或漏桶限流。

## 面试时如何回答

> 当前项目还没有熔断限流，这是和生产级 RPC 框架的重要差距。我的优化思路是客户端按服务或节点统计调用失败率，超过阈值后熔断，避免持续请求异常节点；服务端按接口或客户端 IP 做限流，避免突发流量打满业务线程。实现上可以先做简单滑动窗口，后续也可以接 Sentinel 或 Resilience4j。

## 4. 心跳机制

## 当前项目现状

客户端 Pipeline 中：

```java
p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
```

客户端写空闲时发送心跳：

```java
rpcMessage.setMessageType(RpcConstants.HEARTBEAT_REQUEST_TYPE);
rpcMessage.setData(RpcConstants.PING);
```

位置：

```text
simple.remoting.transport.netty.client.NettyRpcClientHandler#userEventTriggered
```

服务端 Pipeline 中：

```java
p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
```

服务端读空闲时关闭连接。

但是服务端处理心跳时存在问题：

```java
if (messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
    rpcMessage.setMessageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE);
    rpcMessage.setData(RpcConstants.PONG);
}
```

位置：

```text
simple.remoting.transport.netty.server.NettyRpcServerHandler#channelRead
```

客户端发送的是：

```java
HEARTBEAT_REQUEST_TYPE
```

服务端却判断：

```java
HEARTBEAT_RESPONSE_TYPE
```

## 存在风险

- 心跳请求可能被服务端当作普通 RPC 请求处理。
- `RpcMessage.data` 是 `PING` 字符串，不是 `RpcRequest`，强转可能失败。
- 心跳机制无法正常维持连接。
- 客户端没有统计连续心跳失败次数。
- 服务端只是读空闲关闭，客户端没有更完整的重连策略。

## 优化方案

1. 修复服务端判断：

```java
if (messageType == RpcConstants.HEARTBEAT_REQUEST_TYPE) {
    rpcMessage.setMessageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE);
    rpcMessage.setData(RpcConstants.PONG);
}
```

2. 客户端收到 `PONG` 后记录连接健康状态。

3. 连续 N 次心跳失败后关闭 Channel 并从 `ChannelProvider` 移除。

4. 心跳消息不走业务反射调用链。

5. 心跳间隔、最大失败次数配置化。

## 面试时如何回答

> 项目已经使用 `IdleStateHandler` 做了心跳雏形，客户端写空闲发 `PING`，服务端读空闲关闭连接。但当前服务端判断心跳类型时用了 `HEARTBEAT_RESPONSE_TYPE`，而客户端发的是 `HEARTBEAT_REQUEST_TYPE`，这里需要修复。优化后，心跳请求应该直接返回 `PONG`，并且客户端要统计连续心跳失败次数，失败后移除 Channel 并触发重连。

## 5. 服务下线和节点监听

## 当前项目现状

服务注册时使用：

```java
CuratorUtils.createPersistentNode(zkClient, servicePath);
```

位置：

```text
simple.registry.Impl.ZKServiceRegistryImpl#registerService
```

即当前注册节点是持久节点。

项目有 JVM 关闭钩子：

```java
Runtime.getRuntime().addShutdownHook(...)
```

关闭时调用：

```java
CuratorUtils.clearRegistry(...)
```

客户端服务发现时会缓存地址：

```java
SERVICE_ADDRESS_MAP.put(rpcServiceName, result);
```

并注册监听：

```java
PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, servicePath, true);
```

## 存在风险

1. 使用持久节点时，如果服务异常宕机，JVM shutdown hook 不一定执行，ZooKeeper 上可能留下脏节点。

2. 注册路径拼接存在可读性问题：

```java
ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString()
```

服务名和地址之间缺少明确的 `/` 分隔，路径结构不够清晰。

3. 客户端依赖监听更新缓存，但没有处理监听器生命周期。

4. `PathChildrenCache` 较旧，Curator 新版本更推荐 `CuratorCache`。

5. 如果节点下线但客户端已经缓存了对应 Channel，`ChannelProvider` 不会主动移除这个地址的 Channel。

## 优化方案

1. 服务实例节点使用临时节点：

```java
CreateMode.EPHEMERAL
```

ZooKeeper 会在会话断开后自动删除节点。

2. 规范路径：

```text
/my-rpc/{rpcServiceName}/{host:port}
```

3. 节点变化监听更新地址缓存时，同步清理已下线地址对应的 Channel。

4. 处理监听器关闭和重复注册问题。

5. 服务端优雅下线：

- 先从注册中心摘除节点。
- 等待一段时间让客户端感知。
- 再关闭 Netty 服务。

## 面试时如何回答

> 当前服务注册使用的是 ZooKeeper 持久节点，并依赖 JVM 关闭钩子清理服务。如果进程异常宕机，可能残留脏节点。生产级优化应该用临时节点，让 ZooKeeper 会话断开后自动删除服务实例。同时客户端监听节点变化后，不仅要更新服务地址缓存，还要清理下线节点对应的 Channel，避免继续请求不可用实例。

## 6. 连接池和 Channel 管理

## 当前项目现状

`ChannelProvider` 使用：

```java
ConcurrentHashMap<String, Channel>
```

缓存服务地址到 Channel 的映射。

获取连接时：

```java
Channel channel = channelProvider.get(inetSocketAddress);
if (channel == null) {
    channel = doConnect(inetSocketAddress);
    channelProvider.set(inetSocketAddress, channel);
}
```

## 存在风险

1. 高并发下多个线程可能同时发现 Channel 不存在，重复建连。

2. 每个地址最多缓存一个 Channel，没有真正的连接池。

3. Channel 关闭时没有统一监听并从缓存中移除。

4. 服务下线时没有联动清理 Channel。

5. 没有最大连接数、空闲回收、连接预热等能力。

## 优化方案

1. 使用地址级锁或 `CompletableFuture<Channel>` 避免重复建连。

2. Channel 创建后添加 close listener：

```java
channel.closeFuture().addListener(...)
```

关闭后自动从 `ChannelProvider` 移除。

3. 支持每个服务地址多个 Channel：

```text
Map<String, List<Channel>>
```

或按负载选择 Channel。

4. 服务下线监听触发 Channel 清理。

5. 加入连接池配置：

```text
rpc.channel.max-per-address=4
rpc.channel.idle-timeout=60s
```

## 面试时如何回答

> 当前项目做了基础 Channel 复用，一个服务地址缓存一个 active Channel，避免每次 RPC 都建连。但它还不是完整连接池，高并发下也可能重复建连。优化时我会缓存建连中的 `CompletableFuture<Channel>` 或使用地址级锁，保证同一地址只建一次连接；同时给 Channel 加 close listener，关闭后自动从缓存移除，并在服务下线时联动清理连接。

## 7. 异常处理

## 当前项目现状

客户端发送失败：

```java
resultFuture.completeExceptionally(future.cause());
```

但是未清理 `UnprocessedRequests`。

客户端 handler 异常：

```java
cause.printStackTrace();
ctx.close();
```

服务端 handler 异常：

```java
cause.printStackTrace();
ctx.close();
```

服务端业务调用异常：

```java
throw new RpcException(e.getMessage(), e);
```

但 `NettyRpcServerHandler#channelRead` 没有将业务异常封装成失败响应返回客户端。

`RpcMessageEncoder` 捕获异常后只记录日志：

```java
log.error("Encode request error!", e);
```

没有向调用方传递失败。

## 存在风险

- 客户端发送失败后未完成请求表可能残留。
- 服务端业务异常可能导致客户端超时，而不是收到明确失败响应。
- 编码失败可能被吞掉，调用方不知道失败原因。
- `printStackTrace()` 不利于统一日志管理。
- 异常类型不够细，面试官会觉得工程化不足。

## 优化方案

1. 定义清晰异常类型：

- `RpcTimeoutException`
- `RpcConnectException`
- `RpcSerializeException`
- `RpcCodecException`
- `RpcServiceException`

2. 服务端捕获业务异常，封装：

```java
RpcResponse.fail(...)
```

并带回原 requestId。

3. 客户端发送失败、超时时都清理 `UnprocessedRequests`。

4. 编解码异常通过 Netty pipeline 传播，并关闭异常连接。

5. 用日志框架记录完整上下文：

- requestId
- serviceName
- methodName
- remoteAddress

## 面试时如何回答

> 当前异常处理还比较粗，比如服务端业务异常没有统一封装成失败响应，客户端发送失败也没有清理未完成请求。优化时我会建立统一异常模型，服务端捕获异常后返回带 requestId 的失败 `RpcResponse`，客户端根据响应码抛出对应异常。同时所有超时、发送失败、编码失败都要清理资源并记录结构化日志。

## 8. 序列化安全

## 当前项目现状

项目支持：

- Hessian
- Kryo
- Protostuff

普通请求默认使用 Hessian：

```java
SerializationTypeEnum.HESSIAN.getCode()
```

Protostuff 实现中：

```java
private static final LinkedBuffer BUFFER =
        LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE);
```

`RpcMessageDecoder` 中解压时存在疑似 bug：

```java
String compressName = CompressTypeEnum.getName(codecType);
```

这里应该使用：

```java
compressType
```

## 存在风险

1. 反序列化不可信数据存在安全风险。

2. Hessian、Kryo 如果暴露给不可信网络，可能有反序列化攻击面。

3. Protostuff 静态共享 `LinkedBuffer` 有线程安全风险。

4. 解码器使用 `codecType` 查压缩器可能导致解压失败。

5. 当前协议没有 body 校验、签名、认证。

6. 当前没有限制具体允许反序列化的类白名单。

## 优化方案

1. 增加反序列化白名单，只允许 DTO 包和基础类型。

2. 修复 decoder：

```java
String compressName = CompressTypeEnum.getName(compressType);
```

3. Protostuff `LinkedBuffer` 改为局部变量或 `ThreadLocal`。

4. 协议增加认证、签名或 checksum。

5. 内网使用也要限制端口暴露范围。

6. 生产环境优先考虑更安全可控的协议，如 Protobuf，并明确 schema。

## 面试时如何回答

> 当前项目实现了多种序列化，但从安全角度还比较基础。生产环境不能直接反序列化任意来源的数据，应该加类白名单、协议认证和完整性校验。另外当前 Protostuff 的 `LinkedBuffer` 是 static 共享的，多线程下有风险；解码器里压缩类型也应该用 `compressType` 而不是 `codecType`。这些都是我会优先修的点。

## 9. 请求 ID 设计

## 当前项目现状

项目中有两个 requestId：

1. `RpcClientProxy` 在 `RpcRequest` 中生成：

```java
UUID.randomUUID().toString()
```

2. `RpcMessageEncoder` 在协议头中生成：

```java
ATOMIC_INTEGER.getAndIncrement()
```

客户端请求响应匹配使用的是 body 中的：

```java
RpcRequest.requestId
RpcResponse.requestId
```

协议头中的 int requestId 没有真正用于响应匹配。

## 存在风险

- requestId 概念重复。
- 协议头字段价值没有发挥。
- 排查日志时容易混淆两个 ID。
- `AtomicInteger` 存在溢出问题。
- UUID 字符串体积更大，增加序列化开销。

## 优化方案

1. 统一 requestId 来源。

2. 推荐使用协议头 requestId 做匹配：

```text
RpcMessage.requestId -> UnprocessedRequests key
```

3. requestId 类型可改为 long：

```java
AtomicLong
```

4. 请求体中可以去掉 requestId，减少重复字段。

5. 日志、链路追踪统一打印同一个 requestId。

## 面试时如何回答

> 当前项目有两个 requestId，一个是 body 里的 UUID，一个是协议头里的 AtomicInteger。真正用于响应匹配的是 body 里的 UUID，协议头 requestId 没有充分利用。优化时我会统一 requestId，最好放在协议头中，用 `AtomicLong` 生成，并让 `UnprocessedRequests` 直接基于协议头 requestId 匹配响应，减少重复字段和排查成本。

## 10. 线程池隔离

## 当前项目现状

服务端使用：

```java
DefaultEventExecutorGroup serviceHandlerGroup =
        new DefaultEventExecutorGroup(RuntimeUtil.cpus() * 2, ...)
```

并将业务 handler 放入该线程组：

```java
p.addLast(serviceHandlerGroup, new NettyRpcServerHandler());
```

这说明项目已经避免把反射调用直接放在 Netty worker I/O 线程中执行。

## 存在风险

1. 所有服务共用同一个 `serviceHandlerGroup`。

2. 没有按服务或接口隔离线程池。

3. 某个慢接口可能占满业务线程，影响其他接口。

4. `DefaultEventExecutorGroup` 没有显式配置队列大小和拒绝策略。

5. 没有线程池监控。

## 优化方案

1. 按服务或接口维度做线程池隔离。

2. 对核心接口配置独立线程池。

3. 增加队列长度和拒绝策略。

4. 监控线程池指标：

- activeCount
- queueSize
- completedTaskCount
- rejectCount

5. 对慢调用设置业务执行超时。

6. 服务端过载时快速失败，而不是无限堆积。

## 面试时如何回答

> 当前项目已经把业务处理器放到了 `DefaultEventExecutorGroup`，避免阻塞 Netty I/O 线程，这是一个正确方向。但它还没有做到按服务隔离，所有请求共用一个业务线程组。如果某个慢接口把线程池占满，会影响其他接口。生产级优化可以按服务或接口配置线程池、队列和拒绝策略，并暴露线程池监控指标。

## 11. 配置化能力

## 当前项目现状

项目只对 ZooKeeper 地址做了配置读取：

```java
RpcConfigEnum.RPC_CONFIG_PATH
RpcConfigEnum.ZK_ADDRESS
```

很多核心选项是硬编码的：

- 服务端端口：`NettyRpcServer.PORT = 9998`
- 注册中心：`ServiceRegistryEnum.ZK`
- 服务发现：`ServiceDiscoveryEnum.ZK`
- 传输方式：`RpcRequestTransportEnum.NETTY`
- 负载均衡：`LoadBalanceEnum.LOADBALANCENEW`
- 序列化：`SerializationTypeEnum.HESSIAN`
- 压缩：`CompressTypeEnum.GZIP`
- 心跳时间：客户端 5 秒，服务端 30 秒
- 请求超时：无

## 存在风险

- 使用方无法灵活切换组件。
- 不同环境需要改代码重新打包。
- SPI 扩展能力没有完全发挥。
- 面试官可能追问“既然有 SPI，为什么还写死实现”。

## 优化方案

1. 新增统一配置类：

```java
RpcConfig
```

2. 支持配置文件：

```text
rpc.registry=zk
rpc.discovery=zk
rpc.transport=netty
rpc.serializer=hessian
rpc.compress=gzip
rpc.loadbalance=loadBalanceNew
rpc.server.port=9998
rpc.request.timeout=3000
rpc.heartbeat.interval=5000
```

3. `@RpcReference` 支持覆盖配置：

```java
@RpcReference(timeout = 3000, retries = 2, loadBalance = "random")
```

4. 用配置值传给 `ExtensionLoader#getExtension(name)`。

## 面试时如何回答

> 项目虽然做了 SPI，但很多扩展选择仍然硬编码，比如默认 ZK、Netty、Hessian、Gzip 和一致性 Hash。后续我会加统一 `RpcConfig`，从配置文件读取注册中心、序列化、压缩、负载均衡、超时、重试等参数，再通过 `ExtensionLoader` 动态装配。这样 SPI 的扩展能力才真正完整。

## 12. 日志和监控

## 当前项目现状

项目使用了 `Slf4j` 日志。

日志内容主要包括：

- 客户端发送消息。
- 服务端接收消息。
- codec 名称。
- 服务注册发现信息。

但是目前没有完整监控指标。

异常处理里还有：

```java
cause.printStackTrace();
```

## 存在风险

1. 日志缺少统一 requestId 上下文。

2. 没有调用耗时统计。

3. 没有成功率、失败率、超时数、重试次数统计。

4. 没有连接数、活跃 Channel 数、线程池队列长度监控。

5. 大量打印完整请求对象可能泄漏敏感数据。

6. `printStackTrace()` 不利于生产日志收集。

## 优化方案

1. 所有日志带上：

- requestId
- serviceName
- methodName
- remoteAddress

2. 增加调用耗时统计：

```text
rpc_client_request_duration
rpc_server_request_duration
```

3. 增加指标：

- QPS
- RT
- P95/P99
- errorCount
- timeoutCount
- retryCount
- activeChannels
- pendingRequests

4. 接入 Micrometer + Prometheus。

5. 敏感参数脱敏。

6. 替换 `printStackTrace()` 为结构化日志。

## 面试时如何回答

> 当前项目日志能辅助调试，但还不是生产级观测。生产环境我会围绕 requestId 做链路日志，统计客户端和服务端耗时、成功率、失败率、超时数、重试数、未完成请求数、活跃连接数和线程池状态，并接入 Micrometer/Prometheus。这样出问题时能快速定位是网络、注册中心、服务端处理还是序列化异常。

## 13. 单元测试和压测

## 当前项目现状

项目中有一些 `src/test` 文件，但多为 Spring Boot 默认测试骨架，例如：

```text
RpcFrameworkSimpleApplicationTests
ExampleClientApplicationTests
ExampleServerApplicationTests
```

没有看到针对核心 RPC 组件的系统性测试，例如：

- 编解码测试。
- 序列化兼容测试。
- SPI 加载测试。
- 负载均衡测试。
- 服务注册发现测试。
- 并发请求响应匹配测试。
- 超时和异常场景测试。

也没有压测脚本或性能报告。

## 存在风险

- 协议字段改动容易破坏兼容性。
- `LengthFieldBasedFrameDecoder` 参数错误难以及时发现。
- 并发请求可能存在隐藏问题。
- 服务上下线、连接断开、超时等异常场景没有验证。
- 无法说明项目性能上限。

## 优化方案

1. 单元测试：

- `RpcMessageEncoder/RpcMessageDecoder` 编解码往返测试。
- Hessian/Kryo/Protostuff 序列化测试。
- Gzip 压缩解压测试。
- `ExtensionLoader` SPI 加载测试。
- `RandomLoadBalance` 和一致性 Hash 测试。
- `UnprocessedRequests` requestId 匹配测试。

2. 集成测试：

- 启动嵌入式 ZooKeeper。
- 启动服务端。
- 客户端调用远程接口。
- 验证结果和服务发现。

3. 异常测试：

- 服务端不可用。
- 响应超时。
- 节点下线。
- Channel 关闭。
- 序列化失败。

4. 压测：

- 使用 JMH 测序列化和负载均衡。
- 使用多线程客户端压测 QPS、RT、P99。
- 对比不同序列化方式。
- 观察线程池、连接数、pending requests。

## 面试时如何回答

> 当前测试还不够系统，主要是默认测试骨架。后续我会补核心组件单测，比如编解码、SPI、序列化、负载均衡、未完成请求表；再补集成测试，启动注册中心、服务端和客户端验证完整调用链；最后做压测，统计 QPS、平均耗时、P99 和不同序列化方式的性能差异。这样项目可信度会更高。

## 其他真实代码问题补充

## Holder.set 赋值 bug

当前：

```java
public void set(T instance) {
    this.value = value;
}
```

应该：

```java
this.value = instance;
```

影响：

- `ExtensionLoader` 扩展缓存失效。
- `SingletonFactory` 单例缓存失效。
- 可能导致重复创建对象或空指针。

面试回答：

> 这是我复盘时发现的明确代码 bug，属于优先级最高的修复项之一。

## 服务端心跳判断 bug

当前服务端判断：

```java
messageType == HEARTBEAT_RESPONSE_TYPE
```

但客户端发送：

```java
HEARTBEAT_REQUEST_TYPE
```

应该修复为服务端收到 `HEARTBEAT_REQUEST_TYPE` 后返回 `HEARTBEAT_RESPONSE_TYPE`。

## RpcMessageDecoder 解压字段 bug

当前：

```java
String compressName = CompressTypeEnum.getName(codecType);
```

应该：

```java
String compressName = CompressTypeEnum.getName(compressType);
```

否则用序列化类型编码去查压缩类型，可能导致解压失败。

## 服务注册路径不规范

当前：

```java
ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString()
```

建议：

```text
/my-rpc/{rpcServiceName}/{host:port}
```

路径层级更清晰，客户端获取子节点也更稳定。

## 优先级排序

如果面试官问“你会先改哪些”，可以按这个顺序答：

| 优先级 | 优化项 | 原因 |
| --- | --- | --- |
| P0 | 修复 `Holder.set()` | 影响 SPI 和单例缓存正确性 |
| P0 | 修复心跳类型判断 | 影响连接保活 |
| P0 | 修复 decoder 压缩字段 | 影响协议解码 |
| P1 | 增加 RPC 调用超时和资源清理 | 防止线程阻塞和内存泄漏 |
| P1 | 服务注册改临时节点 | 防止服务宕机后脏节点 |
| P1 | 服务端异常封装失败响应 | 提升调用方可感知性 |
| P2 | Channel 建连去重和关闭清理 | 提升连接管理稳定性 |
| P2 | 配置化 SPI 组件选择 | 提升框架易用性 |
| P2 | 监控指标和压测 | 提升可观测性和可信度 |
| P3 | 熔断、限流、重试策略 | 向生产级治理演进 |

## 如果面试官问项目有什么不足，我该怎么答

> 这个项目我会定位成一个学习型轻量 RPC 框架，它的主链路是完整的，包括代理调用、Netty 通信、自定义协议、服务注册发现、序列化压缩、负载均衡和 SPI 扩展。但如果按生产级 RPC 框架来看，还有不少优化空间。第一是调用治理能力不足，目前 RPC 响应没有超时控制，也没有请求级重试、熔断和限流；第二是连接和服务治理还不完善，比如 Channel 复用比较基础，服务注册当前使用持久节点，异常宕机可能有脏节点；第三是异常处理和可观测性不足，服务端业务异常没有统一失败响应，日志和监控指标也不够完整；第四是安全和稳定性方面还需要加强，比如反序列化白名单、协议校验、请求 ID 统一。除此之外，我复盘代码时也发现了一些明确问题，比如 `Holder.set()` 赋值错误、服务端心跳类型判断不一致、Decoder 解压时使用了错误字段。我的优化顺序会先修正确性问题，再补超时和资源清理，然后完善服务下线、连接管理、配置化、监控和压测，最后再做熔断限流等服务治理能力。
