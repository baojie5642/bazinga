package org.bazinga.client.executor;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

import org.bazinga.common.logger.InternalLogger;
import org.bazinga.common.logger.InternalLoggerFactory;

/**
 * 
 * @author BazingaLyn
 * @copyright fjc
 * @time
 */
public class ForkJoinPoolExecutorFactory implements ExecutorFactory {

	private static final InternalLogger logger = InternalLoggerFactory.getInstance(ForkJoinPoolExecutorFactory.class);

    @Override
    public Executor newExecutor(int parallelism) {
        return new ForkJoinPool(
                parallelism,
                new DefaultForkJoinWorkerThreadFactory("bazinga.forkjoin.processor"),
                new DefaultUncaughtExceptionHandler(), true);
    }

    private static final class DefaultForkJoinWorkerThreadFactory implements ForkJoinPool.ForkJoinWorkerThreadFactory {

        private final AtomicInteger idx = new AtomicInteger();
        private final String namePrefix;

        public DefaultForkJoinWorkerThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            ForkJoinWorkerThread thread = new DefaultForkJoinWorkerThread(pool);
            thread.setName(namePrefix + '-' + idx.getAndIncrement());
            return thread;
        }
    }

    private static final class DefaultForkJoinWorkerThread extends ForkJoinWorkerThread {

        public DefaultForkJoinWorkerThread(ForkJoinPool pool) {
            super(pool);
        }
    }

    private static final class DefaultUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            logger.error("Uncaught exception in thread: {}", t.getName(), e);
        }
    }
}
