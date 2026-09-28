# 06 ZooKeeper Registry

## 一句话定位

这个项目使用 ZooKeeper 作为注册中心，使用 Curator 作为 ZooKeeper 客户端工具。服务端启动时把服务名和服务地址注册到 ZooKeeper；客户端发起 RPC 调用前，根据服务名从 ZooKeeper 查询服务地址列表，再通过负载均衡选择一个服务端节点。

面试可以先这样说：

> ZooKeeper 在项目中承担服务注册与发现的角色。服务端扫描到 `@RpcService` 后，会把服务对象先放入本地 `serviceMap`，再把服务名和服务地址注册到 ZooKeeper；客户端调用远程方法时，根据接口名、group、version 生成服务名，到 ZooKeeper 查询可用服务地址，再通过负载均衡选出一个节点进行 Netty 调用。

## 核心类总览

| 类 / 接口 | 角色 |
| --- | --- |
| `ServiceRegistry` | 服务注册接口，定义注册中心注册能力 |
| `ServiceDiscovery` | 服务发现接口，定义客户端查找服务地址能力 |
| `ZKServiceRegistryImpl` | ZooKeeper 服务注册实现 |
| `ZkServiceDiscoveryImpl` | ZooKeeper 服务发现实现 |
| `CuratorUtils` | Curator 工具类，封装 ZK 客户端、节点创建、节点查询、监听、清理 |
| `ZKServiceProviderImpl` | 服务提供者管理类，维护本地服务缓存并发布服务 |
| `RpcServiceConfig` | 服务配置，封装服务对象、group、version，并生成服务名 |
| `CustomShutdownHook` | JVM 关闭钩子，用于服务下线清理和线程池关闭 |

## 服务端如何注册服务

### 入口：服务实现类标注 @RpcService

示例服务实现：

```java
@RpcService(group = "test1", version = "version1")
@Service
@Primary
public class HelloServiceImpl implements HelloService {
}
```

涉及类：

- `exampleServer.serviceImpl.HelloServiceImpl`
- `simple.annotation.RpcService`

`@RpcService` 的作用：

- 标记这个类是 RPC 服务提供者。
- 指定服务分组 `group`。
- 指定服务版本 `version`。

### Spring BeanPostProcessor 自动发现服务

核心方法：

- `SpringBeanPostProcessor.postProcessBeforeInitialization()`

流程：

1. Spring 创建 Bean。
2. Bean 初始化前进入 `postProcessBeforeInitialization()`。
3. 判断当前 Bean 的类上是否有 `@RpcService`。
4. 如果有，读取注解中的 group/version。
5. 构造 `RpcServiceConfig`：

```java
RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
        .group(rpcService.group())
        .version(rpcService.version())
        .service(bean)
        .build();
```

6. 调用：

```java
serviceProvider.publishService(rpcServiceConfig);
```

### ZKServiceProviderImpl 发布服务

核心类和方法：

- `ZKServiceProviderImpl.publishService()`
- `ZKServiceProviderImpl.addService()`

`publishService()` 做两件事：

1. 把服务加入服务端本地缓存。
2. 把服务地址注册到 ZooKeeper。

代码逻辑：

```java
String host = InetAddress.getLocalHost().getHostAddress();
this.addService(rpcServiceConfig);
serviceRegistry.registerService(
        rpcServiceConfig.getRpcServiceName(),
        new InetSocketAddress(host, NettyRpcServer.PORT)
);
```

为什么先 `addService()` 再注册 ZooKeeper：

- `addService()` 会把真实服务对象放进本地 `serviceMap`。
- 服务注册到 ZooKeeper 后，客户端可能马上发现并调用。
- 如果本地服务对象还没准备好，服务端收到请求会找不到服务。
- 所以先本地就绪，再对外发布，这是更合理的顺序。

### ZKServiceRegistryImpl 注册到 ZooKeeper

核心类和方法：

- `ServiceRegistry.registerService()`
- `ZKServiceRegistryImpl.registerService()`
- `CuratorUtils.createPersistentNode()`

`ServiceRegistry` 是服务注册接口：

```java
@SPI
public interface ServiceRegistry {
    void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress);
}
```

`ZKServiceRegistryImpl` 是 ZooKeeper 实现：

