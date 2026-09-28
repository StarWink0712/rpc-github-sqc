package simple.registry.Impl;

import common.enums.LoadBalanceEnum;
import common.enums.RpcErrorMessageEnum;
import common.exception.RpcException;
import common.extension.ExtensionLoader;
import common.utils.CollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import simple.loadbalance.LoadBalance;
import simple.registry.Impl.util.CuratorUtils;
import simple.registry.ServiceDiscovery;
import simple.remoting.dto.RpcRequest;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * ZkServiceDiscoveryImpl
 *
 * @author 执笔画棠
 * @date 2025/10/19 16:11
 **/
@Slf4j
public class ZkServiceDiscoveryImpl implements ServiceDiscovery {

    //声明一个负载均衡的接口
    //final 关键字表示这个字段一旦被赋值，就不能再改变，强调了负载均衡器在 ZkServiceDiscoveryImpl 实例的生命周期中是固定的
    private final LoadBalance loadBalance;

    public ZkServiceDiscoveryImpl() throws Exception {
        //用扩展加载器新建实例
        this.loadBalance = ExtensionLoader.getExtensionLoader(LoadBalance.class)
                .getExtension(LoadBalanceEnum.LOADBALANCENEW.getName());
    }

    //实现了ServiceDiscovery接口的lookupService方法，根据RPC请求的服务名称，从Zookeeper中查找服务地址
    //并根据负载均衡策略选择一个服务地址返回
    @Override
    public InetSocketAddress lookupService(RpcRequest rpcRequest) {
        //从rpc请求中获取服务名字
        String rpcServiceName = rpcRequest.getRpcServiceName();
        //获取zookeeper客户端实例
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        log.info("连上了zk客户端,[{}]", zkClient);
        //从zookeeper中获取指定name的全部服务
        //zookeeper在远程的地址上，这里zookeeper的客户端是curator封装的方法罢了
        //调用这个工具类，传入要找的zookeeper和服务名称，底层就能给我们返回需要的服务
        List<String> serviceUrlList = CuratorUtils.getChildrenNodes(zkClient, rpcServiceName);
        if (CollectionUtil.isEmpty(serviceUrlList)) {
            throw new RpcException(RpcErrorMessageEnum.SERVICE_CAN_NOT_BE_FOUND, rpcServiceName);
        }
        //利用负载均衡找一个服务来用
        String targetServiceUrl = loadBalance.selectServiceAddress(serviceUrlList, rpcRequest);
        log.info("Successfully found the service address:[{}]", targetServiceUrl);
        //从服务地址字符串中提取主机名和端口号，即找出这个服务的所在地（ip和端口号）
        String[] socketAddressArray = targetServiceUrl.split(":");
        String host = socketAddressArray[0];
        int port = Integer.parseInt(socketAddressArray[1]);
        //返回一个inet对象，包含主机号和端口号
        //到这就找到了需要的那个服务的地址，把地址返回给上层
        return new InetSocketAddress(host, port);
    }
}
