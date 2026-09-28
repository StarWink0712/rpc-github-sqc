# 04 Interview Questions

## 使用说明

这份文档按 Java 开发工程师项目面试的常见追问路径整理。问题都结合当前项目代码，不是泛泛八股。

标记说明：

- **高概率追问：是**：面试中很容易被问到，建议背熟。
- **高概率追问：中**：有一定概率被问到，建议理解。
- **高概率追问：低**：偏深入或偏优化，作为加分项准备。

## 一、项目整体设计

### 1. 请你介绍一下这个项目

**高概率追问：是**

参考回答：

> 这是一个基于 Netty 和 ZooKeeper 的轻量级 RPC 框架。它的目标是让客户端像调用本地接口一样调用远程服务。服务端通过 `@RpcService` 暴露服务，框架在 Spring Bean 初始化阶段自动注册到 ZooKeeper；客户端通过 `@RpcReference` 注入远程服务代理，调用接口方法时会进入 JDK 动态代理，代理对象把方法调用封装成 `RpcRequest`，通过 ZooKeeper 服务发现和负载均衡找到服务端地址，再通过 Netty 按自定义协议发送请求。服务端解码后，根据服务名找到本地服务对象，反射执行目标方法，再把结果封装成 `RpcResponse` 返回。

### 2. 这个项目分了哪些模块？为什么这么分？

**高概率追问：是**

参考回答：

> 项目是 Maven 多模块结构。`rpc-framework-common` 放公共枚举、异常、工具类、SPI 和单例工厂；`rpc-framework-simple` 是核心 RPC 框架，包含注册发现、代理、Netty 通信、协议编解码、序列化压缩、负载均衡和 Spring 集成；`hello-service-api` 是服务接口契约模块，客户端和服务端共同依赖；`example-server` 是服务提供方示例；`example-client` 是服务消费方示例。这样拆分的好处是 API、框架和业务示例解耦，客户端只依赖接口和框架，不依赖服务端实现类。

### 3. 这个项目的核心亮点是什么？

**高概率追问：是**

参考回答：

> 我会重点讲五个亮点。第一是用 Netty 实现高性能网络通信和 Channel 复用；第二是设计自定义协议，并用 `LengthFieldBasedFrameDecoder` 解决粘包半包；第三是基于 ZooKeeper/Curator 实现服务注册发现；第四是通过 Spring `BeanPostProcessor` 和 JDK 动态代理实现注解式远程调用；第五是实现自定义 SPI，让序列化、压缩、注册中心、负载均衡等组件可扩展。另外，项目还用 `CompletableFuture` 和 `requestId` 做异步响应匹配。

### 4. 为什么需要 `hello-service-api` 模块？

**高概率追问：中**

参考回答：

> RPC 通常要求服务提供方和消费方依赖同一份接口契约。`hello-service-api` 中定义了 `HelloService` 和 `Hello`，服务端实现 `HelloService`，客户端只依赖接口并生成代理对象。这样客户端不需要依赖服务端实现类，符合面向接口编程，也更接近真实分布式服务的调用方式。

## 二、RPC 原理

### 5. 一次完整 RPC 调用链路是什么？

**高概率追问：是**

参考回答：

> 客户端启动时，`SpringBeanPostProcessor.postProcessAfterInitialization()` 会扫描 `@RpcReference` 字段，并注入 `RpcClientProxy` 生成的 JDK 动态代理对象。业务代码调用 `helloService.hello()` 后进入 `RpcClientProxy.invoke()`，代理对象构造 `RpcRequest`，再调用 `NettyRpcClient.sendRpcRequest()`。客户端先通过 `ZkServiceDiscoveryImpl.lookupService()` 去 ZooKeeper 查询服务地址，并用负载均衡选择一个节点；然后通过 `ChannelProvider` 获取或建立 Netty Channel，把 `RpcRequest` 包装成 `RpcMessage` 编码发送。服务端 `RpcMessageDecoder` 解码请求，`NettyRpcServerHandler.channelRead()` 取出 `RpcRequest`，交给 `RpcRequestHandler.handle()`，根据服务名从 `ZKServiceProviderImpl.serviceMap` 找服务对象，再反射调用方法，最后封装 `RpcResponse` 返回。客户端收到响应后，通过 `UnprocessedRequests.complete()` 根据 `requestId` 匹配 Future，并把结果返回给业务代码。

