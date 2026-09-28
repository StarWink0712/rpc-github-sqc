package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * RpcResponseCodeEnum rpc响应枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/18 13:02
 **/
@AllArgsConstructor
@Getter
@ToString
public enum RpcResponseCodeEnum {

    /*
 *┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  服务端处理  │────>│ 响应生成    │────>│ 网络传输    │────>│ 客户端接收  │
│  RPC请求    │     │(设置状态码) │     │ 序列化数据  │     │ 反序列化数据│
└─────────────┘     └─────────────┘     └─────────────┘     └──────┬──────┘
                                                                   │
                                                                   ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ 返回结果    │<────│ 获取数据    │<────│ 验证状态码  │<────│ RpcClientProxy│
│ 给调用方    │     │(如果成功)   │     │(SUCCESS?)   │     │ 处理响应    │
└─────────────┘     └─────────────┘     └──────┬──────┘     └─────────────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │ 抛出异常    │
                                        │(如果失败)   │
                                        └─────────────┘
 */

    SUCCESS(200, "The remote call is successful"),
    FAIL(500, "The remote call is fail");


    private final int code;

    private final String message;
}