```java
String servicePath = CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString();
CuratorFramework zkClient = CuratorUtils.getZkClient();
CuratorUtils.createPersistentNode(zkClient, servicePath);
```

注册路径格式：

```text
/my-rpc/{rpcServiceName}{host:port}
```

示例大致类似：

```text
/my-rpc/helloserviceapi.HelloServicetest1version1/192.168.x.x:9998
```

注意：当前代码是字符串拼接：

```java
CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString()
```

从设计上更清晰的路径应该是：

```text
/my-rpc/{rpcServiceName}/{host:port}
```

面试时可以说当前项目实现的是学习型版本，路径结构还能进一步规范。

### CuratorUtils 创建节点

核心方法：

- `CuratorUtils.getZkClient()`
- `CuratorUtils.createPersistentNode()`

`getZkClient()`：

- 从 `rpc.properties` 读取 `rpc.zookeeper.address`。
- 如果没有配置，使用默认地址 `127.0.0.1:2181`。
- 使用 `ExponentialBackoffRetry` 重试策略。
- 通过 `CuratorFrameworkFactory.builder()` 创建 Curator 客户端。
- 调用 `zkClient.start()` 启动客户端。
- 最多等待 30 秒连接 ZooKeeper。

`createPersistentNode()`：

- 如果节点已存在，不重复创建。
- 如果不存在，调用：

```java
zkClient.create()
        .creatingParentsIfNeeded()
        .withMode(CreateMode.PERSISTENT)
        .forPath(path);
```

- 把已注册路径加入 `REGISTERED_PATH_SET`，方便后续关闭时清理。

## 客户端如何发现服务

### 入口：NettyRpcClient 发送请求前查找服务地址

核心方法：

- `NettyRpcClient.sendRpcRequest()`

发送请求前：

```java
InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
```

这里的 `serviceDiscovery` 是通过 SPI 加载的：

```java
this.serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
        .getExtension(ServiceDiscoveryEnum.ZK.getName());
```

配置文件：

```text
META-INF/simple.registry.ServiceDiscovery
```

配置内容：

```text
zk=simple.registry.Impl.ZkServiceDiscoveryImpl
```

### ServiceDiscovery 接口

核心接口：

```java
@SPI
public interface ServiceDiscovery {
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}
```

它的职责：

- 根据 `RpcRequest` 中的服务信息查找服务地址。
- 返回一个可调用的 `InetSocketAddress`。

### ZkServiceDiscoveryImpl 查找服务

核心方法：

- `ZkServiceDiscoveryImpl.lookupService()`

流程：

1. 根据 `rpcRequest.getRpcServiceName()` 获取服务名。
2. 调用 `CuratorUtils.getZkClient()` 获取 ZooKeeper 客户端。
3. 调用 `CuratorUtils.getChildrenNodes(zkClient, rpcServiceName)` 获取服务地址列表。
4. 如果地址列表为空，抛出 `SERVICE_CAN_NOT_BE_FOUND`。
5. 调用负载均衡器选择一个服务地址：

```java
String targetServiceUrl = loadBalance.selectServiceAddress(serviceUrlList, rpcRequest);
```

6. 将 `host:port` 拆分成 host 和 port。
7. 返回 `new InetSocketAddress(host, port)`。

### CuratorUtils 查询服务节点

核心方法：

- `CuratorUtils.getChildrenNodes()`
- `CuratorUtils.registerWatcher()`

`getChildrenNodes()` 逻辑：

1. 先查本地缓存 `SERVICE_ADDRESS_MAP`。
2. 如果缓存中已有服务地址列表，直接返回。
3. 如果没有缓存，从 ZooKeeper 读取：

```java
zkClient.getChildren().forPath(servicePath);
```

4. 将读取结果放入 `SERVICE_ADDRESS_MAP`。
5. 调用 `registerWatcher()` 注册子节点监听器。

`registerWatcher()` 使用：

- `PathChildrenCache`
- `PathChildrenCacheListener`

监听逻辑：

- 当服务路径下的子节点变化时，重新读取子节点列表。
- 更新 `SERVICE_ADDRESS_MAP`。

面试表达：

> 客户端发现服务时不会每次都直接访问 ZooKeeper。项目里用 `SERVICE_ADDRESS_MAP` 做本地缓存，第一次查询时从 ZooKeeper 拉取地址列表，并注册 `PathChildrenCache` 监听器。后续如果服务节点变化，监听器会重新拉取地址并更新缓存。