### 6. `RpcRequest` 里为什么要有接口名、方法名、参数类型和参数值？

**高概率追问：是**

参考回答：

> 因为服务端需要根据请求信息定位并调用目标方法。`interfaceName`、`group`、`version` 拼成服务名，用来从本地 `serviceMap` 找服务对象；`methodName` 表示要调用哪个方法；`parameters` 是真实参数值；`paramTypes` 是参数类型，服务端反射调用时通过 `service.getClass().getMethod(methodName, paramTypes)` 获取 Method。参数类型很重要，因为 Java 有方法重载，只靠方法名不够。

### 7. 客户端为什么能像调用本地方法一样调用远程服务？

**高概率追问：是**

参考回答：

> 因为客户端注入的不是服务实现类，而是 JDK 动态代理对象。`@RpcReference` 字段在 Spring 初始化后被 `SpringBeanPostProcessor` 处理，框架调用 `RpcClientProxy.getProxy()` 生成接口代理。业务代码调用接口方法时，实际进入代理对象的 `invoke()` 方法，由代理对象完成请求封装、服务发现、Netty 发送和响应校验，所以对业务层来说就像本地调用。

### 8. 请求和响应是如何匹配的？

**高概率追问：是**

参考回答：

> 项目用 `RpcRequest.requestId` 做匹配。客户端发送请求前，在 `NettyRpcClient.sendRpcRequest()` 中创建 `CompletableFuture`，然后调用 `UnprocessedRequests.put(requestId, future)` 保存起来。服务端处理完成后，用原请求的 `requestId` 构造 `RpcResponse.success(result, requestId)`。客户端收到响应后，`NettyRpcClientHandler.channelRead()` 调用 `UnprocessedRequests.complete(rpcResponse)`，根据响应里的 `requestId` 找到对应 Future 并完成它。

### 9. 为什么不能按发送顺序匹配响应？

**高概率追问：中**

参考回答：

> 因为 Netty 通信是异步的，多个请求可以同时发出去，服务端处理耗时和网络延迟都不一样，响应返回顺序可能和发送顺序不一致。如果按顺序匹配，结果可能给错业务线程。所以必须给每个请求一个唯一 `requestId`，用 Map 做精确匹配。

## 三、Netty

### 10. 项目里服务端 Netty 是怎么启动的？

**高概率追问：是**

参考回答：

> 服务端在 `NettyRpcServer.start()` 中启动。它创建一个 `bossGroup` 接收连接，一个 `workerGroup` 处理 IO，然后用 `ServerBootstrap` 绑定 `NioServerSocketChannel`。Pipeline 里加入 `IdleStateHandler`、`RpcMessageEncoder`、`RpcMessageDecoder` 和 `NettyRpcServerHandler`。最后通过 `b.bind(host, PORT).sync()` 绑定端口，默认端口是 `9998`。

### 11. bossGroup、workerGroup、DefaultEventExecutorGroup 分别干什么？

**高概率追问：是**

参考回答：

> `bossGroup` 主要负责接收客户端连接，项目里设置为一个线程；`workerGroup` 负责连接建立后的网络读写；`DefaultEventExecutorGroup` 用来执行 RPC 业务处理逻辑，比如反射调用服务方法。这样可以避免业务逻辑阻塞 Netty IO 线程，提高 IO 线程的响应能力。

### 12. 客户端如何建立和复用 Channel？

**高概率追问：是**

参考回答：

> 客户端在 `NettyRpcClient.getChannel()` 中先通过 `ChannelProvider.get(inetSocketAddress)` 查缓存。如果对应地址已有活跃 Channel，就直接复用；如果没有，就调用 `doConnect()`，通过 `Bootstrap.connect()` 异步建立连接，再用 `CompletableFuture.get()` 等待连接结果。连接成功后，通过 `ChannelProvider.set()` 缓存起来，后续请求复用这个 Channel。

