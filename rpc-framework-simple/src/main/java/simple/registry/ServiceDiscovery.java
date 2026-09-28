package simple.registry;

import common.extension.SPI;
import simple.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;

/**
 * ServiceDiscovery 服务发现器
 * 根据rpc请求中封装的所需要的服务名称
 * 从注册中心找对应的服务，是rpc客户端在做
 *
 * @author 执笔画棠
 * @version 2025/10/19 16:09
 **/
@SPI
public interface ServiceDiscovery {

    /**
     * lookup service by rpcServiceName
     *
     * @param rpcRequest rpc service pojo
     * @return service address
     */
    // 实现了ServiceDiscovery接口的lookupService方法，根据RPC请求的服务名称，从注册中心中查找服务地址
    // 并根据负载均衡策略选择一个服务地址返回
    InetSocketAddress lookupService(RpcRequest rpcRequest);
}