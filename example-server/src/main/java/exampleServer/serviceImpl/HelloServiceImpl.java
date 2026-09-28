package exampleServer.serviceImpl;


import helloserviceapi.Hello;
import helloserviceapi.HelloService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import simple.annotation.RpcService;

/**
 * HelloServiceImpl2 helloservice实例
 *
 * @author 执笔画棠
 * @date 2025/10/14 17:30
 **/
@Slf4j
@RpcService(group = "test1", version = "version1")
@Service
@Primary
public class HelloServiceImpl implements HelloService {

    /*
    这里起提示作用，这个实现类被加载时会调用这个方法打印
     */
    static {
        System.out.println("hellserviceImpl被创建");
    }

    /**
     * @param hello :
     * @description: 客户端的controller层调用了helloservice
     * 然后controller的test方法内用了helloservice的
     * hello方法，传入的参数就是new的Hello对象
     * 后面就是代理对象发力了，其构建rpc请求到服务端的
     * helloservice的这个实例，然后就是这个hello方法
     * 该方法内部处理后就把result结果返回给客户端
     * @date: 2025/10/14 17:40
     * @author: 执笔画棠
     * @return: java.lang.String
     **/
    @Override
    public String hello(Hello hello) {
        //这里打印在控制台表示服务端收到了客户端请求的参数了
        log.info("HelloServiceImpl收到: {}.", hello.getMessage());

        String result = "这里是菅田将晖，收到森七菜的信息了" + hello.getDescription();
        //将响应结果返回给客户端
        log.info("HelloServiceImpl返回: {}.", result);
        return result;
    }
}
