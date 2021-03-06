package org.bazinga.client.comsumer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.bazinga.common.utils.Constants.AVAILABLE_PROCESSORS;
import static org.bazinga.common.utils.Constants.NO_AVAILABLE_WRITEBUFFER_HIGHWATERMARK;
import static org.bazinga.common.utils.Constants.NO_AVAILABLE_WRITEBUFFER_LOWWATERMARK;
import static org.bazinga.common.utils.Constants.WRITER_IDLE_TIME_SECONDS;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ThreadFactory;

import org.bazinga.client.decoder.ConsumerDecoder;
import org.bazinga.client.encoder.RequestEncoder;
import org.bazinga.client.handler.ConsumerHandler;
import org.bazinga.client.processor.consumer.DefaultConsumerProcessor;
import org.bazinga.client.trigger.ConnectorIdleStateTrigger;
import org.bazinga.client.watch.ConnectionWatchdog;
import org.bazinga.common.UnresolvedAddress;
import org.bazinga.common.exception.ConnectFailedException;
import org.bazinga.common.group.BChannelGroup;
import org.bazinga.common.idle.IdleStateChecker;
import org.bazinga.common.message.SubScribeInfo;
import org.bazinga.common.utils.NamedThreadFactory;
import org.bazinga.common.utils.NativeSupport;


/**
 * DefaultConsumer是consumer端连接provider端的Client端，继承DefaultConsumerRegistry
 *  因为DefaultConsumerRegistry连接registry后返回某服务需要连接的IP:HOST,这样就能直接调用
 *  @{link DefaultConsumer #connectToProvider}的方法了
 *  
 *  但这样做的缺点也很明显：无法复用NettyConnector
 *  
 * @author BazingaLyn
 * @time 2016年7月18日
 */
public class DefaultConsumer extends DefaultConsumerRegistry {
	
	private RequestEncoder encoder = new RequestEncoder();
	
	//consumer消费provider端返回的response的handler
	private ConsumerHandler handler = new ConsumerHandler(new DefaultConsumerProcessor());
	
	protected final HashedWheelTimer timer = new HashedWheelTimer(new NamedThreadFactory("consumer.timer"));
	
	private Bootstrap bootstrap;
	
	private EventLoopGroup worker;
    private int nWorkers;
    
    private final boolean nativeEt;
    
    protected volatile ByteBufAllocator allocator;
    
    private final ConnectorIdleStateTrigger idleStateTrigger = new ConnectorIdleStateTrigger();
    
    private volatile int writeBufferHighWaterMark = -1;
    private volatile int writeBufferLowWaterMark = -1;

	public DefaultConsumer(SubScribeInfo info,int writeBufferHighWaterMark,int writeBufferLowWaterMark) {
		super(info);
		this.nWorkers = AVAILABLE_PROCESSORS << 1;
		this.nativeEt = true;
		init();
		
		this.writeBufferHighWaterMark = writeBufferHighWaterMark;
		this.writeBufferLowWaterMark = writeBufferLowWaterMark;
	}
	
	public DefaultConsumer(SubScribeInfo info) {
		this(info, NO_AVAILABLE_WRITEBUFFER_HIGHWATERMARK, NO_AVAILABLE_WRITEBUFFER_LOWWATERMARK);
	}
	
	private void init() {
		
		ThreadFactory workerFactory = new DefaultThreadFactory("bazinga.connector");
        worker = initEventLoopGroup(nWorkers, workerFactory);
        
        bootstrap = new Bootstrap().group(worker);
        
        if (worker instanceof EpollEventLoopGroup) {
            ((EpollEventLoopGroup) worker).setIoRatio(100);
        } else if (worker instanceof NioEventLoopGroup) {
            ((NioEventLoopGroup) worker).setIoRatio(100);
        }
        
        bootstrap.option(ChannelOption.ALLOCATOR, allocator)
        .option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT)
        .option(ChannelOption.SO_REUSEADDR, true)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) SECONDS.toMillis(3))
        .channel(NioSocketChannel.class);
        
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.ALLOW_HALF_CLOSURE, false);
        
        if (writeBufferLowWaterMark >= 0 && writeBufferHighWaterMark > 0) {
            WriteBufferWaterMark waterMark = new WriteBufferWaterMark(writeBufferLowWaterMark, writeBufferHighWaterMark);
            bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK, waterMark);
        }
        
	}

	private EventLoopGroup initEventLoopGroup(int nWorkers,ThreadFactory workerFactory) {
		return isNativeEt() ? new EpollEventLoopGroup(nWorkers, workerFactory) : new NioEventLoopGroup(nWorkers, workerFactory);
	}

	private boolean isNativeEt() {
		return nativeEt && NativeSupport.isSupportNativeET();
	}


	public Channel connectToProvider(int port, String host) {
		
		final Bootstrap boot = bootstrap();
		
		final SocketAddress socketAddress = InetSocketAddress.createUnresolved(host, port);
		
		final BChannelGroup group = group(new UnresolvedAddress(host, port));
		
		final ConnectionWatchdog watchdog = new ConnectionWatchdog(boot, timer, socketAddress,group) {

			public ChannelHandler[] handlers() {
				return new ChannelHandler[] {
                        this,
                        new IdleStateChecker(timer, 0, WRITER_IDLE_TIME_SECONDS, 0),
                        idleStateTrigger,
                        new ConsumerDecoder(),
						encoder,
                        handler
                };
			}
		};
		watchdog.setReconnect(true);
		ChannelFuture future;
		try {
            synchronized (bootstrap) {
                boot.handler(new ChannelInitializer<Channel>() {

                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        ch.pipeline().addLast(watchdog.handlers());
                    }
                });

                future = boot.connect(socketAddress);
            }
               future.sync();
        } catch (Throwable t) {
            throw new ConnectFailedException("connects to [" + host+port + "] fails", t);
        }
		return future.channel();
	}


	private Bootstrap bootstrap() {
		return bootstrap;
	}
	
}
