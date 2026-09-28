package simple.spring;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.annotation.Annotation;

/**
 * CustomScanner
 *
 * @author 执笔画棠
 * @date 2025/10/20 11:13
 **/
public class CustomScanner extends ClassPathBeanDefinitionScanner {
    public CustomScanner(BeanDefinitionRegistry registry, Class<? extends Annotation> annoType) {
        super(registry);
        // ✅ 表示该方法用于添加指定的注解类型过滤器，用于过滤扫描到的 Bean 定义。
        super.addIncludeFilter(new AnnotationTypeFilter(annoType));
    }

    /**
     * ✅ 表示该方法用于扫描指定的基础包路径，返回扫描到的 Bean 定义数量。
     */
    @Override
    public int scan(String... basePackages) {
        return super.scan(basePackages);
    }
}
