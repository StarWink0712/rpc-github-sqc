package exampleClient;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import simple.annotation.RpcScan;

/**
 * NettyClientMain 客户端启动类
 *
 * @author 执笔画棠
 * @date 2025/10/14 17:03
 **/
@RpcScan(basePackage = {"exampleClient"})
@SpringBootApplication
public class NettyClientMain implements CommandLineRunner {

    /*
    在 NettyClientMain 这个 Spring Boot 应用里，
    helloController 是一个业务组件，它应该被 Spring 管理。
    因此，必须使用 @Autowired。
    而之前的 RpcRequestHandler 中使用 SingletonFactory 是正确的，
    因为它本身是底层 RPC 框架的一部分，目标是独立于 Spring 运行。
     */
    @Autowired
    private HelloController helloController;

    /// /////////////////////////////////////////客户端启动第一步////////////////////////////////////////////
    public static void main(String[] args) {
        SpringApplication.run(NettyClientMain.class, args);
    }
    ////////////////////////////////////////////客户端启动第一步////////////////////////////////////////////

    /**
     * @param args :
     * @description:springboot启动后自动执行的方法，进行测试
     * @date: 2025/10/14 17:26
     * @author: 执笔画棠
     * @return: void
     **/
    @Override
    public void run(String... args) throws Exception {
        //这个测试方法就是springboot启动后的第一个方法
        //这里调用test方法，进入到hellocontroller内部

        /*
         *到这基本上就没有客户端主动的操作了
         *这里调用了controller的操作，而controller又调用其他进程的服务
         */
        helloController.test();
    }
}
