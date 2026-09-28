package simple.registry.Impl.util;

import common.enums.RpcConfigEnum;
import common.utils.PropertiesFileUtil;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * CuratorUtils 客户端工具类
 *
 * @author 执笔画棠
 * @date 2025/10/15 16:36
 **/
@Slf4j
public class CuratorUtils {
    //重试策略的初始等待时间
    private static final int BASE_SLEEP_TIME = 1000;
    //定义了失败后最大连接次数
    private static final int MAX_RETRIES = 3;
    //定义了rpc服务在zookeeper中注册的根路径，所有的服务都会在这个路径下创建子节点
    public static final String ZK_REGISTER_ROOT_PATH = "/my-rpc";
    //本地缓存，key是服务名称，rpcServiceName，value是该服务所有提供者的地址，线程安全
    private static final Map<String, List<String>> SERVICE_ADDRESS_MAP = new ConcurrentHashMap<>();
    //本地缓存，存储当前客户端实例在zookeeper上创建的所有节点的路径,主要用于服务下线时，能够精确地清理自己注册过的所有节点
    private static final Set<String> REGISTERED_PATH_SET = ConcurrentHashMap.newKeySet();
    //作为整个应用全局唯一的zookeeper客户端（单例模式）
    //CuratorFramework: Curator 框架的主要接口，提供了对 ZooKeeper 的高级 API 封装，简化了 ZooKeeper 的使用,即zookeeper客户端。
    private static CuratorFramework zkClient;
    //如果配置文件中没有指定zookeeper的地址，则用这个地址
    private static final String DEFAULT_ZOOKEEPER_ADDRESS = "127.0.0.1:2181";

    //构造方法
    private CuratorUtils() {
    }

    /**
     * @param zkClient :zookeeper客户端
     * @param path     :节点路径,即服务在zookeeper这个目录树
     *                 上的唯一位置标识
     * @description:创建永久节点，一般是根节点
     * @date: 2025/10/15 17:11
     * @author: 执笔画棠
     * @return: void
     **/
    public static void createPersistentNode(CuratorFramework zkClient, String path) {
        try {
            if (REGISTERED_PATH_SET.contains(path) || zkClient.checkExists().forPath(path) != null) {
                //节点已存在于zookeeper客户端
                log.info("The node already exists. The node is :[{}]", path);
            } else {
                //如果没有则在这个路径上创建一个永久节点
                zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
            }
            //添加到缓存中
            REGISTERED_PATH_SET.add(path);
        } catch (Exception e) {
            //提示创建节点失败
            log.error("create persistent node for path [{}] fail", path);
        }
    }


