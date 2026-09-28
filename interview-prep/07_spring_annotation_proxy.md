# 07 Spring Annotation And Proxy

## 一句话定位

这个项目通过 Spring 自定义注解和 JDK 动态代理，让用户可以像调用本地方法一样调用远程 RPC 服务。

核心机制是：

- 服务端：`@RpcService` 标记服务实现类，`SpringBeanPostProcessor` 在 Bean 初始化前自动注册服务。
- 客户端：`@RpcReference` 标记远程服务字段，`SpringBeanPostProcessor` 在 Bean 初始化后自动注入 JDK 动态代理对象。
- 调用时：JDK 动态代理拦截接口方法调用，构造 `RpcRequest`，通过 Netty 发送远程请求。

面试时可以先这样说：

> 我在项目中通过 Spring 扩展点和 JDK 动态代理实现了透明 RPC 调用。服务端只需要在实现类上加 `@RpcService`，客户端只需要在接口字段上加 `@RpcReference`。Spring 启动时，`BeanPostProcessor` 会自动完成服务注册和代理注入。客户端调用接口方法时，实际进入代理对象的 `invoke()` 方法，由代理对象封装请求并发起远程调用。

## 三个核心注解

## @RpcService

### 作用

`@RpcService` 用在服务提供方的实现类上，表示这个类需要作为 RPC 服务暴露出去。

示例：

```java
@RpcService(group = "test1", version = "version1")
@Service
@Primary
public class HelloServiceImpl implements HelloService {
}
```

### 注解定义

核心属性：

```java
String version() default "";
String group() default "";
```

元注解：

- `@Retention(RetentionPolicy.RUNTIME)`：运行时可通过反射读取。
- `@Target({ElementType.TYPE})`：只能标注在类上。
- `@Inherited`：允许被子类继承。
- `@Documented`：生成文档时包含该注解。

### 在项目中的意义

服务端启动时，框架会扫描 `@RpcService` 标注的 Bean，并读取 group/version。

然后把当前 Bean 封装为：

```java
RpcServiceConfig
```

再调用：

```java
serviceProvider.publishService(rpcServiceConfig);
```

完成：

- 服务对象加入本地 `serviceMap`。
- 服务名和地址注册到 ZooKeeper。

### 面试表达

> `@RpcService` 是服务提供方注解，用来标记某个 Spring Bean 是 RPC 服务实现。框架会在 Bean 初始化前识别这个注解，读取 group 和 version，把服务对象注册到本地缓存和 ZooKeeper。

## @RpcReference

### 作用

`@RpcReference` 用在服务消费方字段上，表示这个字段需要注入远程服务代理对象。

示例：

```java
@RpcReference(version = "version1", group = "test1")
private HelloService helloService;
```

### 注解定义

核心属性：

```java
String version() default "";
String group() default "";
```

元注解：

- `@Retention(RetentionPolicy.RUNTIME)`：运行时可以读取字段注解。
- `@Target({ElementType.FIELD})`：只能标注在字段上。
- `@Inherited`
- `@Documented`

### 在项目中的意义

客户端 Spring Bean 初始化后，框架会扫描字段：

```java
Field[] declaredFields = targetClass.getDeclaredFields();
```

如果字段上有 `@RpcReference`：

1. 读取 group/version。
2. 构造 `RpcServiceConfig`。
3. 创建 `RpcClientProxy`。
4. 调用 `getProxy()` 创建 JDK 动态代理。
5. 通过反射注入字段。

### 面试表达

> `@RpcReference` 是服务消费方注解，用来告诉框架这个字段不是普通本地 Bean，而是远程服务引用。框架会在 Bean 初始化后为这个字段创建一个 JDK 动态代理对象并注入进去。

## @RpcScan

### 作用

`@RpcScan` 用在启动类上，指定 RPC 框架要扫描的业务包。

服务端示例：

```java
@SpringBootApplication(scanBasePackages = {"exampleServer", "simple", "common", "helloserviceapi"})
@RpcScan(basePackage = {"exampleServer"})
public class NettyServerMain implements CommandLineRunner {
}
```

客户端示例：

```java
@RpcScan(basePackage = {"exampleClient"})
@SpringBootApplication
public class NettyClientMain implements CommandLineRunner {
}
```

### 注解定义

核心属性：

```java
String[] basePackage();
```

关键点：

```java
@Import(CustomScannerRegistrar.class)
```

`@RpcScan` 通过 `@Import` 把 `CustomScannerRegistrar` 导入 Spring 容器。

### 在项目中的意义

`@RpcScan` 负责告诉框架：

- 哪些包需要扫描 `@RpcService`。
- 哪些框架组件需要注册为 Spring Bean。

