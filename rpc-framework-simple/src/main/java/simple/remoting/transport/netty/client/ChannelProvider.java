package simple.remoting.transport.netty.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;


import javax.management.InstanceNotFoundException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChannelProvider 存储和获取channel对象
 *
 * @author 执笔画棠
 * @date 2025/10/19 21:54
 **/
/*
 * 是实现Netty连接重用机制的关键类。
 * 它通过维护服务地址到Channel的映射关系，
 * 避免了频繁创建和关闭连接带来的性能开销。
 */
public class ChannelProvider {
    //key是服务地址，value是channel通道
    private final Map<String, Channel> channelMap;

    public ChannelProvider() {
        channelMap = new ConcurrentHashMap<>();
    }

    public Channel get(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();

        //判断是否存在对应地址的连接
        if (channelMap.containsKey(key)) {
            Channel channel = channelMap.get(key);
            //如果存在就判断连接是否可用
            if (channel != null && channel.isActive()) {
                return channel;
            } else {
                channelMap.remove(key);
            }
        }
        return null;
    }


    /*
     * @description:
     * @date: 2025/10/19 21:58
     * @author: 执笔画棠
     * @param inetSocketAddress :
     * @param channel :
     * @return: void
     **/
    public void set(InetSocketAddress inetSocketAddress, Channel channel) {
        String key = inetSocketAddress.toString();
        channelMap.put(key, channel);
    }

    //移出channel对象
    public void remove(InetSocketAddress inetSocketAddress) {
        String key = inetSocketAddress.toString();
        channelMap.remove(key);
    }

}
