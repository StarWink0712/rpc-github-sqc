package simple.remoting.transport.netty.client;

import common.enums.CompressTypeEnum;
import common.enums.SerializationTypeEnum;
import common.enums.ServiceDiscoveryEnum;
import common.extension.ExtensionLoader;
import common.factory.SingletonFactory;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileUrlResource;
import simple.registry.ServiceDiscovery;
import simple.remoting.constants.RpcConstants;
import simple.remoting.dto.RpcMessage;
import simple.remoting.dto.RpcRequest;
import simple.remoting.dto.RpcResponse;
import simple.remoting.transport.RpcRequestTransport;
import simple.remoting.transport.netty.codec.RpcMessageDecoder;
import simple.remoting.transport.netty.codec.RpcMessageEncoder;

import java.beans.IntrospectionException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * NettyRpcClient rpc客户端
 *
 * @author 执笔画棠
 * @date 2025/10/18 23:12
 **/
@Slf4j
public class NettyRpcClient implements RpcRequestTransport {

    // 服务发现
    private final ServiceDiscovery serviceDiscovery;
    // 未处理的请求表，存暂时还没有处理的请求.它像一个“等候室”，存放着所有已发送但尚未收到响应的请求凭证 (`CompletableFuture`)。
    // rpcclient发送请求后，会以requestid作为健把这个请求的completeablefuture对象存到里面，等拿到响应后再去里面找到这个future删除
    private final UnprocessedRequests unprocessedRequests;
    // 通道提供器，管理rpc客户端和rpc服务端之间的channel通道
    private final ChannelProvider channelProvider;
    // bootstrp对象，初始化配置客户端
    private final Bootstrap bootstrap;
    // 事件循环组，处理io操作
    private final EventLoopGroup eventLoopGroup;

