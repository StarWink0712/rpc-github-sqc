package simple.config;

import lombok.*;

/**
 * RpcServiceConfig 是对服务的封装
 *
 * @author 执笔画棠
 * @date 2025/10/14 18:36
 **/
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcServiceConfig {
    /*
    三个成员变量
     */
    private String version = "";
    private String group = "";
    //实际的服务对象！
    private Object service;

    /**
     * @description:这个方法是生成一个服务的全局唯一id
     * @date: 2025/10/14 18:38
     * @author: 执笔画棠
     * @return: java.lang.String
     **/
    public String getRpcServiceName() {
        //全限定名拼上版本和组构成了目标服务的唯一性
        return this.getServiceName() + this.getGroup() + this.getVersion();
    }

    /**
     * @description:这个方法是用来得到实际服务对象的全限定名
     * @date: 2025/10/14 18:39
     * @author: 执笔画棠
     * @return: java.lang.String
     **/
    public String getServiceName() {
        //getCanonicalName获取接口的规范化全名
        //getInterfaces()`: 获取该 `Class` 对象所实现的所有接口的 `Class` 对象数组
        //getClass是获取该service字段存储的实例对象的class类对象
        return this.service.getClass().getInterfaces()[0].getCanonicalName();
    }
}