### CustomScannerRegistrar 工作流程

核心类：

- `CustomScannerRegistrar`
- `CustomScanner`

流程：

1. Spring 解析到启动类上的 `@RpcScan`。
2. `@Import(CustomScannerRegistrar.class)` 生效。
3. Spring 调用 `CustomScannerRegistrar.registerBeanDefinitions()`。
4. 从注解元数据里读取 `basePackage`。
5. 创建 `CustomScanner(beanDefinitionRegistry, RpcService.class)` 扫描 `@RpcService`。
6. 创建 `CustomScanner(beanDefinitionRegistry, Component.class)` 扫描 Spring 组件。
7. 扫描到的类注册为 Spring BeanDefinition。

### 面试表达

> `@RpcScan` 类似一个 RPC 框架的启动开关。它通过 `@Import` 导入 `CustomScannerRegistrar`，在 Spring 注册 BeanDefinition 阶段扫描指定包下的 `@RpcService`，让这些服务类进入 Spring 容器，后续才能被 `BeanPostProcessor` 识别并注册到 ZooKeeper。

## SpringBeanPostProcessor 做了什么

核心类：

- `simple.spring.SpringBeanPostProcessor`

它同时承担服务端和客户端两种职责：

- 服务端：注册 `@RpcService` 服务。
- 客户端：注入 `@RpcReference` 代理。

## 服务端：自动注册服务

核心方法：

```java
postProcessBeforeInitialization(Object bean, String beanName)
```

执行阶段：

- Bean 实例化完成。
- 属性填充完成。
- 初始化方法执行前。

处理逻辑：

```java
if (bean.getClass().isAnnotationPresent(RpcService.class)) {
    RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);

    RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
            .group(rpcService.group())
            .version(rpcService.version())
            .service(bean)
            .build();

    serviceProvider.publishService(rpcServiceConfig);
}
```

它做了这些事：

1. 判断当前 Bean 是否标注 `@RpcService`。
2. 读取注解中的 group/version。
3. 把当前 Bean 作为服务对象封装进 `RpcServiceConfig`。
4. 调用 `serviceProvider.publishService()`。
5. 服务提供者将服务加入本地缓存并注册到 ZooKeeper。

为什么放在 before：

- 服务提供者启动时要尽早注册服务。
- 这里的 Bean 已经实例化并完成属性填充，能拿到真实服务对象。
- 在服务对外可见前，可以先把本地服务缓存准备好。

面试表达：

> 服务端部分主要在 `postProcessBeforeInitialization()` 中完成。框架判断 Bean 上是否有 `@RpcService`，如果有就把当前 Bean 封装成 `RpcServiceConfig`，然后发布到服务提供者。这个发布过程会先放入本地 `serviceMap`，再注册到 ZooKeeper。

## 客户端：自动注入代理

核心方法：

```java
postProcessAfterInitialization(Object bean, String beanName)
```

执行阶段：

- Bean 初始化完成后。
- 返回给 Spring 容器前。

处理逻辑：

```java
Class<?> targetClass = bean.getClass();
Field[] declaredFields = targetClass.getDeclaredFields();

for (Field declaredField : declaredFields) {
    RpcReference rpcReference = declaredField.getAnnotation(RpcReference.class);
    if (rpcReference != null) {
        RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                .group(rpcReference.group())
                .version(rpcReference.version())
                .build();

        RpcClientProxy rpcClientProxy = new RpcClientProxy(rpcClient, rpcServiceConfig);
        Object clientProxy = rpcClientProxy.getProxy(declaredField.getType());
        declaredField.setAccessible(true);
        declaredField.set(bean, clientProxy);
    }
}
```

它做了这些事：

1. 获取当前 Bean 的所有字段。
2. 判断字段上是否有 `@RpcReference`。
3. 读取注解里的 group/version。
4. 构造 `RpcServiceConfig`。
5. 创建 `RpcClientProxy`。
6. 根据字段类型创建 JDK 动态代理。
7. 通过反射把代理对象注入字段。

为什么放在 after：

- 客户端 Bean 已经创建完成，可以安全遍历字段并注入代理。
- 注入的是远程接口代理，不依赖本地服务实现类。
- 用户拿到 Bean 后，字段已经可以直接调用。

面试表达：

> 客户端部分主要在 `postProcessAfterInitialization()` 中完成。框架遍历每个 Bean 的字段，如果发现 `@RpcReference`，就为字段类型创建 JDK 动态代理，并通过反射注入进去。这样业务代码调用字段方法时，实际调用的是代理对象。

## BeanPostProcessor 的生命周期切入点

