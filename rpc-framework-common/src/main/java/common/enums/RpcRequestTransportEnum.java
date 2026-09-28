package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RpcRequestTransportEnum rpc请求枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/18 22:40
 **/
@AllArgsConstructor
@Getter
public enum RpcRequestTransportEnum {

    NETTY("netty"),
    SOCKET("socket");


    private final String name;
}