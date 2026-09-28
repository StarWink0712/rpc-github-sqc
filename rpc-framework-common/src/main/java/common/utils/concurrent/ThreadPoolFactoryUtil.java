package common.utils.concurrent;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * ThreadPoolFactoryUtil 自定义线程工具类
 *
 * @author 执笔画棠
 * @date 2025/10/15 16:39
 **/
@Slf4j
public class ThreadPoolFactoryUtil {

    /**
     * 线程池缓存，缓存已经建立的线程池
     * 通过 threadNamePrefix 来区分不同线程池（我们可以把相同 threadNamePrefix 的线程池看作是为同一业务场景服务）。
     * key: threadNamePrefix
     * value: threadPool
     */
    private static final Map<String, ExecutorService> THREAD_POOLS = new ConcurrentHashMap<>();

    //私有构造方法
    private ThreadPoolFactoryUtil() {

    }


    /**
     * 如果不存在以 threadNamePrefix 为名称的线程池的话就创建一个新的线程池.
     * 三个自定义线程池创建方法，第一个是用默认配置文件创建
     *
     * @param threadNamePrefix 线程池名称的前缀
     * @return 新创建的线程池
     */
    public static ExecutorService createCustomThreadPoolIfAbsent(String threadNamePrefix) {
        //新建一个配置文件，然后用这个默认配置创建线程池
        CustomThreadPoolConfig customThreadPoolConfig = new CustomThreadPoolConfig();
        //这个创建线程池的方法自定义
        return createCustomThreadPoolIfAbsent(customThreadPoolConfig, threadNamePrefix, false);
    }

    /**
     * 根据已有配置文件创建线程池.
     * 第二种
     *
     * @param threadNamePrefix       线程池名称的前缀
     * @param customThreadPoolConfig 线程池配置
     * @return 新创建的线程池
     */
    public static ExecutorService createCustomThreadPoolIfAbsent(String threadNamePrefix, CustomThreadPoolConfig customThreadPoolConfig) {
        return createCustomThreadPoolIfAbsent(customThreadPoolConfig, threadNamePrefix, false);
    }

    //第三种，也是第一二种调用的真正创建的方式
    public static ExecutorService createCustomThreadPoolIfAbsent(CustomThreadPoolConfig customThreadPoolConfig, String threadNamePrefix, Boolean daemon) {
        //如果不存在threadnameprefix的线程池的话，就创建一个新的
        ExecutorService threadPool = THREAD_POOLS.computeIfAbsent(threadNamePrefix, k -> createThreadPool(customThreadPoolConfig, threadNamePrefix, daemon));

        //如果threadpool被shutdown的话就重新创建一个
        if (threadPool.isShutdown() || threadPool.isTerminated()) {
            //移出这个被关闭的线程池
            THREAD_POOLS.remove(threadNamePrefix);
            //重新创建并存入缓存
            threadPool = createThreadPool(customThreadPoolConfig, threadNamePrefix, daemon);
            THREAD_POOLS.put(threadNamePrefix, threadPool);
        }
        return threadPool;
    }


    /**
     * 关闭所有线程池
     *
     */
    public static void shutDownAllThreadPool() {
        log.info("call shutdownallthreadpool method");
        THREAD_POOLS.entrySet().parallelStream().forEach(entry -> {
            ExecutorService executorService = entry.getValue();
            executorService.shutdown();
            // isTerminated() 方法用于判断线程池是否已终止
            log.info("shut down thread pool[{}][{}]", entry.getKey(), executorService.isTerminated());
            try {
                // 等待所有任务完成
                executorService.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 出现异常时强制关闭线程池
                log.error("thread pool never terminated,出现异常时强制关闭线程池");
                executorService.shutdownNow();
            }
        });
    }

    /**
     * 创建线程池
     *
     * @param customThreadPoolConfig 线程池配置
     * @param threadNamePrefix       线程池名称的前缀
     * @param daemon                 指定是否为 Daemon Thread(守护线程)
     * @return 新创建的线程池
     */
    private static ExecutorService createThreadPool(CustomThreadPoolConfig customThreadPoolConfig, String threadNamePrefix, Boolean daemon) {
        //ThreadFactory是Java并发包中的一个接口，它的主要职责是创建新线程。在线程池中，当需要创建新线程来执行任务时，会调用ThreadFactory的方法。
        // 创建一个线程工厂（ThreadFactory）。这个工厂将决定后续如何创建单个线程
        ThreadFactory threadFactory = createThreadFactory(threadNamePrefix, daemon);
        //实例化并返回一个新的线程池对象。构造函数接收的参数全部来自于传入的 CustomThreadPoolConfig 配置对象和上一步创建的 threadFactory。
        return new ThreadPoolExecutor(customThreadPoolConfig.getCorePoolSize(),
                customThreadPoolConfig.getMaximumPoolSize(),
                customThreadPoolConfig.getKeepAliveTime(), customThreadPoolConfig.getUnit(),
                customThreadPoolConfig.getWorkQueue(),
                threadFactory);
    }

    /**
     * !线程并不是在创建线程池时立即创建的，而是在线程池需要执行任务时，通过 ThreadFactory 来创建的。
     * 创建 ThreadFactory
     * 。如果threadNamePrefix不为空则使用自建ThreadFactory，否则使用defaultThreadFactory
     *
     * @param threadNamePrefix 作为创建的线程名字的前缀
     * @param daemon           指定是否为 Daemon Thread(守护线程)
     * @return ThreadFactory
     */
    public static ThreadFactory createThreadFactory(String threadNamePrefix, Boolean daemon) {
        if (threadNamePrefix != null) {
            if (daemon != null) {
                //使用了 Google Guava 库的 ThreadFactoryBuilder 来简化 ThreadFactory 的创建
                //它设置了线程的命名格式，比如，如果 threadNamePrefix 是 "rpc-business"，
                //那么创建出的线程就会被命名为 "rpc-business-0", "rpc-business-1" 等等
                return new ThreadFactoryBuilder()
                        .setNameFormat(threadNamePrefix + "-%d")
                        .setDaemon(daemon).build();
            } else {
                return new ThreadFactoryBuilder().setNameFormat(threadNamePrefix + "-%d").build();
            }
        }
        // 如果 threadNamePrefix 为空的话就使用默认的 ThreadFactory
        return Executors.defaultThreadFactory();
    }

    /**
     * 打印线程池的状态
     *
     * @param threadPool 线程池对象
     */
    public static void printThreadPoolStatus(ThreadPoolExecutor threadPool) {
        // scheduleAtFixedRate 方法用于定期执行任务
        ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1,
                // createThreadFactory 方法用于创建线程工厂
                createThreadFactory("print-thread-pool-status", false));
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            log.info("============ThreadPool Status=============");
            log.info("ThreadPool Size: [{}]", threadPool.getPoolSize());
            log.info("Active Threads: [{}]", threadPool.getActiveCount());
            log.info("Number of Tasks : [{}]", threadPool.getCompletedTaskCount());
            log.info("Number of Tasks in Queue: {}", threadPool.getQueue().size());
            log.info("===========================================");
        }, 0, 1, TimeUnit.SECONDS);
    }
}
