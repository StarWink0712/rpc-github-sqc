# 13 Final Review Guide

## 复习顺序

面试前 30 分钟按这个顺序看：

1. `必须背熟`：项目一句话介绍、1 分钟介绍、核心调用链、10 个技术亮点、项目不足话术。
2. `必须背熟`：20 个高频问题里的第 1、2、3、4、5、6、7、8、10、15、20 题。
3. `理解即可`：Netty、ZooKeeper、SPI、协议、并发各自的实现细节。
4. `可能追问`：项目 bug、优化方向、为什么不用其他方案。

## 项目一句话介绍

`必须背熟`

这是一个基于 Netty 和 ZooKeeper 实现的轻量级 Java RPC 框架，通过 Spring 自定义注解和 JDK 动态代理让客户端像调用本地接口一样调用远程服务，底层实现了服务注册发现、自定义协议、序列化压缩、负载均衡、SPI 扩展和请求响应匹配。

## 1 分钟介绍

`必须背熟`

> 我做的是一个基于 Netty 和 ZooKeeper 的轻量级 RPC 框架，主要目标是理解并实现 Dubbo 这类 RPC 框架的核心原理。服务端通过 `@RpcService` 暴露服务，启动时会把服务名和地址注册到 ZooKeeper；客户端通过 `@RpcReference` 注入远程服务代理对象，调用接口方法时实际进入 JDK 动态代理的 `invoke()` 方法。代理对象会封装 `RpcRequest`，通过 ZooKeeper 服务发现和负载均衡找到服务端地址，再用 Netty 发送请求。网络层使用自定义协议，协议头包含魔数、版本、消息长度、消息类型、序列化方式和压缩方式，并通过 `LengthFieldBasedFrameDecoder` 解决粘包半包。服务端解码请求后，根据服务名从本地缓存找到服务对象，通过反射调用目标方法，再封装 `RpcResponse` 返回。客户端通过 requestId 和 `CompletableFuture` 匹配响应，最终把远程结果返回给业务代码。

## 3 分钟介绍

`必须背熟`

> 这个项目是一个轻量级 Java RPC 框架，核心是让分布式场景下的远程方法调用像本地接口调用一样简单。项目是 Maven 多模块结构，主要包括公共模块 `rpc-framework-common`、核心框架模块 `rpc-framework-simple`、公共 API 模块 `hello-service-api`，以及客户端和服务端示例模块。
>
> 在易用性上，我通过 Spring 自定义注解和 `BeanPostProcessor` 接入框架。服务提供方只需要在实现类上加 `@RpcService`，框架会在 Bean 初始化前读取 group 和 version，将服务对象放入本地 `serviceMap`，并注册到 ZooKeeper。服务消费方只需要在接口字段上加 `@RpcReference`，框架会在 Bean 初始化后为该字段创建 JDK 动态代理对象并注入。
>
> 在调用链上，客户端调用接口方法时会进入 `RpcClientProxy.invoke()`，代理对象把接口名、方法名、参数、参数类型、requestId、group、version 封装成 `RpcRequest`。然后 `NettyRpcClient` 通过 ZooKeeper 服务发现获取服务地址列表，再经过负载均衡选择一个目标地址，并通过 `ChannelProvider` 获取或复用 Netty Channel。发送请求前，客户端把 requestId 和 `CompletableFuture` 放入 `UnprocessedRequests`，用于响应回来后匹配。
>
> 在网络通信上，我使用 Netty 实现客户端和服务端。服务端有 bossGroup、workerGroup，并额外使用 `DefaultEventExecutorGroup` 处理业务请求，避免反射调用阻塞 I/O 线程。协议层自定义了 `RpcMessage`，协议头包含 magic number、version、full length、message type、codec、compress、requestId，body 存放序列化压缩后的 `RpcRequest` 或 `RpcResponse`。解码器继承 `LengthFieldBasedFrameDecoder`，通过 full length 字段解决 TCP 粘包半包。
>
> 在扩展性上，项目实现了自定义 SPI，使用 `@SPI` 和 `ExtensionLoader` 从 `META-INF/接口全限定名` 配置文件中按名称加载实现类。注册中心、服务发现、传输层、序列化、压缩、负载均衡都可以扩展。当前实现了 ZooKeeper 注册发现、Netty 传输、Hessian/Kryo/Protostuff 序列化、Gzip 压缩，以及随机和一致性 Hash 负载均衡。
>
> 如果按生产级 RPC 框架要求看，项目还需要补超时、重试、熔断限流、服务优雅下线、连接池治理、监控和压测等能力。我也复盘出了一些真实代码问题，比如心跳类型判断、Decoder 压缩字段、`Holder.set()` 缓存赋值问题，这些可以作为后续优化方向。

