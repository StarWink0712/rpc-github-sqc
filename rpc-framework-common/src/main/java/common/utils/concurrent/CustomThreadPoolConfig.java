package common.utils.concurrent;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * CustonThreadPoolConfig 线程池自定义配置类
 *
 * @author 执笔画棠
 * @date 2025/10/16 11:31
 **/
@Setter
@Getter
public class CustomThreadPoolConfig {

    /**
     * 线程池默认参数
     */
    private static final int DEFAULT_CORE_POOL_SIZE = 10;
    /**
     * 线程池最大线程数，默认值为 100
     */
    private static final int DEFAULT_MAXIMUM_POOL_SIZE_SIZE = 100;
    /**
     * 线程池保持活动时间，默认值为 1 分钟
     */
    private static final int DEFAULT_KEEP_ALIVE_TIME = 1;
    /**
     * 线程池保持活动时间单位，默认值为分钟
     */
    private static final TimeUnit DEFAULT_TIME_UNIT = TimeUnit.MINUTES;
    /**
     * 线程池阻塞队列容量，默认值为 100
     */
    private static final int DEFAULT_BLOCKING_QUEUE_CAPACITY = 100;
    /**
     * 线程池阻塞队列，默认值为 ArrayBlockingQueue，容量为 100
     */
    private static final int BLOCKING_QUEUE_CAPACITY = 100;
    /**
     * 可配置参数
     */
    private int corePoolSize = DEFAULT_CORE_POOL_SIZE;
    private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE_SIZE;
    private long keepAliveTime = DEFAULT_KEEP_ALIVE_TIME;
    private TimeUnit unit = DEFAULT_TIME_UNIT;
    // 使用有界队列可以防止线程池中的线程数增加导致内存占用过高
    private BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(BLOCKING_QUEUE_CAPACITY);

}
