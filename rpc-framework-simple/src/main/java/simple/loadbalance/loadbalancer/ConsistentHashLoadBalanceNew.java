package simple.loadbalance.loadbalancer;

import common.extension.SPI;
import common.factory.SingletonFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.shaded.com.google.common.hash.HashFunction;
import simple.loadbalance.AbstractLoadBalance;
import simple.remoting.dto.RpcRequest;

import javax.swing.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConsistentHashLoadBalanceNew
 * * @Description: 实现能够动态添加和删除结点的一致性hash负载均衡
 * *
 * *               1. 单例模式创建对象，减少频繁创建对象带来的负载均衡消耗
 * *               2. 每次重构服务器列表，采用了无锁（自旋锁） + 双锁检测，减少上下文切换的异常
 * *               3. 重构服务器列表前，会对整个列表进行检测，减少无用的重构
 *
 * @author 执笔画棠
 * @date 2025/10/19 18:00
 **/
@Slf4j
public class ConsistentHashLoadBalanceNew extends AbstractLoadBalance {

    private final ConcurrentHashMap<String, ConsistentHashingLoadBalancer> selectors = new ConcurrentHashMap<>();

    //重构次数，测试使用
    public static AtomicInteger count = new AtomicInteger();
    public static AtomicInteger createCount = new AtomicInteger();

    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        // 获取 RPC 服务名称，例如 "github.javaguide.HelloService"
        String rpcServiceName = rpcRequest.getRpcServiceName();

        // 1. 从缓存中获取该服务对应的一致性哈希选择器
        ConsistentHashingLoadBalancer selector = selectors.get(rpcServiceName);

        // 如果选择器不存在 (通常是该服务第一次被调用)
        if (selector == null) {
            // 2. 使用单例工厂模式创建一个新的选择器实例，确保每个服务只有一个选择器对象
            // 这样做可以避免重复创建哈希环带来的开销
            selector = SingletonFactory.getInstance(() -> new ConsistentHashingLoadBalancer(
                    serviceAddresses, // 传入当前可用的服务地址列表
                    160,            // 每个真实节点对应的虚拟节点数量
                    new ConsistentHashingLoadBalancer.MD5HashFunction()), ConsistentHashingLoadBalancer.class); // 指定哈希函数实现
            // 将新创建的选择器放入缓存
            selectors.put(rpcServiceName, selector);

        } else if (selector.hasChanged(serviceAddresses)) {
            // 3. 如果选择器已存在，但服务地址列表发生了变化（有节点上线或下线）
            // 先从缓存中获取最新的选择器引用（虽然通常和上面的 selector 是同一个，但这是更严谨的写法）
            selector = selectors.get(rpcServiceName);
            // 调用 reBuild 方法，增量更新哈希环，而不是完全重建
            selector.reBuild(serviceAddresses);
        }