## 核心调用链

`必须背熟`

1. 客户端启动，`SpringBeanPostProcessor` 扫描 `@RpcReference` 字段。
2. 框架读取 group/version，创建 `RpcClientProxy`。
3. `RpcClientProxy#getProxy()` 使用 JDK 动态代理生成接口代理对象。
4. 业务代码调用 `helloService.hello()`，实际进入 `RpcClientProxy#invoke()`。
5. `invoke()` 封装 `RpcRequest`：接口名、方法名、参数、参数类型、requestId、group、version。
6. `NettyRpcClient#sendRpcRequest()` 发起远程调用。
7. `ZkServiceDiscoveryImpl#lookupService()` 根据服务名从 ZooKeeper 获取地址列表。
8. `LoadBalance#selectServiceAddress()` 选择一个服务地址。
9. `ChannelProvider#get()` 获取可复用 Channel；没有则 `doConnect()` 新建连接。
10. 客户端创建 `CompletableFuture`，放入 `UnprocessedRequests`，key 是 requestId。
11. `RpcMessageEncoder` 写协议头，序列化并压缩 body。
12. Netty `writeAndFlush()` 发送请求。
13. 服务端 `RpcMessageDecoder` 根据 full length 拆包，校验魔数和版本，解压反序列化。
14. `NettyRpcServerHandler#channelRead()` 取出 `RpcRequest`。
15. `RpcRequestHandler#handle()` 根据服务名从 `serviceMap` 找本地服务对象。
16. 服务端通过反射调用目标方法。
17. 服务端封装 `RpcResponse`，保留原 requestId。
18. 服务端编码响应并通过 Netty 写回客户端。
19. 客户端 `NettyRpcClientHandler#channelRead()` 收到响应。
20. `UnprocessedRequests#complete()` 根据 requestId 找到 future 并 complete。
21. 业务线程从 `future.get()` 返回，代理对象校验响应后返回 data。

一句话版：

> `@RpcReference` 注入代理对象，代理拦截方法调用并封装 `RpcRequest`，客户端服务发现和负载均衡后通过 Netty 发送请求，服务端解码后从本地缓存找服务并反射调用，再将 `RpcResponse` 返回，客户端通过 requestId 和 `CompletableFuture` 匹配响应。

## 10 个必背技术亮点

## 1. 注解式远程调用

`必须背熟`

- 服务端：`@RpcService` 标记服务实现。
- 客户端：`@RpcReference` 注入远程代理。
- 扫描：`@RpcScan` 触发自定义扫描。
- 核心：`SpringBeanPostProcessor` 在 Bean 生命周期中完成服务注册和代理注入。

面试说法：

> 业务只需要写注解，框架在 Spring 生命周期里自动完成服务发布和代理注入。

## 2. JDK 动态代理

`必须背熟`

- 类：`RpcClientProxy`
- 方法：`getProxy()`、`invoke()`
- 作用：拦截接口调用，封装请求并发送 RPC。

可能追问：

> JDK 动态代理只能代理接口，如果要代理类可以考虑 CGLIB。

## 3. Netty 通信

`必须背熟`

- 服务端：`ServerBootstrap`、bossGroup、workerGroup。
- 客户端：`Bootstrap`、`NioSocketChannel`。
- 业务隔离：`DefaultEventExecutorGroup` 执行服务端业务 handler。
- 连接复用：`ChannelProvider` 缓存服务地址到 Channel。

可能追问：

> 为什么不用 BIO？BIO 一个连接一个线程，并发成本高；Netty 基于 NIO 和事件驱动，更适合 RPC 长连接通信。

## 4. 自定义协议

`必须背熟`

协议头 16 字节：

```text
magic number + version + full length + message type + codec + compress + requestId
```

body：

```text
序列化并压缩后的 RpcRequest 或 RpcResponse
```

可能追问：

> full length 用于定义消息边界，codec/compress 用于扩展序列化和压缩方式。

## 5. 粘包半包处理

`必须背熟`

`RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`：

```java
this(MAX_FRAME_LENGTH, 5, 4, -9, 0);
```

解释：

- `5`：长度字段前有 4B 魔数 + 1B 版本。
- `4`：full length 是 int。
- `-9`：full length 包含前面 9 字节。
- `0`：不剥离协议头，后续自己解析。

## 6. ZooKeeper 服务注册发现

`必须背熟`

- 服务注册：`ZKServiceRegistryImpl#registerService()`
- 服务发现：`ZkServiceDiscoveryImpl#lookupService()`
- 工具类：`CuratorUtils`
- 服务名：接口名 + group + version。