### 13. Netty Pipeline 里有哪些 Handler？顺序有什么意义？

**高概率追问：中**

参考回答：

> 客户端 Pipeline 里有 `IdleStateHandler`、`RpcMessageDecoder`、`RpcMessageEncoder`、`NettyRpcClientHandler`。服务端 Pipeline 里有 `IdleStateHandler`、`RpcMessageEncoder`、`RpcMessageDecoder`、`NettyRpcServerHandler`。入站数据会经过 Decoder 解码成 `RpcMessage`，再进入业务 Handler；出站数据会经过 Encoder 编码成字节流再发送。`IdleStateHandler` 用来触发空闲事件，实现基础心跳和连接检测。

### 14. 为什么项目里要做心跳？

**高概率追问：中**

参考回答：

> 因为 RPC 通常使用长连接，长时间没有数据时连接可能已经失效。客户端在 `NettyRpcClient` 中配置了 `IdleStateHandler(0, 5, 0)`，写空闲时由 `NettyRpcClientHandler.userEventTriggered()` 发送 PING；服务端在 `NettyRpcServer` 中配置了 `IdleStateHandler(30, 0, 0)`，读空闲时关闭连接。这个机制可以帮助检测和清理长期无数据的连接。

## 四、ZooKeeper

### 15. 服务端是如何注册服务到 ZooKeeper 的？

**高概率追问：是**

参考回答：

> 服务端实现类 `HelloServiceImpl` 标注了 `@RpcService`。Spring 初始化 Bean 时，`SpringBeanPostProcessor.postProcessBeforeInitialization()` 识别这个注解，构造 `RpcServiceConfig`，调用 `ZKServiceProviderImpl.publishService()`。这个方法先通过 `addService()` 把服务对象放进本地 `serviceMap`，再通过 `ZKServiceRegistryImpl.registerService()` 调用 `CuratorUtils.createPersistentNode()` 在 ZooKeeper `/my-rpc` 下创建服务节点。

### 16. 客户端是如何发现服务地址的？

**高概率追问：是**

参考回答：

> 客户端在 `NettyRpcClient.sendRpcRequest()` 中调用 `serviceDiscovery.lookupService(rpcRequest)`。实际实现是 `ZkServiceDiscoveryImpl.lookupService()`。它先通过 `rpcRequest.getRpcServiceName()` 得到服务名，再用 `CuratorUtils.getChildrenNodes()` 查询 ZooKeeper 中该服务下的所有地址节点，最后通过 `LoadBalance.selectServiceAddress()` 选择一个地址，转换成 `InetSocketAddress` 返回。

### 17. 服务名是怎么生成的？group 和 version 有什么意义？

**高概率追问：是**

参考回答：

> 服务名由接口全限定名、group 和 version 拼接。客户端是 `RpcRequest.getRpcServiceName()`，服务端是 `RpcServiceConfig.getRpcServiceName()`。group 可以区分同一个接口的不同分组，比如测试组和正式组；version 可以区分同一个接口的不同版本。这样同一个接口可以同时存在多个实现，客户端按 group/version 精确调用。

### 18. 为什么服务端要先放本地 serviceMap，再注册 ZooKeeper？

**高概率追问：中**

参考回答：

> 这样可以避免服务还没在本地准备好就被客户端发现。如果先注册到 ZooKeeper，客户端可能马上发现服务并发起请求，但服务端本地 `serviceMap` 还没有对应对象，就会调用失败。当前代码在 `ZKServiceProviderImpl.publishService()` 中先 `addService()`，再 `serviceRegistry.registerService()`，顺序是合理的。

### 19. 当前 ZooKeeper 节点设计有什么优化空间？

**高概率追问：中**

参考回答：

