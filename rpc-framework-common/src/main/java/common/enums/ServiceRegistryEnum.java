package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ServiceRegistryEnum 服务注册类型枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/15 15:11
 **/
@AllArgsConstructor
@Getter
public enum ServiceRegistryEnum {
    //只有zookeeper一种
    ZK("zk");

    private final String name;
}