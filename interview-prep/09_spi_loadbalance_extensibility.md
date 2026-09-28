# 09 SPI LoadBalance Extensibility

## 一句话定位

这个 RPC 项目通过自定义 SPI 机制，把注册中心、服务发现、网络传输、序列化、压缩和负载均衡都设计成可插拔组件。框架代码面向接口编程，运行时再根据名称从 `META-INF/` 配置文件中加载具体实现。

面试时可以先这样说：

> 我在项目中实现了一个轻量级自定义 SPI，核心类是 `ExtensionLoader`。它要求扩展接口必须标注 `@SPI`，然后根据接口全限定名去 `META-INF/` 目录下读取配置文件，配置文件中是 `name=实现类全限定名` 的映射。框架运行时通过 `getExtension(name)` 获取具体实现，所以注册中心、服务发现、Netty 传输、序列化、压缩和负载均衡都可以通过新增实现类和配置文件来扩展。

## 核心类和配置文件

## SPI 核心

- `common.extension.SPI`
- `common.extension.ExtensionLoader`
- `common.extension.Holder`
- `common.factory.SingletonFactory`

## 扩展接口

- `simple.registry.ServiceRegistry`
- `simple.registry.ServiceDiscovery`
- `simple.remoting.transport.RpcRequestTransport`
- `simple.serialize.Serializer`
- `simple.compress.Compress`
- `simple.loadbalance.LoadBalance`

## 扩展配置文件

配置目录：

```text
rpc-framework-simple/src/main/resources/META-INF/
```

当前已有配置：

```text
simple.registry.ServiceRegistry
simple.registry.ServiceDiscovery
simple.remoting.transport.RpcRequestTransport
simple.serialize.Serializer
simple.compress.Compress
simple.loadbalance.LoadBalance
```

## ExtensionLoader 实现原理

`ExtensionLoader` 是项目自定义 SPI 的核心，它的职责是：

1. 校验扩展点是否合法。
2. 为每个扩展接口维护一个 `ExtensionLoader`。
3. 从 `META-INF/接口全限定名` 配置文件中读取扩展实现。
4. 根据名称选择具体实现类。
5. 创建扩展实例并做缓存。

## 1. 扩展接口必须加 @SPI

项目自定义了一个注解：

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
}
```

`ExtensionLoader.getExtensionLoader()` 会校验：

```java
if (!type.isInterface()) {
    throw new IllegalArgumentException("extension type should be an interface");
}

if (type.getAnnotation(SPI.class) == null) {
    throw new IllegalArgumentException("extension type should be annotated by @SPI");
}
```

也就是说，只有接口，并且接口上带 `@SPI`，才能作为扩展点。

面试表达：

> 这个校验可以避免框架误把普通类当成扩展点，扩展边界更清晰。

## 2. 为每个扩展接口缓存一个 ExtensionLoader

源码中有一个静态缓存：

```java
private static final Map<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS =
        new ConcurrentHashMap<>();
```

调用：

```java
ExtensionLoader.getExtensionLoader(Serializer.class)
```

时，会为 `Serializer` 接口创建并缓存一个专属的 `ExtensionLoader`。

面试表达：

> `EXTENSION_LOADERS` 是按扩展接口维度缓存的，比如 `Serializer`、`Compress`、`LoadBalance` 分别对应不同的加载器，避免重复创建加载器对象。

## 3. 根据接口全限定名定位配置文件

项目中扩展配置文件目录是：

```java
private static final String SERVICE_DIRECTORY = "META-INF/";
```

`loadDirectory()` 会拼接：

```java
String fileName = ExtensionLoader.SERVICE_DIRECTORY + type.getName();
```

比如：

```java
Serializer.class.getName()
```

得到：

```text
simple.serialize.Serializer
```

所以配置文件路径就是：

```text
META-INF/simple.serialize.Serializer
```

文件内容：

```text
kyro=simple.serialize.kyro.KryoSerializer
protostuff=simple.serialize.protostuff.ProtostuffSerializer
hessian=simple.serialize.hessian.HessianSerializer
```

## 4. 解析 name 到实现类的映射

`loadResource()` 会逐行读取配置文件。

核心规则是：

```text
扩展名=实现类全限定名
```

然后通过类加载器加载实现类：

```java
Class<?> clazz = classLoader.loadClass(clazzName);
extensionClasses.put(name, clazz);
```

最终得到：

```text
hessian -> HessianSerializer.class
gzip -> GzipCompress.class
loadBalanceNew -> ConsistentHashLoadBalanceNew.class
```

面试表达：

> 项目不是把所有实现都写死在代码里，而是通过配置文件建立扩展名和实现类的映射。运行时想用哪个实现，就通过扩展名选择。

## 5. 根据 name 获取具体扩展实例

典型调用：

```java
Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
        .getExtension(codecName);
```

`getExtension(name)` 的核心流程：

1. 校验 name 非空。
2. 从 `cachedInstances` 中查找实例缓存。
3. 没有缓存则进入同步块。
4. 调用 `createExtension(name)` 创建实例。
5. 将实例放入缓存。

源码中的实例缓存：

```java
private final Map<String, Holder<Object>> cachedInstances =
        new ConcurrentHashMap<>();