> 当前 `ZKServiceRegistryImpl` 调用的是 `CuratorUtils.createPersistentNode()`，也就是持久节点。更适合服务实例注册的其实是临时节点，因为服务进程宕机或会话断开时，临时节点会自动删除，客户端不会发现失效服务。项目里虽然提供了 `createEphemeralNode()`，但注册服务时没有使用。后续可以把服务实例节点改成临时节点，并完善节点监听和重连逻辑。

## 五、动态代理

### 20. 项目为什么使用 JDK 动态代理？

**高概率追问：是**

参考回答：

> 因为 RPC 客户端依赖的是服务接口，不依赖服务实现类。JDK 动态代理正好可以基于接口生成代理对象。项目中的 `RpcClientProxy` 实现了 `InvocationHandler`，在 `getProxy()` 中通过 `Proxy.newProxyInstance()` 创建代理，调用接口方法时进入 `invoke()`，完成请求封装和远程调用。

### 21. `RpcClientProxy.invoke()` 做了什么？

**高概率追问：是**

参考回答：

> 它是客户端远程调用的核心入口。`invoke()` 会拿到接口名、方法名、参数值和参数类型，然后结合 `RpcServiceConfig` 中的 group/version 构造 `RpcRequest`，并生成 UUID 作为 `requestId`。接着调用 `rpcRequestTransport.sendRpcRequest(rpcRequest)` 发送请求。拿到 `RpcResponse` 后，会校验响应是否为空、requestId 是否匹配、响应码是否成功，最后返回 `rpcResponse.getData()`。

### 22. JDK 动态代理有什么限制？如果不是接口怎么办？

**高概率追问：中**

参考回答：

> JDK 动态代理只能代理接口，不能直接代理普通类。当前项目的服务调用就是基于接口的，比如 `HelloService`。如果要支持没有接口的类，可以考虑引入 CGLIB 或 ByteBuddy，通过继承目标类生成子类代理。但在 RPC 框架里，面向接口调用更常见，也更适合客户端和服务端共享契约。

### 23. 为什么代理里要校验 requestId？

**高概率追问：中**

参考回答：

> 因为并发 RPC 调用时响应可能乱序返回，`requestId` 是判断响应是否属于当前请求的关键字段。`RpcClientProxy.check()` 中会比较 `rpcRequest.getRequestId()` 和 `rpcResponse.getRequestId()`，如果不一致说明响应错配，需要抛异常。

## 六、Spring 扩展点

### 24. `@RpcReference` 是如何完成代理注入的？

**高概率追问：是**

参考回答：

> 在 `SpringBeanPostProcessor.postProcessAfterInitialization()` 中，框架遍历 Bean 的字段。如果字段上有 `@RpcReference`，就读取注解中的 group/version，构造 `RpcServiceConfig`，创建 `RpcClientProxy`，调用 `getProxy(declaredField.getType())` 得到接口代理对象，最后通过反射 `declaredField.set(bean, clientProxy)` 注入字段。

### 25. `@RpcService` 是如何完成服务注册的？

**高概率追问：是**

参考回答：

> 在 `SpringBeanPostProcessor.postProcessBeforeInitialization()` 中，框架判断当前 Bean 的类上是否有 `@RpcService`。如果有，就读取注解中的 group/version，并把当前 Bean 作为服务对象封装到 `RpcServiceConfig` 中，然后调用 `serviceProvider.publishService()`，完成本地缓存和 ZooKeeper 注册。

### 26. 为什么服务注册在 before，代理注入在 after？

**高概率追问：中**

参考回答：

> 服务注册需要尽早完成，让服务端启动时把可提供的服务发布出去，所以放在初始化前处理。代理注入需要处理已经创建好的客户端 Bean 字段，注入远程代理对象，所以放在初始化后处理。这个设计也符合它们的职责：before 偏服务提供方注册，after 偏服务消费方注入。

### 27. `@RpcScan` 是怎么工作的？

**高概率追问：中**

参考回答：

