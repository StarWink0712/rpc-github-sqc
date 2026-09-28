package simple.remoting.dto;

import lombok.*;

/**
 * RpcMessage 自定义rpcmessage信息，传递用
 * 封装了请求的数据data以及请求头的一些信息
 *
 * @author 执笔画棠
 * @date 2025/10/16 18:00
 **/
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class RpcMessage {

    //消息类型
    private byte messageType;

    //序列化方式
    private byte codec;

    //压缩方式
    private byte compress;

    //请求ID
    private int requestId;

    //请求数据
    private Object data;
}
