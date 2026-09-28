package simple.remoting.transport.netty.client;

import common.enums.CompressTypeEnum;
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
import simple.remoting.dto.RpcResponse;

import java.net.InetSocketAddress;

/**
 * NettyRpcClientHandler rpc客户端channel通道处理器
 * 处理服务端发送来的响应数据
 *
 * @author 执笔画棠
 * @date 2025/10/19 23:00
 **/
@Slf4j
public class NettyRpcClientHandler extends ChannelInboundHandlerAdapter {

    private final UnprocessedRequests unprocessedRequests;

    private final NettyRpcClient nettyRpcClient;

    public NettyRpcClientHandler() throws Exception {
        this.nettyRpcClient = SingletonFactory.getInstance(NettyRpcClient.class);
        this.unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
    }

    /**
     * 当 Netty 客户端收到服务器端发送的消息时，会调用此方法
     * 这里我们主要是处理心跳响应和响应消息
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            log.info("client receive msg:[{}]", msg);
            if (msg instanceof RpcMessage) {
                RpcMessage tmp = (RpcMessage) msg;
                byte messageType = tmp.getMessageType();
                if (messageType == RpcConstants.HEARTBEAT_RESPONSE_TYPE) {
                    log.info("heart [{}]", tmp.getData());
                } else if (messageType == RpcConstants.RESPONSE_TYPE) {
                    RpcResponse<Object> rpcResponse = (RpcResponse<Object>) tmp.getData();
                    //这里就和前面对应上了
                    //这个complete方法封装了future的complete方法
                    //这里就把rpcresponse响应数据注入给了CompletableFuture<RpcResponse<Object>
                    unprocessedRequests.complete(rpcResponse);
                }
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * 当 Netty 客户端触发了用户事件时，会调用此方法
     * 这里我们主要是处理心跳事件，当客户端写入空闲时，发送心跳包
     * idleState是Netty提供的一个枚举类，用于表示通道的空闲状态
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleState state = ((IdleStateEvent) evt).state();
            if (state == IdleState.WRITER_IDLE) {
                log.info("write idle happen[{}]", ctx.channel().remoteAddress());
                /*
                 * getChannel方法用于获取Netty RPC客户端与服务器端建立的通道
                 * remoteAddress方法用于获取通道的远程地址，这里是服务器端的地址
                 * 我们需要将心跳包发送到服务器端
                 */
                // 这一行的作用就是根据channel连接的服务器端地址，获取到与服务器端建立的通道
                // 然后将心跳包发送到该通道
                Channel channel = nettyRpcClient.getChannel((InetSocketAddress) ctx.channel().remoteAddress());
                RpcMessage rpcMessage = new RpcMessage();
                rpcMessage.setCodec(SerializationTypeEnum.PROTOSTUFF.getCode());
                rpcMessage.setCompress(CompressTypeEnum.GZIP.getCode());
                rpcMessage.setMessageType(RpcConstants.HEARTBEAT_REQUEST_TYPE);
                rpcMessage.setData(RpcConstants.PING);
                channel.writeAndFlush(rpcMessage).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 当 Netty 客户端捕获到异常时，会调用此方法
     * 这里我们主要是记录异常日志，并关闭通道
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("client catch exception：", cause);
        cause.printStackTrace();
        ctx.close();
    }
}
