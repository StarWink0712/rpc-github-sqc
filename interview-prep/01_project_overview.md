# 01 Project Overview

## 项目一句话定位

这是一个基于 Netty 和 ZooKeeper 实现的轻量级 Java RPC 框架。它让客户端可以像调用本地接口一样调用远程服务，底层由框架完成服务注册发现、动态代理、网络通信、协议编解码、序列化压缩、负载均衡和响应匹配。

面试中可以这样定位：

> 我准备的是一个轻量级 RPC 框架项目，核心目标是理解并实现 Dubbo 这类 RPC 框架的基础能力。项目基于 Netty 做高性能网络通信，基于 ZooKeeper/Curator 做服务注册与发现，通过 Spring 自定义注解和 JDK 动态代理屏蔽远程调用细节，同时实现了自定义协议、序列化压缩、负载均衡和 SPI 扩展机制。

## 这个项目解决什么问题

在单体应用中，一个对象调用另一个对象的方法，直接通过 JVM 内存完成。但在分布式系统中，服务可能部署在不同进程甚至不同机器上，调用方不能直接拿到服务实现类对象。

这个项目解决的核心问题是：如何让客户端以本地接口调用的形式，调用远程服务端上的方法。

具体拆开看，它解决了这些问题：

- 服务端如何暴露服务：通过 `@RpcService` 标记服务实现类，并在 Spring Bean 生命周期中自动注册到 ZooKeeper。
- 客户端如何引用服务：通过 `@RpcReference` 标记字段，并自动注入 JDK 动态代理对象。
- 客户端如何找到服务端地址：通过 ZooKeeper 服务发现拿到可用服务节点列表。
- 多个服务节点如何选择一个：通过负载均衡策略选择目标地址。
- 请求如何跨网络传输：通过 Netty 建立连接、编码消息、发送请求、接收响应。
- Java 对象如何变成网络字节流：通过 Hessian/Kryo/Protostuff 序列化，再用 Gzip 压缩。
- 响应如何匹配请求：通过 `requestId` 和 `UnprocessedRequests` 维护未完成请求。
- 框架如何支持扩展：通过自定义 `@SPI` 和 `ExtensionLoader` 加载不同实现。

## 整体模块结构

项目是 Maven 多模块工程，根 `pom.xml` 中包含五个模块：

- `rpc-framework-common`
- `rpc-framework-simple`
- `hello-service-api`
- `example-server`
- `example-client`

### rpc-framework-common

这个模块放的是 RPC 框架的公共基础设施，主要包括枚举、异常、工具类、单例工厂和 SPI 扩展加载器。

核心内容：

- `common.enums`：定义框架中的各种类型枚举，比如序列化方式、压缩方式、负载均衡、注册中心、响应码、错误信息等。
- `common.exception`：封装 `RpcException`、`SerializeException`。
- `common.extension`：实现自定义 SPI 机制，包括 `@SPI`、`ExtensionLoader`、`Holder`。
- `common.factory.SingletonFactory`：提供单例对象缓存和反射创建能力。
- `common.utils`：提供集合、字符串、运行时 CPU 核心数、配置文件读取等工具。
- `common.utils.concurrent`：提供线程池配置、线程工厂创建和线程池关闭能力。

面试中可以这样讲：

> `rpc-framework-common` 是框架的基础模块，我把通用枚举、异常、工具类、SPI 扩展加载器和单例工厂放在这里，供核心 RPC 模块复用。这样可以避免核心模块里混杂大量通用代码，也方便后续扩展注册中心、序列化器、压缩器等组件。

### rpc-framework-simple

这是项目最核心的 RPC 框架模块，实现了服务注册发现、客户端代理、服务端处理、Netty 通信、自定义协议、序列化压缩、负载均衡和 Spring 注解接入。

核心包结构：

