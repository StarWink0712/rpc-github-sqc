package common.exception;

import common.enums.RpcErrorMessageEnum;

/**
 * RpcException 自定义rpc异常
 *
 * @author 执笔画棠
 * @date 2025/10/15 15:20
 **/
public class RpcException extends RuntimeException {

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum, String detail) {
        super(rpcErrorMessageEnum.getMessage() + ":" + detail);
    }

    //cause 是导致此异常的Throwable
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcException(RpcErrorMessageEnum rpcErrorMessageEnum) {
        super(rpcErrorMessageEnum.getMessage());
    }
}