## ServiceRegistry 和 ServiceDiscovery 的区别

### ServiceRegistry

角色：

- 服务注册接口。
- 用于服务端。
- 负责把服务名和服务地址写入注册中心。

当前实现：

- `ZKServiceRegistryImpl`

调用位置：

- `ZKServiceProviderImpl.publishService()`

面试表达：

> `ServiceRegistry` 站在服务提供者角度，负责把服务发布到注册中心。

### ServiceDiscovery

角色：

- 服务发现接口。
- 用于客户端。
- 负责根据请求中的服务名查找服务地址。

当前实现：

- `ZkServiceDiscoveryImpl`

调用位置：

- `NettyRpcClient.sendRpcRequest()`

面试表达：

> `ServiceDiscovery` 站在服务消费者角度，负责从注册中心查询可用服务地址。

## 服务名如何生成

服务名是服务注册和服务发现能够对上的关键。

### 服务端生成服务名

类：

- `RpcServiceConfig`

方法：

```java
public String getRpcServiceName() {
    return this.getServiceName() + this.getGroup() + this.getVersion();
}
```

`getServiceName()`：

```java
return this.service.getClass().getInterfaces()[0].getCanonicalName();
```

服务端通过服务对象实现的接口名，加上 group/version，生成服务名。

### 客户端生成服务名

类：

- `RpcRequest`

方法：

```java
public String getRpcServiceName() {
    return this.getInterfaceName() + this.getGroup() + this.getVersion();
}
```

客户端通过代理对象构造 `RpcRequest` 时，填入：

- `interfaceName`
- `group`
- `version`

最终生成和服务端一致的服务名。

### group/version 的作用

group：

- 区分同一个接口的不同分组。
- 比如测试环境、灰度环境、不同业务组。

version：

- 区分同一个接口的不同版本。
- 比如 `v1` 和 `v2` 同时存在，客户端按版本调用。

示例：

服务端：

```java
@RpcService(group = "test1", version = "version1")
public class HelloServiceImpl implements HelloService
```

客户端：

```java
@RpcReference(version = "version1", group = "test1")
private HelloService helloService;
```

只有客户端和服务端 group/version 对齐，生成的服务名才一致。

面试表达：

> 服务名由接口全限定名、group 和 version 拼接。这样同一个接口可以有多个不同分组或版本的实现，客户端通过注解指定 group/version，就能精确调用目标服务。

## serviceMap 和 registeredService 的意义

这两个字段都在 `ZKServiceProviderImpl` 中。

### serviceMap

定义：

```java
private final Map<String, Object> serviceMap;
```

初始化：

```java
serviceMap = new ConcurrentHashMap<>();
```

作用：

- 保存服务名到真实服务对象的映射。
- 服务端收到 RPC 请求后，根据 `rpcRequest.getRpcServiceName()` 从这里找服务对象。
- 找到服务对象后，`RpcRequestHandler` 才能反射调用目标方法。

调用链：

```text
RpcRequestHandler.handle()
    -> serviceProvider.getService(rpcRequest.getRpcServiceName())
    -> ZKServiceProviderImpl.getService()
    -> serviceMap.get(rpcServiceName)
```

面试表达：

> ZooKeeper 只告诉客户端服务地址，不负责真正执行方法。服务端真正执行方法时，要从本地 `serviceMap` 里根据服务名找到服务实现对象。

### registeredService

定义：

```java
private final Set<String> registeredService;
```

初始化：

```java
registeredService = ConcurrentHashMap.newKeySet();
```

作用：

- 保存已经处理过的服务名。
- 避免同一个服务重复加入本地缓存和重复注册。

`addService()` 中逻辑：

```java
if (registeredService.contains(rpcServiceName)) {
    return;
}
registeredService.add(rpcServiceName);
serviceMap.put(rpcServiceName, rpcServiceConfig.getService());
```

面试表达：

> `registeredService` 用来做去重，避免同一个服务重复注册。`serviceMap` 用来保存真正的服务实例，供服务端收到请求后执行本地方法。

## 临时节点、服务下线和 JVM 关闭钩子

### 当前项目使用的是持久节点