`BeanPostProcessor` 是 Spring 提供的 Bean 生命周期扩展点。

常见流程可以简化理解为：

```text
实例化 Bean
    -> 属性填充
    -> postProcessBeforeInitialization()
    -> 初始化方法
    -> postProcessAfterInitialization()
    -> Bean 可被使用
```

在这个项目中：

| 方法 | 阶段 | 项目用途 |
| --- | --- | --- |
| `postProcessBeforeInitialization()` | 初始化前 | 服务端扫描 `@RpcService` 并注册服务 |
| `postProcessAfterInitialization()` | 初始化后 | 客户端扫描 `@RpcReference` 并注入代理 |

面试表达：

> `BeanPostProcessor` 能让框架在 Spring 创建 Bean 的过程中插入自己的逻辑。这个项目中，初始化前用于处理服务提供方注册，初始化后用于处理服务消费方代理注入。这样服务注册和远程引用注入都能自动完成，不需要用户手动调用框架 API。

## RpcClientProxy 如何拦截接口方法调用

核心类：

- `simple.proxy.RpcClientProxy`

它实现了：

```java
InvocationHandler
```

## 创建代理对象

核心方法：

```java
public <T> T getProxy(Class<T> clazz)
```

实现：

```java
return (T) Proxy.newProxyInstance(
        clazz.getClassLoader(),
        new Class<?>[]{clazz},
        this
);
```

参数解释：

- `clazz.getClassLoader()`：使用接口的类加载器加载代理类。
- `new Class<?>[]{clazz}`：代理对象实现的接口。
- `this`：方法调用处理器，也就是当前 `RpcClientProxy`。

结果：

- 返回一个实现了目标接口的代理对象。
- 例如返回一个实现了 `HelloService` 接口的代理对象。

## 拦截方法调用

核心方法：

```java
public Object invoke(Object proxy, Method method, Object[] args)
```

业务代码调用：

```java
helloService.hello(new Hello(...))
```

实际进入：

```java
RpcClientProxy.invoke()
```

## invoke() 中做了什么

### 1. 记录被调用方法

```java
log.info("invoked method:[{}]", method.getName());
```

### 2. 构造 RpcRequest

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

字段含义：

| 字段 | 作用 |
| --- | --- |
| `methodName` | 远程方法名 |
| `parameters` | 参数值 |
| `interfaceName` | 接口全限定名 |
| `paramTypes` | 参数类型，用于服务端反射定位方法 |
| `requestId` | 请求唯一 ID，用于响应匹配 |
| `group` | 服务分组 |
| `version` | 服务版本 |

### 3. 发送 RPC 请求

```java
RpcResponse<Object> rpcResponse =
        (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);
```

这里的 `rpcRequestTransport` 是传输层接口。当前通过 SPI 加载的是：

```text
NettyRpcClient
```

### 4. 校验响应

```java
this.check(rpcResponse, rpcRequest);
```

校验内容：

- 响应不能为空。
- 请求 ID 和响应 ID 必须一致。
- 响应码必须是成功状态。

### 5. 返回远程结果

```java
return rpcResponse.getData();
```

最终业务代码拿到的就是远程服务方法返回值。

## 为什么用户可以像调用本地方法一样调用远程服务

因为框架在用户和远程服务之间插入了代理对象。

用户看到的是：

```java
helloService.hello(new Hello(...));
```

实际执行的是：

```text
JDK 代理对象
    -> RpcClientProxy.invoke()
    -> 构造 RpcRequest
    -> 服务发现
    -> Netty 发送请求
    -> 服务端反射执行
    -> 返回 RpcResponse
    -> 代理对象取出 data 返回
```

用户不需要关心：

- 服务端 IP 和端口。
- ZooKeeper 服务发现。
- Netty Channel。
- 自定义协议。
- 序列化和压缩。
- 请求响应匹配。

这些都被代理对象和框架内部逻辑屏蔽了。

面试表达：

> 用户可以像调用本地方法一样调用远程服务，是因为注入到字段里的其实是代理对象。代理对象实现了相同的接口，所以编译期看起来就是普通接口调用；运行期方法调用被 `InvocationHandler` 拦截，框架在拦截逻辑里完成远程调用，并把响应结果返回给业务代码。

## 为什么用 JDK 动态代理

### 原因 1：RPC 天然面向接口

客户端和服务端一般共享接口契约，例如：

```java
public interface HelloService {
    String hello(Hello hello);
}
```

客户端只需要依赖接口，不需要依赖服务端实现类。

JDK 动态代理正好适合基于接口生成代理对象。

### 原因 2：实现简单，和当前项目匹配

当前项目的服务消费方字段类型都是接口：

