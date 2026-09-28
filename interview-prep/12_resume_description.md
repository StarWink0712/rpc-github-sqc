# 12 Resume Description

## 使用建议

项目名称建议统一使用：

```text
基于 Netty 和 ZooKeeper 的轻量级 RPC 框架
```

简历中不要写“生产级”“高可用治理平台”“完整熔断限流”等当前项目没有真正实现的内容。这个项目更适合定位为：

- 学习并实现 RPC 框架核心原理。
- 能讲清楚远程调用完整链路。
- 能展开 Netty、ZooKeeper、动态代理、自定义协议、SPI、序列化压缩、负载均衡和并发请求匹配。

下面提供 3 个版本，可以根据简历篇幅选择。

## 版本一：保守真实版

## 项目简介

基于 Netty 和 ZooKeeper 实现的轻量级 Java RPC 框架，支持服务提供方注册服务、服务消费方发现服务，并通过动态代理让客户端像调用本地接口一样调用远程服务。项目主要用于学习和实践 RPC 框架的核心流程，包括服务注册发现、网络通信、协议编解码、序列化压缩、负载均衡和请求响应匹配。

## 技术栈

Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、自定义注解、自定义 SPI、Hessian、Kryo、Protostuff、Gzip、Maven

## 个人职责

- 设计并实现 RPC 服务端和客户端基础调用链，完成从代理调用、请求封装、网络发送到服务端反射执行和响应返回的完整流程。
- 基于 `@RpcService`、`@RpcReference`、`@RpcScan` 和 `BeanPostProcessor` 实现服务自动注册和远程代理对象自动注入。
- 使用 Netty 实现客户端和服务端通信，编写自定义编解码器，基于协议长度字段处理 TCP 粘包半包问题。
- 基于 ZooKeeper/Curator 实现服务注册与发现，客户端通过服务名获取可用服务地址，并结合负载均衡策略选择目标节点。
- 实现 Hessian、Kryo、Protostuff 等序列化方式和 Gzip 压缩，并通过自定义 SPI 支持组件扩展。
- 使用 `CompletableFuture` 和 requestId 维护未完成请求表，实现异步响应与请求的匹配。

## 核心亮点

- 通过 Spring 扩展点和 JDK 动态代理屏蔽远程调用细节，降低业务接入成本。
- 自定义 RPC 通信协议，协议头包含魔数、版本号、消息长度、消息类型、序列化方式、压缩方式和请求编号。
- 使用 `LengthFieldBasedFrameDecoder` 根据消息长度字段解决 TCP 粘包半包问题。
- 使用 ZooKeeper 作为注册中心，实现服务注册、服务发现和节点监听。
- 抽象注册中心、服务发现、网络传输、序列化、压缩和负载均衡接口，并通过自定义 SPI 加载具体实现。
- 使用 `ConcurrentHashMap` 管理本地服务缓存、Channel 缓存和未完成请求表，保证并发场景下的数据安全。

## 适合放简历的写法

```text
基于 Netty 和 ZooKeeper 的轻量级 RPC 框架
技术栈：Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、自定义 SPI、Hessian/Kryo/Protostuff、Gzip

项目简介：实现一个轻量级 Java RPC 框架，支持服务注册发现、远程代理调用、自定义协议、序列化压缩、负载均衡和请求响应匹配，使客户端可以像调用本地接口一样调用远程服务。

个人职责：
1. 基于 Spring 自定义注解和 BeanPostProcessor，实现 @RpcService 服务注册和 @RpcReference 远程代理注入。
2. 基于 JDK 动态代理封装远程方法调用，构造 RpcRequest，并通过 Netty 客户端发送请求。
3. 基于 Netty 实现服务端和客户端通信，设计自定义协议并使用 LengthFieldBasedFrameDecoder 解决粘包半包。
4. 基于 ZooKeeper/Curator 实现服务注册与发现，并结合负载均衡策略选择服务实例。
5. 实现自定义 SPI，支持序列化、压缩、负载均衡、注册中心等组件扩展。
6. 使用 CompletableFuture、requestId 和 ConcurrentHashMap 实现异步响应匹配和未完成请求管理。
```