```

面试表达：

> `getExtension(name)` 类似一个按名称获取策略对象的工厂方法。第一次会根据配置文件反射创建对象，后续从缓存中拿，避免重复创建。

## 6. createExtension 创建实例

`createExtension(name)` 会先从扩展类映射中找到 class：

```java
Class<?> clazz = getExtensionClasses().get(name);
```

然后通过单例工厂创建实例：

```java
Object instance = SingletonFactory.getInstance(clazz);
```

这说明项目希望扩展对象是单例复用的。

面试表达：

> 扩展实现类最后是通过 `SingletonFactory` 反射创建的，框架希望同一个扩展实现在运行期只保留一个实例，减少对象创建开销。

## 7. 缓存设计

项目有三层缓存思路：

| 缓存 | 位置 | 作用 |
| --- | --- | --- |
| `EXTENSION_LOADERS` | `ExtensionLoader` 静态字段 | 缓存每个接口对应的扩展加载器 |
| `cachedClasses` | `ExtensionLoader` 实例字段 | 缓存某个接口下 name 到 Class 的映射 |
| `cachedInstances` | `ExtensionLoader` 实例字段 | 缓存某个 name 对应的实现对象 |

缓存配合 `ConcurrentHashMap` 和 `synchronized`，目标是保证并发场景下安全加载扩展。

## 当前实现中需要注意的问题

## Holder.set 存在明显 bug

当前源码中：

```java
public void set(T instance) {
    this.value = value;
}
```

这里应该是：

```java
this.value = instance;
```

否则 `Holder` 无法真正保存传入的实例。这个问题会影响：

- `ExtensionLoader.cachedClasses`
- `ExtensionLoader.cachedInstances`
- `SingletonFactory.OBJECT_MAP_NEW`

面试中可以这样讲：

> 我复盘 SPI 实现时发现 `Holder.set()` 有一个赋值错误，应该把参数 `instance` 赋给 `this.value`。这个问题会导致缓存失效，严重时会影响扩展对象创建。它属于实现层面的 bug，修复点很明确。

## createExtension 空判断顺序可以优化

当前代码：

```java
Class<?> clazz = getExtensionClasses().get(name);
log.info("准备创建扩展实例: {}", clazz.getName());
if (clazz == null) {
    throw new RuntimeException("扩展类不存在" + name);
}
```

如果 name 不存在，`clazz.getName()` 会先触发空指针。

更合理的是先判断 `clazz == null`，再打印日志。

## 自定义 SPI 和 Java 原生 SPI 的区别

Java 原生 SPI 使用：

```text
META-INF/services/接口全限定名
```

文件中通常直接写实现类全限定名：

```text
com.xxx.HessianSerializer
com.xxx.KryoSerializer
```

使用方式一般是：

```java
ServiceLoader.load(Serializer.class)
```

项目自定义 SPI 使用：

```text
META-INF/接口全限定名
```

文件内容是：

```text
name=实现类全限定名
```

使用方式是：

```java
ExtensionLoader.getExtensionLoader(Serializer.class)
        .getExtension("hessian");
```

## 对比表

| 对比点 | Java 原生 SPI | 项目自定义 SPI |
| --- | --- | --- |
| 配置目录 | `META-INF/services/` | `META-INF/` |
| 配置格式 | 实现类全限定名列表 | `name=实现类全限定名` |
| 获取方式 | 遍历所有实现 | 按 name 获取指定实现 |
| 是否支持命名选择 | 不直接支持 | 支持 |
| 是否校验 `@SPI` | 不需要 | 需要接口标注 `@SPI` |
| 实例缓存 | `ServiceLoader` 自身有懒加载机制 | 项目自己维护缓存 |
| 使用灵活性 | 标准、通用 | 更适合框架内部按配置选择策略 |

面试表达：

> Java 原生 SPI 更像是发现所有实现，然后调用方自己遍历选择。项目自定义 SPI 增加了 name 到实现类的映射，可以直接通过 `getExtension("hessian")` 获取指定实现，更适合 RPC 框架中按配置选择注册中心、序列化器、负载均衡策略这种场景。

## 项目中通过 SPI 扩展的组件

## 1. 注册中心 ServiceRegistry

接口：

```java
@SPI
public interface ServiceRegistry {
    void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress);
}
```

配置文件：

```text
META-INF/simple.registry.ServiceRegistry
```

当前配置：

```text
zk=simple.registry.Impl.ZKServiceRegistryImpl
```

使用位置：

```java
serviceRegistry = ExtensionLoader.getExtensionLoader(ServiceRegistry.class)
        .getExtension(ServiceRegistryEnum.ZK.getName());