当前注册代码：

- `ZKServiceRegistryImpl.registerService()`
- `CuratorUtils.createPersistentNode()`

使用：

```java
CreateMode.PERSISTENT
```

也就是说，当前服务实例节点是持久节点。

持久节点特点：

- 创建后不会因为客户端会话断开自动删除。
- 服务进程异常退出后，节点可能仍然留在 ZooKeeper。
- 客户端可能发现已经不可用的服务地址。

### 更推荐使用临时节点

`CuratorUtils` 中已经有：

```java
createEphemeralNode()
```

使用：

```java
CreateMode.EPHEMERAL
```

临时节点特点：

- 和 ZooKeeper 会话绑定。
- 服务进程宕机或连接断开后，节点自动删除。
- 更适合服务实例注册。

面试表达：

> 当前项目注册服务时使用的是持久节点，这是学习阶段的实现。生产级注册中心更适合用临时节点，因为服务实例下线或宕机后，ZooKeeper 会自动删除临时节点，客户端不会继续发现失效地址。项目里已经有 `createEphemeralNode()` 方法，后续可以把服务实例注册逻辑改成临时节点。

### JVM 关闭钩子

核心类：

- `CustomShutdownHook`

注册位置：

- `NettyRpcServer.start()`

调用：

```java
CustomShutdownHook.getCustomShutdownHook().clearAll();
```

关闭钩子逻辑：

1. 调用 `Runtime.getRuntime().addShutdownHook()` 注册线程。
2. JVM 关闭时，获取当前服务端地址。
3. 调用：

```java
CuratorUtils.clearRegistry(CuratorUtils.getZkClient(), inetSocketAddress);
```

4. 删除当前服务实例注册过的节点。
5. 调用 `ThreadPoolFactoryUtil.shutDownAllThreadPool()` 关闭线程池。

### CuratorUtils.clearRegistry()

核心逻辑：

- 遍历 `REGISTERED_PATH_SET`。
- 如果路径以当前服务实例地址结尾，则从 ZooKeeper 删除。

```java
if (p.endsWith(inetSocketAddress.toString())) {
    zkClient.delete().forPath(p);
}
```

面试表达：

> 项目里除了可以用临时节点自动下线之外，还写了 JVM 关闭钩子。服务端启动时注册关闭钩子，JVM 正常退出时会根据当前服务地址清理自己注册过的 ZooKeeper 节点，并关闭线程池。这个能处理正常关闭场景，但异常宕机时还是临时节点更可靠。

## 当前注册发现机制的优点

- 服务注册和服务发现通过接口抽象：`ServiceRegistry`、`ServiceDiscovery`。
- ZooKeeper 实现通过 SPI 加载，后续可以替换为 Nacos、Etcd。
- 服务端先本地缓存服务，再注册到 ZooKeeper，顺序合理。
- 客户端服务发现后使用本地缓存，减少 ZooKeeper 访问。
- 通过 `PathChildrenCache` 监听节点变化，能更新本地服务地址缓存。
- group/version 支持服务分组和版本隔离。
- JVM 关闭钩子能处理正常服务下线。

## 当前注册发现机制的不足和优化

### 1. 服务实例注册应优先使用临时节点

现状：

- 当前使用 `createPersistentNode()`。

风险：

- 服务异常宕机后，持久节点不会自动删除。

优化：

- 使用 `CreateMode.EPHEMERAL` 注册服务实例节点。
- 或者根节点持久，服务实例子节点临时。

### 2. 注册路径结构可以更规范

现状：

```java
ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString()
```

优化：

```text
/my-rpc/{rpcServiceName}/{host:port}
```

好处：

- 服务名和实例地址层级更清晰。
- 客户端 `getChildren()` 获取实例列表更自然。

### 3. 需要考虑客户端缓存一致性

现状：

- `SERVICE_ADDRESS_MAP` 做本地缓存。
- 通过 `PathChildrenCache` 更新。

优化：

- 启动监听时使用初始缓存构建模式。
- 处理 ZooKeeper 重连后的重新订阅。
- 服务发现失败时可以主动刷新缓存。

### 4. 服务下线场景可以更完整

现状：

- JVM 正常关闭时清理节点。
- 异常宕机依赖不够强，因为当前注册是持久节点。

优化：

