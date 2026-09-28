package simple.remoting.transport;

import common.extension.SPI;
import simple.remoting.dto.RpcRequest;

/**
 * RpcRequestTransport 传输层核心接口
 * 其方法就是发送rpc请求，有netty和socket两种实现类
 * <p>
 * rpc客户端实现这个接口，在代理对象里面
 * RpcResponse<Object> rpcResponse = (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);
 * **在客户端（调用方）**
 * <p>
 * 1. **业务调用**: “传统用户客户端” (您的业务代码) 像调用一个本地接口一样，调用代理对象的方法。
 * - `UserService userService = RpcProxy.getProxy(UserService.class);`
 * - `User user = userService.findUserById(123); // 看似本地调用`
 * 2. **代理拦截与封装**: 这个调用实际上被RPC框架生成的**代理对象 (Proxy)** 拦截了。
 * 代理对象将调用的信息（接口名`UserService`、方法名`findUserById`、参数`123`）封装成一个标准的、可在网络中传输的**RPC请求对象 (RpcRequest)**。
 * 3. **网络发送**: 代理对象将这个 `RpcRequest` 对象交给 **RPC客户端 (NettyRpcClient)**。`NettyRpcClient` 负责：
 * - 将 `RpcRequest` 对象**序列化**成二进制字节流。
 * - 通过Netty管理的长连接，将这个二进制流发送到网络中。
 *
 * @author 执笔画棠
 * @version 2025/10/14 18:20
 **/
@SPI
public interface RpcRequestTransport {

    //就一个方法，rpc客户端的作用就是发送rpc请求
    Object sendRpcRequest(RpcRequest rpcRequest);
}