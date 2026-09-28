package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SerializationTypeEnum 序列化枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/18 11:24
 **/
@AllArgsConstructor
@Getter
public enum SerializationTypeEnum {

    /*
     * 定义三种序列化方法
     * 和前面的transport层的实现接口一样
     * 这里也需要实现serializer接口
     * 在nettyrpclient中调用，即根据code获取序列化方法
     * 这里需要根据code获取序列化方法，因为在nettyrpcclient中，
     * 我们需要根据code来判断使用哪种序列化方法
     * 在rpcmessageencoder和decoder中，
     * Serializer serializer = ExtensionLoader.getExtensionLoader(Serializer.class)
     * .getExtension(codecName);
     * 是根据codecName获取序列化器
     * 和transport层的实现接口一样
     * 然后
     */

    KYRO((byte) 0x01, "kryo"),
    PROTOSTUFF((byte) 0x02, "protostuff"),
    HESSIAN((byte) 0x03, "hessian");


    private final byte code;
    private final String name;

    public static String getName(byte code) {
        for (SerializationTypeEnum c : SerializationTypeEnum.values()) {
            if (c.getCode() == code) {
                return c.name;
            }
        }
        return null;
    }
}