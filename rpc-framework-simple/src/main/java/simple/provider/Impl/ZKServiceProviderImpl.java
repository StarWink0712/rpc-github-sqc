package simple.provider.Impl;

import common.enums.RpcErrorMessageEnum;
import common.enums.ServiceRegistryEnum;
import common.exception.RpcException;
import common.extension.ExtensionLoader;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import simple.config.RpcServiceConfig;
import simple.provider.ServiceProvider;
import simple.registry.ServiceRegistry;
import simple.remoting.transport.netty.server.NettyRpcServer;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ServiceProviderImpl 服务提供接口的实现类
 * 包含三个方法，注册服务获取服务发布服务
 *
 * @author 执笔画棠
 * @date 2025/10/14 22:19
 **/
@Slf4j
public class ZKServiceProviderImpl implements ServiceProvider {

    //定义一个集合存储要处理的服务,key是服务的名称name加version和group，value是服务对象
    private final Map<String, Object> serviceMap;
    //已经处理好的服务
    private final Set<String> registeredService;
    //这个是服务注册器，用于将服务注册到zookeeper
    private final ServiceRegistry serviceRegistry;

    public ZKServiceProviderImpl() throws Exception {
        //用线程安全的集合存服务对象，确保多线程安全
        serviceMap = new ConcurrentHashMap<>();
        //一样用线程安全的集合存已处理的服务
        registeredService = ConcurrentHashMap.newKeySet();
        //利用扩展加载器获取zookeeper服务注册器实例（调用自定义扩展加载器）
        //用自定义的服务注册接口，通过自定义扩展加载器获取其单例，
        //而自定义扩展加载器又通过调用singleFactory创建单例
        //后面枚举类是通过枚举类获取要创建的单例的name
        serviceRegistry = ExtensionLoader.getExtensionLoader(ServiceRegistry.class)
                //这里从枚举类中得到想要实现的实例的name，
                .getExtension(ServiceRegistryEnum.ZK.getName());
    }

    /**
     *
     */
    @Override
    public void addService(RpcServiceConfig rpcServiceConfig) {
        //从服务配置对象中获取服务名称
        String rpcServiceName = rpcServiceConfig.getRpcServiceName();
        //检查服务是否已经注册
        if (registeredService.contains(rpcServiceName)) {
            return;
        }
        //否则就注册到zookeeper
        registeredService.add(rpcServiceName);
        serviceMap.put(rpcServiceName, rpcServiceConfig.getService());
        log.info("添加服务:{} and 接口{}", rpcServiceName, rpcServiceConfig.getService().getClass().getInterfaces());
    }

    /**
     * 从服务映射中获取服务对象
     * 1. 从服务映射中根据服务名称获取服务对象
     * 2. 如果服务对象为空，抛出服务未找到异常
     * 3. 返回服务对象,即从本地服务端获取对应的服务
     */
    @Override
    public Object getService(String rpcServiceName) {
        Object service = serviceMap.get(rpcServiceName);
        if (null == service) {
            //自定义异常和自定义错误枚举类
            throw new RpcException(RpcErrorMessageEnum.SERVICE_CAN_NOT_BE_FOUND);
        }
        return service;
    }

    /*
     * 发布服务到Zookeeper注册中心
     * 1. 从服务配置对象中获取服务名称
     * 2. 检查服务是否已注册，避免重复注册
     * 3. 注册服务到Zookeeper
     */
    @Override
    public void publishService(RpcServiceConfig rpcServiceConfig) {
        try {
            // host: 服务提供者的主机地址，这里获取本地主机的IP地址
            String host = InetAddress.getLocalHost().getHostAddress();
            this.addService(rpcServiceConfig);
            //这里调用服务注册接口的注册服务方法，把两个参数
            //rpcserviceconfig和服务提供者地址传递过去
            serviceRegistry.registerService(rpcServiceConfig.getRpcServiceName(),
                    new InetSocketAddress(host, NettyRpcServer.PORT));
        } catch (UnknownHostException e) {
            log.error("occur exception when getHostAddress", e);
        }
    }

    /*
     *1. 核心职责：服务管家
     *这个类就像一个“服务管家”，它负责管理所有需要在本机上运行的 RPC 服务。它的核心工作有两方面：
     *对内管理 (Internal Management)：通过 serviceMap 和 registeredService 这两个本地缓存，
     *它精确地知道自己手上有哪些服务 (serviceMap)，以及哪些服务已经被处理过 (registeredService)。
     *当 RPC 请求进来时，它能通过 getService 方法快速、高效地从内存中拿出对应的服务实例。
     *对外宣传 (External Publishing)：通过 serviceRegistry 组件，
     *它将自己管理的服务信息（服务名 + 服务器 IP 和端口）发布到 Zookeeper 上。
     *这样，远端的服务消费者（客户端）就能通过 Zookeeper 发现这些服务，并知道该往哪个地址发请求。
     *2. 数据结构的选择：线程安全
     *ConcurrentHashMap 和 ConcurrentHashMap.newKeySet() 的使用是该类的一个关键亮点。
     *RPC 服务器是一个典型的多线程环境，会有多个工作线程（Netty Worker Threads）同时处理进来的请求。
     *如果使用普通的 HashMap 或 HashSet，在没有额外同步措施的情况下，多线程并发读写会导致数据不一致甚至程序崩溃。
     *ConcurrentHashMap 专为高并发场景设计，能保证线程安全，同时性能远高于使用 Collections.synchronizedMap 或简单的 synchronized 关键字锁住整个 Map。
     *3. 设计模式与原则
     *单一职责原则：ZKServiceProviderImpl 的职责很清晰，就是管理和发布服务。
     *它不关心网络通信（那是 NettyRpcServer 的事），也不关心如何具体与 Zookeeper 交互（那是 serviceRegistry 的事），它只负责编排和管理。
     *依赖倒置原则：它不直接依赖一个具体的 Zookeeper 注册类，而是依赖 ServiceRegistry 这个接口。
     *这使得注册中心的实现可以被轻松替换（比如从 Zookeeper 换成 Nacos 或 Etcd），只需提供一个新的 ServiceRegistry 实现，并通过 ExtensionLoader 机制注入即可。
     *SPI (Service Provider Interface) 机制：ExtensionLoader 是这个框架的精髓之一。
     *它是一种自定义的、类似 Java原生 SPI 的机制，用于实现模块的解耦和动态加载。
     *getExtension(ServiceRegistryEnum.ZK.getName()) 这行代码，就是通过 SPI 机制
     *在运行时动态地找到了标记为 "zk" 的 ServiceRegistry 实现类并实例化。这比硬编码 new ZkServiceRegistryImpl() 要灵活得多。
     *4. 逻辑流程的健壮性
     *publishService 方法中的执行顺序非常重要：
     *this.addService(rpcServiceConfig); （先在本地就绪）
     *serviceRegistry.registerService(...); （再对外发布）
     *这个顺序确保了服务在发布到注册中心之前，一定已经在本地准备好，可以随时被调用。如果顺序反过来，可能会出现一个极端情况：
     *客户端从 Zookeeper 上发现了服务并立即发起了请求，但此时服务端的 serviceMap 还没有准备好这个服务，导致 getService 失败。
     */

}
