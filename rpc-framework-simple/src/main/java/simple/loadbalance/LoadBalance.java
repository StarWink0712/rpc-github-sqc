package simple.loadbalance;

import common.extension.SPI;
import simple.remoting.dto.RpcRequest;

import java.util.List;

/**
 * LoadBalance 负载均衡
 *
 * @author 执笔画棠
 * @version 2025/10/19 16:13
 **/
@SPI
public interface LoadBalance {

    //从一堆服务地址中选择一个目标服务地址
    String selectServiceAddress(List<String> serviceUrllist, RpcRequest rpcRequest);
}