- `simple.annotation`：定义 `@RpcService`、`@RpcReference`、`@RpcScan`。
- `simple.spring`：通过 `CustomScannerRegistrar`、`CustomScanner`、`SpringBeanPostProcessor` 接入 Spring。
- `simple.proxy`：`RpcClientProxy` 基于 JDK 动态代理拦截接口方法调用。
- `simple.registry`：定义服务注册和服务发现接口。
- `simple.registry.Impl`：基于 ZooKeeper/Curator 实现服务注册和发现。
- `simple.provider`：服务提供者本地缓存和发布逻辑。
- `simple.remoting.transport.netty`：Netty 客户端、服务端、Handler、Channel 管理。
- `simple.remoting.transport.netty.codec`：自定义协议的编码器和解码器。
- `simple.remoting.dto`：定义 `RpcRequest`、`RpcResponse`、`RpcMessage`。
- `simple.serialize`：定义序列化接口及 Hessian、Kryo、Protostuff 实现。
- `simple.compress`：定义压缩接口及 Gzip 实现。
- `simple.loadbalance`：定义负载均衡接口、随机负载均衡、一致性 Hash 负载均衡。
- `simple.config`：服务配置和 JVM 关闭钩子。

面试中可以这样讲：

> `rpc-framework-simple` 是我实现 RPC 框架能力的核心模块。服务端启动后，它会扫描 `@RpcService` 服务并注册到 ZooKeeper；客户端启动后，它会扫描 `@RpcReference` 字段并注入代理对象。真正调用时，代理对象构造 `RpcRequest`，通过 Netty 客户端发送给服务端，服务端解码后根据服务名找到本地实现类，再通过反射调用目标方法并返回结果。

### hello-service-api

这个模块是服务 API 模块，定义客户端和服务端共同依赖的接口和传输对象。

核心内容：

- `HelloService`：远程服务接口，定义 `hello(Hello hello)` 方法。
- `Hello`：请求参数对象，包含 `message` 和 `description` 字段。

它的作用是让客户端和服务端依赖同一份接口契约：

- 服务端实现 `HelloService`。
- 客户端只依赖 `HelloService` 接口，不依赖服务端实现类。
- 动态代理也是基于这个接口生成代理对象。

面试中可以这样讲：

> `hello-service-api` 相当于 RPC 中的公共契约模块。服务提供方实现这里的接口，服务消费方依赖这里的接口。这样客户端不需要知道服务端实现类，只需要面向接口调用。

### example-server

这个模块是服务提供者示例，用来启动 RPC 服务端并暴露 `HelloService` 服务。

核心内容：

- `NettyServerMain`：服务端启动类，使用 `@SpringBootApplication` 和 `@RpcScan(basePackage = {"exampleServer"})`。
- `HelloServiceImpl`：服务实现类，标注 `@RpcService(group = "test1", version = "version1")` 和 Spring `@Service`。

启动流程大致是：

1. Spring Boot 启动服务端应用。
2. `@RpcScan` 触发自定义扫描器，扫描 RPC 服务和框架组件。
3. `SpringBeanPostProcessor` 在 Bean 初始化前发现 `@RpcService`。
4. 将 `HelloServiceImpl` 封装成 `RpcServiceConfig`。
5. `ZKServiceProviderImpl` 先放入本地 `serviceMap`，再注册到 ZooKeeper。
6. `NettyRpcServer.start()` 启动 Netty 服务端并监听客户端请求。

面试中可以这样讲：

> `example-server` 是服务提供方，里面的 `HelloServiceImpl` 是真实业务服务实现。它加上 `@RpcService` 后，框架会在 Spring 初始化阶段自动把服务注册到 ZooKeeper，并在本地缓存服务对象。之后客户端请求过来时，服务端可以根据服务名从本地缓存拿到实现类并反射调用。

### example-client

这个模块是服务消费者示例，用来启动 RPC 客户端并调用远程 `HelloService`。

核心内容：

- `NettyClientMain`：客户端启动类，使用 `@RpcScan(basePackage = {"exampleClient"})`。
- `HelloController`：模拟业务调用层，字段 `HelloService helloService` 标注 `@RpcReference(version = "version1", group = "test1")`。

调用流程大致是：

