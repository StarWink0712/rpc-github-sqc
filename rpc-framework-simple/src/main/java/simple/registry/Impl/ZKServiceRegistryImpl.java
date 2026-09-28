package simple.registry.Impl;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import simple.registry.Impl.util.CuratorUtils;
import simple.registry.ServiceRegistry;

import java.net.InetSocketAddress;


/**
 * ZKServiceRegistryImpl 服务注册接口的实现类
 *
 * @author 执笔画棠
 * @date 2025/10/14 23:16
 * <p>
 * ?CuratorFramework是什么
 * 这个是zookeeper客户端对象，zkclient
 * 用来和真正的zookeeper中心联系
 **/
@Slf4j
@NoArgsConstructor
public class ZKServiceRegistryImpl implements ServiceRegistry {


    @Override
    public void registerService(String rpcServiceName, InetSocketAddress inetSocketAddress) {
        //服务路径格式
        String servicePath = CuratorUtils.ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName + inetSocketAddress.toString();
        //创建持久节点，路径为服务路径
        CuratorFramework zkClient = CuratorUtils.getZkClient();
        // 创建持久节点，路径为服务路径
        CuratorUtils.createPersistentNode(zkClient, servicePath);
    }
}
