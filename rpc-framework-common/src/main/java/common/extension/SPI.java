package common.extension;

import java.lang.annotation.*;

/**
 * SPI 自定义扩展加载器注解
 *
 * @author 执笔画棠
 * @version 2025/10/15 13:09
 **/
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPI {
}