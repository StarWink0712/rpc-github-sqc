package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ServiceDiscoveryEnum 注册中心枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/19 22:54
 **/
@AllArgsConstructor
@Getter
public enum ServiceDiscoveryEnum {
    /**
     * zookeeper 服务发现中心
     * 这里只有一个，依然用的了自定义扩展加载器与spi机制
     * ServiceDiscovery 接口
     */
    ZK("zk");

    private final String name;
}