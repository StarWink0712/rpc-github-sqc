package simple.spring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.stereotype.Component;
import simple.annotation.RpcScan;
import simple.annotation.RpcService;

/**
 * CustomScannerRegistrar 自定义扫描器
 *
 * @author 执笔画棠
 * @date 2025/10/20 10:48
 **/
/*
四、工作流程图
```
启动类使用@RpcScan注解
         ↓
Spring容器启动，触发@Import
         ↓
调用registerBeanDefinitions方法
         ↓
    ┌────┴────┐
    ↓         ↓
扫描@Component  扫描@RpcService
    ↓         ↓
注册Spring Bean 注册RPC服务
 */
@Slf4j
public class CustomScannerRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware {

    /*
     *使用场景示例
     *@SpringBootApplication
     *@RpcScan(basePackage = {"com.example.rpc"})
     *public class RpcServerApplication {
     *public static void main(String[] args) {
     *SpringApplication.run(RpcServerApplication.class, args);
     * 在NettyServerMain中我写了@RpcScan(basePackage = {"exampleServer"})
     * 即扫描这个包下的全部rpcService注解
     */

    //这是硬编码的包路径，用于扫描标准的Spring组件（@Component注解）。
    //<groupId>com.guoxu</groupId>用自己的项目代码的包
    //将 Maven 的 <groupId> 作为项目所有代码的根包名（base package），是连接项目构建和代码组织的关键桥梁。
    private static final String SPRING_BEAN_BASE_PACKAGE = "com.guoxu";
    /**
     * ✅ 表示该字段用于存储 RpcScan 注解的 basePackage 属性名。
     * 这是注解属性的名称，用于从 @RpcScan 注解中提取用户配置的包路径。
     * 与rpcscan中的注解相对应
     * //要扫描的基础包路径
     * String[] basePackage();
     */
    private static final String BASE_PACKAGE_ATTRIBUTE_NAME = "basePackage";
    private ResourceLoader resourceLoader;

    //Spring容器自动注入ResourceLoader,在Bean初始化阶段自动调用
    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry beanDefinitionRegistry) {
        //获取rpcscan注解的属性值,从注解元数据中提取@RpcScan的所有属性,返回一个Map形式的注解属性集合
        AnnotationAttributes rpcScanAnnotationAttributes = AnnotationAttributes.fromMap(
                annotationMetadata.getAnnotationAttributes(RpcScan.class.getName()));

        //获取用户在@RpcScan中指定的扫描包路径
        String[] rpcScanBasePackages = new String[0];
        if (rpcScanAnnotationAttributes != null) {
            rpcScanBasePackages = rpcScanAnnotationAttributes.getStringArray(BASE_PACKAGE_ATTRIBUTE_NAME);
        }

        //如果用户没有指定扫描包，则默认扫描当前配置类所在的包
        if (rpcScanBasePackages.length == 0) {
            rpcScanBasePackages = new String[]{
                    ((StandardAnnotationMetadata) annotationMetadata).getIntrospectedClass().getPackage().getName()};
        }


        // ✅ 表示该方法用于扫描 RpcService 注解。
        CustomScanner rpcServiceScanner = new CustomScanner(beanDefinitionRegistry, RpcService.class);
        // ✅ 表示该方法用于扫描 Component 注解。
        CustomScanner springBeanScanner = new CustomScanner(beanDefinitionRegistry, Component.class);

        // ✅ 表示该方法用于扫描指定的基础包路径，返回扫描到的 Bean 定义数量。
        //为扫描器注入资源加载器,使扫描器能够读取类路径下的class文件
        if (resourceLoader != null) {
            rpcServiceScanner.setResourceLoader(resourceLoader);
            springBeanScanner.setResourceLoader(resourceLoader);
        }
        // ✅ 表示该方法用于扫描指定的基础包路径，返回扫描到的 Bean 定义数量。
        int springBeanAmount = springBeanScanner.scan(SPRING_BEAN_BASE_PACKAGE);
        log.info("springBeanScanner扫描的数量 [{}]", springBeanAmount);
        // ✅ 表示该方法用于扫描指定的基础包路径，返回扫描到的 RpcService 注解数量。
        int rpcServiceCount = rpcServiceScanner.scan(rpcScanBasePackages);
        log.info("rpcServiceScanner扫描的数量 [{}]", rpcServiceCount);
    }
}
