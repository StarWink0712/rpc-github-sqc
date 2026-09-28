# 03 Technical Highlights

## 使用建议

面试讲项目亮点时，不要一次把所有技术都堆出来。更好的表达方式是：

1. 先讲用户体验：客户端像调用本地接口一样调用远程服务。
2. 再讲框架链路：代理封装请求、注册中心发现服务、Netty 发送请求、服务端反射执行。
3. 最后挑 3 到 5 个亮点深入讲：Netty、自定义协议、ZooKeeper、动态代理/Spring 集成、SPI、并发请求匹配。

下面每个亮点都按面试可用结构整理：

- 解决了什么问题
- 项目中如何实现
- 涉及哪些类和方法
- 面试时怎么讲
- 面试官可能怎么追问

## 亮点 1：基于 Netty 实现高性能网络通信

### 解决了什么问题

RPC 框架的核心是跨进程网络通信。如果直接使用 BIO，一个连接通常要占用一个线程，在并发连接较多时线程开销大、扩展性差。原生 NIO 虽然性能更好，但 API 复杂，容易出错。

Netty 封装了 NIO 的复杂细节，提供了成熟的 Reactor 线程模型、ChannelPipeline、编解码器、异步事件机制，适合 RPC 这种高并发网络通信场景。

### 项目中如何实现

服务端：

- `NettyRpcServer.start()` 使用 `ServerBootstrap` 启动服务。
- 使用 `bossGroup` 接收客户端连接。
- 使用 `workerGroup` 处理网络 IO。
- 使用 `DefaultEventExecutorGroup` 执行业务请求处理，避免反射调用阻塞 Netty IO 线程。
- Pipeline 中加入 `IdleStateHandler`、`RpcMessageEncoder`、`RpcMessageDecoder`、`NettyRpcServerHandler`。

客户端：

- `NettyRpcClient` 使用 `Bootstrap` 和 `NioSocketChannel` 建立连接。
- Pipeline 中加入 `IdleStateHandler`、`RpcMessageDecoder`、`RpcMessageEncoder`、`NettyRpcClientHandler`。
- 使用 `ChannelProvider` 复用 Channel，避免每次 RPC 调用都重新建连。

### 涉及类和方法

