package common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * CompressTypeEnum 压缩类型枚举类
 *
 * @author 执笔画棠
 * @version 2025/10/16 21:27
 **/
@AllArgsConstructor
@Getter
public enum CompressTypeEnum {
    GZIP((byte) 0x01, "gzip");

    private final byte code;
    private final String name;

    //这个方法是根据传入的code来找对应的name
    //通过传入一个压缩类型的字节编码（code），查找并返回对应的压缩类型名称（String name）。
    /*
     * 这个方法的核心用途就是接收方接收到数据后，根据rpcmessage里面的code
     * 再调用这个方法，找到对应的压缩名称GZIP，然后就可以用这个算法解压缩
     * 如：// 编码时写入压缩类型
     * out.writeByte(CompressTypeEnum.GZIP.getCode());
     * 根据压缩类型码获取压缩名称
     * String compressName = CompressTypeEnum.getName(rpcMessage.getCompress());
     */
    public static String getName(byte code) {
        for (CompressTypeEnum c : CompressTypeEnum.values()) {
            if (c.getCode() == code) {
                return c.name;
            }
        }
        return null;
    }

}