package simple.remoting.transport.netty.server;

import common.enums.CompressTypeEnum;
import common.enums.RpcResponseCodeEnum;
import common.enums.SerializationTypeEnum;
import common.factory.SingletonFactory;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import simple.remoting.constants.RpcConstants;
import simple.remoting.dto.RpcMessage;
import simple.remoting.dto.RpcRequest;
import simple.remoting.dto.RpcResponse;
import simple.remoting.handler.RpcRequestHandler;

/**
 * 这个 NettyRpcServerHandler 是 RPC 服务器端的核心业务处理器，
 * 负责处理客户端发来的 RPC 请求。
 * 客户端通过netty向服务端发送rpc请求，
 * 即RpcClientProxy客户端代理对象里
 * RpcResponse<Object> rpcResponse = (RpcResponse<Object>) rpcRequestTransport.sendRpcRequest(rpcRequest);
 * 这里就是处理这个请求
 *
 * @author 执笔画棠
 * @date 2025/10/16 18:12
 **/
@Slf4j
public class NettyRpcServerHandler extends ChannelInboundHandlerAdapter {

    private final RpcRequestHandler rpcRequestHandler;

    public NettyRpcServerHandler() throws Exception {
        this.rpcRequestHandler = SingletonFactory.getInstance(RpcRequestHandler.class);
    }

    //***********************************************服务端第三步***********************************************//
    //到这就是读取通道内的数据了，之后就是一系列的处理从通道内得到的rpc请求，再调用rpcRequestHandler，得到对应的结果
    //然后封装成响应再到rpcmessage返回给客户端（服务需求者），到这服务端的逻辑基本上就差不多了
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            //如果通道中获得到的msg是rpcmessage类型的
            if (msg instanceof RpcMessage) {
                log.info("server receive msg:[{}]", msg);
                //从通道中获取的请求信息中，得到消息类型
                byte messageType = ((RpcMessage) msg).getMessageType();
                //构建一个rpc消息,用于封装rpc响应
                RpcMessage rpcMessage = new RpcMessage();
                rpcMessage.setCodec(SerializationTypeEnum.HESSIAN.getCode());
                rpcMessage.setCompress(CompressTypeEnum.GZIP.getCode());
                if (messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
                    rpcMessage.setMessageType(RpcConstants.HEARTBEAT_RESPONSE_TYPE);
                    rpcMessage.setData(RpcConstants.PONG);
                } else {
                    //从rpcmessage中提取data，即rpc请求
                    RpcRequest rpcRequest = (RpcRequest) ((RpcMessage) msg).getData();

                    //这里是核心，前面从通道中一系列操作获取到了rpc请求，这里把请求传递给
                    //rpc请求处理器来处理请求，然后进到请求处理器里面就是在本地获取服务执行方法
                    //然后得到结果
                    Object result = rpcRequestHandler.handle(rpcRequest);
                    //控制台展示结果
                    log.info(String.format("server get result: %s", result.toString()));
                    //设置消息类型为rpc响应
                    rpcMessage.setMessageType(RpcConstants.RESPONSE_TYPE);
                    if (ctx.channel().isActive() && ctx.channel().isWritable()) {
                        //这里请求id很重要，前面说过了rpc是非阻塞的并且是多线程，每个请求都有自己的独特id
                        //返回响应时要把请求中的id拿出来再封装到响应里，以便服务需求端根据id相对应
                        RpcResponse<Object> rpcResponse = RpcResponse.success(result, rpcRequest.getRequestId());
                        rpcMessage.setData(rpcResponse);
                    } else {
                        RpcResponse<Object> rpcResponse = RpcResponse.fail(RpcResponseCodeEnum.FAIL);
                        rpcMessage.setData(rpcResponse);
                        log.error("现在服务不可用");
                    }
                }
                //写入响应消息到客户端，通过channel通道,并添加一个监听器，当发送失败时关闭连接,点进去看源码一眼明白
                ctx.writeAndFlush(rpcMessage).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

            }
        } finally {
            //无论成功与否释放缓冲区
            ReferenceCountUtil.release(msg);
        }
    }
//***********************************************服务端第三步***********************************************//

    /**
     * 处理用户事件触发
     * <p>
     * 当检测到读取空闲状态时，关闭连接
     *
     * @param ctx 通道处理上下文
     * @param evt 事件对象
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        //如果是事件空闲状态
        if (evt instanceof IdleStateEvent) {
            //从事件中获取空闲状态
            IdleState state = ((IdleStateEvent) evt).state();
            //然后是读取空闲状态，则关闭连接
            if (state == IdleState.READER_IDLE) {
                log.info("idle check happen, so close the connection");
                //如果发生读空闲，服务器认为客户端可能已经断开，主动关闭连接以释放资源
                ctx.close();
            }
        } else {
            //如果是其他事件，则传递给父类处理
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 处理异常事件
     * <p>
     * 当捕获到异常时，记录错误日志并关闭连接
     *
     * @param ctx   通道处理上下文
     * @param cause 异常对象
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {

        log.error("server catch exception");
        //关闭与客户端的连接
        cause.printStackTrace();
        ctx.close();
    }

}
