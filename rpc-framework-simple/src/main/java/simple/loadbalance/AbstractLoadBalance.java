package simple.loadbalance;

import common.utils.CollectionUtil;
import simple.remoting.dto.RpcRequest;

import java.util.List;

/**
 * AbstractLoadBalance
 *
 * @author 执笔画棠
 * @version 2025/10/19 16:15
 **/
public abstract class AbstractLoadBalance implements LoadBalance {
    @Override
    public String selectServiceAddress(List<String> serviceAddress, RpcRequest rpcRequest) {
        //判空
        if (CollectionUtil.isEmpty(serviceAddress)) {
            return null;
        }
        //如果只有一个服务地址可以用
        if (serviceAddress.size() == 1) {
            return serviceAddress.get(0);
        }
        //使用钩子函数进行选择
        return doSelect(serviceAddress, rpcRequest);
    }

    /**
     * 钩子函数：子类可以根据需要重写该方法，实现自定义的负载均衡策略
     *
     * @param serviceAddresses 服务地址列表
     * @param rpcRequest       RPC请求对象
     * @return 目标服务地址
     */
    protected abstract String doSelect(List<String> serviceAddresses, RpcRequest rpcRequest);
}