    /**
     * @param zkClient       :zookeeper客户端
     * @param rpcServiceName :服务名称
     * @description:获取指定的子节点，即服务节点
     * @date: 2025/10/15 18:13
     * @author: 执笔画棠
     * @return: java.util.List<java.lang.String>
     **/
    public static List<String> getChildrenNodes(CuratorFramework zkClient, String rpcServiceName) {
        if (SERVICE_ADDRESS_MAP.containsKey(rpcServiceName)) {
            //如果存在直接返回
            return SERVICE_ADDRESS_MAP.get(rpcServiceName);
        }
        //result用来保存拿到的子节点
        List<String> result = null;
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;
        try {
            result = zkClient.getChildren().forPath(servicePath);
            //把子节点存入到服务地址map集合里,即缓存子节点列表
            SERVICE_ADDRESS_MAP.put(rpcServiceName, result);
            //注册节点变化监听器，监听这个服务节点的动向
            registerWatcher(rpcServiceName, zkClient);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /**
     * 清除指定服务实例的注册信息
     * 主要作用是资源清理，当服务实例关闭时，就从注册中心删除该实例的所有信息
     * 防止其他服务消费者调用到已关闭的服务实例
     *
     * @param zkClient          zookeeper 客户端
     * @param inetSocketAddress 服务实例的地址
     */
    public static void clearRegistry(CuratorFramework zkClient, InetSocketAddress inetSocketAddress) {
        // 遍历已注册的路径集合, 并行处理每个路径
        REGISTERED_PATH_SET.stream().parallel().forEach(p -> {
            try {
                if (p.endsWith(inetSocketAddress.toString())) {
                    // 如果路径以服务实例地址结尾, 则删除该节点
                    zkClient.delete().forPath(p);
                }
            } catch (Exception e) {
                log.error("清除{}路径下的服务实例失败", p);
            }
            // 清除服务实例地址缓存
            log.info("All registered services on the server are cleared:[{}]", REGISTERED_PATH_SET);
        });
    }

    /**
     * 获取 zookeeper 客户端实例
     * 是一个完整的工厂方法，负责获取或创建并初始化ZooKeeper客户端实例。
     * 具体来说，它有以下几个重要功能：
     * 从配置文件读取ZooKeeper地址，如果用户未配置则使用默认地址 127.0.0.1:2181
     * 在ZooKeeper架构中存在两个主要角色：
     * - ZooKeeper服务器 ：作为中心注册中心，存储服务信息的地方
     * - ZooKeeper客户端 ：应用程序中用于连接ZooKeeper服务器的组件
     * 创建 CuratorFramework 实例并不是创建ZooKeeper服务器，而是创建一个客户端对象
     * 即zkclient，这个对象知道如何：
     * 1. 1.
     * 连接到ZooKeeper服务器（使用提供的地址，如 127.0.0.1:2181 ）
     * 2. 2.
     * 发送请求（如创建节点、获取子节点等）
     * 3. 3.
     * 处理响应
     * 4. 4.
     * 维护连接状态
     * ### 关于连接地址
     *
     * @return zookeeper 客户端实例
     */
    public static CuratorFramework getZkClient() {
        //根据配置文件读取类和枚举类中标记的配置文件地址，读取zookeeper的配置文件,读取配置文件并获取zookeeper的地址,
        Properties properties = PropertiesFileUtil.readPropertiesFile(RpcConfigEnum.RPC_CONFIG_PATH.getPropertyValue());

        //这里读取配置文件properties中的zookeeper的地址，如果是空就用默认地址
        String zookeeperAddress = properties != null
                && properties.getProperty(RpcConfigEnum.ZK_ADDRESS.getPropertyValue()) != null
                ? properties.getProperty(RpcConfigEnum.ZK_ADDRESS.getPropertyValue()) : DEFAULT_ZOOKEEPER_ADDRESS;

        // 如果 zookeeper 客户端实例已存在且状态为已启动, 则直接返回该实例
        if (zkClient != null && zkClient.getState() == CuratorFrameworkState.STARTED) {
            return zkClient;
        }
        // 指数补偿重试策略, 初始重试间隔为 1000 毫秒, 最大重试次数为 3 次
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(BASE_SLEEP_TIME, MAX_RETRIES);
        zkClient = CuratorFrameworkFactory.builder()
                .connectString(zookeeperAddress)
                .retryPolicy(retryPolicy)
                .build();
        zkClient.start();

        try {
            //等30秒，直到连上zookeeper
            if (!zkClient.blockUntilConnected(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("time out of waiting to connect to ZK!");
            }
        } catch (InterruptedException e) {
            log.info("没有连接到zookeeper客户端");
            e.printStackTrace();
        }
        return zkClient;
    }

    /**
     * @param rpcServiceName :服务名称
     * @param zkClient       :zookeeper客户端
     * @description:注册节点变化监听器
     * @date: 2025/10/15 18:20
     * @author: 执笔画棠
     * @return: void
     **/
    private static void registerWatcher(String rpcServiceName, CuratorFramework zkClient) throws Exception {

        //优化：pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        //// 手动触发一次初始数据加载
        //List<String> initialAddresses = zkClient.getChildren().forPath(servicePath);
        //SERVICE_ADDRESS_MAP.put(rpcServiceName, initialAddresses);

        //子节点的路径，跟路径加服务名称
        String servicePath = ZK_REGISTER_ROOT_PATH + "/" + rpcServiceName;

        //创建 PathChildrenCache 实例，用于监听指定路径的子节点变化,即一个监听器
        PathChildrenCache pathChildrenCache = new PathChildrenCache(zkClient, servicePath, true);
        //这个是监听逻辑，当这个路径下的节点发生添加删除，更新，都会触发这个监听器
        PathChildrenCacheListener pathChildrenCacheListener = (curatorFramework, pathChildrenCacheEvent) -> {
            //重新获取所有子节点列表
            List<String> serviceAddresses = curatorFramework.getChildren().forPath(servicePath);
            //将这些服务重新放到服务地址缓存中
            SERVICE_ADDRESS_MAP.put(rpcServiceName, serviceAddresses);
        };
        //注册 PathChildrenCacheListener 到 PathChildrenCache 实例中，用于监听子节点变化事件
        pathChildrenCache.getListenable().addListener(pathChildrenCacheListener);
        //启动监听
        pathChildrenCache.start();

    }

    /**
     * 创建临时结点
     */
    public static void createEphemeralNode(CuratorFramework zkClient, String path) {
        try {
            String createdPath = zkClient.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .withACL(org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE)
                    .forPath(path, "status:ok".getBytes());
        } catch (Exception e) {
            log.error("创建临时结点失败!", e);
        }
    }


    /*
    强制删除节点
     */
    public static void deleteEphemeralNode(CuratorFramework zkClient, String path) {
        try {
            zkClient.delete().deletingChildrenIfNeeded().forPath(path);
        } catch (Exception e) {
            log.error("删除临时结点：{}失败", path);
        }
    }
}
