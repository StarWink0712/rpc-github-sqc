package simple.annotation;

import java.lang.annotation.*;

/**
 * RpcReference
 * 自定义客户端用注解
 * hellocontroller中给
 * hellservice字段上面加
 *
 * @author 执笔画棠
 * @version 2025/10/14 16:44
 **/
@Documented
//只能用于字段(helloservice)上，不能用在方法和类上，
@Target({ElementType.FIELD})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcReference {

    /*
    群组和版本默认值都是空字符串
     * */
    String version() default "";

    String group() default "";
}