可能追问：

> group/version 用于区分同一接口的不同分组或版本，避免服务名冲突。

## 7. SPI 扩展机制

`必须背熟`

- 注解：`@SPI`
- 加载器：`ExtensionLoader`
- 配置：`META-INF/接口全限定名`
- 格式：`name=实现类全限定名`

扩展点：

> 注册中心、服务发现、网络传输、序列化、压缩、负载均衡。

## 8. 序列化和压缩

`理解即可`

- 序列化：Hessian、Kryo、Protostuff。
- 压缩：Gzip。
- 选择方式：协议头 codec/compress + SPI。

可能追问：

> Kryo 不是线程安全的，所以项目里用 `ThreadLocal<Kryo>`。

## 9. 负载均衡

`理解即可`

- 接口：`LoadBalance`
- 模板父类：`AbstractLoadBalance`
- 实现：随机、一致性 Hash。
- 默认使用：`loadBalanceNew`

一致性 Hash 意义：

> 节点上下线时减少请求大面积迁移，适合需要路由稳定或本地缓存命中的场景。

## 10. CompletableFuture 响应匹配

`必须背熟`

- 客户端发送请求前创建 `CompletableFuture`。
- `UnprocessedRequests` 保存 `requestId -> future`。
- 响应回来后按 requestId complete future。
- 支持并发请求和乱序响应。

一句话：

> 底层 Netty 异步收发，上层通过 `CompletableFuture.get()` 保持同步调用体验。

## 20 个最高频面试问题和答案

## 1. 这个项目是什么？

`必须背熟`

答：

> 这是一个基于 Netty 和 ZooKeeper 的轻量级 Java RPC 框架，目标是让客户端像调用本地接口一样调用远程服务。它实现了服务注册发现、动态代理、自定义协议、Netty 通信、序列化压缩、负载均衡、SPI 扩展和请求响应匹配。

## 2. 为什么要做 RPC 框架？

`必须背熟`

答：

> 分布式系统中服务部署在不同进程或机器上，不能直接调用实现类。RPC 框架要解决远程方法调用问题，让调用方只依赖接口，底层自动完成寻址、网络通信、序列化和结果返回。

## 3. 一次 RPC 调用完整流程是什么？

`必须背熟`

答：

> 客户端通过 `@RpcReference` 注入代理对象，调用接口方法时进入 `RpcClientProxy.invoke()`，封装 `RpcRequest`。客户端通过 ZooKeeper 发现服务地址，负载均衡选节点，复用或创建 Netty Channel，编码后发送请求。服务端解码后从本地 `serviceMap` 找服务对象，反射调用目标方法，封装 `RpcResponse` 返回。客户端根据 requestId 找到 `CompletableFuture` 并返回结果。

## 4. `@RpcReference` 是怎么生效的？

`必须背熟`

答：

> `SpringBeanPostProcessor#postProcessAfterInitialization()` 会扫描 Bean 字段，发现 `@RpcReference` 后读取 group/version，创建 `RpcClientProxy`，再通过 JDK 动态代理生成接口代理对象，最后反射注入字段。

## 5. `@RpcService` 是怎么注册服务的？

`必须背熟`

答：

> `SpringBeanPostProcessor#postProcessBeforeInitialization()` 发现 Bean 类上有 `@RpcService`，会读取 group/version，封装成 `RpcServiceConfig`，调用 `ZKServiceProviderImpl#publishService()`。它先把服务放入本地 `serviceMap`，再通过 `ZKServiceRegistryImpl` 注册到 ZooKeeper。

## 6. 为什么用 JDK 动态代理？

`必须背熟`

答：

> 客户端依赖的是服务接口，JDK 动态代理正适合代理接口。它能拦截接口方法调用，把本地调用转成远程请求。限制是只能代理接口，不能直接代理普通类；如果要代理类可以考虑 CGLIB。

## 7. Netty 服务端线程模型怎么设计？

`必须背熟`

答：

> 服务端使用 bossGroup 接收连接，workerGroup 处理网络 I/O 和编解码，另外使用 `DefaultEventExecutorGroup` 执行业务 handler。这样反射调用和业务处理不会阻塞 Netty I/O 线程。

## 8. 自定义协议头有哪些字段？

`必须背熟`

答：

> 4 字节 magic number、1 字节 version、4 字节 full length、1 字节 message type、1 字节 codec、1 字节 compress、4 字节 requestId，后面是 body。body 存放序列化压缩后的 `RpcRequest` 或 `RpcResponse`。