## 版本二：面试突出亮点版

## 项目简介

基于 Netty 和 ZooKeeper 从零实现的轻量级 RPC 框架，目标是模拟 Dubbo 类 RPC 框架的核心能力。框架支持服务自动注册与发现、注解式远程服务引用、动态代理调用、自定义协议通信、序列化压缩、负载均衡和 SPI 扩展机制。客户端只需通过 `@RpcReference` 引用接口，即可像调用本地方法一样完成远程服务调用。

## 技术栈

Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、BeanPostProcessor、自定义注解、自定义通信协议、自定义 SPI、Hessian、Kryo、Protostuff、Gzip、CompletableFuture、ConcurrentHashMap、Maven

## 个人职责

- 负责 RPC 框架核心链路设计与实现，打通客户端代理调用、服务发现、Netty 通信、服务端反射执行和响应回写流程。
- 设计 `@RpcService`、`@RpcReference`、`@RpcScan` 等注解，并结合 Spring `BeanPostProcessor` 实现服务端服务发布和客户端代理注入。
- 实现 `RpcClientProxy`，基于 JDK 动态代理拦截接口方法调用，自动构造包含接口名、方法名、参数类型、group、version 和 requestId 的 `RpcRequest`。
- 基于 Netty 实现 RPC 客户端和服务端，使用 boss/worker 线程组处理网络事件，并通过 `DefaultEventExecutorGroup` 隔离服务端业务处理逻辑。
- 设计固定协议头加可变 body 的自定义通信协议，并实现 `RpcMessageEncoder`、`RpcMessageDecoder` 完成消息编解码。
- 基于 ZooKeeper/Curator 实现服务注册发现，服务端启动时发布服务地址，客户端调用前根据服务名拉取可用节点并执行负载均衡。
- 实现自定义 SPI 扩展加载机制，支持按名称加载注册中心、服务发现、网络传输、序列化、压缩和负载均衡实现。
- 使用 `CompletableFuture` 和 `UnprocessedRequests` 管理未完成请求，实现 Netty 异步响应到同步方法返回的转换。

## 核心亮点

- 注解式接入：服务端使用 `@RpcService` 暴露服务，客户端使用 `@RpcReference` 注入代理对象，屏蔽远程调用细节。
- 自定义协议：协议头包含 magic number、version、full length、message type、codec、compress、requestId，具备可扩展性。
- 粘包半包处理：基于 `LengthFieldBasedFrameDecoder` 使用 full length 字段拆分完整消息帧。
- 服务注册发现：基于 ZooKeeper/Curator 实现服务地址注册、客户端发现和节点变化监听。
- 扩展机制：自定义 `@SPI` 和 `ExtensionLoader`，通过 `META-INF/接口全限定名` 配置文件加载扩展实现。
- 并发请求匹配：使用 requestId + `ConcurrentHashMap` + `CompletableFuture` 处理并发请求和乱序响应。
- 连接复用：使用 `ChannelProvider` 缓存服务地址与 Netty Channel 映射，减少重复建连成本。
- 负载均衡：实现随机和一致性 Hash 负载均衡策略，客户端从多个服务节点中选择目标地址。

## 适合放简历的写法

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

## 版本三：简洁压缩版

## 项目简介

实现一个基于 Netty 和 ZooKeeper 的轻量级 RPC 框架，支持注解式服务注册与引用、自定义协议通信、服务发现、序列化压缩、负载均衡和异步响应匹配，让客户端可以像调用本地接口一样调用远程服务。

## 技术栈

Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、自定义 SPI、Hessian/Kryo/Protostuff、Gzip、CompletableFuture

## 个人职责