    public NettyRpcClient() throws Exception {
        eventLoopGroup = new NioEventLoopGroup();
        // 这完全正确。通常，Bootstrap (客户端) 或 ServerBootstrap (服务端) 的配置和启动调用
        // 是在我们应用程序的主线程 (main 线程) 或某个专门的初始化线程中完成的。
        // 这个线程就是我们所说的业务线程
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new LoggingHandler(LogLevel.INFO))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        // 如果5秒没有数据交互，则发送心跳请求判断是否还在连接
                        p.addLast(new IdleStateHandler(0, 5, 0, TimeUnit.SECONDS));
                        p.addLast(new RpcMessageDecoder());
                        p.addLast(new RpcMessageEncoder());
                        // 客户端的hanler处理器,这里面包含了sendRpcRequest方法中的
                        // netty框架的io线程拿到响应，在handler处理器中，完成
                        // resultFuture结果的响应注入操作
                        p.addLast(new NettyRpcClientHandler());
                    }
                });

        // 初始化服务发现器,用扩展加载器
        this.serviceDiscovery = ExtensionLoader.getExtensionLoader(ServiceDiscovery.class)
                .getExtension(ServiceDiscoveryEnum.ZK.getName());

        // 初始化未处理请求表
        this.unprocessedRequests = SingletonFactory.getInstance(UnprocessedRequests.class);
        // 初始化通道提供器，用于管理已连接的通道
        /*
         * 是实现Netty连接重用机制的关键类。
         * 它通过维护服务地址到Channel的映射关系，
         * 避免了频繁创建和关闭连接带来的性能开销。
         */
        this.channelProvider = SingletonFactory.getInstance(ChannelProvider.class);
    }

    /**
     * 连接服务器并获取通道，用于发送Rpc消息
     * 即创建连接
     *
     * @param inetSocketAddress 服务器地址
     * @return 通道对象
     */
    // 这个注解是为了处理异常，避免在方法中抛出checked异常
    @SneakyThrows
    public Channel doConnect(InetSocketAddress inetSocketAddress) {
        /*
         * CompletableFuture 在这个场景下的经典用法：将一个异步操作转换为一个同步等待的结果。
         * 调用线程 (我们称之为“业务线程”)：即执行 doConnect 方法的线程。
         * Netty I/O 线程：由 eventLoopGroup 管理，专门负责网络 I/O 操作（如建立连接、读写数据）的线程。
         * 执行流程如下：
         * [业务线程] 调用 doConnect 方法。
         * [业务线程] 创建一个空的 CompletableFuture<Channel> 对象 completableFuture。
         * [业务线程] 调用 bootstrap.connect(...)。这是一个异步调用，它会立即返回，不会等待连接建立成功。
         * 业务线程只是向 Netty I/O 线程提交了一个“请帮我连接这个地址”的任务，并注册了一个监听器（addListener）。
         * [业务线程] 代码继续向下执行，到达 return completableFuture.get();。
         * 此时，get() 方法会导致业务线程被阻塞（挂起/等待），因为它试图从一个尚未完成的 future 中获取结果。
         * --- 与此同时，在另一个线程上 ---
         * [Netty I/O 线程] 在后台执行实际的 TCP 连接操作。
         * [Netty I/O 线程] 当连接操作完成时（成功或失败），Netty 会回调业务线程之前注册的监听器。
         * [Netty I/O 线程] 在监听器内部，future.isSuccess() 被判断为 true，
         * 于是 I/O 线程执行 completableFuture.complete(future.channel());。
         * --- 这一步是关键的“信令” ---
         * [业务线程] 由于它正在等待的 completableFuture 对象被 I/O 线程“完成”了（结果被填入了），get() 方法立即解除阻塞
         * 并成功获取到由 I/O 线程填入的 Channel 对象。
         * [业务线程] get() 方法返回 Channel 对象，doConnect 方法随之将这个 Channel 对象返回给最初的调用者。
         */
        CompletableFuture<Channel> completableFuture = new CompletableFuture<>();
        bootstrap.connect(inetSocketAddress).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // 如果连接成功
                log.info("客户端成功连接到[{}]", inetSocketAddress.toString());
                /*
                 * 分析：这个方法展示了 CompletableFuture 的第一个应用场景。
                 * bootstrap.connect() 是一个异步操作，它会立即返回一个 ChannelFuture，而不会等待连接真正建立。
                 * 代码通过 .addListener() 注册了一个回调。当连接成功时，这个回调函数会被 Netty 的 I/O 线程执行。
                 * 在回调中，completableFuture.complete(future.channel()) 被调用，将成功建立的 Channel 放入
                 * completableFuture 中。
                 * 主线程调用 completableFuture.get()，它会阻塞在这里，直到 I/O 线程完成了连接并调用了 complete
                 * 方法。这样，一个异步的连接操作就被转换成了对调用者而言的同步等待。
                 */
                completableFuture.complete(future.channel());
            } else {
                throw new IllegalStateException();
            }
        });
        /*
         * get方法是CompletableFuture的一个阻塞方法，
         * 它(业务线程)会等待异步操作完成并返回结果。
         * 在这个例子中，它会等待连接成功并返回成功建立的 Channel。
         * return completableFuture.get();
         * 这里是业务线程等待前面的 completableFuture.complete(future.channel());
         * 执行完，然后从completableFuture中拿到channel返回
         */
        return completableFuture.get();
    }

    @Override
    public Object sendRpcRequest(RpcRequest rpcRequest) {
        /*
         * 业务线程: 调用 sendRpcRequest 的线程，它想要 RPC 调用的结果。
         * Netty I/O 线程: 负责处理网络数据读写的线程，它从服务器接收响应。
         * UnprocessedRequests: 共享的“公告板”，用于存放“寻物启事”（CompletableFuture）。
         */
        // 先创建一个和doconnect方法里同理的凭证,不过这里存的是响应，前面存channel
        CompletableFuture<RpcResponse<Object>> resultFuture = new CompletableFuture<>();
        // 查找服务地址
        InetSocketAddress inetSocketAddress = serviceDiscovery.lookupService(rpcRequest);
        // 获取channel通道
        Channel channel = getChannel(inetSocketAddress);
        if (channel.isActive()) {
            log.info("client send message [{}]", rpcRequest);
            // 重点，因为netty是多线程处理（io多路复用），一个个线程就可以管理多个请求，因此要以请求id为key把凭证存入到unprocessedRequests中
            // 以便拿到响应之后再根据请求id把响应结果存入到凭证中
            unprocessedRequests.put(rpcRequest.getRequestId(), resultFuture);

            // 构建rpc消息
            RpcMessage rpcMessage = RpcMessage.builder().data(rpcRequest)
                    .codec(SerializationTypeEnum.HESSIAN.getCode())
                    .compress(CompressTypeEnum.GZIP.getCode())
                    .messageType(RpcConstants.REQUEST_TYPE).build();

            // 发送rpc消息
            channel.writeAndFlush(rpcMessage).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    // 这里成功了但是和doConnect里不一样，没有future.complete方法把响应注入到future中
                    // 这是因为由client的响应处理器完成这个操作
                    // 响应通过channel传回来时，会经过添加的channehandler
                    // 即NettyRpcClientHandler
                    log.info("成功发送消息[{}]", rpcMessage);
                } else {
                    future.channel().close();
                    // 如果发送失败，关闭通道并完成凭证 exceptionally
                    resultFuture.completeExceptionally(future.cause());
                    log.error("发送识别[{}]", future.cause());
                }
            });
        } else {
            throw new IllegalStateException();
        }
        // 得到响应结果
        try {
            // 和doconnect里一样，这里业务线程阻塞拿到响应并返回
            return resultFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("rpc请求失败，" + e.getMessage());
        }
    }

    /**
     * 获取通道，若不存在则创建新连接
     * 连接重用机制
     *
     * @param inetSocketAddress 服务器地址
     * @return 通道对象
     */
    public Channel getChannel(InetSocketAddress inetSocketAddress) {
        // 从通道提供器中获取通道
        Channel channel = channelProvider.get(inetSocketAddress);
        if (channel == null) {
            // 如果通道不存在，创建新连接
            channel = doConnect(inetSocketAddress);
            // 将新连接存入通道提供器
            channelProvider.set(inetSocketAddress, channel);
        }
        return channel;
    }

    public void close() {
        // 关闭事件循环组
        eventLoopGroup.shutdownGracefully();
    }
}