## 9. 如何解决 TCP 粘包半包？

`必须背熟`

答：

> 协议头中有 full length 字段，`RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`，根据长度字段判断是否收到完整消息。半包会等待更多数据，粘包会按长度拆成多个完整帧。

## 10. `LengthFieldBasedFrameDecoder(MAX, 5, 4, -9, 0)` 怎么解释？

`必须背熟`

答：

> `5` 是长度字段偏移，因为前面有 4 字节魔数和 1 字节版本；`4` 是长度字段长度；`-9` 是因为 full length 包含 magic、version 和 full length 这 9 字节；`0` 表示不剥离协议头，后续自己校验和解析。

## 11. ZooKeeper 在项目中做什么？

`必须背熟`

答：

> ZooKeeper 作为注册中心。服务端启动时把服务名和地址注册进去；客户端调用前根据服务名查询可用服务地址列表，再通过负载均衡选择一个节点。

## 12. 服务名怎么生成？group/version 有什么用？

`必须背熟`

答：

> 服务名由接口全限定名 + group + version 组成。group 用于区分不同分组，version 用于区分同一接口的不同版本，避免服务冲突，也方便灰度和版本演进。

## 13. SPI 是怎么实现的？

`必须背熟`

答：

> 扩展接口标注 `@SPI`，`ExtensionLoader` 根据接口全限定名去 `META-INF/` 下读取配置文件，配置格式是 `name=实现类全限定名`。运行时调用 `getExtension(name)`，通过反射和单例工厂创建实现并缓存。

## 14. 自定义 SPI 和 Java 原生 SPI 有什么区别？

`理解即可`

答：

> Java 原生 SPI 主要通过 `ServiceLoader` 加载接口所有实现；项目自定义 SPI 支持 `name=实现类`，可以按名称精确获取，比如 `hessian`、`gzip`、`loadBalanceNew`，更适合框架按配置选择组件。

## 15. 请求和响应如何匹配？

`必须背熟`

答：

> 客户端每次请求生成 requestId，发送前把 `requestId -> CompletableFuture` 放入 `UnprocessedRequests`。服务端响应时带回同一个 requestId。客户端收到响应后按 requestId remove 出 future 并 complete，业务线程从 `future.get()` 返回。

## 16. 为什么用 CompletableFuture？

`必须背熟`

答：

> Netty 网络 I/O 是异步的，业务调用希望同步拿结果。`CompletableFuture` 用来跨线程传递结果：业务线程等待 future，Netty I/O 线程收到响应后 complete future，实现异步转同步。

## 17. ChannelProvider 有什么作用？

`理解即可`

答：

> 它用 `ConcurrentHashMap` 缓存服务地址到 Channel 的映射。如果已有 active Channel 就复用，否则创建新连接，避免每次 RPC 调用都建立 TCP 连接。

## 18. 一致性 Hash 负载均衡有什么意义？

`理解即可`

答：

> 一致性 Hash 的价值是路由稳定。节点上下线时只影响哈希环上一小段请求，避免普通取模 Hash 大面积重映射。RPC 场景下，如果服务端有本地缓存或希望同类请求落到同一节点，就比较有用。

## 19. 项目有哪些真实不足？

`必须背熟`

答：

> 当前没有 RPC 响应超时、请求级重试、熔断限流；服务注册使用持久节点，异常宕机可能有脏节点；Channel 管理比较基础，高并发下可能重复建连；异常处理不够统一；监控和压测不足。另外还有几个真实代码问题，比如 `Holder.set()` 赋值错误、服务端心跳类型判断不一致、Decoder 解压字段使用错误。

## 20. 如果继续优化，你会怎么做？

`必须背熟`

答：

> 我会先修正确性问题，比如 `Holder.set()`、心跳类型、Decoder 解压字段；然后补 RPC 调用超时和未完成请求清理；再把服务注册改成临时节点并完善下线监听；之后优化 Channel 建连去重和关闭清理；最后做配置化、监控、单元测试、压测，以及重试、熔断限流等服务治理能力。

## 项目不足与优化话术

`必须背熟`

