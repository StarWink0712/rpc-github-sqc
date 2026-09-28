package simple.remoting.transport.netty.server;

import common.utils.RuntimeUtil;
import common.utils.concurrent.ThreadPoolFactoryUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import simple.config.CustomShutdownHook;
import simple.config.RpcServiceConfig;
import common.factory.SingletonFactory;
import simple.provider.Impl.ZKServiceProviderImpl;
import simple.provider.ServiceProvider;
import simple.remoting.transport.netty.codec.RpcMessageDecoder;
import simple.remoting.transport.netty.codec.RpcMessageEncoder;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

/**
 * NettyRpcServer nettyRpc服务端
 * 设计了一个完整的rpc服务端服务器
 * 包含boss线程组，worker线程组
 * 客户端 (Client)  <-------- 网络通信 -------->  服务器 (Server)
 * |                                            |
 * 调用远程方法                                 执行实际方法
 * |                                            |
 * 1. 发送请求                                 2. 接收请求
 * 2. 等待响应                                 3. 处理业务逻辑
 * 3. 接收结果                                 4. 返回结果
 * netty就在网络通信里发挥作用，通过网络在服务端客户端以及注册中心之间传递rpc请求和rpc响应时，
 * 传统nio很麻烦，但是如果用netty就很方便
 * <p>
 * 传统 NIO: 😭 麻烦、复杂、容易出错
 * ↓
 * Netty:    😎 方便、简洁、稳定可靠
 * ┌─────────────┐
 * ↓             │
 * ┌─────────┐   ┌──────────────┐   ┌─────────┐
 * │ 服务端  │←──│ 注册中心(ZK)  │──→│ 客户端  │
 * │(Server) │   │              │   │(Client) │
 * └─────────┘   └──────────────┘   └─────────┘
 * ↑                                  │
 * │      RPC 请求/响应                │
 * └──────────────────────────────────┘
 * 这三者之间都需要网络通信：
 * 服务端 ↔ 注册中心：注册/注销服务
 * 客户端 ↔ 注册中心：查询可用服务
 * 客户端 ↔ 服务端：发送 RPC 请求，接收响应
 *
 * @author 执笔画棠
 * @date 2025/10/14 18:30
 **/
@Slf4j
@Component
public class NettyRpcServer {

    //服务器的端口号，默认9998
    public static final int PORT = 9998;

    //服务提供者，
    private final ServiceProvider serviceProvider = SingletonFactory.getInstance(ZKServiceProviderImpl.class);

    public NettyRpcServer() throws Exception {
    }

    /**
     * @param rpcServiceConfig: 这个参数用来表示要添加到注册中心的服务
     * @description:自动注册方法，内部调用服务提供器，将服务注册到注册中心手动注册 API （NettyRpcServer.registerService）
     * - NettyRpcServer 类提供了 registerService 方法作为公共 API
     * - 该方法内部也是调用 serviceProvider.publishService(rpcServiceConfig)
     * - 这种方式适用于不使用 Spring 或需要动态注册服务的场景
     * 所有这里这个方法并没有用到，而是在SpringBeanPostProcessor方法中在服务端启动的时候就注册服务到注册中心
     * @date: 2025/10/14 18:33
     * @author: 执笔画棠
     * @return: void
     **/
    public void registerService(RpcServiceConfig rpcServiceConfig) {

        /**
         * 这里调用服务提供器，将服务注册到注册中心
         * @param rpcServiceConfig :
         * @return: void
         **/
        serviceProvider.publishService(rpcServiceConfig);
    }

//***********************************************服务端第二步***********************************************//

    /**
     * @description:启动rpc服务器 注册关闭钩子，当jvm关闭时执行清楚所有服务注册的操作
     * 绑定服务器端口，开始监听客户端连接
     * 打印服务器启动日志，包含主机地址和端口
     * @date: 2025/10/15 15:33
     * @author: 执笔画棠
     * @return: void
     **/
    @SneakyThrows
    public void start() {
        //注册服务器钩子
        CustomShutdownHook.getCustomShutdownHook().clearAll();
        //获取本机地址
        String host = InetAddress.getLocalHost().getHostAddress();
        // 打印服务器启动日志，包含主机地址和端口号
        log.info("Server started on host: {}, port: {}", host, PORT);
        // 创建 Boss 线程组，用于接受客户端连接
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        //创建worker线程组，用于处理已连接的链接，即读取数据等处理
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        //创建服务处理线程组，用于rpc请求，特别的worker线程组，专门用来处理rpc请求，普通的worker线程处理其他的请求
        DefaultEventExecutorGroup serviceHandlerGroup = new DefaultEventExecutorGroup(
                //线程数为cpu核心数的二倍
                //todo为什么是核心数的二倍
                RuntimeUtil.cpus() * 2,
                //创建一个线程工厂，用于创建线程，传入的参数就是线程工厂的名字，作为创建的线程名字的前缀
                ThreadPoolFactoryUtil.createThreadFactory("service-handler-group", false)
        );
        try {
            //创建服务端启动引导类
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    //指定使用nio异步io
                    .channel(NioServerSocketChannel.class)
                    //todo什么是nagle算法？为什么不用
                    //指定tcp传输，其默认开启了nagle算法，
                    //该算法的作用是尽可能的发送大数据块，减少网络传输，
                    //这个参数就是控制是否使用nagle算法
                    // true 表示禁用 Nagle 算法，发送数据立即发送，而不是等待数据累积到一定大小后发送。
                    //https://blog.csdn.net/m0_61567378/article/details/130886149
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    //是否开启TCP底层心跳机制 https://zhuanlan.zhihu.com/p/626960589
                    //定期发送探测包检测连接是否存活
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    //表示系统用于临时存放已完成三次握手的请求的队列的最大长度
                    //如果建立连接频繁，服务器处理新连接较慢，可以调大这个参数
                    //最多允许 128 个已完成三次握手但未被 accept 的连接排队等待。
                    .option(ChannelOption.SO_BACKLOG, 128)
                    //为服务器添加日志处理器log级别为info
                    .handler(new LoggingHandler(LogLevel.INFO))
                    //客户端第一次进行请求连接时才会进行初始化
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        //初始化channel通道，配置netty处理链
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            //30秒没有收到客户端请求的话就关闭连接
                            ChannelPipeline p = ch.pipeline();
                            //30秒没有客户端的请求的化就关闭连接
                            //这里的关闭连接是指服务端关闭连接,客户端还可以继续发送请求,但是服务端不会再处理该请求
                            p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
                            //添加编解码器
                            p.addLast(new RpcMessageEncoder());
                            p.addLast(new RpcMessageDecoder());
                            //添加服务端channel的处理链，包含服务处理线程组和nettyrpc处理器
                            //这个处理器里面会从ctx通道中获取rpcmessage信息处理，从中提取出rpcrequest
                            //然后在本地找服务调用相应的方法得到结果，最后把结果封装成rpcresponse再到rpcmessage
                            //通过ctx返回给客户端
                            //***********************************************服务端第三步***********************************************//
                            //启动rpc服务端后，就是监听通道了，然后就是进入监听通道内部的处理逻辑
                            p.addLast(serviceHandlerGroup, new NettyRpcServerHandler());
                            //***********************************************服务端第三步***********************************************//
                        }
                    });
            //绑定ip和端口，阻塞等待绑定成功
            ChannelFuture f = b.bind(host, PORT).sync();
            //等待服务端监听端口关闭
            f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("occur exception when start server:", e);
        } finally {
            log.error("shutdown bossGroup and workerGroup");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            serviceHandlerGroup.shutdownGracefully();
        }

    }
}