1. Spring Boot 启动客户端应用。
2. `SpringBeanPostProcessor` 在 Bean 初始化后扫描字段。
3. 发现 `@RpcReference` 后，为该字段创建 JDK 动态代理对象。
4. 调用 `helloService.hello(...)` 时，其实进入 `RpcClientProxy.invoke()`。
5. 代理对象构造 `RpcRequest`，通过 `NettyRpcClient` 发送请求。
6. 客户端从 ZooKeeper 发现服务地址，通过负载均衡选择一个服务端节点。
7. Netty 发送请求并等待响应，最终返回远程方法执行结果。

面试中可以这样讲：

> `example-client` 是服务消费方。业务代码只看到一个 `HelloService` 接口字段，并通过 `@RpcReference` 注入，调用时就像调用本地方法一样。实际底层由代理对象完成请求封装、服务发现、网络发送和响应解析。

## 核心技术栈总结

### Netty

项目使用 Netty 作为底层网络通信框架。

相关类：

- `NettyRpcServer`
- `NettyRpcServerHandler`
- `NettyRpcClient`
- `NettyRpcClientHandler`
- `RpcMessageEncoder`
- `RpcMessageDecoder`

服务端通过 `ServerBootstrap`、`NioEventLoopGroup`、`NioServerSocketChannel` 启动网络服务。客户端通过 `Bootstrap`、`NioSocketChannel` 建立连接。双方通过 `ChannelPipeline` 添加空闲检测、编解码器和业务处理器。

面试表达：

> 我用 Netty 负责客户端和服务端之间的长连接通信。服务端使用 bossGroup 接收连接、workerGroup 处理 IO，并额外使用 `DefaultEventExecutorGroup` 执行业务处理，避免反射调用等业务逻辑阻塞 Netty IO 线程。客户端通过 `ChannelProvider` 复用 Channel，减少频繁建连开销。

### ZooKeeper / Curator

项目使用 ZooKeeper 作为注册中心，使用 Curator 简化 ZooKeeper 客户端操作。

相关类：

- `ServiceRegistry`
- `ServiceDiscovery`
- `ZKServiceRegistryImpl`
- `ZkServiceDiscoveryImpl`
- `CuratorUtils`
- `ZKServiceProviderImpl`

服务端将服务名和地址注册到 ZooKeeper；客户端根据服务名查询可用节点，再通过负载均衡选出目标地址。

面试表达：

> ZooKeeper 在项目中承担注册中心角色。服务端启动后，把服务名和服务端地址写入 ZooKeeper；客户端调用前，根据 `RpcRequest` 中的服务名去 ZooKeeper 获取服务地址列表，并使用负载均衡选择一个节点。

### Spring 自定义注解

项目通过自定义注解降低使用成本。

相关注解：

- `@RpcService`：标记服务提供方实现类。
- `@RpcReference`：标记客户端需要注入的远程服务接口。
- `@RpcScan`：指定 RPC 框架扫描路径。

相关类：

- `CustomScannerRegistrar`
- `CustomScanner`
- `SpringBeanPostProcessor`

面试表达：

> 我把 RPC 框架和 Spring 做了集成。服务端只需要在实现类上加 `@RpcService`，客户端只需要在接口字段上加 `@RpcReference`。框架通过 `BeanPostProcessor` 在 Bean 初始化前后分别完成服务注册和代理注入，让业务代码不关心底层 RPC 细节。

### JDK 动态代理

项目使用 JDK 动态代理实现透明远程调用。

相关类：

- `RpcClientProxy`

核心逻辑：

- `getProxy()` 创建接口代理对象。
- `invoke()` 拦截方法调用。
- 根据接口名、方法名、参数、参数类型、group、version 构造 `RpcRequest`。
- 调用 `RpcRequestTransport.sendRpcRequest()` 发送请求。
- 校验 `RpcResponse` 后返回结果。

面试表达：

> 客户端调用远程接口时，其实调用的是 JDK 动态代理对象。代理对象拦截方法调用，把本地方法调用信息封装成 `RpcRequest`，交给 Netty 客户端发送。这样业务层就像调用本地方法一样调用远程服务。

### 自定义协议与编解码

项目自定义了 RPC 消息协议，消息头包含：

- magic number
- version
- full length
- message type
- codec
- compress
- requestId
- body

相关类：

- `RpcConstants`
- `RpcMessage`
- `RpcMessageEncoder`
- `RpcMessageDecoder`