> `@RpcScan` 使用 `@Import(CustomScannerRegistrar.class)` 引入自定义注册器。Spring 启动时会调用 `CustomScannerRegistrar.registerBeanDefinitions()`，读取 `@RpcScan` 的 `basePackage`，创建 `CustomScanner` 扫描指定包下的 `@RpcService`，同时扫描框架内部组件。这样 `@RpcService` 标注的类就能注册为 Spring Bean，并被后续 `BeanPostProcessor` 处理。

## 七、自定义协议

### 28. 你这个 RPC 协议头有哪些字段？

**高概率追问：是**

参考回答：

> 协议头在 `RpcMessageEncoder` 的注释和 `RpcConstants` 中可以看到，主要包括 4 字节 magic number、1 字节 version、4 字节 full length、1 字节 messageType、1 字节 codec、1 字节 compress、4 字节 requestId，后面是 body。body 存放序列化压缩后的 `RpcRequest` 或 `RpcResponse`。

### 29. 魔数和版本号有什么作用？

**高概率追问：是**

参考回答：

> 魔数用于快速判断收到的数据是否属于当前 RPC 协议，项目中是 `grpc` 对应的字节数组。如果魔数不匹配，`RpcMessageDecoder.checkMagicNumber()` 会抛异常。版本号用于协议兼容控制，`checkVersion()` 会判断收到的版本是否等于当前 `RpcConstants.VERSION`。

### 30. 编码流程是怎样的？

**高概率追问：中**

参考回答：

> `RpcMessageEncoder.encode()` 会先写魔数和版本号，预留 full length，然后写消息类型、序列化类型、压缩类型和协议头 requestId。如果不是心跳消息，就根据 codec 通过 `ExtensionLoader` 获取 `Serializer`，把 data 序列化成字节数组，再根据 compress 获取 `Compress` 进行压缩。最后写 body，并回填完整消息长度。

### 31. 解码流程是怎样的？

**高概率追问：中**

参考回答：

> `RpcMessageDecoder` 继承 `LengthFieldBasedFrameDecoder`，先根据长度字段切完整帧。之后 `decodeFrame()` 校验魔数和版本，读取 fullLength、messageType、codec、compress 和 requestId。如果 body 存在，就读取 body 字节，先解压，再根据 codec 获取序列化器，按消息类型反序列化成 `RpcRequest` 或 `RpcResponse`。

### 32. 当前协议实现有什么明显可优化点？

**高概率追问：中**

参考回答：

> 有几个点。第一，`RpcMessageDecoder.decodeFrame()` 里读取了 `compressType`，但实际获取压缩器时用了 `CompressTypeEnum.getName(codecType)`，语义上应该改成使用 `compressType`。第二，协议头里的 `RpcMessage.requestId` 是 int 自增，而业务匹配用的是 `RpcRequest.requestId` 字符串 UUID，两套 ID 容易混淆，可以统一设计。第三，编码异常现在只是记录日志，没有把异常抛出或关闭连接，错误处理还可以完善。

## 八、序列化和压缩

### 33. 项目支持哪些序列化方式？

**高概率追问：是**

参考回答：

> 项目抽象了 `Serializer` 接口，并实现了 Hessian、Kryo、Protostuff 三种方式，对应 `HessianSerializer`、`KryoSerializer`、`ProtostuffSerializer`。协议头里的 codec 字段会通过 `SerializationTypeEnum` 映射成名称，再通过 `ExtensionLoader` 加载具体序列化器。

### 34. Kryo 为什么用了 ThreadLocal？

**高概率追问：中**

参考回答：

> 因为 Kryo 实例本身不是线程安全的。如果多个线程共享一个 Kryo，可能出现序列化数据错乱。项目中的 `KryoSerializer` 使用 `ThreadLocal<Kryo>`，每个线程持有自己的 Kryo 实例，从而避免并发问题。

### 35. 压缩是在什么时候做的？为什么要压缩？

**高概率追问：中**

参考回答：

> 压缩发生在序列化之后。`RpcMessageEncoder.encode()` 先把 `RpcMessage.data` 序列化成字节数组，然后根据 compress 字段加载 `GzipCompress` 对字节数组压缩。解码时则先解压，再反序列化。压缩可以减少网络传输体积，但也会增加 CPU 开销，所以实际生产中需要结合消息大小决定是否压缩。