- 临时节点自动删除。
- 客户端监听节点删除事件。
- Channel 失败时配合服务地址刷新。

### 5. 注册中心配置可以更完善

现状：

- 默认地址是 `127.0.0.1:2181`。
- 从 `rpc.properties` 读取 `rpc.zookeeper.address`。

优化：

- 支持多个 ZooKeeper 地址。
- 支持命名空间。
- 支持会话超时、连接超时配置。

## 面试高频问题

### 1. 服务端如何注册服务到 ZooKeeper？

**高概率追问：是**

参考回答：

> 服务端实现类加了 `@RpcService`。Spring 初始化 Bean 时，`SpringBeanPostProcessor.postProcessBeforeInitialization()` 会识别这个注解，读取 group/version，并把当前 Bean 封装成 `RpcServiceConfig`。然后调用 `ZKServiceProviderImpl.publishService()`，它会先 `addService()` 把服务对象放进本地 `serviceMap`，再调用 `ZKServiceRegistryImpl.registerService()`，通过 Curator 在 ZooKeeper 的 `/my-rpc` 路径下创建服务节点。

### 2. 客户端如何从 ZooKeeper 发现服务？

**高概率追问：是**

参考回答：

> 客户端发送请求前，`NettyRpcClient.sendRpcRequest()` 会调用 `serviceDiscovery.lookupService(rpcRequest)`。实际实现是 `ZkServiceDiscoveryImpl.lookupService()`。它根据 `rpcRequest.getRpcServiceName()` 生成服务名，然后通过 `CuratorUtils.getChildrenNodes()` 查询 ZooKeeper 中该服务下的地址列表，最后通过负载均衡选出一个地址并返回 `InetSocketAddress`。

### 3. `ServiceRegistry` 和 `ServiceDiscovery` 有什么区别？

**高概率追问：是**

参考回答：

> `ServiceRegistry` 是服务注册接口，站在服务提供方角度，把服务名和服务地址注册到注册中心；`ServiceDiscovery` 是服务发现接口，站在服务消费方角度，根据 `RpcRequest` 中的服务名去注册中心查找可用服务地址。当前它们的 ZooKeeper 实现分别是 `ZKServiceRegistryImpl` 和 `ZkServiceDiscoveryImpl`。

### 4. 服务名是怎么生成的？

**高概率追问：是**

参考回答：

> 服务名由接口全限定名、group、version 拼接生成。服务端在 `RpcServiceConfig.getRpcServiceName()` 中通过服务对象实现的接口名加 group/version 生成；客户端在 `RpcRequest.getRpcServiceName()` 中通过代理请求里的 interfaceName 加 group/version 生成。只要客户端和服务端 group/version 对齐，就能找到同一个服务节点。

### 5. group 和 version 有什么作用？

**高概率追问：是**

参考回答：

> group 用来做服务分组，version 用来做服务版本隔离。比如同一个 `HelloService` 可以有测试组和正式组，也可以有 v1 和 v2 两个版本。客户端通过 `@RpcReference` 指定 group/version，服务端通过 `@RpcService` 指定 group/version，双方匹配后才会调用到对应服务。

### 6. 为什么要有 serviceMap？

**高概率追问：是**

参考回答：

> ZooKeeper 只负责服务地址注册和发现，不保存真实服务对象。服务端收到 RPC 请求后，需要根据服务名找到本地 Java 对象并反射调用，所以 `ZKServiceProviderImpl` 用 `serviceMap` 保存 `rpcServiceName -> serviceObject`。`RpcRequestHandler.handle()` 会通过 `serviceProvider.getService()` 从这个 Map 里取服务对象。

### 7. registeredService 有什么意义？

**高概率追问：中**

参考回答：

> `registeredService` 是一个线程安全 Set，用来保存已经注册过的服务名。`addService()` 里会先判断服务名是否已存在，如果存在就直接返回，避免同一个服务重复注册或重复放入 `serviceMap`。

### 8. 为什么服务端要先加入本地缓存再注册到 ZooKeeper？

**高概率追问：中**

参考回答：

> 因为服务一旦注册到 ZooKeeper，客户端就可能马上发现并调用。如果服务端本地 `serviceMap` 还没有准备好，收到请求后会找不到服务对象。所以当前项目在 `publishService()` 里先调用 `addService()`，再调用 `registerService()`，保证服务对外可见前，本地已经可以处理请求。