```

所在类：

```text
simple.provider.Impl.ZKServiceProviderImpl
```

作用：

- 服务端发布服务时，不直接依赖 `ZKServiceRegistryImpl`。
- 通过 `ServiceRegistry` 接口隔离注册中心实现。
- 后续可以扩展 Nacos、Etcd、Consul。

## 2. 服务发现 ServiceDiscovery

接口：

```java
@SPI
public interface ServiceDiscovery {
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}
```

配置文件：

```text
META-INF/simple.registry.ServiceDiscovery
```

当前配置：

```text
zk=simple.registry.Impl.ZkServiceDiscoveryImpl
```

使用位置：

```java
this.serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
        .getExtension(ServiceDiscoveryEnum.ZK.getName());
```

所在类：

```text
simple.remoting.transport.netty.client.NettyRpcClient
```

作用：

- 客户端发起 RPC 前，先根据服务名从注册中心查找服务地址。
- 查到多个地址后，再交给负载均衡策略选择一个。

## 3. 网络传输 RpcRequestTransport

接口：

```java
@SPI
public interface RpcRequestTransport {
    Object sendRpcRequest(RpcRequest rpcRequest);
}
```

配置文件：

```text
META-INF/simple.remoting.transport.RpcRequestTransport
```

当前配置：

```text
netty=simple.remoting.transport.netty.client.NettyRpcClient
```

使用位置：

```java
this.rpcClient = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class)
        .getExtension(RpcRequestTransportEnum.NETTY.getName());
```

所在类：

```text
simple.spring.SpringBeanPostProcessor
```

作用：

- 客户端代理对象不关心底层网络通信方式。
- 当前使用 Netty。
- 后续可以扩展 Socket、HTTP、gRPC 等传输方式。

## 4. 序列化 Serializer

接口：

```java
@SPI
public interface Serializer {
    byte[] serialize(Object obj);
    <T> T deserialize(byte[] bytes, Class<T> clazz);
}
```

配置文件：

```text
META-INF/simple.serialize.Serializer
```

当前配置：

```text
kyro=simple.serialize.kyro.KryoSerializer
protostuff=simple.serialize.protostuff.ProtostuffSerializer
hessian=simple.serialize.hessian.HessianSerializer
```

使用位置：

- `RpcMessageEncoder`
- `RpcMessageDecoder`

作用：

- 根据协议头中的 `codec` 字段选择序列化器。
- 当前支持 Hessian、Kryo、Protostuff。
- 后续可以扩展 JSON、Fastjson2、Jackson、Avro 等。

## 5. 压缩 Compress

接口：

```java
@SPI
public interface Compress {
    byte[] compress(byte[] bytes);
    byte[] decompress(byte[] bytes);
}
```

配置文件：

```text
META-INF/simple.compress.Compress
```

当前配置：

```text
gzip=simple.compress.gzip.GzipCompress
```

使用位置：

- `RpcMessageEncoder`
- `RpcMessageDecoder`

作用：

- 根据协议头中的 `compress` 字段选择压缩器。
- 当前使用 Gzip。
- 后续可以扩展 Snappy、LZ4、Zstd。

## 6. 负载均衡 LoadBalance

接口：

```java
@SPI
public interface LoadBalance {
    String selectServiceAddress(List<String> serviceUrllist, RpcRequest rpcRequest);
}
```

配置文件：

```text
META-INF/simple.loadbalance.LoadBalance
```

当前配置：

```text
loadBalance=simple.loadbalance.loadbalancer.ConsistentHashLoadBalance
loadBalanceNew=simple.loadbalance.loadbalancer.ConsistentHashLoadBalanceNew
```

使用位置：

```java
this.loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class)
        .getExtension(LoadBalanceEnum.LOADBALANCENEW.getName());
```

所在类：

```text
simple.registry.Impl.ZkServiceDiscoveryImpl
```

作用：

- 服务发现返回多个服务地址时，选择一个目标服务节点。
- 当前默认使用 `loadBalanceNew`，也就是新版一致性 Hash 实现。

## 负载均衡整体设计

负载均衡接口：

```java
String selectServiceAddress(List<String> serviceUrllist, RpcRequest rpcRequest);
```

抽象父类：

```java
public abstract class AbstractLoadBalance implements LoadBalance
```

`AbstractLoadBalance` 做通用逻辑：

1. 服务地址为空，返回 `null`。
2. 只有一个地址，直接返回。
3. 多个地址时，调用子类的 `doSelect()`。

核心代码：

```java
if (CollectionUtil.isEmpty(serviceAddress)) {
    return null;
}
if (serviceAddress.size() == 1) {
    return serviceAddress.get(0);
}
return doSelect(serviceAddress, rpcRequest);
```

这种设计相当于模板方法模式：

- 父类处理通用边界。
- 子类只关注具体选择策略。

面试表达：

> 负载均衡这里用了模板方法模式。`AbstractLoadBalance` 处理空列表和单节点这种通用情况，真正多节点选择交给子类实现，比如随机、一致性 Hash、后续的轮询等。

## RandomLoadBalance 分析

实现类：

```text
simple.loadbalance.loadbalancer.RandomLoadBalance
```

核心逻辑：

```java
Random random = new Random();
return serviceAddresses.get(random.nextInt(serviceAddresses.size()));
```

特点：

- 实现简单。
- 每次随机选择一个服务地址。
- 长期来看请求会大致均匀分布。
- 不需要维护状态。

适合场景：

- 服务节点性能差不多。
- 请求量比较大，随机后整体比较均匀。
- 不关心同一个用户或同一个参数是否落到同一台机器。

不足：

- 短时间内可能不均匀。
- 不考虑机器性能差异。
- 不考虑服务节点当前负载。
- 当前项目的 `META-INF/simple.loadbalance.LoadBalance` 没有配置 `RandomLoadBalance`，所以这个类存在，但默认不能通过 SPI 名称直接获取。

如果要启用随机策略，需要补配置，例如：

```text
random=simple.loadbalance.loadbalancer.RandomLoadBalance
```

再增加枚举：

```java
RANDOM("random")
```

并在 `ZkServiceDiscoveryImpl` 中切换：

```java
.getExtension(LoadBalanceEnum.RANDOM.getName());
```

面试表达：

> 随机负载均衡实现成本最低，不需要维护额外状态，服务节点能力差不多时长期分布还可以。但它不能保证短期均匀，也不适合需要请求粘性的场景。

## ConsistentHashLoadBalance 分析

实现类：

```text
simple.loadbalance.loadbalancer.ConsistentHashLoadBalance
```

它的核心数据结构是：

```java
private final ConcurrentHashMap<String, ConsistentHashSelector> selectors =
        new ConcurrentHashMap<>();
