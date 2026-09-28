package simple.provider;

import common.extension.SPI;
import simple.config.RpcServiceConfig;

/**
 * ServiceProvider 服务提供接口
 * 定义了添加服务、获取服务和发布服务的方法
 *
 * @author 执笔画棠
 * @version 2025/10/14 22:17
 **/
@SPI
public interface ServiceProvider {

    /**
     * @param rpcServiceConfig 服务配置对象，包含服务名称、服务实现类和服务版本等信息
     *                         添加服务
     *                         这个方法负责将一个服务实例注册到本地的一个容器或缓存中。
     *                         而不是将服务注册到服务中心
     */
    void addService(RpcServiceConfig rpcServiceConfig);

    /**
     * @param rpcServiceName 服务名称，用于唯一标识一个服务
     * @return service object 服务实现类的实例对象
     * 根据传入的服务名称，从第一步创建的本地容器中查找并返回对应的服务实例对象。
     * 即在本地找到服务，作用自然就是客户端那边发请求的时候
     * 服务端根据请求信息从本地找对应的服务
     */
    Object getService(String rpcServiceName);

    /*
     * 发布服务到Zookeeper注册中心
     * 1. 从服务配置对象中获取服务名称
     * 2. 检查服务是否已注册，避免重复注册
     * 3. 注册服务到Zookeeper
     */
    void publishService(RpcServiceConfig rpcServiceConfig);
}