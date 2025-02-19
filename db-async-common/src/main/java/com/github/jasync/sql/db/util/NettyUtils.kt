package com.github.jasync.sql.db.util

import com.github.jasync.sql.db.Configuration
import com.github.jasync.sql.db.SSLConfiguration
import io.netty.channel.Channel
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDomainSocketChannel
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueDomainSocketChannel
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.internal.logging.InternalLoggerFactory
import io.netty.util.internal.logging.Slf4JLoggerFactory
import mu.KotlinLogging
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.KeyStore
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory

private val logger = KotlinLogging.logger {}

object NettyUtils {

    init {
        InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE)
        when {
            tryOrFalse { Epoll.isAvailable() } -> logger.info { "jasync available transport - native (epoll)" }
            tryOrFalse { KQueue.isAvailable() } -> logger.info { "jasync available transport - native (kqueue)" }
            else -> logger.info { "jasync selected transport - nio" }
        }
    }

    val DefaultEventLoopGroup: EventLoopGroup by lazy {
        when {
            tryOrFalse { Epoll.isAvailable() } -> EpollEventLoopGroup(0, DaemonThreadsFactory("db-sql-netty"))
            tryOrFalse { KQueue.isAvailable() } -> KQueueEventLoopGroup(0, DaemonThreadsFactory("db-sql-netty"))
            else -> NioEventLoopGroup(0, DaemonThreadsFactory("db-sql-netty"))
        }
    }

    fun getSocketChannelClass(eventLoopGroup: EventLoopGroup, useDomainSocket: Boolean = false): Class<out Channel> =
        when {
            tryOrFalse { eventLoopGroup is EpollEventLoopGroup } -> if (useDomainSocket) EpollDomainSocketChannel::class.java else EpollSocketChannel::class.java
            tryOrFalse { eventLoopGroup is KQueueEventLoopGroup } -> if (useDomainSocket) KQueueDomainSocketChannel::class.java else KQueueSocketChannel::class.java
            else -> {
                if (useDomainSocket) {
                    logger.info { "domain socket is not supported by NioEventLoopGroup, useDomainSocket flag is skipped" }
                }
                NioSocketChannel::class.java
            }
        }

    fun getSocketChannelClassAndSocketAddress(
        eventLoopGroup: EventLoopGroup,
        configuration: Configuration
    ): Pair<Class<out Channel>, SocketAddress> {
        var useDomainSocket = configuration.socketPath != null
        val socketChannelClass = when {
            tryOrFalse { eventLoopGroup is EpollEventLoopGroup } -> if (useDomainSocket) EpollDomainSocketChannel::class.java else EpollSocketChannel::class.java
            tryOrFalse { eventLoopGroup is KQueueEventLoopGroup } -> if (useDomainSocket) KQueueDomainSocketChannel::class.java else KQueueSocketChannel::class.java
            else -> {
                if (useDomainSocket) {
                    logger.info { "domain socket is not supported by NioEventLoopGroup, configuration for socketPath is ignored" }
                    useDomainSocket = false
                }
                NioSocketChannel::class.java
            }
        }
        val socketAddress = if (useDomainSocket) {
            DomainSocketAddress(configuration.socketPath)
        } else {
            InetSocketAddress(configuration.host, configuration.port)
        }
        return socketChannelClass to socketAddress
    }

    fun createSslContext(sslConfiguration: SSLConfiguration): SslContext {
        val ctxBuilder = SslContextBuilder.forClient()
        if (sslConfiguration.mode >= SSLConfiguration.Mode.VerifyCA) {
            if (sslConfiguration.rootCert == null) {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                val ks = KeyStore.getInstance(KeyStore.getDefaultType())
                val cacerts = FileInputStream(System.getProperty("java.home") + "/lib/security/cacerts")
                cacerts.use { ks.load(it, "changeit".toCharArray()) }
                tmf.init(ks)
                ctxBuilder.trustManager(tmf)
            } else {
                ctxBuilder.trustManager(sslConfiguration.rootCert)
            }
        } else {
            ctxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE)
        }
        ctxBuilder.keyManager(sslConfiguration.clientCert, sslConfiguration.clientPrivateKey)
        return ctxBuilder.build()
    }

    /**
     * Enable host name identity verification by checking the host name the driver uses for connecting to the server
     * against the identity in the certificate that the server sends back.
     *
     * @see SSLConfiguration.Mode.VerifyFull
     */
    fun verifyHostIdentity(sslEngine: SSLEngine) {
        val sslParams = sslEngine.sslParameters
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        sslEngine.sslParameters = sslParams
    }

    private fun tryOrFalse(fn: () -> Boolean): Boolean {
        return try {
            fn.invoke()
        } catch (t: Throwable) {
            false
        }
    }
}