```

含义：

- key：RPC 服务名。
- value：该服务对应的一致性 Hash 选择器。

每个服务维护一套自己的 Hash 环，避免不同服务之间互相影响。

## Hash 环

内部类：

```java
ConsistentHashSelector
```

核心字段：

```java
private final TreeMap<Long, String> virtualInvokers;
private final int identityHashCode;
```

`TreeMap<Long, String>` 用来模拟 Hash 环：

- key：虚拟节点的 hash 值。
- value：虚拟节点对应的真实服务地址。

## 虚拟节点

构造器中会为每个真实地址创建虚拟节点：

```java
for (String invoker : invokers) {
    for (int i = 0; i < replicaNumber / 4; i++) {
        byte[] digest = md5(invoker + i);
        for (int h = 0; h < 4; h++) {
            long m = hash(digest, h);
            virtualInvokers.put(m, invoker);
        }
    }
}
```

项目中 `replicaNumber` 是 160，也就是每个真实节点对应 160 个虚拟节点。

虚拟节点的意义：

- 让节点在 Hash 环上分布更均匀。
- 减少真实节点过少时的倾斜问题。
- 某个节点上下线时，只影响 Hash 环上的部分区间。

## 选择过程

选择服务时：

1. 根据请求 key 计算 MD5。
2. 从 MD5 摘要生成 hash 值。
3. 在 `TreeMap` 中找大于等于该 hash 的第一个虚拟节点。
4. 如果没找到，回到环的第一个节点。
5. 返回虚拟节点对应的真实服务地址。

核心代码：

```java
Map.Entry<Long, String> entry =
        virtualInvokers.tailMap(hashCode, true).firstEntry();

if (entry == null) {
    entry = virtualInvokers.firstEntry();
}