```java
private HelloService helloService;
```

所以可以直接用：

```java
Proxy.newProxyInstance()
```

### 原因 3：避免引入额外依赖

JDK 动态代理是 JDK 自带能力，不需要引入 CGLIB、ByteBuddy 等库。

面试回答：

> 我用 JDK 动态代理是因为这个 RPC 框架是面向接口调用的，客户端和服务端共享接口契约，客户端字段类型也是接口。JDK 动态代理可以直接为接口生成代理对象，不需要引入额外依赖，和项目设计比较匹配。

## JDK 动态代理有什么限制

主要限制：

- 只能代理接口。
- 不能直接代理普通类。
- 目标类如果没有接口，就无法用 JDK 动态代理生成代理对象。

在当前项目中的影响：

- 当前项目服务都是接口式调用，比如 `HelloService`。
- 所以 JDK 动态代理足够使用。

如果要支持普通类：

- 可以引入 CGLIB。
- 或者使用 ByteBuddy。
- 或者强制 RPC 服务必须暴露接口。

面试回答：

> JDK 动态代理的限制是只能代理接口，不能直接代理普通类。当前项目就是接口式 RPC 调用，所以这个限制可以接受。如果后续要支持没有接口的服务类，可以考虑引入 CGLIB 或 ByteBuddy 做基于子类的代理。

## 为什么不用 CGLIB

### 当前项目不需要

服务契约模块 `hello-service-api` 提供的是接口：

- `HelloService`

客户端远程引用也是接口字段：

- `private HelloService helloService`

JDK 动态代理已经满足需求。

### CGLIB 的特点

CGLIB 通过生成目标类的子类来代理方法。

优点：

- 可以代理普通类。

限制：

- 不能代理 final 类。
- 不能代理 final 方法。
- 需要额外依赖。
- 对当前接口式 RPC 项目来说不是必须。

面试回答：

> 这个项目没有用 CGLIB，主要是因为 RPC 调用本身更推荐面向接口，当前服务消费方持有的也是接口类型，JDK 动态代理已经足够。CGLIB 更适合没有接口的类代理，但它需要额外依赖，也不能代理 final 类和 final 方法。所以在这个项目里我选择了更轻量的 JDK 动态代理。

## 为什么要结合 Spring BeanPostProcessor

如果不用 `BeanPostProcessor`，用户可能需要手动写：

```java
RpcClientProxy proxy = new RpcClientProxy(...);
HelloService helloService = proxy.getProxy(HelloService.class);
```

服务端也可能需要手动写：

```java
serviceProvider.publishService(...);
```

这样业务代码会强依赖 RPC 框架 API，使用体验不好。

结合 Spring 后：

服务端：

```java
@RpcService
public class HelloServiceImpl implements HelloService {
}
```

客户端：

```java
@RpcReference
private HelloService helloService;
```

框架自动完成：

- 服务扫描。
- 服务注册。
- 代理创建。
- 字段注入。

面试回答：

> 结合 `BeanPostProcessor` 是为了把 RPC 框架接入 Spring 生命周期，实现注解式使用。服务端不需要手动注册服务，客户端不需要手动创建代理。框架在 Bean 初始化前后自动完成服务注册和代理注入，这样业务代码只关注接口和注解，底层 RPC 细节都由框架处理。

## 可能追问与参考回答

### 1. `@RpcService` 和 Spring 的 `@Service` 有什么区别？

**高概率追问：是**

参考回答：

> `@Service` 是 Spring 注解，用来把类注册成 Spring Bean；`@RpcService` 是 RPC 框架自定义注解，用来标识这个 Bean 需要作为远程服务暴露。项目里的 `HelloServiceImpl` 同时用了两个注解，`@Service` 让它进入 Spring 容器，`@RpcService` 让框架把它注册到 ZooKeeper。

### 2. `@RpcReference` 为什么标在字段上？

**高概率追问：中**

参考回答：

> 因为它表示这个字段需要注入远程服务代理对象。`SpringBeanPostProcessor.postProcessAfterInitialization()` 会遍历 Bean 的字段，发现字段上有 `@RpcReference` 后，根据字段类型生成代理对象，再通过反射注入字段。

### 3. `@RpcScan` 为什么需要 `@Import`？

**高概率追问：中**

参考回答：

> `@Import` 可以把 `CustomScannerRegistrar` 导入到 Spring 启动流程中。这样 Spring 解析到 `@RpcScan` 后，会调用 `CustomScannerRegistrar.registerBeanDefinitions()`，读取扫描路径并注册相关 BeanDefinition。

### 4. `BeanPostProcessor` 两个方法分别什么时候执行？

