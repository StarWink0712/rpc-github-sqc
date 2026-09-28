package simple.annotation;

import java.lang.annotation.*;

/**
 * RpcService 自定义service注解
 *
 * @author 执笔画棠
 * @version 2025/10/14 16:26
 **/
/*
 *标记在服务提供者的实现类上
 * 用于将服务注册到注册中心
 **/
//用于javadoc文档生成
@Documented
//该注解在运行时可见，即可被反射机制获取
@Retention(RetentionPolicy.RUNTIME)
//表示该注解只能用于类上，不能用于字段或方法上
@Target({ElementType.TYPE})
//表示该注解可以被子类继承
@Inherited
public @interface RpcService {

    //服务版本默认空字符串
    String version() default "";

    //群组默认空字符串
    String group() default "";
}