return entry.getValue();
```

## 服务列表变化检测

旧版 `ConsistentHashLoadBalance` 使用：

```java
int identityHashCode = System.identityHashCode(serviceAddresses);
```

来判断服务列表是否变化。

这个方式只能判断列表对象引用是否变化，不能严谨判断列表内容是否变化。

当前源码中还有一个明显问题：

```java
if (selectors == null || selector.identityHashCode != identityHashCode) {
}
```

这里应该判断：

```java
if (selector == null || selector.identityHashCode != identityHashCode) {
}
```

否则第一次访问某个服务时，`selector` 为空，后面访问 `selector.identityHashCode` 会空指针。

面试中可以这样讲：

> 旧版一致性 Hash 思路是对的：每个服务维护一个 Hash 环，用虚拟节点改善分布。但实现上还有优化点，比如第一次访问时应判断 `selector == null`，服务列表变化也不应该只靠 `identityHashCode`，更稳妥的是比较服务地址集合内容。

## ConsistentHashLoadBalanceNew 分析

项目当前默认使用：

```java
LoadBalanceEnum.LOADBALANCENEW
```

对应配置：

```text
loadBalanceNew=simple.loadbalance.loadbalancer.ConsistentHashLoadBalanceNew
```

新版实现相对旧版增加了：

- 每个服务缓存一个 `ConsistentHashingLoadBalancer`。
- 用 `physicalNodes` 保存真实节点集合。
- 用 `hasChanged()` 判断服务地址列表内容是否变化。
- 用 `reBuild()` 增量添加和删除节点。
- 用 `volatile initFlag` 表示 Hash 环是否处于可用状态。

核心字段：

```java
private final TreeMap<Long, String> virtualNodes = new TreeMap<>();
private final Set<String> physicalNodes = new HashSet<>();
private final int virtualNodeCount;
private final HashFunction hashFunction;
private volatile boolean initFlag = false;
```

## hasChanged

新版通过集合内容判断服务地址是否变化：

```java
if (address.size() != this.physicalNodes.size()) {
    return true;
}
for (String addr : address) {
    if (!this.physicalNodes.contains(addr)) {
        return true;
    }
}
return false;
```

这比旧版只比较 `identityHashCode` 更稳。

## reBuild

服务列表变化时，会计算：

- 待删除节点。
- 待新增节点。

然后分别调用：

```java
removeNode(r);
addNode(r);
```

这属于增量更新 Hash 环，而不是完全重建。

## 当前新版实现也有优化点

`ConsistentHashLoadBalanceNew` 中创建选择器时使用：

```java
SingletonFactory.getInstance(
    () -> new ConsistentHashingLoadBalancer(...),
    ConsistentHashingLoadBalancer.class
)
```

如果单例工厂按类维度缓存，那么多个服务可能会共享同一个 `ConsistentHashingLoadBalancer`，这和外层 `selectors` 按服务缓存的设计目标冲突。

更稳妥的方式：

- 直接 `new ConsistentHashingLoadBalancer(...)`。
- 或者单例 key 中加入 `rpcServiceName`。
- 或者把 `selectors.computeIfAbsent(rpcServiceName, ...)` 作为唯一缓存入口。

另外，当前选择 key 是：

```java
rpcServiceName + rpcRequest.getRequestId()
```

由于 `requestId` 每次请求都不同，这会更接近随机均衡，但不能保证同一参数请求落到同一节点。如果希望缓存命中或请求粘性，可以改成：

```java
rpcServiceName + methodName + stableHash(arguments)
```

面试表达：

> 新版一致性 Hash 比旧版更好的一点是能比较服务地址集合内容，并且支持增量更新 Hash 环。不过从负载均衡语义看，如果用随机 requestId 作为 Hash key，它更偏向均匀分散；如果想要同参数路由到同一节点，应该使用服务名、方法名和关键参数作为 Hash key。

## 一致性 Hash 在 RPC 负载均衡中的意义

普通 Hash 的问题：

```text
hash(key) % 节点数
```

当节点数变化时，比如 3 台变成 4 台，大量 key 的取模结果都会变化，导致大量请求重新路由。

一致性 Hash 的目标：

- 节点上下线时，只让一小部分请求迁移。
- 大部分请求仍然落到原来的节点。
- 减少服务节点变化带来的路由抖动。

在 RPC 中的意义：

1. 提高缓存命中率  
   如果服务端有本地缓存，同一个用户、同一个订单、同一个参数尽量落到同一节点，可以提高缓存命中。

2. 降低节点上下线影响  
   扩容、缩容或故障下线时，只迁移 Hash 环上一小段请求，避免全量请求重新分布。

3. 支持有状态或半有状态场景  
   虽然 RPC 服务最好无状态，但真实业务中可能有本地缓存、连接池预热、热点数据等，一致性 Hash 更稳定。

4. 配合虚拟节点改善均衡  
   虚拟节点可以减少真实节点数量少导致的流量倾斜。

面试表达：

> 一致性 Hash 最大的价值不是绝对均匀，而是稳定。节点上下线时，它只影响 Hash 环上相邻区间的请求，避免普通取模 Hash 那种大面积重映射。在 RPC 场景下，这对缓存命中、服务扩缩容和请求路由稳定性都有帮助。

## 整体扩展性分析

这个项目扩展性主要来自三个层面。

## 1. 面向接口编程

核心组件都有接口：

- `ServiceRegistry`
- `ServiceDiscovery`
- `RpcRequestTransport`
- `Serializer`
- `Compress`
- `LoadBalance`

调用方依赖接口，不直接依赖具体实现。

## 2. 自定义 SPI 做运行时装配

实现类不写死在业务流程中，而是通过：

```java
ExtensionLoader.getExtensionLoader(接口.class).getExtension(name)
```

来获取。

新增实现通常只需要：

1. 实现接口。
2. 在 `META-INF/接口全限定名` 中增加配置。
3. 增加枚举或配置项。
4. 在使用位置切换扩展名。

## 3. 协议字段支持扩展

自定义协议中有：

- `codec`
- `compress`
- `messageType`

这让序列化、压缩、消息类型都可以继续扩展。

例如新增 JSON 序列化时，不需要改协议结构，只要新增一个 codec 编码即可。

## 当前扩展性不足

项目目前还存在一些硬编码点：

- `ZKServiceProviderImpl` 构造器中写死 `ServiceRegistryEnum.ZK`。
- `NettyRpcClient` 构造器中写死 `ServiceDiscoveryEnum.ZK`。
- `SpringBeanPostProcessor` 构造器中写死 `RpcRequestTransportEnum.NETTY`。
- `ZkServiceDiscoveryImpl` 构造器中写死 `LoadBalanceEnum.LOADBALANCENEW`。
- 普通请求和响应中默认写死 Hessian + Gzip。

优化方向：

- 引入配置类，例如 `RpcConfig`。
- 支持配置文件指定注册中心、负载均衡、序列化、压缩和传输方式。
- 启动时读取配置，再传给 `ExtensionLoader`。
- 减少枚举和具体实现之间的硬编码。

面试表达：

> 目前项目已经把核心能力抽象成 SPI 接口，但具体选择还散落在构造器和枚举中。进一步优化可以做统一配置中心，比如 `rpc.registry=nacos`、`rpc.serializer=json`、`rpc.loadbalance=roundRobin`，启动时读取配置再通过 `ExtensionLoader` 装配。

## 扩展示例 1：扩展 Nacos 注册中心

目标：

把当前 ZooKeeper 注册中心扩展为 Nacos。

## 第一步：添加 Nacos 依赖

在 `pom.xml` 中添加 Nacos client 依赖。

示例：

```xml
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>
</dependency>
```

## 第二步：实现服务注册

新增类：

```text
simple.registry.Impl.NacosServiceRegistryImpl
```

实现接口：

```java
public class NacosServiceRegistryImpl implements ServiceRegistry {
    @Override
    public void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress) {
        // 连接 Nacos
        // 将 rpcServiceName、host、port 注册到 Nacos
    }
}
```

它对应服务端发布服务。

## 第三步：实现服务发现

新增类：

```text
simple.registry.Impl.NacosServiceDiscoveryImpl
```

实现接口：

```java
public class NacosServiceDiscoveryImpl implements ServiceDiscovery {
    private final LoadBalance loadBalance;

    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        // 根据 rpcRequest.getRpcServiceName() 从 Nacos 查询实例列表
        // 转换成 List<String> 地址列表
        // 使用 loadBalance.selectServiceAddress(...) 选择一个地址
        // 返回 InetSocketAddress
    }
}
```

它对应客户端发现服务。

## 第四步：更新 SPI 配置文件

修改：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.registry.ServiceRegistry
```