项目使用 `LengthFieldBasedFrameDecoder` 根据消息长度字段解决 TCP 粘包半包问题。

面试表达：

> 我没有直接用 Java 对象传输，而是设计了一个简单的二进制协议。协议头中有魔数做协议校验，版本号做兼容控制，长度字段解决粘包半包，消息类型区分请求、响应和心跳，codec 和 compress 用来选择序列化和压缩方式。

### 序列化与压缩

项目抽象了序列化和压缩接口：

- `Serializer`
- `Compress`

已有实现：

- Hessian：`HessianSerializer`
- Kryo：`KryoSerializer`
- Protostuff：`ProtostuffSerializer`
- Gzip：`GzipCompress`

序列化器和压缩器通过 SPI 扩展加载。

面试表达：

> RPC 需要把 Java 对象转成网络字节流，所以我抽象了 `Serializer` 接口，并实现了 Hessian、Kryo、Protostuff 几种序列化方式。编码时先序列化对象，再用 Gzip 压缩；解码时先解压，再反序列化成 `RpcRequest` 或 `RpcResponse`。

### SPI 扩展机制

项目实现了自定义 SPI 机制。

相关类：

- `@SPI`
- `ExtensionLoader`
- `Holder`
- `SingletonFactory`

扩展配置文件放在：

- `META-INF/simple.registry.ServiceRegistry`
- `META-INF/simple.registry.ServiceDiscovery`
- `META-INF/simple.remoting.transport.RpcRequestTransport`
- `META-INF/simple.loadbalance.LoadBalance`
- `META-INF/simple.serialize.Serializer`
- `META-INF/simple.compress.Compress`

面试表达：

> 我参考 Java SPI 和 Dubbo SPI 的思想，实现了一个简单的扩展加载器。接口上标注 `@SPI`，配置文件中维护扩展名到实现类的映射，运行时通过 `ExtensionLoader.getExtension(name)` 加载具体实现。这样注册中心、负载均衡、序列化、压缩、传输层都可以替换。

### 负载均衡

项目提供了负载均衡抽象：

- `LoadBalance`
- `AbstractLoadBalance`

实现类：

- `RandomLoadBalance`
- `ConsistentHashLoadBalance`
- `ConsistentHashLoadBalanceNew`

客户端服务发现拿到多个服务地址后，通过负载均衡选择目标节点。

面试表达：

> 服务发现可能返回多个服务提供者地址，所以我抽象了负载均衡接口。简单场景可以随机选择，进阶场景使用一致性 Hash。通过虚拟节点把请求尽量均匀分散到不同服务节点，并在节点上下线时减少请求迁移。

### 并发与异步处理

项目中有几处并发设计：

- `CompletableFuture`：把 Netty 异步发送和响应回调转换成客户端同步等待结果。
- `UnprocessedRequests`：用 `ConcurrentHashMap` 保存 `requestId -> CompletableFuture`。
- `ChannelProvider`：用 `ConcurrentHashMap` 缓存服务地址到 Channel 的映射，实现连接复用。
- `ZKServiceProviderImpl`：用 `ConcurrentHashMap` 和线程安全 Set 保存本地服务。
- `ThreadPoolFactoryUtil`：统一创建和关闭线程池。
- `NettyRpcServer`：使用 `DefaultEventExecutorGroup` 独立处理业务逻辑。

面试表达：

> 客户端发送请求后不能只靠调用顺序匹配响应，因为网络响应可能乱序返回。所以我给每个请求生成 `requestId`，发送前把 `requestId` 和 `CompletableFuture` 放入未完成请求表。客户端 Handler 收到响应后，根据响应里的 `requestId` 找到对应 Future 并完成它，业务线程就能拿到远程调用结果。

## 项目核心流程概览

### 服务端启动流程

