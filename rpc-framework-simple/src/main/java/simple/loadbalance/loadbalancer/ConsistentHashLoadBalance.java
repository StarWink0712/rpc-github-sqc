package simple.loadbalance.loadbalancer;

import org.apache.log4j.nt.NTEventLogAppender;
import org.checkerframework.checker.fenum.qual.SwingTitleJustification;
import simple.loadbalance.AbstractLoadBalance;
import simple.remoting.dto.RpcRequest;

import javax.imageio.plugins.tiff.BaselineTIFFTagSet;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConsistentHashLoadBalance 一致性hash的负载均衡策略
 *
 * @author 执笔画棠
 * @date 2025/10/19 16:21
 **/
public class ConsistentHashLoadBalance extends AbstractLoadBalance {

    // 定义一个线程安全的集合，key是rpc服务名，value是对应的选择器(即一个哈希环)
    private final ConcurrentHashMap<String, ConsistentHashSelector> selectors = new ConcurrentHashMap<>();

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        // 获取serviceaddress列表的identihashcode
        // system.identityhashcode()返回对象的内存地址计算出的哈希码，无论该类是否重写了hashcode方法
        // 这里用他来判断服务地址列表是否发生了变化，例如节点上线或下线
        /*
         * 根据这一堆服务地址计算一个hash值，如果有服务上下线值就会变，以此来判断是否有服务变化
         */
        int identityHashCode = System.identityHashCode(serviceAddresses);

        // 从rpc请求中得到所需服务的名称
        String rpcServiceName = rpcRequest.getRpcServiceName();

        // 从缓存中获取该服务的服务选择器，即这个服务需要上的哈希环
        ConsistentHashSelector selector = selectors.get(rpcServiceName);
        // 如果selector为空，或服务地址列表更新了
        if (selectors == null || selector.identityHashCode != identityHashCode) {
            // 创建一个新的选择器放入缓存中
            // 这里的 160 是虚拟节点的数量，增加虚拟节点可以使节点在哈希环上分布更均匀，从而达到更好的负载均衡效果. [6, 12, 15]
            selectors.put(rpcServiceName, new ConsistentHashSelector(serviceAddresses, 160, identityHashCode));
            selector = selectors.get(rpcServiceName);
        }
        // 调用 selector 的 select 方法来选择一个服务地址
        // Dubbo 源码中使用 rpcServiceName + Arrays.stream(rpcRequest.getParameters()) 作为 key
        // 这样可以保证对于相同服务和相同参数的请求，总是会路由到同一个服务节点上
        return selector.select(rpcServiceName + Arrays.stream(rpcRequest.getParameters()));
    }

    // 静态内部类，封装了一致性hash选择的逻辑
    static class ConsistentHashSelector {

        // 用treemap存虚拟节点，key是虚拟节点的hash值，value是虚拟节点对应的地址，即真实的服务地址
        // 因为treemap的key是有序的，这对于在hash环上找下一个节点很重要，即用treemap模拟哈希环
        private final TreeMap<Long, String> virtualInvokers;

        // 保存了创建当前selector时服务地址列表的identiHashCode
        private final int identityHashCode;

        /**
         * ConsistentHashSelector 的构造函数
         *
         * @param invokers         真实的服务地址列表
         * @param replicaNumber    每个真实节点对应的虚拟节点数量
         * @param identityHashCode 服务地址列表的 identityHashCode
         */
        ConsistentHashSelector(List<String> invokers, int replicaNumber, int identityHashCode) {
            this.virtualInvokers = new TreeMap<>();
            this.identityHashCode = identityHashCode;

            // 遍历所有真实服务的地址
            for (String invoker : invokers) {
                // 为每个真实的节点创建replicanumber个虚拟节点
                // 为了让虚拟节点更分散，这里将replicanumber分为四份
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 使用md5算法计算摘要，确保哈希值的分布性
                    // 将真实节点地址和循环变量i作为md5的输入，确保每个虚拟节点的key不同
                    byte[] digest = md5(invoker + i);
                    // 每个md5摘要（16字节）可生成4个long类型的哈希值
                    for (int h = 0; h < 4; h++) {
                        // 通过位运算从摘要字节数组中生成一个long类型的hash值
                        long m = hash(digest, h);
                        // 将计算出的虚拟节点哈希值和对应的真实服务地址存入treemap
                        virtualInvokers.put(m, invoker);
                    }
                }
            }

        }

        static byte[] md5(String key) {
            MessageDigest md;
            try {
                // 获取一个md5消息摘要实例
                md = MessageDigest.getInstance("MD5");
                byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                // 更新摘要
                md.update(bytes);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // 完成hash计算，返回摘要
            return md.digest();
        }

        static long hash(byte[] digest, int idx) {
            // 通过位运算，将digest数组中4个字节拼接成一个long类型的值
            // & 255 是为了将有符号的 byte 转换成无符号的 int
            // << 24, << 16, << 8 是位移操作
            // & 4294967295L (即 0xFFFFFFFFL) 是为了确保结果是一个 32 位的无符号整数，存储在 long 中
            return ((long) (digest[3 + idx * 4] & 255) << 24 | (long) (digest[2 + idx * 4] & 255) << 16
                    | (long) (digest[1 + idx * 4] & 255) << 8 | (long) (digest[idx * 4] & 255)) & 4294967295L;
        }

        /**
         * 根据 RPC 服务 key 选择一个服务地址
         * 这里就是根据所需要的服务的key生成一个hash值
         * 然后上环，再调用selectforkey，找顺时针第一个虚拟节点对应的服务
         * 就选这个服务
         *
         * @param rpcServiceKey RPC 服务的唯一标识 (通常是服务名+参数)
         * @return 选择的服务地址
         */
        public String select(String rpcServiceKey) {
            // 计算rpcservicekey的md5摘要
            byte[] digest = md5(rpcServiceKey);
            // 从摘要中生成一个hash值，并根据这个hash值选择一个节点
            return selectForKey(hash(digest, 0));

        }

        /**
         * 根据给定的哈希码在哈希环上选择一个服务地址
         *
         * @param hashCode 请求的哈希码
         * @return 选择的服务地址
         */
        public String selectForKey(long hashCode) {
            // 使用 TreeMap 的 tailMap 方法找到哈希环上所有大于或等于 hashCode 的虚拟节点. [2, 4, 18]
            // inclusive=true 表示查找结果包含 hashCode 本身
            // firstEntry() 返回这个子 Map 中的第一个条目，即顺时针方向上的第一个虚拟节点
            Map.Entry<Long, String> entry = virtualInvokers.tailMap(hashCode, true).firstEntry();

            // 如果 entry 为 null，说明 hashCode 大于所有虚拟节点的哈希值
            // 这种情况下，请求会落在哈希环的末尾，根据环形结构，应该由第一个节点来处理
            if (entry == null) {
                // 获取 TreeMap 中的第一个条目，即哈希值最小的虚拟节点
                entry = virtualInvokers.firstEntry();
            }

            // 返回该虚拟节点对应的真实服务地址
            return entry.getValue();
        }
    }
}