增加：

```text
nacos=simple.registry.Impl.NacosServiceRegistryImpl
```

修改：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.registry.ServiceDiscovery
```

增加：

```text
nacos=simple.registry.Impl.NacosServiceDiscoveryImpl
```

## 第五步：增加枚举

修改：

```text
common.enums.ServiceRegistryEnum
common.enums.ServiceDiscoveryEnum
```

增加：

```java
NACOS("nacos")
```

## 第六步：切换使用位置

当前服务端注册写在：

```text
simple.provider.Impl.ZKServiceProviderImpl
```

当前客户端发现写在：

```text
simple.remoting.transport.netty.client.NettyRpcClient
```

现有代码里写死的是：

```java
ServiceRegistryEnum.ZK.getName()
ServiceDiscoveryEnum.ZK.getName()
```

最简单的切换方式是改为：

```java
ServiceRegistryEnum.NACOS.getName()
ServiceDiscoveryEnum.NACOS.getName()
```

更好的方式是：

- 引入配置项 `rpc.registry=nacos`。
- `ZKServiceProviderImpl` 改名为更通用的 `DefaultServiceProviderImpl`。
- 构造器根据配置选择 `zk` 或 `nacos`。

面试表达：

> 扩展 Nacos 不需要改调用链主流程，只需要实现 `ServiceRegistry` 和 `ServiceDiscovery` 两个接口，并在 SPI 配置文件中增加 `nacos=实现类`。当前项目还需要把硬编码的 `ZK` 枚举切换成 `NACOS`，更好的优化是用配置文件决定注册中心类型。

## 扩展示例 2：扩展 JSON 序列化

目标：

新增 JSON 序列化方式，比如基于 Jackson。

## 第一步：添加 JSON 依赖

在 `pom.xml` 中添加 Jackson 或 Gson。

示例：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

## 第二步：实现 Serializer

新增类：

```text
simple.serialize.json.JsonSerializer
```

实现接口：

```java
public class JsonSerializer implements Serializer {
    @Override
    public byte[] serialize(Object obj) {
        // objectMapper.writeValueAsBytes(obj)
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        // objectMapper.readValue(bytes, clazz)
    }
}
```

## 第三步：更新 SPI 配置

修改：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.serialize.Serializer
```

增加：

```text
json=simple.serialize.json.JsonSerializer
```

## 第四步：更新枚举

修改：

```text
common.enums.SerializationTypeEnum
```

增加：

```java
JSON((byte) 0x04, "json")
```

## 第五步：切换协议 codec

当前普通 RPC 请求中使用：

```java
SerializationTypeEnum.HESSIAN.getCode()
```

位置：

- `NettyRpcClient.sendRpcRequest()`
- `NettyRpcServerHandler.channelRead()`

如果要默认使用 JSON，可以改成：

```java
SerializationTypeEnum.JSON.getCode()
```

更好的方式是让 codec 来自配置：

```text
rpc.serializer=json
```

然后构造 `RpcMessage` 时读取配置。

面试表达：

> 扩展 JSON 序列化只需要实现 `Serializer`，增加 SPI 配置，再给 `SerializationTypeEnum` 分配一个新的 codec 编码。因为协议头里已经有 codec 字段，所以接收方能根据 codec 自动找到 JSON 序列化器。

## 扩展示例 3：扩展轮询负载均衡

目标：

新增 RoundRobin 轮询策略。

## 第一步：新增实现类

新增类：

```text
simple.loadbalance.loadbalancer.RoundRobinLoadBalance
```

继承：