### 36. 当前序列化实现有什么风险？

**高概率追问：中**

参考回答：

> 有几个风险。`ProtostuffSerializer` 使用了 `static final LinkedBuffer BUFFER`，多个线程共享同一个 Buffer 会有线程安全问题，应该改成方法内局部变量或 `ThreadLocal`。另外，Hessian 这类反序列化机制需要注意安全风险，生产环境要限制可反序列化类型。Kryo 也需要完整注册可能传输的类型，否则复杂对象可能有兼容问题。

## 九、负载均衡

### 37. 项目什么时候进行负载均衡？

**高概率追问：是**

参考回答：

> 负载均衡发生在客户端服务发现阶段。`ZkServiceDiscoveryImpl.lookupService()` 从 ZooKeeper 获取某个服务的所有地址后，调用 `loadBalance.selectServiceAddress(serviceUrlList, rpcRequest)` 选择一个目标地址。选完后，客户端才用这个地址建立或复用 Netty Channel。

### 38. 项目实现了哪些负载均衡策略？

**高概率追问：中**

参考回答：

> 项目定义了 `LoadBalance` 接口和 `AbstractLoadBalance` 抽象类。具体实现包括 `RandomLoadBalance`、`ConsistentHashLoadBalance` 和 `ConsistentHashLoadBalanceNew`。`AbstractLoadBalance` 处理空列表和单节点情况，多节点时交给子类的 `doSelect()`。

### 39. 一致性 Hash 在这个项目里怎么实现？

**高概率追问：中**

参考回答：

> `ConsistentHashLoadBalance` 使用 `TreeMap` 模拟哈希环，真实服务节点会映射成多个虚拟节点，选择时根据服务名和请求参数计算 hash，然后在 TreeMap 中找顺时针方向第一个节点。`ConsistentHashLoadBalanceNew` 进一步维护物理节点集合，支持检测服务地址变化并重构哈希环。

### 40. 虚拟节点的作用是什么？

**高概率追问：中**

参考回答：

> 虚拟节点是为了解决真实节点较少时 hash 分布不均匀的问题。一个真实节点会对应多个虚拟节点，分散在哈希环不同位置，这样请求更容易均匀落到不同机器上。同时节点上下线时，一致性 Hash 只会影响环上相邻区间，减少请求迁移。

## 十、SPI 扩展机制

### 41. 你这个自定义 SPI 是怎么加载扩展的？

**高概率追问：是**

参考回答：

> 可扩展接口会标注 `@SPI`，比如 `Serializer`、`Compress`、`LoadBalance`。调用 `ExtensionLoader.getExtensionLoader(interfaceClass)` 时，会校验它是接口并带有 `@SPI`。真正获取实现时，`getExtension(name)` 会读取 `META-INF/{接口全限定名}` 配置文件，解析 `name=实现类全限定名`，再通过类加载器加载 Class，最后用 `SingletonFactory` 创建单例并缓存。

### 42. 项目中哪些组件通过 SPI 扩展？

**高概率追问：是**

参考回答：

> 包括服务注册 `ServiceRegistry`、服务发现 `ServiceDiscovery`、传输层 `RpcRequestTransport`、负载均衡 `LoadBalance`、序列化 `Serializer` 和压缩 `Compress`。比如 `META-INF/simple.serialize.Serializer` 中配置了 `kyro`、`protostuff`、`hessian` 对应的实现类。

### 43. 自定义 SPI 相比直接 new 实现类有什么好处？

**高概率追问：中**

参考回答：

> 好处是解耦和可扩展。如果代码里直接 `new ZKServiceRegistryImpl()` 或 `new HessianSerializer()`，后续切换实现要改业务代码。SPI 方式只依赖接口和扩展名，新增实现时实现接口、加配置文件即可。比如将注册中心扩展成 Nacos，可以新增 Nacos 实现类并配置扩展名。

### 44. 当前 SPI 实现有什么不足？

