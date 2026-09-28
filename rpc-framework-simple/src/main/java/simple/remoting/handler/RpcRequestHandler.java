package simple.remoting.handler;

import common.exception.RpcException;
import common.factory.SingletonFactory;
import lombok.extern.slf4j.Slf4j;
import simple.provider.Impl.ZKServiceProviderImpl;
import simple.provider.ServiceProvider;
import simple.remoting.dto.RpcRequest;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * RpcRequestHandler
 * rpc请求处理器
 * RpcRequestHandler 在 RPC 服务端的调用链中起到承上启下的关键作用：
 * - 上游：接收来自网络传输层（如 Netty）转发的 RPC 请求
 * - 下游：调用实际的业务服务对象，完成远程方法调用的核心逻辑
 *
 * @author 执笔画棠
 * @date 2025/10/18 13:57
 **/
@Slf4j
public class RpcRequestHandler {

    // 服务提供者
    private final ServiceProvider serviceProvider;

    // 单例工厂获取实例
    public RpcRequestHandler() throws Exception {
        serviceProvider = SingletonFactory.getInstance(ZKServiceProviderImpl.class);
    }

    // 处理rpc请求,从本地获取服务，调用本地服务对应的方法
    public Object handle(RpcRequest rpcRequest) {
        // 通过请求中的服务名字，调用服务提供者的获取服务方法,
        Object service = serviceProvider.getService(rpcRequest.getRpcServiceName());
        return invokeTargetMethod(rpcRequest, service);
    }

    /**
     * 调用目标方法：根据Rpc请求中的方法名和参数列表，调用目标服务对象的对应方法
     *
     * @param rpcRequest 客户端请求
     * @param service    目标服务对象
     * @return 目标方法执行结果
     */
    private Object invokeTargetMethod(RpcRequest rpcRequest, Object service) {
        Object result;
        try {
            Method method = service.getClass().getMethod(rpcRequest.getMethodName(), rpcRequest.getParamTypes());
            result = method.invoke(service, rpcRequest.getParameters());
            log.info("service:[{}] successful invoke method:[{}]", rpcRequest.getInterfaceName(),
                    rpcRequest.getMethodName());
        } catch (NoSuchMethodException | IllegalArgumentException | InvocationTargetException
                | IllegalAccessException e) {
            throw new RpcException(e.getMessage(), e);
        }
        return result;
    }
}