- `simple.remoting.transport.netty.server.NettyRpcServer.start()`
- `simple.remoting.transport.netty.server.NettyRpcServerHandler.channelRead()`
- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.remoting.transport.netty.client.NettyRpcClient.doConnect()`
- `simple.remoting.transport.netty.client.NettyRpcClient.getChannel()`
- `simple.remoting.transport.netty.client.NettyRpcClientHandler.channelRead()`
- `simple.remoting.transport.netty.client.ChannelProvider.get()`
- `simple.remoting.transport.netty.client.ChannelProvider.set()`

### 面试时怎么讲

> 这个项目底层网络通信用的是 Netty。服务端通过 `ServerBootstrap` 启动，bossGroup 负责接收连接，workerGroup 负责 IO 读写，另外我还用了 `DefaultEventExecutorGroup` 来处理 RPC 业务逻辑，避免反射调用阻塞 Netty 的 IO 线程。客户端通过 `Bootstrap` 建立连接，并用 `ChannelProvider` 缓存服务地址到 Channel 的映射，实现连接复用。这样一次 RPC 调用不需要每次都创建 TCP 连接，性能和资源利用会更好。

### 面试官可能追问

- Netty 的 bossGroup 和 workerGroup 分别负责什么？
- 为什么服务端要额外使用 `DefaultEventExecutorGroup`？
- ChannelPipeline 中各个 Handler 的执行顺序是什么？
- Netty 相比 BIO 和原生 NIO 有什么优势？
- 客户端 Channel 复用有什么风险？Channel 失效后怎么处理？

## 亮点 2：设计自定义 RPC 通信协议

### 解决了什么问题

客户端和服务端通过 TCP 传输的是字节流，而不是 Java 对象。RPC 框架需要定义一套双方都能理解的协议，明确消息边界、消息类型、序列化方式、压缩方式等信息。

如果没有协议设计，服务端无法知道收到的数据属于请求还是响应，也不知道如何反序列化消息体。

### 项目中如何实现

项目定义了 `RpcMessage` 作为网络传输消息，协议头包含：

- magic number：协议魔数，用于快速判断是否为当前 RPC 协议。
- version：协议版本，用于后续兼容升级。
- full length：完整消息长度，用于拆包。
- message type：消息类型，区分请求、响应、心跳请求、心跳响应。
- codec：序列化类型。
- compress：压缩类型。
- requestId：协议头请求编号。
- body：消息体，存放 `RpcRequest` 或 `RpcResponse`。

编码时，`RpcMessageEncoder` 将 `RpcMessage` 转成字节流；解码时，`RpcMessageDecoder` 将字节流还原成 `RpcMessage`。

### 涉及类和方法

- `simple.remoting.dto.RpcMessage`
- `simple.remoting.dto.RpcRequest`
- `simple.remoting.dto.RpcResponse`
- `simple.remoting.constants.RpcConstants`
- `simple.remoting.transport.netty.codec.RpcMessageEncoder.encode()`
- `simple.remoting.transport.netty.codec.RpcMessageDecoder.decode()`
- `simple.remoting.transport.netty.codec.RpcMessageDecoder.decodeFrame()`

### 面试时怎么讲

> 我在项目里没有直接传 Java 对象，而是定义了一个简单的二进制 RPC 协议。协议头里有魔数、版本号、消息长度、消息类型、序列化方式、压缩方式和请求编号。魔数用于识别协议，版本号方便后续兼容，长度字段用于解决 TCP 粘包半包，消息类型用于区分请求、响应和心跳，codec 和 compress 则决定 body 如何序列化和压缩。真正的业务数据放在 body 中，可以是 `RpcRequest` 或 `RpcResponse`。

### 面试官可能追问

- 魔数有什么作用？
- 协议里为什么要有版本号？
- full length 字段如何解决消息边界问题？
- `RpcMessage.requestId` 和 `RpcRequest.requestId` 有什么区别？
- 协议设计还有哪些可以优化的地方？

## 亮点 3：使用 LengthFieldBasedFrameDecoder 解决粘包半包

### 解决了什么问题

TCP 是面向字节流的协议，不保证一次发送对应一次接收。客户端连续发送多个 RPC 请求时，服务端可能一次读到多个包，也可能只读到半个包。

这就是粘包和半包问题。如果不处理，服务端就无法正确还原一个完整的 RPC 消息。

### 项目中如何实现

项目在协议头中设计了 `full length` 字段，表示整条消息的长度。`RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`，通过长度字段自动切出完整消息帧。

构造参数：

```java
this(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
```

含义：

- `maxFrameLength`：最大帧长度，项目中是 `8 * 1024 * 1024`。
- `lengthFieldOffset = 5`：长度字段偏移量，跳过 4 字节魔数和 1 字节版本号。
- `lengthFieldLength = 4`：长度字段占 4 字节。
- `lengthAdjustment = -9`：full length 包含整个消息长度，而解码器读到长度字段时已经经过前 9 字节。
- `initialBytesToStrip = 0`：不丢弃协议头，后续手动校验魔数和版本。

### 涉及类和方法

- `simple.remoting.transport.netty.codec.RpcMessageDecoder`
- `RpcMessageDecoder.decode()`
- `RpcMessageDecoder.decodeFrame()`
- `io.netty.handler.codec.LengthFieldBasedFrameDecoder`
- `simple.remoting.constants.RpcConstants.MAX_FRAME_LENGTH`
- `simple.remoting.constants.RpcConstants.HEAD_LENGTH`

### 面试时怎么讲

> TCP 是流式协议，会有粘包半包问题，所以我在自定义协议中加入了 full length 字段，并让解码器继承 Netty 的 `LengthFieldBasedFrameDecoder`。它会根据协议头中的长度字段判断当前 ByteBuf 是否已经包含一个完整消息。如果数据不完整，就继续等待；如果完整，就切出一帧交给后续解码逻辑。这样服务端和客户端都能稳定地按消息边界解析 `RpcMessage`。

### 面试官可能追问

- TCP 为什么会有粘包半包？
- `LengthFieldBasedFrameDecoder` 的几个参数分别是什么意思？
- 为什么 `lengthFieldOffset` 是 5？
- 为什么 `lengthAdjustment` 是 -9？
- 除了长度字段，还有哪些解决粘包半包的方式？

## 亮点 4：基于 ZooKeeper/Curator 实现服务注册与发现

### 解决了什么问题

RPC 调用方不能把服务端 IP 和端口写死在代码里。服务端可能有多个实例，也可能上下线。客户端需要动态发现可用服务地址。

注册中心解决的就是服务地址管理问题：

- 服务端启动时注册自己。
- 客户端调用前查询服务地址。
- 服务节点变化时，客户端缓存能更新。

### 项目中如何实现

服务注册：

1. 服务实现类标注 `@RpcService`。
2. `SpringBeanPostProcessor.postProcessBeforeInitialization()` 识别服务。
3. `ZKServiceProviderImpl.publishService()` 先把服务对象放入本地 `serviceMap`。
4. `ZKServiceRegistryImpl.registerService()` 调用 `CuratorUtils.createPersistentNode()` 在 ZooKeeper 创建服务节点。

服务发现：

1. 客户端代理构造 `RpcRequest`。
2. `ZkServiceDiscoveryImpl.lookupService()` 根据 `rpcRequest.getRpcServiceName()` 获取服务名。
3. `CuratorUtils.getChildrenNodes()` 从 ZooKeeper 获取服务地址列表。
4. 使用负载均衡选择一个目标地址。
5. 返回 `InetSocketAddress` 给 Netty 客户端。

客户端还通过 `SERVICE_ADDRESS_MAP` 缓存服务地址，并通过 `PathChildrenCache` 监听节点变化，更新本地缓存。

### 涉及类和方法

- `simple.annotation.RpcService`
- `simple.spring.SpringBeanPostProcessor.postProcessBeforeInitialization()`
- `simple.provider.Impl.ZKServiceProviderImpl.publishService()`
- `simple.provider.Impl.ZKServiceProviderImpl.addService()`
- `simple.registry.Impl.ZKServiceRegistryImpl.registerService()`
- `simple.registry.Impl.ZkServiceDiscoveryImpl.lookupService()`
- `simple.registry.Impl.util.CuratorUtils.getZkClient()`
- `simple.registry.Impl.util.CuratorUtils.getChildrenNodes()`
- `simple.registry.Impl.util.CuratorUtils.registerWatcher()`

### 面试时怎么讲

> 我用 ZooKeeper 作为注册中心。服务端启动时，框架会扫描 `@RpcService`，把服务对象先放到本地缓存，再把服务名和服务地址注册到 ZooKeeper。客户端调用前，根据 `RpcRequest` 中的接口名、group 和 version 生成服务名，到 ZooKeeper 查询可用地址列表，然后通过负载均衡选出一个地址。为了减少频繁访问 ZooKeeper，我还做了本地地址缓存，并通过 Curator 的 `PathChildrenCache` 监听节点变化来刷新缓存。

### 面试官可能追问

- 服务名为什么要由接口名、group、version 组成？
- 服务端为什么要先放入本地缓存，再注册到 ZooKeeper？
- Curator 相比原生 ZooKeeper API 有什么优势？
- 服务下线后如何避免客户端调用到不可用节点？
- 当前项目使用持久节点还是临时节点？有什么优化空间？

## 亮点 5：JDK 动态代理实现透明远程调用

### 解决了什么问题

客户端只有服务接口，没有服务实现类。如果让业务代码手动构造请求、查注册中心、发网络请求，使用成本很高，也会让业务逻辑和 RPC 框架强耦合。

动态代理解决的是调用透明化问题：业务代码只需要调用接口方法，底层 RPC 细节由代理对象完成。

### 项目中如何实现

客户端字段：

```java
@RpcReference(version = "version1", group = "test1")
private HelloService helloService;
```

框架在 `SpringBeanPostProcessor.postProcessAfterInitialization()` 中发现该字段后，创建 `RpcClientProxy`，再调用：

```java
rpcClientProxy.getProxy(declaredField.getType())
```

`RpcClientProxy.getProxy()` 内部使用：

```java
Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz}, this)
```

业务调用 `helloService.hello(...)` 时，会进入 `RpcClientProxy.invoke()`，在这里构造 `RpcRequest` 并发送远程请求。

### 涉及类和方法

- `simple.annotation.RpcReference`
- `simple.spring.SpringBeanPostProcessor.postProcessAfterInitialization()`
- `simple.proxy.RpcClientProxy`
- `RpcClientProxy.getProxy()`
- `RpcClientProxy.invoke()`
- `simple.remoting.dto.RpcRequest`
- `simple.remoting.transport.RpcRequestTransport.sendRpcRequest()`

### 面试时怎么讲

> 客户端调用远程服务时，其实拿到的是一个 JDK 动态代理对象。业务代码调用接口方法后，会进入 `RpcClientProxy.invoke()`。在这个方法里，我把接口名、方法名、参数、参数类型、版本、分组封装成 `RpcRequest`，再交给 Netty 客户端发送。这样业务层完全不需要关心网络通信细节，使用体验就像本地方法调用一样。

### 面试官可能追问

- 为什么用 JDK 动态代理？
- JDK 动态代理有什么限制？
- 如果服务不是接口而是类，应该怎么处理？
- JDK 动态代理和 CGLIB 的区别是什么？
- 代理对象在哪里创建，什么时候注入？

## 亮点 6：通过 Spring BeanPostProcessor 实现自动注册和自动注入

### 解决了什么问题

如果服务端每个服务都要手动注册，客户端每个引用都要手动创建代理对象，框架使用成本会很高。

Spring 扩展点解决的是框架自动化接入问题：

- 服务端启动时自动发现并注册服务。
- 客户端启动时自动创建并注入远程代理。

### 项目中如何实现

项目自定义了三个注解：

- `@RpcService`：标记服务提供者。
- `@RpcReference`：标记服务消费者需要注入的远程接口。
- `@RpcScan`：指定扫描路径。

`@RpcScan` 通过 `@Import(CustomScannerRegistrar.class)` 引入自定义扫描器。

服务端逻辑：

- `SpringBeanPostProcessor.postProcessBeforeInitialization()` 在 Bean 初始化前检查是否有 `@RpcService`。
- 如果有，就构造 `RpcServiceConfig` 并调用 `serviceProvider.publishService()`。

客户端逻辑：

- `SpringBeanPostProcessor.postProcessAfterInitialization()` 在 Bean 初始化后遍历字段。
- 如果字段有 `@RpcReference`，就创建代理对象并注入字段。

### 涉及类和方法

- `simple.annotation.RpcService`
- `simple.annotation.RpcReference`
- `simple.annotation.RpcScan`
- `simple.spring.CustomScannerRegistrar.registerBeanDefinitions()`
- `simple.spring.CustomScanner.scan()`
- `simple.spring.SpringBeanPostProcessor.postProcessBeforeInitialization()`
- `simple.spring.SpringBeanPostProcessor.postProcessAfterInitialization()`

### 面试时怎么讲

> 我通过 Spring 的 `BeanPostProcessor` 把 RPC 框架接入到了 Spring 生命周期中。服务端在 Bean 初始化前，如果发现某个 Bean 标注了 `@RpcService`，就把它注册到本地服务缓存和 ZooKeeper；客户端在 Bean 初始化后，如果发现字段标注了 `@RpcReference`，就创建一个动态代理对象注入进去。这样使用者只需要写注解，不需要手动注册服务或创建代理。

### 面试官可能追问

- `postProcessBeforeInitialization` 和 `postProcessAfterInitialization` 有什么区别？
- 为什么服务注册放在初始化前，代理注入放在初始化后？
- `@RpcScan` 是怎么触发自定义扫描器的？
- `ImportBeanDefinitionRegistrar` 的作用是什么？
- 如果 Spring Bean 是代理类，注解扫描会不会受影响？

## 亮点 7：自定义 SPI 扩展机制提升框架可插拔性

### 解决了什么问题

RPC 框架中很多组件都可能需要替换，比如：

- 注册中心从 ZooKeeper 换成 Nacos。
- 序列化从 Hessian 换成 Kryo。
- 负载均衡从随机换成一致性 Hash。
- 压缩方式从 Gzip 换成其他算法。

如果代码里到处写死具体实现类，扩展成本很高。

### 项目中如何实现

项目定义了 `@SPI` 注解，要求可扩展接口必须标注该注解。

`ExtensionLoader` 的核心逻辑：

1. `getExtensionLoader(Class<S> type)` 为每个 SPI 接口创建一个扩展加载器。
2. 校验 type 必须是接口，并且带有 `@SPI`。
3. 根据接口全限定名拼出配置文件路径：`META-INF/{接口全限定名}`。
4. 读取配置文件，解析 `name=实现类全限定名`。
5. 根据扩展名找到实现类。
6. 通过 `SingletonFactory` 创建并缓存单例对象。

项目中通过 SPI 扩展的组件包括：

- `ServiceRegistry`
- `ServiceDiscovery`
- `RpcRequestTransport`
- `LoadBalance`
- `Serializer`
- `Compress`

### 涉及类和方法

- `common.extension.SPI`
- `common.extension.ExtensionLoader.getExtensionLoader()`
- `common.extension.ExtensionLoader.getExtension()`
- `common.extension.ExtensionLoader.createExtension()`
- `common.extension.ExtensionLoader.loadDirectory()`
- `common.extension.ExtensionLoader.loadResource()`
- `common.factory.SingletonFactory.getInstance()`
- `rpc-framework-simple/src/main/resources/META-INF/*`

### 面试时怎么讲

> 我在项目里实现了一个简单的自定义 SPI。可扩展接口用 `@SPI` 标记，然后在 `META-INF` 下配置扩展名和实现类的映射。运行时通过 `ExtensionLoader.getExtension(name)` 获取具体实现。这样注册中心、服务发现、传输层、序列化、压缩和负载均衡都不需要在代码中写死实现类，后续扩展 Nacos、JSON 序列化或者新的负载均衡策略时，只需要实现接口并增加配置。

### 面试官可能追问

- 自定义 SPI 和 Java 原生 SPI 有什么区别？
- 为什么要缓存 `ExtensionLoader` 和扩展实例？
- `Holder` 的作用是什么？
- 如果两个配置文件里有相同扩展名会怎样？
- 这个 SPI 和 Dubbo SPI 相比还缺哪些能力？

## 亮点 8：序列化和压缩机制可扩展

### 解决了什么问题

RPC 请求中的 `RpcRequest` 和 `RpcResponse` 是 Java 对象，不能直接在网络上传输，必须转成字节数组。同时，为了降低网络传输体积，可以对序列化后的字节进行压缩。

不同序列化框架在性能、体积、兼容性方面各有差异，所以框架应该支持可插拔序列化。

### 项目中如何实现

序列化：

- 定义 `Serializer` 接口。
- 实现 `HessianSerializer`、`KryoSerializer`、`ProtostuffSerializer`。
- 通过 `SerializationTypeEnum` 中的 code 映射到序列化名称。
- 在 `RpcMessageEncoder` 中根据 `rpcMessage.getCodec()` 通过 SPI 获取序列化器。
- 在 `RpcMessageDecoder` 中根据协议头 codec 获取序列化器并反序列化。

压缩：

- 定义 `Compress` 接口。
- 实现 `GzipCompress`。
- 通过 `CompressTypeEnum` 中的 code 映射压缩名称。
- 编码时先序列化，再压缩。
- 解码时先解压，再反序列化。

### 涉及类和方法

- `simple.serialize.Serializer`
- `simple.serialize.hessian.HessianSerializer`
- `simple.serialize.kyro.KryoSerializer`
- `simple.serialize.protostuff.ProtostuffSerializer`
- `simple.compress.Compress`
- `simple.compress.gzip.GzipCompress`
- `common.enums.SerializationTypeEnum`
- `common.enums.CompressTypeEnum`
- `RpcMessageEncoder.encode()`
- `RpcMessageDecoder.decodeFrame()`

### 面试时怎么讲

> RPC 通信需要把 Java 对象转成字节流，所以我抽象了 `Serializer` 接口，并实现了 Hessian、Kryo、Protostuff 三种序列化方式。协议头里有 codec 字段，接收方可以根据 codec 选择对应的反序列化器。压缩也是类似的，我定义了 `Compress` 接口，目前实现了 Gzip。编码时先序列化再压缩，解码时先解压再反序列化。这些实现都通过 SPI 加载，后续可以很容易扩展 JSON、MsgPack 等序列化方式。

### 面试官可能追问

- Hessian、Kryo、Protostuff 的区别是什么？
- Kryo 为什么用 `ThreadLocal`？
- Protostuff 对对象有什么要求？
- 为什么先序列化再压缩？
- 当前项目的序列化和压缩实现有没有线程安全问题？

## 亮点 9：支持负载均衡和一致性 Hash

### 解决了什么问题

同一个服务可能有多个提供者实例。客户端从注册中心拿到的是服务地址列表，不能每次都固定选择第一个节点，否则容易造成流量倾斜。

负载均衡解决的是多个服务实例之间如何分配请求的问题。

### 项目中如何实现

项目定义了：

- `LoadBalance`：负载均衡接口。
- `AbstractLoadBalance`：公共模板逻辑，处理空列表和单节点情况，再调用子类 `doSelect()`。
- `RandomLoadBalance`：随机选择一个服务地址。
- `ConsistentHashLoadBalance`：基于 TreeMap 模拟哈希环。
- `ConsistentHashLoadBalanceNew`：支持节点变化检测和哈希环重构。

服务发现时：

```java
String targetServiceUrl = loadBalance.selectServiceAddress(serviceUrlList, rpcRequest);
```

`ZkServiceDiscoveryImpl` 默认通过 SPI 加载 `LoadBalanceEnum.LOADBALANCENEW` 对应的一致性 Hash 实现。

### 涉及类和方法

- `simple.loadbalance.LoadBalance.selectServiceAddress()`
- `simple.loadbalance.AbstractLoadBalance.selectServiceAddress()`
- `simple.loadbalance.AbstractLoadBalance.doSelect()`
- `simple.loadbalance.loadbalancer.RandomLoadBalance.doSelect()`
- `simple.loadbalance.loadbalancer.ConsistentHashLoadBalance.doSelect()`
- `simple.loadbalance.loadbalancer.ConsistentHashLoadBalanceNew.doSelect()`
- `ZkServiceDiscoveryImpl.lookupService()`

### 面试时怎么讲

> 客户端从 ZooKeeper 查询服务时，可能拿到多个服务提供者地址，所以我抽象了负载均衡接口。公共逻辑放在 `AbstractLoadBalance` 中，如果只有一个节点直接返回，如果有多个节点就交给具体策略。项目里实现了随机负载均衡和一致性 Hash。一致性 Hash 通过虚拟节点和 TreeMap 模拟哈希环，在节点上下线时可以减少请求迁移，也能让请求更均匀地分布到不同节点。

### 面试官可能追问

- 随机负载均衡有什么优缺点？
- 一致性 Hash 为什么适合 RPC？
- 虚拟节点的作用是什么？
- 服务节点上下线时哈希环如何更新？
- 当前负载均衡是否考虑了权重、响应时间、失败节点？

## 亮点 10：CompletableFuture 实现异步转同步和请求响应匹配

### 解决了什么问题

Netty 的网络通信是异步的。客户端发送多个请求后，服务端响应可能乱序返回。业务线程需要拿到自己那次请求对应的结果，不能简单依赖发送顺序。

因此项目需要解决两个问题：

- 如何让业务调用表现得像同步调用？
- 如何让异步返回的响应和原请求正确匹配？

### 项目中如何实现

客户端发送请求时：

1. `NettyRpcClient.sendRpcRequest()` 创建 `CompletableFuture<RpcResponse<Object>> resultFuture`。
2. 调用 `unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture)`。
3. 通过 Netty 异步发送请求。
4. 业务线程调用 `resultFuture.get()` 等待结果。

客户端收到响应时：

1. `NettyRpcClientHandler.channelRead()` 解析出 `RpcResponse`。
2. 调用 `unprocessedRequests.complete(rpcResponse)`。
3. `UnprocessedRequests` 根据 `rpcResponse.getRequestId()` 找到对应 Future。
4. 调用 `future.complete(rpcResponse)` 唤醒等待的业务线程。

### 涉及类和方法

- `simple.remoting.transport.netty.client.NettyRpcClient.sendRpcRequest()`
- `simple.remoting.transport.netty.client.NettyRpcClientHandler.channelRead()`
- `simple.remoting.transport.netty.client.UnprocessedRequests.put()`
- `simple.remoting.transport.netty.client.UnprocessedRequests.complete()`
- `simple.proxy.RpcClientProxy.check()`
- `java.util.concurrent.CompletableFuture`

### 面试时怎么讲

> Netty 本身是异步的，但我希望业务层使用时像同步方法调用一样拿到返回值。所以客户端发送请求时会创建一个 `CompletableFuture`，并用请求的 `requestId` 把它放进 `UnprocessedRequests`。响应回来后，客户端 Handler 根据响应里的 `requestId` 找到对应 Future，并调用 `complete()` 填充结果。业务线程阻塞在 `future.get()`，响应完成后就能继续执行。这样既利用了 Netty 的异步通信，又对业务层提供了同步调用体验。

### 面试官可能追问

- 为什么不能按请求发送顺序匹配响应？
- `CompletableFuture.get()` 会不会阻塞？
- 如果服务端一直不返回，会发生什么？
- 未完成请求表会不会内存泄漏？
- 如果请求超时，应该怎么优化？

## 亮点 11：线程安全缓存和单例管理

### 解决了什么问题

RPC 框架运行在多线程环境中：

- 多个业务线程可能同时发起远程调用。
- Netty IO 线程可能同时处理多个响应。
- 服务端可能并发处理多个请求。
- SPI 扩展实例和 Channel 连接需要复用。

如果缓存结构不是线程安全的，可能出现数据错乱、重复创建、响应匹配错误等问题。

### 项目中如何实现

项目中多处使用线程安全结构：

- `UnprocessedRequests` 使用 `ConcurrentHashMap` 保存 `requestId -> CompletableFuture`。
- `ChannelProvider` 使用 `ConcurrentHashMap` 保存 `服务地址 -> Channel`。
- `ZKServiceProviderImpl` 使用 `ConcurrentHashMap` 保存 `服务名 -> 服务对象`。
- `ZKServiceProviderImpl` 使用 `ConcurrentHashMap.newKeySet()` 保存已注册服务名。
- `CuratorUtils` 使用 `ConcurrentHashMap` 缓存服务地址列表。
- `SingletonFactory` 使用 `ConcurrentHashMap` 缓存单例对象。
- `ExtensionLoader` 使用 `ConcurrentHashMap` 缓存扩展加载器和扩展实例。
- `RpcMessageEncoder` 使用 `AtomicInteger` 生成协议头中的自增请求编号。

### 涉及类和方法

- `simple.remoting.transport.netty.client.UnprocessedRequests`
- `simple.remoting.transport.netty.client.ChannelProvider`
- `simple.provider.Impl.ZKServiceProviderImpl`
- `simple.registry.Impl.util.CuratorUtils`
- `common.factory.SingletonFactory`
- `common.extension.ExtensionLoader`
- `simple.remoting.transport.netty.codec.RpcMessageEncoder`

### 面试时怎么讲

> 这个项目里客户端和服务端都存在并发访问，所以我在关键缓存上使用了线程安全集合。比如未完成请求表、Channel 缓存、本地服务缓存、注册中心地址缓存都使用 `ConcurrentHashMap`。SPI 扩展实例和框架组件也通过 `SingletonFactory` 做单例缓存，避免重复创建。对于协议头中的自增编号，我使用 `AtomicInteger` 保证并发情况下自增安全。

### 面试官可能追问

- 为什么用 `ConcurrentHashMap`，不用普通 `HashMap`？
- `ConcurrentHashMap.newKeySet()` 有什么作用？
- 单例工厂如何保证线程安全？
- `AtomicInteger` 和普通 int 自增有什么区别？
- 当前项目中还有哪些潜在线程安全问题？

## 亮点 12：心跳和连接保活设计

### 解决了什么问题

RPC 通常使用长连接。长连接如果长时间没有数据交互，可能因为网络异常、服务端关闭、客户端异常退出等原因变成不可用连接。如果不检测，客户端可能继续向失效 Channel 发送请求。

心跳机制用于检测连接是否存活，并及时释放无效连接。

### 项目中如何实现

客户端：

- `NettyRpcClient` 在 Pipeline 中添加 `IdleStateHandler(0, 5, 0, TimeUnit.SECONDS)`。
- 如果 5 秒没有写事件，会触发 `WRITER_IDLE`。
- `NettyRpcClientHandler.userEventTriggered()` 捕获写空闲事件，发送心跳请求。

服务端：

- `NettyRpcServer` 在 Pipeline 中添加 `IdleStateHandler(30, 0, 0, TimeUnit.SECONDS)`。
- 如果 30 秒没有读事件，会触发 `READER_IDLE`。
- `NettyRpcServerHandler.userEventTriggered()` 捕获读空闲事件，关闭连接。

心跳消息类型由 `RpcConstants` 定义：

- `HEARTBEAT_REQUEST_TYPE`
- `HEARTBEAT_RESPONSE_TYPE`
- `PING`
- `PONG`

### 涉及类和方法

- `simple.remoting.transport.netty.client.NettyRpcClient`
- `simple.remoting.transport.netty.client.NettyRpcClientHandler.userEventTriggered()`
- `simple.remoting.transport.netty.server.NettyRpcServer`
- `simple.remoting.transport.netty.server.NettyRpcServerHandler.userEventTriggered()`
- `simple.remoting.constants.RpcConstants`
- `io.netty.handler.timeout.IdleStateHandler`

### 面试时怎么讲

> 项目里我也做了基础心跳设计。客户端 Pipeline 中加入了 `IdleStateHandler`，如果一段时间没有写数据，就发送 PING 心跳包；服务端如果一段时间没有读到客户端数据，就认为连接可能不可用并关闭连接。这样可以避免长连接失效后仍然被继续使用。当然当前实现还比较基础，后续可以加入失败计数、重连机制和更完整的连接剔除策略。

### 面试官可能追问

- `IdleStateHandler` 能检测哪些空闲事件？
- 客户端为什么检测写空闲，服务端为什么检测读空闲？
- 心跳失败后应该怎么处理？
- 当前项目的心跳机制还有什么不足？
- TCP keepalive 和应用层心跳有什么区别？

## 亮点优先级建议

如果面试时间短，优先讲这 5 个：

1. Spring 注解 + JDK 动态代理：体现框架易用性。
2. Netty 网络通信：体现网络编程能力。
3. 自定义协议 + 粘包半包处理：体现底层协议理解。
4. ZooKeeper 服务注册发现：体现分布式基础能力。
5. CompletableFuture + requestId 匹配响应：体现并发和异步理解。

如果面试官继续深入，再补充：

- 自定义 SPI 扩展机制。
- 序列化和压缩。
- 一致性 Hash 负载均衡。
- 线程安全缓存。
- 心跳和连接保活。

## 总结话术

可以直接背这一段：

> 这个 RPC 项目我觉得主要有几个亮点。第一是使用 Netty 实现客户端和服务端的高性能网络通信，并通过 Channel 复用减少连接开销；第二是设计了自定义 RPC 协议，协议头包含魔数、版本号、消息长度、消息类型、序列化和压缩方式，并用 `LengthFieldBasedFrameDecoder` 解决 TCP 粘包半包；第三是基于 ZooKeeper 和 Curator 实现服务注册发现，服务端启动自动注册，客户端调用前动态发现服务地址；第四是通过 Spring `BeanPostProcessor` 和 JDK 动态代理，让客户端可以像调用本地接口一样调用远程服务；第五是实现了自定义 SPI，让注册中心、传输层、序列化、压缩、负载均衡这些组件都具备扩展能力；另外还通过 `CompletableFuture`、`requestId` 和线程安全 Map 实现了异步响应匹配。这些点基本覆盖了一个 RPC 框架从易用性、通信协议、服务治理到并发处理的核心链路。
