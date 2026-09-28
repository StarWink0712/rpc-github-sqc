package simple.registry;

import common.extension.SPI;

import java.net.InetSocketAddress;

/**
 * ServiceRegistry 服务注册接口，实现服务注册的功能
 *
 * @author 执笔画棠
 * @version 2025/10/14 23:03
 **/
@SPI
public interface ServiceRegistry {


    /**
     * @param rpcServiceName    :要注册的rpc服务的名字
     * @param inetSocketAddress :表示服务端地址
     * @description:
     * @date: 2025/10/14 23:05
     * @author: 执笔画棠
     * @return: void
     **/
    void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress);
    /*
        调用链分析
        * 1. 1.
        * 服务提供者启动时，通过 ServiceProvider 实现类（如 ZkServiceProviderImpl ）调用其 publishService
        * 方法
        * 2. 2.
        * 最终调用 ZkServiceRegistryImpl 的 registerService 方法将服务注册到Zookeeper
     */
}