**高概率追问：是**

参考回答：

> `postProcessBeforeInitialization()` 在 Bean 属性填充后、初始化方法前执行；`postProcessAfterInitialization()` 在初始化方法后执行。这个项目里 before 用来处理 `@RpcService` 服务注册，after 用来处理 `@RpcReference` 代理注入。

### 5. 为什么服务端注册放在 before，客户端注入放在 after？

**高概率追问：中**

参考回答：

> 服务端注册需要尽早完成，Bean 已经实例化并填充属性后就可以把服务对象发布出去，所以放在 before。客户端代理注入需要在 Bean 初始化完成后遍历字段并注入代理，确保用户拿到 Bean 时字段已经可用，所以放在 after。

### 6. JDK 动态代理如何知道调用了哪个方法？

**高概率追问：中**

参考回答：

> 代理对象的方法调用都会进入 `InvocationHandler.invoke()`，这个方法的参数里有 `Method method` 和 `Object[] args`。项目中通过 `method.getName()` 获取方法名，通过 `method.getDeclaringClass().getName()` 获取接口名，通过 `method.getParameterTypes()` 获取参数类型，通过 `args` 获取参数值。

### 7. 为什么 `RpcRequest` 里要保存参数类型？

**高概率追问：是**

参考回答：

> 因为服务端要通过反射定位目标方法。Java 支持方法重载，只靠方法名可能不够，所以需要 `paramTypes`。服务端会调用 `service.getClass().getMethod(methodName, paramTypes)` 获取 Method，然后再执行 `method.invoke(service, parameters)`。

### 8. 如果服务接口有多个实现类怎么办？

**高概率追问：中**

参考回答：

> 当前项目通过 group 和 version 区分服务。服务端 `@RpcService` 可以指定 group/version，客户端 `@RpcReference` 也指定同样的 group/version，服务名由接口名加 group/version 生成。这样同一个接口的不同实现可以通过不同 group 或 version 隔离。

### 9. 当前注解和代理实现有什么不足？

**高概率追问：中**

参考回答：

> 当前实现能跑通核心链路，但还可以优化。比如 `SpringBeanPostProcessor` 直接用 `bean.getClass().isAnnotationPresent()`，如果 Bean 被 Spring AOP 代理，可能需要处理代理类和目标类注解识别问题。字段注入目前只处理字段注解，没有处理 setter 或构造器注入。JDK 动态代理只能代理接口，如果要支持普通类，需要引入 CGLIB 或 ByteBuddy。

## 面试背诵版回答

可以直接背这一段：

> 这个项目通过 Spring 自定义注解和 JDK 动态代理实现了透明远程调用。服务端实现类上加 `@RpcService`，里面可以指定 group 和 version；客户端字段上加 `@RpcReference`，表示这个字段需要注入远程服务代理；启动类上加 `@RpcScan`，通过 `@Import` 导入自定义扫描器，扫描 RPC 服务和框架组件。
>
> Spring 启动时，`SpringBeanPostProcessor` 会在两个生命周期点做不同事情。服务端在 `postProcessBeforeInitialization()` 中判断 Bean 是否有 `@RpcService`，如果有就读取注解配置，把当前 Bean 封装成 `RpcServiceConfig`，然后调用服务提供者发布服务，也就是先放进本地 `serviceMap`，再注册到 ZooKeeper。客户端在 `postProcessAfterInitialization()` 中遍历 Bean 的字段，如果发现 `@RpcReference`，就读取 group/version，创建 `RpcClientProxy`，调用 `getProxy()` 生成 JDK 动态代理对象，并通过反射注入字段。
>
> 业务代码调用 `helloService.hello()` 时，实际调用的是代理对象。`RpcClientProxy` 实现了 `InvocationHandler`，所有接口方法调用都会进入 `invoke()`。在 `invoke()` 中，框架会根据 `Method` 和参数构造 `RpcRequest`，里面包含接口名、方法名、参数、参数类型、requestId、group 和 version，然后交给 Netty 客户端发送远程请求。服务端执行完成后返回 `RpcResponse`，代理对象校验响应并返回 data，所以用户就像调用本地方法一样拿到了远程方法的结果。
>
> 我选择 JDK 动态代理，是因为这个 RPC 框架本身是面向接口调用的，客户端和服务端共享接口契约，JDK 动态代理足够简单且不需要额外依赖。它的限制是只能代理接口，不能代理普通类；如果后续要支持类代理，可以再引入 CGLIB 或 ByteBuddy。结合 Spring `BeanPostProcessor` 的好处是可以实现注解式自动注册和自动注入，业务代码不需要手动创建代理或注册服务。