**高概率追问：低**

参考回答：

> 当前实现还比较基础。比如缺少默认扩展、扩展自动注入、自适应扩展、包装扩展等能力；配置冲突处理也不够完整。另外当前 `Holder.set(T instance)` 源码里写成了 `this.value = value`，应该是 `this.value = instance`，否则缓存赋值会失败，这是需要修复的明显代码问题。

## 十一、并发和线程安全

### 45. 客户端如何处理并发请求？

**高概率追问：是**

参考回答：

> 客户端每次请求都会生成唯一 `requestId`，并创建一个 `CompletableFuture`。`UnprocessedRequests` 使用 `ConcurrentHashMap` 保存 `requestId -> Future`。多个请求可以同时通过 Netty 发送，响应回来后由 `NettyRpcClientHandler` 根据 `requestId` 找到对应 Future 并完成。这样可以处理响应乱序返回的问题。

### 46. 哪些地方用了线程安全缓存？

**高概率追问：中**

参考回答：

> `UnprocessedRequests` 用 `ConcurrentHashMap` 保存未完成请求；`ChannelProvider` 用 `ConcurrentHashMap` 缓存服务地址到 Channel；`ZKServiceProviderImpl` 用 `ConcurrentHashMap` 保存服务名到服务对象，并用 `ConcurrentHashMap.newKeySet()` 保存已注册服务；`CuratorUtils` 用 `ConcurrentHashMap` 缓存服务地址列表；`ExtensionLoader` 和 `SingletonFactory` 也使用并发 Map 做实例缓存。

### 47. `CompletableFuture.get()` 会不会阻塞？为什么还说 Netty 是异步？

**高概率追问：中**

参考回答：

> 会阻塞业务线程。项目底层 Netty 发送和接收是异步的，但框架对业务层暴露的是同步调用体验。业务线程发出请求后阻塞在 `resultFuture.get()`，Netty IO 线程收到响应后调用 `future.complete()`，业务线程再继续执行。所以这是底层异步、上层同步的封装。

### 48. `ChannelProvider` 复用连接有什么风险？

**高概率追问：中**

参考回答：

> 它可以减少频繁建连开销，但也要处理 Channel 失效问题。当前 `ChannelProvider.get()` 会判断 `channel.isActive()`，如果不可用就移除。但还可以优化，比如在连接关闭事件里主动移除缓存、增加连接池、重连机制、最大空闲时间、失败节点隔离等。

### 49. 服务端为什么要把业务 Handler 放到独立线程组？

**高概率追问：中**

参考回答：

> 因为服务端处理请求时会做反射调用和业务逻辑执行，这些操作可能耗时。如果直接在 Netty IO 线程执行，会影响 IO 读写。项目在 `NettyRpcServer.start()` 中使用 `DefaultEventExecutorGroup` 包装 `NettyRpcServerHandler`，让业务逻辑在线程组中执行，避免阻塞 workerGroup。

## 十二、项目缺陷和优化

### 50. 这个项目目前最大的不足是什么？

**高概率追问：是**

参考回答：

> 我会从工程化角度回答。当前项目已经跑通了 RPC 核心链路，但还缺少生产级治理能力，比如请求超时、失败重试、熔断限流、服务节点临时注册、连接池和重连机制、监控指标、压测和更完善的异常处理。另外有些实现细节也需要修复，比如解码器里压缩类型字段使用错误、心跳消息判断不严谨、Protostuff 静态 Buffer 的线程安全问题。

### 51. 当前请求有没有超时控制？如何优化？

**高概率追问：是**

参考回答：

> 当前 `NettyRpcClient.sendRpcRequest()` 最后直接 `resultFuture.get()`，没有设置超时时间。如果服务端不返回，业务线程可能一直阻塞。优化方式是使用 `resultFuture.get(timeout, TimeUnit.MILLISECONDS)`，或者结合 `orTimeout()`，超时后从 `UnprocessedRequests` 中移除请求，并抛出明确的超时异常。

### 52. 如果调用失败，项目有没有重试机制？

