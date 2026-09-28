package simple.proxy;

import common.enums.RpcErrorMessageEnum;
import common.enums.RpcResponseCodeEnum;
import common.exception.RpcException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import simple.config.RpcServiceConfig;
import simple.remoting.dto.RpcRequest;
import simple.remoting.dto.RpcResponse;
import simple.remoting.transport.RpcRequestTransport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * RpcClientProxy 代理对象
 *
 * @author 执笔画棠
 * @date 2025/10/18 23:00
 **/
@Slf4j
public class RpcClientProxy implements InvocationHandler {

    //这个是rpc服务的接口名称，用于在zk上注册服务
    private static final String INTERFACE_NAME = "interfaceName";

    //这个是rpc的请求传输层，用于发送rpc请求到服务器，这个接口实现了两种实现类
    //一种是用netty框架，一种是传统的socket
    //SocketRpcClient和NettyRpcClient
    //也是rpc客户端。没有他就没法向rpc服务端（服务提供者）发送rpc请求
    private final RpcRequestTransport rpcRequestTransport;

    //这个是rpc服务的配置类，用于配置rpc服务的相关信息。
    //在这是封装了对应服务的，代表这个代理对象就是这个服务的，用来实现这个服务的功能
    private final RpcServiceConfig rpcServiceConfig;


    public RpcClientProxy(RpcRequestTransport rpcRequestTransport, RpcServiceConfig rpcServiceConfig) {
        this.rpcRequestTransport = rpcRequestTransport;
        this.rpcServiceConfig = rpcServiceConfig;
    }

    public RpcClientProxy(RpcRequestTransport rpcRequestTransport) {
        this.rpcRequestTransport = rpcRequestTransport;
        this.rpcServiceConfig = new RpcServiceConfig();
    }

    /*
     *创建代理对象
     */
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Class<T> clazz) {
        /*
         * 创建一个动态代理对象，用于调用远程方法
         * clazz.getClassLoader()：获取类加载器，用于加载代理类
         * new Class<?>[] { clazz }：指定要代理的接口，这里是 clazz 接口
         * this：指定InvocationHandler，这里是当前类 RpcClientProxy，用于处理代理对象的方法调用
         */
        return (T) Proxy.newProxyInstance(clazz.getClassLoader(), new Class<?>[]{clazz}, this);
    }

    /*
     *最核心的方法，就是通过代理对象的这个方法，来实现后续的一切操作
     *代理对象实现远程调用，创建rpc请求，通过rpc客户端发送出去得到结果
     */
    @SneakyThrows
    @SuppressWarnings("unchecked")
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        //先创建一个rpc请求
        log.info("invoked method:[{}]", method.getName());
        //创建一个rpc请求对象
        RpcRequest rpcRequest = RpcRequest.builder()
                //方法名称，用于服务端根据方法名称和参数类型找到对应的方法
                .methodName(method.getName())
                .parameters(args)
                .interfaceName(method.getDeclaringClass().getName())
                .paramTypes(method.getParameterTypes())
                .requestId(UUID.randomUUID().toString())
                .group(rpcServiceConfig.getGroup())
                .version(rpcServiceConfig.getVersion())
                .build();

        // 2. 发送RPC请求，通过rpc客户端发送rpc请求
        // RPC 调用触发 -> sendRpcRequest 方法被调用 -> [第1步] 服务发现，拿到IP地址 -> [第2步] 获取/建立连接 ->
        // [第3步] 发送请求数据
        // 发送RPC请求，通过rpc客户端发送rpc请求，返回rpc响应
        RpcResponse<Object> rpcResponse = (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);

        this.check(rpcResponse, rpcRequest);
        return rpcResponse.getData();
    }

    // 这里处理三种异常,在rpcerrormessageenmu中定义的：
    // 1. rpcResponse为空
    // 2. rpcRequest的requestId和rpcResponse的requestId不匹配
    // 3. rpcResponse的code不是SUCCESS
    private void check(RpcResponse<Object> rpcResponse, RpcRequest rpcRequest) {
        if (rpcResponse == null) {
            throw new RpcException(RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                    INTERFACE_NAME + ":" + rpcRequest.getInterfaceName());
        }

        if (!rpcRequest.getRequestId().equals(rpcResponse.getRequestId())) {
            throw new RpcException(RpcErrorMessageEnum.REQUEST_NOT_MATCH_RESPONSE,
                    INTERFACE_NAME + ":" + rpcRequest.getInterfaceName());
        }

        // 这里检查响应码
        if (rpcResponse.getCode() == null || !rpcResponse.getCode().equals(RpcResponseCodeEnum.SUCCESS.getCode())) {
            throw new RpcException(RpcErrorMessageEnum.SERVICE_INVOCATION_FAILURE,
                    INTERFACE_NAME + ":" + rpcRequest.getInterfaceName());
        }
    }
}
