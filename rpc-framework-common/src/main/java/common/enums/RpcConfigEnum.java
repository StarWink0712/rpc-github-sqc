package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * RpcConfigEnum 自定义rpc枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/15 20:09
 **/
@AllArgsConstructor
@Getter
public enum RpcConfigEnum {

    /*
     * rpc配置文件路径
     * 和上面的负载均衡是一样的基本上
     * 主要用于zookeeper客户端初始化的过程中
     * ## 5. 在框架中的整体作用
     * 在 RPC 框架中， RpcConfigEnum 主要起到了连接配置定义与配置使用的桥梁作用：
     *
     * 1. 1.
     * 配置抽象 ：将框架的关键配置项抽象为枚举常量
     * 2. 2.
     * 解耦配置 ：将配置键名与具体使用代码解耦，提高了代码的灵活性和可维护性
     * 3. 3.
     * 默认值机制 ：配合默认值机制，确保框架在没有配置文件的情况下也能正常运行
     */
    // 这个是rpc配置文件的路径，值是rpc.properties
    RPC_CONFIG_PATH("rpc.properties"),
    // zookeeper的地址，注册中心的地址
    ZK_ADDRESS("rpc.zookeeper.address");

    // 这个propertyValue是rpc配置文件中对应的key
    // 在curatorUtils中会根据这个key去读取配置文件中的值
    private final String propertyValue;
}