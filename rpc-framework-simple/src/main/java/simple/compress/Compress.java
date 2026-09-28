package simple.compress;

import common.extension.SPI;

/**
 * Compress 压缩类型
 *
 * @author 执笔画棠
 * @version 2025/10/16 21:33
 **/
@SPI
public interface Compress {

    //两个方法，一个压缩一个解压缩
    byte[] compress(byte[] bytes);

    byte[] decompress(byte[] bytes);


}