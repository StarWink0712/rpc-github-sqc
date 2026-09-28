package simple.loadbalance.loadbalancer;

import simple.loadbalance.AbstractLoadBalance;
import simple.remoting.dto.RpcRequest;

import java.util.List;
import java.util.Random;

/**
 * RandomLoadBalance 随机选择负载均衡
 *
 * @author 执笔画棠
 * @date 2025/10/19 20:22
 **/
public class RandomLoadBalance extends AbstractLoadBalance {
    /**
     * 随机负载均衡策略的实现方法
     *
     * @param serviceAddresses 服务地址列表
     * @param rpcRequest       RPC请求对象
     * @return 目标服务地址
     */
    // 重写了AbstractLoadBalance类的doSelect方法，实现随机负载均衡策略
    @Override
    protected String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest) {
        Random random = new Random();
        // 随机返回一个服务地址
        return serviceAddresses.get(random.nextInt(serviceAddresses.size()));
    }
}
