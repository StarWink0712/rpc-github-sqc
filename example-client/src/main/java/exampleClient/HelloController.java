package exampleClient;

import helloserviceapi.Hello;
import helloserviceapi.HelloService;
import org.springframework.stereotype.Component;
import simple.annotation.RpcReference;

/**
 * HelloController 客户端调用层
 *
 * @author 执笔画棠
 * @date: 2025/10/14 16:09
 **/
@Component
public class HelloController {
    /// /////////////////////////////////////////客户端启动第二步////////////////////////////////////////////
    //这里自定义注解，表示代理对象要注入的是实现helloservice接口的代理对象
    //这里是第二步。SpringBeanPostProcessor发力，创建一个代理对象来处理这个服务的调用！
    // 然后再进入到这个bean处理器内
    //到这客户端的主动操作就已经没了
    //后续创建代理对象是在    SpringBeanPostProcessor的postProcessAfterInitialization方法内，从这往下走就是完整的
    //代理对象发起rpc请求的逻辑了。完全闭环
    @RpcReference(version = "version1", group = "test1")
    private HelloService helloService;
    ////////////////////////////////////////////客户端启动第二步////////////////////////////////////////////
    /**
     * @description:客户端controller层的测试方法
     * @date: 2025/10/14 16:20
     * @author: 执笔画棠
     * @return: void
     **/
    public void test() throws InterruptedException {
        //调用helloservice接口的hello方法，需要传入一个hello对象
        //这是单次调用helloservice服务
        String hello = this.helloService.hello(new Hello("mori_nana", "森七菜"));

        //让主线程等待12秒，确保服务端正常把helloservice注册到注册中心
        //多次调用helloservice服务
        Thread.sleep(12000);

        for (int i = 0; i < 10; i++) {
            System.out.println(helloService.hello(new Hello("mori_nana", "森七菜")));
        }
    }


}