**高概率追问：中**

参考回答：

> 当前没有完整重试机制。发送失败时只是关闭 Channel 并 `completeExceptionally()`。可以在客户端增加可配置的重试次数，并区分可重试异常和不可重试异常。重试时要避免对非幂等接口重复调用造成数据问题，所以最好支持接口级配置。

### 53. 心跳机制有什么问题？

**高概率追问：中**

参考回答：

> 当前心跳是基础版本。客户端写空闲时发送 `HEARTBEAT_REQUEST_TYPE`，但服务端 `NettyRpcServerHandler.channelRead()` 中判断的是 `HEARTBEAT_RESPONSE_TYPE`，语义上应该处理心跳请求并返回心跳响应。后续需要修正消息类型判断，并增加心跳失败计数、连接关闭、客户端重连和 Channel 缓存清理。

### 54. 服务注册节点为什么建议改成临时节点？

**高概率追问：中**

参考回答：

> 服务实例注册更适合临时节点。当前注册使用的是 `createPersistentNode()`，如果服务异常宕机，持久节点不会自动删除，客户端可能发现已失效地址。临时节点绑定 ZooKeeper 会话，服务进程断开后节点自动删除，更符合服务注册场景。项目里已有 `createEphemeralNode()` 方法，可以把注册逻辑改过去。

### 55. 协议解码器里有什么代码问题？

**高概率追问：中**

参考回答：

> `RpcMessageDecoder.decodeFrame()` 读取了 `compressType`，但获取压缩器时使用的是 `CompressTypeEnum.getName(codecType)`。从语义上看应该用 `compressType`。否则序列化类型 code 和压缩类型 code 不一致时会找错压缩器，导致解压失败。这是一个需要修复的协议层 bug。

### 56. Protostuff 序列化有什么线程安全问题？

**高概率追问：低**

参考回答：

> `ProtostuffSerializer` 里使用了 `private static final LinkedBuffer BUFFER`，这是所有线程共享的 Buffer。RPC 场景下多个线程可能同时序列化，如果共享 Buffer，可能出现数据错乱。更安全的做法是每次序列化创建局部 `LinkedBuffer`，或者用 `ThreadLocal<LinkedBuffer>`。

### 57. 当前项目如何增加熔断和限流？

**高概率追问：低**

参考回答：

> 可以在客户端代理调用前后增加治理层，按服务名维护调用统计。限流可以用令牌桶或滑动窗口限制 QPS；熔断可以统计一段时间内失败率，超过阈值后短时间拒绝调用或快速失败。实现位置可以放在 `RpcClientProxy.invoke()` 到 `NettyRpcClient.sendRpcRequest()` 之间，或者抽象成过滤器链。

### 58. 如果让你继续完善这个项目，你会怎么做？

**高概率追问：是**

参考回答：

> 我会按优先级做。第一，修复已知 bug，比如解码 compress 字段、心跳类型、`Holder.set()` 赋值问题和 Protostuff Buffer 线程安全问题。第二，加请求超时和未完成请求清理，避免业务线程无限阻塞。第三，把 ZooKeeper 服务节点改成临时节点，并完善节点监听和重连。第四，加失败重试、熔断限流和连接池。第五，补充单元测试、集成测试和压测数据，让项目更接近可用框架。

## 面试前必背问题

建议优先背熟这些：

- 第 1 题：项目介绍。
- 第 5 题：完整 RPC 调用链路。
- 第 8 题：请求和响应如何匹配。
- 第 10 题：Netty 服务端如何启动。
- 第 15 题：服务如何注册到 ZooKeeper。
- 第 20 题：为什么使用 JDK 动态代理。
- 第 24 题：`@RpcReference` 如何注入代理。
- 第 28 题：自定义协议头有哪些字段。
- 第 32 题：协议实现有什么可优化点。
- 第 41 题：自定义 SPI 如何加载扩展。
- 第 45 题：客户端如何处理并发请求。
- 第 50 题：项目不足。
- 第 58 题：后续如何完善。
