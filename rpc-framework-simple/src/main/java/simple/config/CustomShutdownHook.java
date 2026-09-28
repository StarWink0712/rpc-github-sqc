package simple.config;

import common.utils.concurrent.ThreadPoolFactoryUtil;
import lombok.extern.slf4j.Slf4j;
import simple.registry.Impl.util.CuratorUtils;
import simple.remoting.transport.netty.server.NettyRpcServer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * CustomShutdownHook 自定义钩子方法
 * 当服务关闭时，清除所有服务注册的操作
 *
 * @author 执笔画棠
 * @date 2025/10/15 16:27
 **/
/*
 * NettyRpcServer中调用clearAll方法，当JVM关闭时，会执行清除所有服务注册的操作
 * SocketRpcServer中也调用clearAll方法，当JVM关闭时，会执行清除所有服务注册的操作
 */
@Slf4j
public class CustomShutdownHook {

    //该类采用饿汉式单例，确保在类加载时就创建实例并且只创建一个
    private static final CustomShutdownHook CUSTOM_SHUTDOWN_HOOK = new CustomShutdownHook();

    public static CustomShutdownHook getCustomShutdownHook() {
        return CUSTOM_SHUTDOWN_HOOK;
    }

    public void clearAll() {
        log.info("注册一个关闭钩子线程，addshutdownhook for clearAll");

        //这个add方法就是注册一个关闭钩子，在程序退出时启动
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                //获取本机地址并注销服务，即注销当前服务节点
                InetSocketAddress inetSocketAddress = new InetSocketAddress(InetAddress.getLocalHost().getHostAddress(),
                        NettyRpcServer.PORT);
                // 清除当前服务节点的注册信息
                CuratorUtils.clearRegistry(CuratorUtils.getZkClient(), inetSocketAddress);
            } catch (UnknownHostException e) {
            }
            //关闭所有线程池
            ThreadPoolFactoryUtil.shutDownAllThreadPool();
        }));
    }
}