### 9. 当前项目使用持久节点还是临时节点？

**高概率追问：中**

参考回答：

> 当前注册服务时调用的是 `CuratorUtils.createPersistentNode()`，也就是持久节点。这是当前项目的实现现状。更推荐的生产级做法是服务实例使用临时节点，因为临时节点会随着 ZooKeeper 会话断开自动删除，能避免服务宕机后客户端还发现失效地址。

### 10. JVM 关闭钩子有什么作用？

**高概率追问：中**

参考回答：

> `CustomShutdownHook` 会在服务端启动时注册 JVM 关闭钩子。JVM 正常退出时，钩子线程会获取当前服务端地址，然后调用 `CuratorUtils.clearRegistry()` 删除自己注册过的 ZooKeeper 节点，并关闭线程池。它主要解决正常下线时清理注册信息的问题，但异常宕机还是更依赖临时节点机制。

### 11. 客户端地址缓存如何更新？

**高概率追问：中**

参考回答：

> `CuratorUtils.getChildrenNodes()` 第一次从 ZooKeeper 查询地址后，会把结果放入 `SERVICE_ADDRESS_MAP` 本地缓存，并调用 `registerWatcher()` 注册 `PathChildrenCache` 监听器。当该服务路径下子节点变化时，监听器会重新读取子节点列表，并更新本地缓存。

### 12. 如果 ZooKeeper 挂了，RPC 调用还能继续吗？

**高概率追问：低**

参考回答：

> 如果客户端本地已经缓存了服务地址，短时间内可能还能继续调用已有服务节点。但如果缓存为空、服务节点变化、或者需要发现新服务，就会失败。生产中一般会做本地缓存容灾、注册中心集群、失败重试、缓存过期策略和服务地址降级。

### 13. 这个注册中心机制还有什么不足？

**高概率追问：是**

参考回答：

> 主要有几个不足。第一，服务实例当前用持久节点注册，异常宕机后节点不会自动消失，应该改成临时节点。第二，注册路径拼接可以更规范，建议使用 `/my-rpc/{serviceName}/{host:port}` 结构。第三，客户端本地缓存和监听机制还比较基础，需要考虑 ZK 重连后的重新订阅。第四，服务下线后还需要配合客户端 Channel 清理、失败重试和节点刷新。

## 面试话术：ZooKeeper 注册发现怎么讲

可以直接背这一段：

> 这个项目里 ZooKeeper 主要用来做服务注册和发现。服务端启动时，Spring 会扫描带有 `@RpcService` 的服务实现类，在 `SpringBeanPostProcessor.postProcessBeforeInitialization()` 中把服务对象、group 和 version 封装成 `RpcServiceConfig`，然后调用 `ZKServiceProviderImpl.publishService()`。这个方法会先把服务放入本地 `serviceMap`，保证服务端本地已经可以处理请求，再通过 `ZKServiceRegistryImpl` 把服务名和服务地址注册到 ZooKeeper。
>
> 客户端调用远程服务前，`NettyRpcClient` 会调用 `ZkServiceDiscoveryImpl.lookupService()`。这个方法根据 `RpcRequest` 中的接口名、group、version 生成服务名，然后通过 `CuratorUtils.getChildrenNodes()` 从 ZooKeeper 查询服务地址列表。查到多个地址后，再交给负载均衡器选择一个节点，最后返回 `InetSocketAddress` 给 Netty 客户端建立连接并发送请求。
>
> 服务名的生成规则是接口全限定名加 group 加 version。group 用来做服务分组，version 用来做版本隔离，这样同一个接口可以存在多个版本或分组。服务端本地的 `serviceMap` 保存服务名到真实服务对象的映射，收到 RPC 请求后会根据服务名找到对象并反射调用。`registeredService` 则用来避免重复注册。
>
> 当前项目还实现了本地地址缓存和 ZooKeeper 子节点监听，减少每次调用都访问 ZooKeeper。服务下线方面，项目有 JVM 关闭钩子，正常退出时会清理注册节点。不过当前注册使用的是持久节点，生产环境更推荐服务实例使用临时节点，这样服务异常宕机后 ZooKeeper 能自动删除节点，客户端就不会继续发现失效服务。
