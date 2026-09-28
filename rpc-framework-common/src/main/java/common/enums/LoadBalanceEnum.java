package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.checkerframework.checker.units.qual.A;

/**
 * LoadBalanceEnum 负载均衡枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/19 20:24
 **/
@AllArgsConstructor
@Getter
public enum LoadBalanceEnum {
    //定义一个枚举常量LOADBALANCE,其关联的字符串是loadBalance
    LOADBALANCE("loadBalance"),

    LOADBALANCENEW("loadBalanceNew");

    //用于存储负载均衡 策略的名称
    private final String name;
}