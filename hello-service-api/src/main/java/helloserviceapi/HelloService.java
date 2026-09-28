package helloserviceapi;

/**
 * HelloService 测试接口
 *
 * @author 执笔画棠
 * @version 2025/10/14 13:42
 **/

public interface HelloService {
    /**
     * @param hello :
     * @description:这个方法是服务端的一个接口 服务端实现这个接口生成其实例并注册到注册中心
     * 客户端从注册中心获取这个接口实例的代理对象，然后就可以调用
     * hello方法进行使用
     * @date: 2025/10/14 14:28
     * @author: 执笔画棠
     * @return: java.lang.String
     **/
    String hello(Hello hello);
}