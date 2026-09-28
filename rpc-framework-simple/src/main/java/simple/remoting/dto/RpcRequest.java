package simple.remoting.dto;

import lombok.*;

import java.io.Serializable;

/**
 * RpcRequest rpc请求
 *
 * @author 执笔画棠
 * @date 2025/10/18 12:50
 **/
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class RpcRequest implements Serializable {
    /**
     * 序列化版本号
     * 提供一个稳定的版本标识：它告诉 Java 序列化机制：“请使用我指定的这个 ID 作为类的版本号”。
     */
    private static final long serialVersionUID = 1905122041950251207L;
    /**
     * 请求ID
     */
    private String requestId;
    /**
     * 接口名称
     */
    private String interfaceName;
    /**
     * 方法名称
     */
    private String methodName;
    /**
     * 参数列表
     */
    private Object[] parameters;
    /**
     * 参数类型列表
     */
    private Class<?>[] paramTypes;
    /**
     * 版本号
     */
    private String version;
    /**
     * 组名
     */
    private String group;

    public String getRpcServiceName() {
        return this.getInterfaceName() + this.getGroup() + this.getVersion();
    }
}