1. 启动 `example-server` 的 `NettyServerMain`。
2. `@RpcScan` 触发自定义扫描器，扫描 `exampleServer` 包下的 `@RpcService`。
3. Spring 初始化 Bean 时，`SpringBeanPostProcessor.postProcessBeforeInitialization()` 识别服务实现类。
4. 封装 `RpcServiceConfig`，包含服务对象、group、version。
5. `ZKServiceProviderImpl.publishService()` 将服务先加入本地 `serviceMap`。
6. `ZKServiceRegistryImpl.registerService()` 通过 Curator 把服务路径注册到 ZooKeeper。
7. `NettyRpcServer.start()` 启动 Netty 服务端，监听端口 `9998`。

### 客户端调用流程

1. 启动 `example-client` 的 `NettyClientMain`。
2. Spring 初始化 `HelloController`。
3. `SpringBeanPostProcessor.postProcessAfterInitialization()` 发现 `@RpcReference` 字段。
4. 创建 `RpcClientProxy`，并注入 `HelloService` 的代理对象。
5. 业务代码调用 `helloService.hello(...)`。
6. JDK 动态代理进入 `RpcClientProxy.invoke()`。
7. 构造 `RpcRequest`，包含接口名、方法名、参数、参数类型、requestId、group、version。
8. `NettyRpcClient` 通过 ZooKeeper 服务发现目标地址。
9. 通过负载均衡选择服务节点。
10. 获取或建立 Netty Channel，编码并发送 `RpcMessage`。
11. 服务端解码请求，本地查找服务对象，反射调用目标方法。
12. 服务端封装 `RpcResponse`，编码后返回。
13. 客户端 Handler 根据 `requestId` 完成对应 Future，代理对象返回结果。

## 面试重点怎么讲

这个项目最适合围绕三层来讲：

### 第一层：用户视角

客户端只需要写：

```java
@RpcReference(version = "version1", group = "test1")
private HelloService helloService;
```

服务端只需要写：

```java
@RpcService(group = "test1", version = "version1")
public class HelloServiceImpl implements HelloService {
}
```

用户像调用本地方法一样调用 `helloService.hello(...)`。

### 第二层：框架视角

框架做了四件事：

- 服务端自动注册服务。
- 客户端自动注入代理。
- 调用时自动发现服务地址并发起网络请求。
- 服务端收到请求后自动调用本地实现并返回结果。

### 第三层：底层技术视角

底层涉及：

- Netty 网络通信
- ZooKeeper 注册发现
- JDK 动态代理
- Spring Bean 生命周期扩展
- 自定义协议和编解码
- 序列化和压缩
- 负载均衡
- SPI 扩展
- CompletableFuture 请求响应匹配

## 1 分钟项目介绍

我这个项目是一个基于 Netty 和 ZooKeeper 的轻量级 RPC 框架，主要目标是实现像 Dubbo 一样的基础远程调用能力。服务端通过 `@RpcService` 暴露服务，框架在 Spring Bean 初始化阶段自动把服务注册到 ZooKeeper；客户端通过 `@RpcReference` 引用服务，框架会注入一个 JDK 动态代理对象。业务代码调用接口方法时，代理对象会把方法调用封装成 `RpcRequest`，通过 ZooKeeper 发现服务地址，经过负载均衡选择一个节点，再使用 Netty 按自定义协议发送请求。服务端收到请求后解码，找到本地服务对象，通过反射调用目标方法，再把结果封装成 `RpcResponse` 返回。项目里还实现了自定义 SPI、序列化压缩、连接复用、请求响应匹配和一致性 Hash 负载均衡。

## 3 分钟项目介绍

我准备的项目是一个轻量级 RPC 框架，主要是为了理解分布式服务之间远程调用的底层原理。它的使用方式比较接近 Dubbo：服务提供方只需要在实现类上加 `@RpcService`，服务消费方只需要在接口字段上加 `@RpcReference`，之后就可以像调用本地方法一样调用远程服务。

从模块上看，项目分成五个模块。`hello-service-api` 定义公共服务接口和传输对象；`example-server` 是服务提供方示例，实现并暴露 `HelloService`；`example-client` 是服务消费方示例，调用远程接口；`rpc-framework-common` 放公共枚举、异常、工具类、单例工厂和自定义 SPI；`rpc-framework-simple` 是核心框架模块，包含注册发现、代理、Netty 通信、协议编解码、序列化压缩、负载均衡和 Spring 集成。

