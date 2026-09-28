package simple.remoting.dto;

import common.enums.RpcResponseCodeEnum;
import lombok.*;

import java.io.Serializable;

/**
 * RpcResponse
 *
 * @author 执笔画棠
 * @date 2025/10/18 12:52
 **/
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcResponse<T> implements Serializable {
    //序列化版本号，如果两个 serialVersionUID 完全相同：
    /*
    当你使用 Java 的序列化机制（例如 ObjectOutputStream）将一个对象（比如 RpcResponse 的实例）转换成一串字节流时，Java 运行时会做两件事：
    将对象中的数据（如 requestId、code、data 等字段的值）写入字节流。
    将这个类的 serialVersionUID 也一同写入到字节流中。
    当另一端（可能是在另一台机器上，或者在未来的某个时间点）尝试将这串字节流反序列化回一个对象时，Java 运行时会进行一个关键的验证：
    读取字节流中的 serialVersionUID。
    获取当前 JVM 中加载的对应类（比如 RpcResponse.class）的 serialVersionUID。
    比较这两个 ID。
     */
    //JVM 认为这两个类是兼容的，即使类的定义发生了一些微小的变化（比如增加了一个方法），反序列化过程也会继续进行。这是最常见的情况。
    private static final long serialVersionUID = 715745410605631233L;
    private String requestId;
    /**
     * response code，
     * 这个响应码，表示响应成功与否，
     * 成功则为200，失败则为500
     */
    private Integer code;
    /**
     * response message，
     * 这个响应消息，用于描述响应的详细信息
     */
    private String message;
    /**
     * response body，
     * 这个响应体，用于存储响应的数据
     */
    private T data;

    /**
     * 成功响应
     *
     * @param data      响应数据
     * @param requestId 请求id
     * @return 成功响应
     */
    public static <T> RpcResponse<T> success(T data, String requestId) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(RpcResponseCodeEnum.SUCCESS.getCode());
        response.setMessage(RpcResponseCodeEnum.SUCCESS.getMessage());
        response.setRequestId(requestId);
        if (null != data) {
            response.setData(data);
        }
        return response;
    }

    /**
     * 失败响应
     *
     * @param rpcResponseCodeEnum 响应码枚举
     * @return 失败响应
     */
    public static <T> RpcResponse<T> fail(RpcResponseCodeEnum rpcResponseCodeEnum) {
        RpcResponse<T> response = new RpcResponse<>();
        response.setCode(rpcResponseCodeEnum.getCode());
        response.setMessage(rpcResponseCodeEnum.getMessage());
        return response;
    }
}