```java
public class RoundRobinLoadBalance extends AbstractLoadBalance
```

实现思路：

```java
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    private final ConcurrentHashMap<String, AtomicInteger> indexes =
            new ConcurrentHashMap<>();

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        String serviceName = rpcRequest.getRpcServiceName();
        AtomicInteger index = indexes.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int current = Math.abs(index.getAndIncrement());
        return serviceAddresses.get(current % serviceAddresses.size());
    }
}
```

注意点：

- 用服务名维度维护轮询下标。
- 用 `AtomicInteger` 保证并发安全。
- 需要处理 int 溢出，可以在溢出附近重置，或用 `Math.floorMod()`。

## 第二步：更新 SPI 配置

修改：

```text
rpc-framework-simple/src/main/resources/META-INF/simple.loadbalance.LoadBalance
```

增加：

```text
roundRobin=simple.loadbalance.loadbalancer.RoundRobinLoadBalance
```

## 第三步：更新枚举

修改：

```text
common.enums.LoadBalanceEnum
```

增加：

```java
ROUND_ROBIN("roundRobin")
```

## 第四步：切换负载均衡策略

当前在：

```text
simple.registry.Impl.ZkServiceDiscoveryImpl
```

写死：

```java
LoadBalanceEnum.LOADBALANCENEW.getName()
```

切换为：

```java
LoadBalanceEnum.ROUND_ROBIN.getName()
```

更好的方式是：

```text
rpc.loadbalance=roundRobin
```

启动时读取配置，再传给 `ExtensionLoader`。

面试表达：

> 轮询负载均衡可以作为新的 `LoadBalance` 实现接入。因为发现服务后统一调用 `loadBalance.selectServiceAddress()`，所以新增轮询策略不会影响注册中心、Netty 调用、代理层和协议层。

## 高频面试问题

## 1. 你这个项目的 SPI 是怎么实现的？

参考回答：

> 项目里有一个自定义 `ExtensionLoader`。它要求扩展接口必须加 `@SPI`，然后根据接口全限定名去 `META-INF/` 下找配置文件。配置文件中是 `name=实现类全限定名`。加载时先用类加载器读取配置文件，解析出 name 到 Class 的映射，再根据 `getExtension(name)` 创建对应实例并缓存。这样框架就可以通过名字选择具体实现，比如 `hessian`、`gzip`、`loadBalanceNew`。

高概率追问：

- 为什么不用 Java 原生 SPI？
- SPI 实例怎么缓存？
- 如果 name 不存在会怎样？

## 2. 自定义 SPI 和 Java 原生 SPI 有什么区别？

参考回答：

> Java 原生 SPI 使用 `META-INF/services/接口全限定名`，通常是加载一个接口的所有实现，然后调用方遍历。项目自定义 SPI 使用 `META-INF/接口全限定名`，并且配置格式是 `name=实现类`，所以可以直接按名称获取指定实现。对 RPC 框架来说，按名称选择更方便，比如配置使用 `zk` 注册中心、`hessian` 序列化、`gzip` 压缩。

高概率追问：

- 自定义 SPI 有什么缺点？
- Java SPI 会不会一次性加载所有实现？

## 3. 项目哪些地方用了 SPI？

参考回答：

> 主要有六类。第一是注册中心 `ServiceRegistry`，当前实现是 ZooKeeper。第二是服务发现 `ServiceDiscovery`，当前也是 ZooKeeper。第三是网络传输 `RpcRequestTransport`，当前是 Netty。第四是序列化 `Serializer`，支持 Hessian、Kryo、Protostuff。第五是压缩 `Compress`，当前是 Gzip。第六是负载均衡 `LoadBalance`，当前配置了一致性 Hash。

高概率追问：

- 服务提供者 `ServiceProvider` 为什么没有真正走 SPI？
- 如果要换 Nacos 改哪些地方？

## 4. 负载均衡是在哪个环节发生的？

参考回答：

> 负载均衡发生在客户端服务发现阶段。客户端通过 `ZkServiceDiscoveryImpl.lookupService()` 从 ZooKeeper 获取某个服务的全部可用地址，然后调用 `loadBalance.selectServiceAddress(serviceUrlList, rpcRequest)` 从多个地址中选一个，最后 Netty 客户端和选中的服务端建立或复用连接。

高概率追问：

- 为什么不在服务端做负载均衡？
- 如果服务节点下线，负载均衡怎么感知？

## 5. RandomLoadBalance 怎么实现？有什么问题？

参考回答：

> `RandomLoadBalance` 继承 `AbstractLoadBalance`，在多个服务地址中用 `Random.nextInt(size)` 随机选一个。它的优点是简单、无状态，长期请求量大时分布大致均匀。缺点是短时间内可能不均匀，也不考虑节点性能、当前负载和请求粘性。

高概率追问：

- 高并发下 `new Random()` 有没有优化空间？
- 随机和轮询怎么选？

## 6. 一致性 Hash 怎么实现？

参考回答：

