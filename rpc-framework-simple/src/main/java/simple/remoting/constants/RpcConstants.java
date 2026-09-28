package simple.remoting.constants;

import javax.crypto.interfaces.PBEKey;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * RpcConstants RPC信息的常量类
 *
 * @author 执笔画棠
 * @date 2025/10/16 19:39
 **/
public class RpcConstants {

    //模数
    public static final byte[] MAGIC_NUMBER = {(byte) 'g', (byte) 'r', (byte) 'p', (byte) 'c'};
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    //版本信息
    public static final byte VERSION = 1;
    public static final byte TOTAL_LENGTH = 16;
    public static final byte REQUEST_TYPE = 1;
    public static final byte RESPONSE_TYPE = 2;

    //心跳请求类型
    public static final byte HEARTBEAT_REQUEST_TYPE = 3;
    //心跳响应类型
    public static final byte HEARTBEAT_RESPONSE_TYPE = 4;
    public static final byte HEAD_LENGTH = 16;
    public static final String PING = "ping";
    public static final String PONG = "pong";
    public static final int MAX_FRAME_LENGTH = 8 * 1024 * 1024;
}