- 基于 Spring 注解和 `BeanPostProcessor` 实现 `@RpcService` 服务注册和 `@RpcReference` 代理注入。
- 基于 JDK 动态代理实现远程方法调用拦截，封装 `RpcRequest` 并发送到服务端。
- 基于 Netty 实现客户端和服务端通信，设计自定义协议并完成编解码。
- 基于 ZooKeeper/Curator 实现服务注册发现，并支持负载均衡选择服务节点。
- 基于自定义 SPI 支持序列化、压缩、负载均衡等组件扩展。
- 基于 `CompletableFuture`、requestId 和 `ConcurrentHashMap` 实现请求响应匹配。

## 核心亮点

- 注解式 RPC 调用，客户端无需感知底层网络通信。
- 自定义协议 + Netty 编解码，支持消息类型、序列化和压缩扩展。
- ZooKeeper 服务注册发现，支持 group/version 区分服务。
- SPI 扩展机制，降低组件耦合。
- CompletableFuture 异步转同步，支持并发请求响应匹配。

## 适合放简历的写法

```text
基于 Netty 和 ZooKeeper 的轻量级 RPC 框架
技术栈：Java、Spring、Netty、ZooKeeper、Curator、JDK 动态代理、自定义 SPI、Hessian/Kryo/Protostuff、Gzip

项目简介：实现轻量级 RPC 框架，支持服务注册发现、注解式远程调用、自定义协议、序列化压缩、负载均衡和响应匹配，使客户端能够像调用本地接口一样调用远程服务。

个人职责：
1. 基于 @RpcService、@RpcReference 和 BeanPostProcessor 实现服务注册与代理注入。
2. 基于 JDK 动态代理封装 RpcRequest，并通过 Netty 完成请求发送和响应接收。
3. 设计自定义协议，使用 LengthFieldBasedFrameDecoder 解决粘包半包问题。
4. 基于 ZooKeeper/Curator 实现服务注册发现，并结合负载均衡选择服务节点。
5. 实现自定义 SPI，支持序列化、压缩、负载均衡等组件扩展。
6. 使用 CompletableFuture 和 requestId 实现并发请求下的响应匹配。
```

## 简历使用建议

## 如果你的简历项目空间较多

建议使用“面试突出亮点版”。

它更适合 Java 开发工程师面试，因为能引导面试官追问：

- Netty 线程模型。
- 自定义协议。
- ZooKeeper 服务注册发现。
- JDK 动态代理。
- Spring BeanPostProcessor。
- SPI 扩展机制。
- CompletableFuture 响应匹配。

## 如果你担心被深挖太多

建议使用“保守真实版”。

它表达更稳，不会给人“生产级 RPC 框架”的错觉，适合当前项目真实完成度。

## 如果简历篇幅很紧

建议使用“简洁压缩版”。

它可以放在一页简历中，重点保留项目名称、技术栈、简介和 5 到 6 条职责。

## 不建议写的表达

下面这些表达当前项目支撑不足，不建议写进简历：

- 实现生产级 RPC 框架。
- 支持完善的熔断、限流、降级。
- 支持高可用服务治理。
- 支持完整连接池管理。
- 支持请求超时和自动重试。
- 支持服务端优雅上下线。
- 支持完善监控告警体系。

如果面试时被问，可以诚实说这些是后续优化方向。

## 面试简历追问准备

如果简历写了“自定义协议”，要能回答：

> 协议头有哪些字段？`LengthFieldBasedFrameDecoder` 参数为什么是 `(MAX, 5, 4, -9, 0)`？

如果简历写了“动态代理”，要能回答：

> `RpcClientProxy` 的 `invoke()` 做了什么？为什么只能代理接口？

如果简历写了“服务注册发现”，要能回答：

> 服务名怎么生成？group/version 有什么作用？服务节点下线怎么感知？

如果简历写了“SPI 扩展”，要能回答：

> `ExtensionLoader` 如何根据 name 加载实现？和 Java 原生 SPI 有什么区别？

如果简历写了“CompletableFuture 响应匹配”，要能回答：

> 客户端收到乱序响应时，怎么找到对应的业务线程？