> 项目用 `TreeMap` 模拟 Hash 环。每个真实服务节点会生成多个虚拟节点，把虚拟节点 hash 值作为 key，真实地址作为 value 放到 `TreeMap`。请求进来时，根据服务名和请求 key 算 hash，然后在 TreeMap 中找顺时针第一个虚拟节点，返回它对应的真实服务地址。如果超过最大 hash，就回到 TreeMap 第一个节点。

高概率追问：

- 为什么要虚拟节点？
- 节点上下线时为什么影响范围小？
- 一致性 Hash 一定比随机好吗？

## 7. 一致性 Hash 在 RPC 中有什么意义？

参考回答：

> 它的意义主要是路由稳定。普通取模 Hash 在节点数量变化时，大量请求都会重新路由；一致性 Hash 只影响 Hash 环上的一部分区间。在 RPC 场景下，如果服务端有本地缓存，或者希望同一类请求尽量落到同一节点，一致性 Hash 可以提高缓存命中，也能减少扩缩容带来的路由抖动。

高概率追问：

- 如果所有请求都用随机 requestId 做 Hash key，还能保证粘性吗？
- 一致性 Hash 会不会造成热点？

## 8. 如果让你扩展 Nacos，你会怎么做？

参考回答：

> 我会分别实现 `ServiceRegistry` 和 `ServiceDiscovery`。服务端实现 `NacosServiceRegistryImpl`，负责把服务名、IP、端口注册到 Nacos；客户端实现 `NacosServiceDiscoveryImpl`，根据服务名查询实例列表，并复用现有 `LoadBalance` 选择地址。然后在 `META-INF/simple.registry.ServiceRegistry` 和 `META-INF/simple.registry.ServiceDiscovery` 中增加 `nacos=实现类`，再增加对应枚举。最后把当前写死的 `zk` 改成配置化，比如 `rpc.registry=nacos`。

高概率追问：

- Nacos 和 ZooKeeper 注册模型有什么区别？
- 服务下线怎么处理？

## 9. 如果让你扩展 JSON 序列化，你会怎么做？

参考回答：

> 我会新增 `JsonSerializer` 实现 `Serializer` 接口，用 Jackson 或 Gson 完成对象和 byte 数组的转换。然后在 `META-INF/simple.serialize.Serializer` 增加 `json=JsonSerializer`，在 `SerializationTypeEnum` 中增加 `JSON((byte)0x04, "json")`。因为协议头有 codec 字段，只要客户端和服务端都识别这个 codec，就能自动通过 SPI 找到 JSON 序列化器。

高概率追问：

- JSON 和 Hessian/Kryo 相比有什么优缺点？
- JSON 反序列化泛型怎么处理？

## 10. 这个项目的扩展性还有哪些不足？

参考回答：

> 当前项目核心接口已经抽象出来，也有 SPI 装配能力，但还有一些硬编码，比如注册中心固定选 `zk`，负载均衡固定选 `loadBalanceNew`，序列化默认 Hessian，压缩默认 Gzip。后续可以引入统一配置类，把这些选择变成配置项。另外当前 `Holder.set()` 有赋值 bug，需要先修复，否则会影响缓存和单例逻辑。

高概率追问：

- 你会怎么设计配置文件？
- SPI 和 Spring Bean 管理怎么结合？

## 可背诵面试话术

> 这个 RPC 项目的扩展性主要是通过自定义 SPI 实现的。核心类是 `ExtensionLoader`，它要求扩展接口必须标注 `@SPI`，然后根据接口全限定名去 `META-INF/` 下读取配置文件，配置文件采用 `name=实现类全限定名` 的格式。运行时框架通过 `getExtension(name)` 获取具体实现，所以注册中心、服务发现、网络传输、序列化、压缩和负载均衡都可以按名称扩展。比如注册中心现在是 `zk`，序列化有 Hessian、Kryo、Protostuff，压缩是 Gzip，负载均衡是 `loadBalanceNew`。负载均衡这块抽象了 `LoadBalance` 接口和 `AbstractLoadBalance` 模板父类，当前有随机和一致性 Hash 思路。一致性 Hash 使用 `TreeMap` 模拟 Hash 环，并通过虚拟节点改善分布，它的价值是节点上下线时只影响一部分请求，适合需要路由稳定或服务端有本地缓存的 RPC 场景。如果要扩展 Nacos、JSON 序列化或轮询负载均衡，基本都是新增实现类、更新 SPI 配置、增加枚举或配置项，然后在使用位置切换扩展名。这个设计体现了面向接口编程和开闭原则。

## 面试加分回答

如果面试官继续追问项目优化，可以补充：

> 这个项目的扩展思路是完整的，但还有工程化优化空间。第一，`Holder.set()` 当前有赋值错误，会影响缓存逻辑，要优先修复。第二，很多扩展选择还是枚举硬编码，应该引入统一配置，比如 `rpc.registry`、`rpc.serializer`、`rpc.compress`、`rpc.loadbalance`。第三，负载均衡可以继续扩展加权随机、加权轮询、最少活跃调用数，并结合服务端健康状态和调用耗时做更智能的选择。第四，SPI 可以增加默认扩展、自适应扩展和扩展实例生命周期管理，让框架更接近生产级 RPC 框架。
