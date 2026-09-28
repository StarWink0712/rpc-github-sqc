package simple.annotation;

import org.springframework.context.annotation.Import;
import simple.spring.CustomScannerRegistrar;

import java.lang.annotation.*;

/**
 * RpcScan 自定义扫描器
 *
 * @author 执笔画棠
 * @version 2025/10/14 16:35
 **/
@Documented
@Retention(RetentionPolicy.RUNTIME)
/*
 * 这里导入CustomScannerRegistrar.class类
 * 用于注册自定义扫描器
 * @RpcScan提供配置信息（扫描路径）
- CustomScannerRegistrar负责实际的扫描逻辑实现
- 两者共同构成了"配置+实现"的经典设计模式
* 工作流程：
* - Spring容器初始化时检测到@RpcScan注解
- 通过@Import机制实例化CustomScannerRegistrar
- 调用registerBeanDefinitions方法，读取@RpcScan的basePackage参数
- 创建CustomScanner实例，扫描指定包下的@RpcService注解类
- 同时扫描框架内部包下的@Component组件
 * */
@Import(CustomScannerRegistrar.class)
//表示该注解可以用在类和方法上
@Target({ElementType.TYPE, ElementType.METHOD})

/*@RpcScan(basePackage{"com.example.service"})
 * public class RpcClientApplication {
 *  // 应用主类
 * }
 * */
public @interface RpcScan {

    //要扫描的基础包路径
    String[] basePackage();
}