        // 使用 "服务名 + 请求ID" 作为哈希的 key，来选择一个服务节点
        // 使用请求ID可以使得不同的请求尽可能均匀地分布到不同的服务器上
        return selector.selectNode(rpcServiceName + rpcRequest.getRequestId());
    }

    static class ConsistentHashingLoadBalancer {
        //哈希环定义部分，依然使用treemap模拟哈希环
        /*
         *1是虚拟节点，2是hash函数，3是treemap存储节点，4是物理节点列表
         */
        private final TreeMap<Long, String> virtualNodes = new TreeMap<>();
        // 2. 使用 HashSet 存储所有物理节点的地址，方便快速判断节点是否存在。
        private final Set<String> physicalNodes = new HashSet<>();
        private final int virtualNodeCount;
        // 4. 哈希函数，通过接口定义，方便未来替换成其他哈希算法
        private final HashFunction hashFunction;

        /**
         * 状态标志位，使用 volatile 保证其在多线程间的可见性。
         * 当正在初始化或重构哈希环时，此值为 false，防止其他线程使用一个尚未准备好的哈希环。
         */
        private volatile boolean initFlag = false;

        //构造函数，在初始化的时候就要进行hash环的构建
        public ConsistentHashingLoadBalancer(List<String> invokers, int virualNodeCount, HashFunction hashFunction) {
            log.info("创建服务的选择器");
            this.initFlag = false;
            this.virtualNodeCount = virualNodeCount;
            this.hashFunction = hashFunction;
            //构建hash环,遍历所有的物理地址，将其添加到hash环中
            for (String addr : invokers) {
                this.addNode(addr);
            }
            //初始化结束，可以直接使用了
            this.initFlag = true;
            //增加创建次数的计数
            createCount.getAndIncrement();
        }

        /*
         *判断地址列表是否已经发生了变化
         */
        public boolean hasChanged(List<String> address) {
            //判断当前哈希环中的物理节点与传入的服务地址列表是否发生了变化
            /*
            如果节点数目不同那肯定变了。如果数量相同就遍历新的地址列表
            检查是否所有地址都已存在于物理节点集合中
             */
            if (address.size() != this.physicalNodes.size()) {
                return true;
            }
            for (String addr : address) {
                if (!this.physicalNodes.contains(addr)) {
                    //有一个不存在就表明发生了变化
                    return true;
                }
            }
            return false;
        }

        /*
        根据请求的key选择节点，例如服务名+请求id作为请求key
         */
        public String selectNode(String key) {
            // 这是一个自旋锁。如果哈希环正在初始化或重构 (initFlag 为 false)，
            // 当前线程会在此处空转等待，而不是被挂起。这避免了线程上下文切换的开销，适用于等待时间很短的场景。
            while (!initFlag) {
                //初始化没有完成就死循环等待，不进行上下文切换，浪费时间
            }
            if (virtualNodes.isEmpty()) {
                return null;
            }
            //用指定的hash函数计算key的hash值
            long keyHash = hashFunction.hash(key);
            //顺时针找到所有大于等于keyhash的虚拟节点，获取大于等于keyhash的键值对
            SortedMap<Long, String> tailMap = virtualNodes.tailMap(keyHash);
            //如果tailmap为空，说明keyhash比所有虚拟节点的哈希值都大，则选第一个环上节点
            //否则就选tailmap中的第一个节点，即挨着keyhash的顺时针第一个节点
            Long nodeHash = tailMap.isEmpty() ? virtualNodes.firstKey() : tailMap.firstKey();
            //根据找到的节点的哈希值，返回对应的物理节点地址
            return virtualNodes.get(nodeHash);
        }

        /**
         * 重构哈希环。使用 synchronized 关键字保证在同一时刻只有一个线程能执行重构操作。
         */
        public synchronized void reBuild(List<String> address) {
            //先将标志位设置为false
            this.initFlag = false;
            //双重锁定检查，获取锁之后再次检查地址列表是否发生了变化
            //防止多个线程等待锁时，第一个线程已经完成了重构，其他线程就不需要再重构
            if (!this.hasChanged(address)) {
                //如果没有变化就直接恢复标志为并直接返回
                //没有变化就表明没有必要重构选择器，之前的还能用
                this.initFlag = true;
                return;
            }
            log.info("服务列表发生变化，政治重构一次性哈希选择器");
            count.getAndIncrement();//增加重构次数的计数

            //重构哈希环，
            //获取当前地址列表和之前的地址列表，用于计算差异
            Set<String> currentAddress = new HashSet<>(address);
            Set<String> preAddress = new HashSet<>(this.physicalNodes);

            //找出要删除的节点和新增的节点
            List<String> readyToRemove = new ArrayList<>();//待删除列表
            List<String> readyToAdd = new ArrayList<>();//待添加列表
            //遍历新地址列表，如果某个地址在旧列表中不存在，则为新增节点
            for (String addr : address) {
                if (!preAddress.contains(addr)) {
                    readyToAdd.add(addr);
                }
            }
            //遍历旧地址列表，如果有个节点不存在，则为删除节点
            for (String addr : this.physicalNodes) {
                if (!currentAddress.contains(addr)) {
                    readyToRemove.add(addr);
                }
            }

            //计算出差异，更新哈希环
            //移出删除的节点
            for (String r : readyToRemove) {
                this.removeNode(r);
            }
            //添加节点
            for (String r : readyToAdd) {
                this.addNode(r);
            }

            //重构完成，将标志设置为true
            this.initFlag = true;
            log.info("哈希环重构完成");
        }

        /**
         * 向哈希环中添加一个物理节点及其对应的所有虚拟节点
         */
        private void addNode(String node) {
            if (physicalNodes.contains(node)) {
                return;
            }
            physicalNodes.add(node);
            //为这个物理节点创建指定数量的虚拟节点
            for (int i = 0; i < virtualNodeCount; i++) {
                String virtualNodeName = node + "#" + i;
                long hash = hashFunction.hash(virtualNodeName);
                virtualNodes.put(hash, node);
            }

        }

        /**
         * 从哈希环中移除一个物理节点及其对应的所有虚拟节点
         */
        private void removeNode(String node) {
            // 如果节点不存在，则无需操作
            if (!physicalNodes.contains(node)) {
                return;
            }
            // 从物理节点集合中移除
            physicalNodes.remove(node);

            // 移除该物理节点对应的所有虚拟节点
            for (int i = 0; i < virtualNodeCount; i++) {
                // 按照与添加时相同的规则生成虚拟节点名称
                String virtualNodeName = node + "#" + i;
                // 计算哈希值
                long hash = hashFunction.hash(virtualNodeName);
                // 从 TreeMap 中移除这个虚拟节点
                virtualNodes.remove(hash);
            }
        }

        /**
         * 获取当前所有的物理节点列表（只读）
         */
        public List<String> getAllNodes() {
            // 同样使用自旋锁，确保在获取节点列表时，哈希环已处于稳定状态
            while (!initFlag) {
                // 等待
            }
            // 返回一个不可修改的列表视图，防止外部代码意外修改节点列表
            return Collections.unmodifiableList(new ArrayList<>(physicalNodes));
        }

        /**
         * 哈希函数的接口定义，使得哈希算法可以被替换
         */
        public interface HashFunction {
            long hash(String key);
        }

        /**
         * 使用 MD5 算法实现的哈希函数
         */
        public static class MD5HashFunction implements HashFunction {
            @Override
            public long hash(String key) {
                try {
                    // 获取 MD5 消息摘要实例
                    MessageDigest md5 = MessageDigest.getInstance("MD5");
                    // 计算 key 的字节数组的 MD5 摘要
                    byte[] digest = md5.digest(key.getBytes());

                    // 从生成的16字节摘要中，取前8个字节，通过位运算拼接成一个 long 类型的哈希值
                    return ((long) (digest[0] & 0xFF) << 56) |
                            ((long) (digest[1] & 0xFF) << 48) |
                            ((long) (digest[2] & 0xFF) << 40) |
                            ((long) (digest[3] & 0xFF) << 32) |
                            ((long) (digest[4] & 0xFF) << 24) |
                            ((long) (digest[5] & 0xFF) << 16) |
                            ((long) (digest[6] & 0xFF) << 8) |
                            (digest[7] & 0xFF);
                } catch (NoSuchAlgorithmException e) {
                    // 如果系统不支持 MD5 算法，则抛出运行时异常
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
