package simple.spring;

import common.enums.RpcRequestTransportEnum;
import common.extension.ExtensionLoader;
import common.factory.SingletonFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import simple.annotation.RpcReference;
import simple.annotation.RpcService;
import simple.config.RpcServiceConfig;
import simple.provider.Impl.ZKServiceProviderImpl;
import simple.provider.ServiceProvider;
import simple.proxy.RpcClientProxy;
import simple.remoting.transport.RpcRequestTransport;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * SpringBeanPostProcessor spring初始化时对bean自动进行处理
 *
 * @author 执笔画棠
 * @date 2025/10/18 17:28
 **/

/*
## 工作机制详解
### 在服务端（NettyServerMain）中的作用
   扫描 @RpcScan 指定的包路径下的所有类
   检测到带有 @RpcService 注解的 Bean
   在 Bean 初始化前，调用 postProcessBeforeInitialization 方法
   将服务信息注册到 ZooKeeper 注册中心
   提供给客户端发现和调用
### 在客户端（NettyClientMain）中的作用
   扫描 @RpcScan 指定的包路径下的所有类
   检测到带有 @RpcReference 注解的字段
   在 Bean 初始化后，调用 postProcessAfterInitialization 方法
   为这些字段创建代理对象，代理对象封装了远程调用逻辑
   当客户端代码调用这些字段的方法时，实际触发的是远程 RPC 调用
## 技术优势
   代码复用 ：一个类实现了客户端和服务端的双重功能，减少代码重复
   职责分离 ：通过不同的生命周期方法处理不同类型的注解
   无缝集成 ：与 Spring 框架深度集成，利用 Spring 的依赖注入和生命周期管理
   简化使用 ：用户只需关注业务逻辑和注解使用，框架自动处理底层复杂性
 */
@Slf4j
@Component
public class SpringBeanPostProcessor implements BeanPostProcessor {

    //这里不能依赖注入，这种设计是一种刻意的选择，
    //目的是为了保持 RPC 框架核心逻辑的独立性和对 Spring 的解耦
    //SpringBeanPostProcessor 就像一个适配器，
    //它在构造时，负责启动和初始化框架的非 Spring 组件；
    //然后在它的 postProcess 方法中，再与 Spring 容器中的 Bean 进行交互。
    private final ServiceProvider serviceProvider;
    private final RpcRequestTransport rpcClient;

    //
    public SpringBeanPostProcessor() throws Exception {


        //这个就一个实现类，直接单例工厂就可以
        this.serviceProvider = SingletonFactory.getInstance(ZKServiceProviderImpl.class);


        /// ///////////////Objects.requireNonNull 断言,如果serviceprovider为空直接抛出异常
        //Objects.requireNonNull(this.serviceProvider, "ServiceProvider instance is NULL right after creation! The problem lies within SingletonFactory or ZKServiceProviderImpl's constructor.");

        this.rpcClient = ExtensionLoader.getExtensionLoader(RpcRequestTransport.class)
                .getExtension(RpcRequestTransportEnum.NETTY.getName());
    }

    /*
     *主要处理服务端，把带有rpcservice注解的服务注册到服务中心
     *beforeInitialization是在初始化之前调用的，即bean初始化之前完成的
     *这个方法在服务端启动后，各个bean初始化之前就会启动实现把服务
     * 注册到注册中心的功能
     * //TODO这里要再去研究spring bean的生命周期
     */
    @SneakyThrows
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean.getClass().isAnnotationPresent(RpcService.class)) {
            log.info("此[{}]服务有rpcservice注解，是被提供的服务，要注册到注册中心", beanName);

            //获取这个bean上的rpcservice注解
            RpcService rpcService = bean.getClass().getAnnotation(RpcService.class);

            //建立一个rpcconfig类，封装这个要注册的服务
            RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                    .group(rpcService.group())
                    .version(rpcService.version())
                    .service(bean).build();

            //调用服务提供者的注册服务方法
            serviceProvider.publishService(rpcServiceConfig);
        }
        return bean;
    }


    //这里是处理客户端的东西，创建代理对象，之后就是代理对象发布rpc请求了,初始化之后
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        //获取bean的类对象，即hellocontroller类对象包括其所有信息
        //字段，方法，构造函数等
        Class<?> targetClass = bean.getClass();
        //获取bean类上的所有字段
        Field[] declaredFields = targetClass.getDeclaredFields();
        //遍历其字段哪些有rpcreference的注解
        for (Field declaredField : declaredFields) {
            RpcReference rpcReference = declaredField.getAnnotation(RpcReference.class);
            if (rpcReference != null) {
                RpcServiceConfig rpcServiceConfig = RpcServiceConfig.builder()
                        .group(rpcReference.group())
                        .version(rpcReference.version()).build();
                //创建代理对象，这里很明显，传入的参数是rpclient和封装了helloservice的config类，
                //所以就是需要的服务的代理对象，这里就是helloservice的代理对象，但是这里注意并没有传入helloservice
                //只传了group和version
                RpcClientProxy rpcClientProxy = new RpcClientProxy(rpcClient, rpcServiceConfig);
                Object clientProxy = rpcClientProxy.getProxy(declaredField.getType());
                declaredField.setAccessible(true);
                try {
                    //注入代理对象,往hellocontroller中注入helloservice的代理对象
                    declaredField.set(bean, clientProxy);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
        return bean;
    }
}