服务端启动时，`@RpcScan` 会触发自定义扫描器，扫描带有 `@RpcService` 的服务实现类。然后 `SpringBeanPostProcessor` 在 Bean 初始化前识别这些服务，把服务对象、版本号、分组封装成 `RpcServiceConfig`。框架会先把服务对象放到本地 `ConcurrentHashMap` 缓存中，然后通过 Curator 把服务名和服务端地址注册到 ZooKeeper。最后 `NettyRpcServer` 启动，监听客户端请求。

客户端启动时，`SpringBeanPostProcessor` 会在 Bean 初始化后扫描字段，如果发现 `@RpcReference`，就为这个接口字段创建一个 JDK 动态代理对象并注入进去。业务代码调用接口方法时，会进入 `RpcClientProxy.invoke()`，代理对象把接口名、方法名、参数、参数类型、group、version 和 requestId 封装成 `RpcRequest`。然后 `NettyRpcClient` 根据服务名到 ZooKeeper 查询可用服务地址，通过负载均衡选出一个节点，复用或新建 Netty Channel，把请求封装成 `RpcMessage` 后编码发送。

网络通信层我使用 Netty 实现。协议层自定义了消息头，包括魔数、版本号、消息总长度、消息类型、序列化方式、压缩方式和请求编号。其中长度字段配合 `LengthFieldBasedFrameDecoder` 解决 TCP 粘包半包问题。消息体会先经过 Hessian、Kryo 或 Protostuff 序列化，再通过 Gzip 压缩。服务端收到消息后解码成 `RpcRequest`，根据服务名从本地缓存获取服务对象，再通过反射调用目标方法，最后封装成 `RpcResponse` 返回给客户端。

另外，项目还实现了一个自定义 SPI 机制，用 `@SPI` 标记可扩展接口，通过 `META-INF` 下的配置文件把扩展名映射到实现类。注册中心、服务发现、传输方式、序列化、压缩、负载均衡都可以通过这个机制替换。并发方面，客户端用 `CompletableFuture` 和 `UnprocessedRequests` 维护未完成请求，根据 `requestId` 匹配响应，避免并发请求乱序返回时结果错配。

如果面试官问项目亮点，我会重点讲四个方面：第一是通过 Spring 注解和动态代理实现透明远程调用；第二是基于 Netty 自定义协议实现高性能通信；第三是通过 ZooKeeper 实现服务注册发现和节点变化监听；第四是通过 SPI、序列化、压缩和负载均衡增强框架扩展性。

## 可以重点准备的追问方向

- 为什么 RPC 要使用动态代理？
- `@RpcService` 和 `@RpcReference` 分别在 Spring 生命周期哪个阶段处理？
- 服务名为什么要由接口名、group、version 组成？
- Netty 服务端的 bossGroup、workerGroup、业务线程组分别负责什么？
- `LengthFieldBasedFrameDecoder` 为什么能解决粘包半包？
- ZooKeeper 在服务注册发现中扮演什么角色？
- 客户端如何根据 `requestId` 匹配响应？
- 为什么要抽象 Serializer、Compress、LoadBalance 这些接口？
- 一致性 Hash 负载均衡相比随机负载均衡有什么优势？
- 这个项目还有哪些不足，后续会如何优化？

## 个人表达建议

面试时不要一上来陷入所有类名细节，可以按这个顺序讲：

1. 先讲项目目标：实现轻量级 RPC 框架。
2. 再讲使用方式：服务端 `@RpcService`，客户端 `@RpcReference`。
3. 然后讲核心链路：代理封装请求、服务发现、Netty 发送、服务端反射调用、响应返回。
4. 最后讲技术亮点：Netty、自定义协议、ZooKeeper、SPI、序列化压缩、负载均衡、并发请求匹配。

推荐收尾：

> 这个项目虽然是学习型 RPC 框架，但我不是只写了一个网络 demo，而是把 RPC 框架中比较关键的链路都串了一遍，包括服务注册发现、动态代理、协议编解码、序列化、负载均衡和扩展机制。通过这个项目，我对分布式服务调用的底层原理、Netty 网络编程和 Spring 扩展点都有了比较系统的理解。
