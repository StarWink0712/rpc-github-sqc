package simple.serialize;

import common.extension.SPI;

/**
 * Serializer 序列化接口
 *
 * @author 执笔画棠
 * @version 2025/10/18 11:54
 **/
@SPI
public interface Serializer {

    /**
     *
     * @param obj 需要被序列化的 Java 对象
     * @return 序列化后生成的字节数组
     */
    byte[] serialize(Object obj);


    /**
     * 反序列化
     *
     * @param bytes 序列化后的字节数组
     * @param clazz 目标类
     * @param <T>   类的类型。举个例子, {@code String.class} 的类型是 {@code Class<String>}.
     *              如果不知道类的类型的话，使用 {@code Class<?>}
     * @return 反序列化的对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);

}