> 这个项目我会定位成学习型轻量 RPC 框架，主链路是完整的，包括代理调用、Netty 通信、自定义协议、服务注册发现、序列化压缩、负载均衡和 SPI 扩展。但如果按生产级 RPC 框架来看，还有不少优化空间。第一是调用治理能力不足，目前 RPC 响应没有超时控制，也没有请求级重试、熔断和限流；第二是连接和服务治理还不完善，比如 Channel 复用比较基础，服务注册当前使用持久节点，异常宕机可能有脏节点；第三是异常处理和可观测性不足，服务端业务异常没有统一失败响应，日志和监控指标也不够完整；第四是安全和稳定性方面还需要加强，比如反序列化白名单、协议校验、请求 ID 统一。除此之外，我复盘代码时也发现了一些明确问题，比如 `Holder.set()` 赋值错误、服务端心跳类型判断不一致、Decoder 解压时使用了错误字段。我的优化顺序会先修正确性问题，再补超时和资源清理，然后完善服务下线、连接管理、配置化、监控和压测，最后再做熔断限流等服务治理能力。

## 真实代码问题速记

`可能追问`

| 问题 | 当前现状 | 优化 |
| --- | --- | --- |
| RPC 调用无超时 | `resultFuture.get()` 无限等待 | `get(timeout)`，超时清理 requestId |
| 发送失败未清理请求 | `completeExceptionally()` 后未 remove | 失败时移除 `UnprocessedRequests` |
| 服务注册持久节点 | `createPersistentNode()` | 改临时节点，异常宕机自动下线 |
| 服务路径不规范 | 服务名和地址直接拼接 | `/my-rpc/{serviceName}/{host:port}` |
| 心跳判断错误 | 服务端判断 `HEARTBEAT_RESPONSE_TYPE` | 收到 request 返回 response |
| Decoder 解压字段错误 | 用 `codecType` 查压缩器 | 改为 `compressType` |
| requestId 重复设计 | header int + body UUID | 统一 requestId |
| Protostuff 线程安全 | static `LinkedBuffer` | 局部变量或 ThreadLocal |
| Holder 缓存 bug | `this.value = value` | `this.value = instance` |
| Channel 重复建连 | 并发下多个线程同时 connect | 地址级锁或缓存 `Future<Channel>` |

## 简历项目描述推荐版本

`必须背熟`

推荐使用“面试突出亮点版”，但不要写生产级能力。

```text
基于 Netty 和 ZooKeeper 的轻量级 RPC 框架
技术栈：Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、BeanPostProcessor、自定义 SPI、Hessian/Kryo/Protostuff、Gzip、CompletableFuture

项目简介：参考 Dubbo 基础思想实现轻量级 RPC 框架，支持注解式服务暴露与引用、服务注册发现、动态代理调用、自定义协议、编解码、序列化压缩、负载均衡和 SPI 扩展，使客户端能够像调用本地接口一样调用远程服务。

个人职责：
1. 设计 RPC 调用主链路，完成客户端代理封装请求、ZooKeeper 服务发现、Netty 发送请求、服务端反射调用和响应返回。
2. 基于 @RpcService、@RpcReference 和 Spring BeanPostProcessor 实现服务自动注册和代理对象自动注入。
3. 基于 JDK 动态代理实现 RpcClientProxy，拦截接口方法调用并构造 RpcRequest。
4. 基于 Netty 实现客户端和服务端通信，使用 DefaultEventExecutorGroup 隔离服务端业务处理。
5. 设计自定义 RPC 协议，实现 RpcMessageEncoder/RpcMessageDecoder，并使用 LengthFieldBasedFrameDecoder 解决粘包半包。
6. 基于 ZooKeeper/Curator 实现服务注册发现，结合随机和一致性 Hash 负载均衡选择服务实例。
7. 实现自定义 SPI 扩展机制，支持序列化、压缩、负载均衡、注册中心和传输层扩展。
8. 使用 CompletableFuture 和 requestId 管理未完成请求，实现并发请求下的响应匹配。
```

## 最后 5 分钟只看这个

`必须背熟`

1. 项目定位：轻量级 RPC 框架，让客户端像调用本地接口一样调用远程服务。
2. 主链路：代理封装请求 -> ZK 服务发现 -> 负载均衡 -> Netty 发送 -> 服务端反射调用 -> requestId 匹配响应。
3. 五个核心亮点：Netty、自定义协议、ZooKeeper、Spring + JDK 动态代理、SPI。
4. 协议字段：magic、version、full length、message type、codec、compress、requestId、body。
5. 并发匹配：`requestId -> CompletableFuture`，响应回来 complete。
6. 不足回答：无超时/重试/熔断限流，持久节点，Channel 管理基础，异常和监控不足，真实 bug 已复盘。

## 不要这样说

`必须背熟`

不要说：

- 我实现了生产级 RPC 框架。
- 我实现了完善熔断限流。
- 我实现了完整连接池。
- 我实现了完善监控告警。
- 服务下线已经完全可靠。

可以说：

> 这些是我后续规划的优化方向，当前项目主要完整实现 RPC 核心链路和关键原理。
