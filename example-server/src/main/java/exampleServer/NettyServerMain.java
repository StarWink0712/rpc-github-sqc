package exampleServer;

import helloserviceapi.Hello;
import helloserviceapi.HelloService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import simple.annotation.RpcReference;
import simple.annotation.RpcScan;
import simple.remoting.transport.netty.server.NettyRpcServer;


/**
 * NettyServerMain 服务端启动类
 *
 * @author 执笔画棠
 * @date 2025/10/14 17:29
 **/
@SpringBootApplication(scanBasePackages = {"exampleServer", "simple", "common", "helloserviceapi"})
/*
 * basePackage: 扫描指定包下的所有类，将标注了 @RpcService 注解的类注册为 Spring Bean
 */
@RpcScan(basePackage = {"exampleServer"})
public class NettyServerMain implements CommandLineRunner {

    //注入rpc服务器，
    @Autowired
    private NettyRpcServer nettyRpcServer;

    public static void main(String[] args) {
        SpringApplication.run(NettyServerMain.class, args);

    }

    //本地测试
    @Autowired
    private HelloService helloService;

    /*
     * @description:服务端启动时，会自动执行此方法
     * 首先本地调用一下helloservice测试一下
     * 然后启动服务端的rpc服务器，其作用是将有rpcService注解的实例
     * 注册到注册中心，如HelloServiceImpl，并开始监听客户端的连接端口
     * @date: 2025/10/14 18:15
     * @author: 执笔画棠
     * @param args :
     * @return: void
     **/
    @Override
    public void run(String... args) throws Exception {
        helloService.hello(new Hello("西野七濑", "你在哪呢？"));
        //启动rpc服务器，其会开始向注册中心注册服务，并开始监听客户端连接端口
        //***********************************************服务端第一步***********************************************//
        //因为服务端这个进程是提供服务的，所有就直接启动rpcserver服务器就可以
        //进start之后就是一系列监听客户端端口，接收客户端的请求等
        nettyRpcServer.start();
        //***********************************************服务端第一步***********************************************//
    }
}
