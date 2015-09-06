/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.log4j.Logger;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.proto.AuthPacket;
import org.apache.zookeeper.proto.ConnectRequest;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.auth.AuthenticationProvider;
import org.apache.zookeeper.server.auth.ProviderRegistry;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 * 
 * 一个NIOServerCnxn代表客户端与Server的一个连接上下文
 * 
 */
public class NIOServerCnxn implements Watcher, ServerCnxn {
    private static final Logger LOG = Logger.getLogger(NIOServerCnxn.class);

    private ConnectionBean jmxConnectionBean;

    
    /**
     * Factory: 建立tcp server、listen接入来自客户端的连接
     * 一个Factory代表一个建立监听的server、以及从这里accept的很多个连接NIOServerCnxn
     * 
     * */
    static public class Factory extends Thread {
        static {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    LOG.error("Thread " + t + " died", e);
                }
            });
            /**
             * this is to avoid the jvm bug:
             * NullPointerException in Selector.open()
             * http://bugs.sun.com/view_bug.do?bug_id=6427854
             */
            try {
                Selector.open().close();
            } catch(IOException ie) {
                LOG.error("Selector failed to open", ie);
            }
        }

        /**
         * 所在的ZooKeeperServer引用
         * 
         * */
        ZooKeeperServer zks;

        /**
         * 建立server时accept的ServerSocketChannel
         * 
         * */
        final ServerSocketChannel ss;

        /**
         * 管理epoll的Selector
         * 
         * */
        final Selector selector = Selector.open();

        /**
         * We use this buffer to do efficient socket I/O. Since there is a single
         * sender thread per NIOServerCnxn instance, we can use a member variable to
         * only allocate it once.
         * 
         * 写IO时的字节缓冲区
         * 有很多个NIOServerCnxn 为什么只有一个directBuffer？？？TODO
         * 
        */
        final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

        /**
         * 保存所有accept的连接
         * 
         * */
        final HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();
        /**
         * 保存来自不同客户端机器->对应连接的映射
         * 
         * */
        final HashMap<InetAddress, Set<NIOServerCnxn>> ipMap =
            new HashMap<InetAddress, Set<NIOServerCnxn>>( );

        /**
         * 未处理请求队列的长度 防止客户端发送请求过快
         * 默认是1000
         * 
         * */
        int outstandingLimit = 1;

        /**
         * 限制单机建立的连接个数
         * 
         * */
        int maxClientCnxns = 10;

        /**
         * Construct a new server connection factory which will accept an unlimited number
         * of concurrent connections from each client (up to the file descriptor
         * limits of the operating system). startup(zks) must be called subsequently.
         * @param port
         * @throws IOException
         */
        public Factory(InetSocketAddress addr) throws IOException {
            this(addr, 0);
        }


        /**
         * Constructs a new server connection factory where the number of concurrent connections
         * from a single IP address is limited to maxcc (or unlimited if 0).
         * startup(zks) must be called subsequently.
         * @param port - the port to listen on for connections.
         * @param maxcc - the number of concurrent connections allowed from a single client.
         * @throws IOException
         * 
         * 在addr建立listen、限制每个机器来的连接数为maxcc
         * 
         */
        public Factory(InetSocketAddress addr, int maxcc) throws IOException {
            super("NIOServerCxn.Factory:" + addr);
            setDaemon(true);
            maxClientCnxns = maxcc;
            this.ss = ServerSocketChannel.open();
            ss.socket().setReuseAddress(true);
            LOG.info("binding to port " + addr);
            ss.socket().bind(addr);
            ss.configureBlocking(false);
            ss.register(selector, SelectionKey.OP_ACCEPT);
        }

        @Override
        public void start() {
            // ensure thread is started once and only once
            if (getState() == Thread.State.NEW) {
                super.start();
            }
        }

        /**
         * 启动accept、启动zks(参见zks)
         * 
         * */
        public void startup(ZooKeeperServer zks) throws IOException,
                InterruptedException {
            start();
            zks.startdata();
            zks.startup();
            setZooKeeperServer(zks);
        }

        public void setZooKeeperServer(ZooKeeperServer zks) {
            this.zks = zks;
            if (zks != null) {
                this.outstandingLimit = zks.getGlobalOutstandingLimit();
                zks.setServerCnxnFactory(this);
            } else {
                this.outstandingLimit = 1;
            }
        }

        public ZooKeeperServer getZooKeeperServer() {
            return this.zks;
        }

        public InetSocketAddress getLocalAddress(){
            return (InetSocketAddress)ss.socket().getLocalSocketAddress();
        }

        public int getLocalPort(){
            return ss.socket().getLocalPort();
        }

        public int getMaxClientCnxns() {
            return maxClientCnxns;
        }

        /**
         * 新增一个NIOServerCnxn
         * 优化到了极致啊啊啊啊
         * */
        private void addCnxn(NIOServerCnxn cnxn) {
            synchronized (cnxns) {
                cnxns.add(cnxn);
                synchronized (ipMap){
                    InetAddress addr = cnxn.sock.socket().getInetAddress();
                    Set<NIOServerCnxn> s = ipMap.get(addr);
                    if (s == null) {
                        // in general we will see 1 connection from each
                        // host, setting the initial cap to 2 allows us
                        // to minimize mem usage in the common case
                        // of 1 entry --  we need to set the initial cap
                        // to 2 to avoid rehash when the first entry is added
                        s = new HashSet<NIOServerCnxn>(2);
                        s.add(cnxn);
                        ipMap.put(addr,s);
                    } else {
                        s.add(cnxn);
                    }
                }
            }
        }

        protected NIOServerCnxn createConnection(SocketChannel sock,
                SelectionKey sk) throws IOException {
            return new NIOServerCnxn(zks, sock, sk, this);
        }

        private int getClientCnxnCount(InetAddress cl) {
            // The ipMap lock covers both the map, and its contents
            // (that is, the cnxn sets shouldn't be modified outside of
            // this lock)
            synchronized (ipMap) {
                Set<NIOServerCnxn> s = ipMap.get(cl);
                if (s == null) return 0;
                return s.size();
            }
        }

        public void run() {
        	/**
        	 * 如果没有关闭的话 就一直在ss上:
        	 * 1 accept、接受建立连接的请求
        	 * 2 处理读写请求
        	 * 
        	 * */
            while (!ss.socket().isClosed()) {
                try {
                    selector.select(1000);
                    Set<SelectionKey> selected;
                    synchronized (this) {
                        selected = selector.selectedKeys();
                    }
                    ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
                            selected);
                    Collections.shuffle(selectedList);
                    for (SelectionKey k : selectedList) {
                    	// 1 建立连接的请求
                        if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                            SocketChannel sc = ((ServerSocketChannel) k
                                    .channel()).accept();
                            InetAddress ia = sc.socket().getInetAddress();
                            int cnxncount = getClientCnxnCount(ia);
                            // 判断是否超出单机限制最大连接数
                            if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
                                LOG.warn("Too many connections from " + ia
                                         + " - max is " + maxClientCnxns );
                                sc.close();
                            } else {
                                LOG.info("Accepted socket connection from "
                                        + sc.socket().getRemoteSocketAddress());
                                sc.configureBlocking(false);
                                SelectionKey sk = sc.register(selector,
                                        SelectionKey.OP_READ);
                                NIOServerCnxn cnxn = createConnection(sc, sk);
                                // 注意这个用法 将NIOServerCnxn绑定在SelectionKey上、在后面有用
                                // 可以根据SelectionKey取出NIOServerCnxn
                                sk.attach(cnxn);
                                addCnxn(cnxn);
                            }
                        } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                        	// 2 读写请求 在doIO里处理
                            NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                            c.doIO(k);
                        } else {
                        	// 其他请求不做处理
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Unexpected ops in select "
                                          + k.readyOps());
                            }
                        }
                    }
                    selected.clear();
                } catch (RuntimeException e) {
                    LOG.warn("Ignoring unexpected runtime exception", e);
                } catch (Exception e) {
                    LOG.warn("Ignoring exception", e);
                }
            }
            // 如果ss已关闭、退出accept
            clear();
            LOG.info("NIOServerCnxn factory exited run method");
        }

        /**
         * Clear all the connections in the selector.
         * 
         * You must first close ss (the serversocketchannel) if you wish
         * to block any new connections from being established.
         *
         * 清理所有连接---主动关闭
         * 注意在这之前需要已经将ss关闭 不在接受新建立的连接和请求
         * 
         */
        @SuppressWarnings("unchecked")
        synchronized public void clear() {
            selector.wakeup();
            HashSet<NIOServerCnxn> cnxns;
            synchronized (this.cnxns) {
                cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
            }
            // got to clear all the connections that we have in the selector
            for (NIOServerCnxn cnxn: cnxns) {
                try {
                    // don't hold this.cnxns lock as deadlock may occur
                    cnxn.close();
                } catch (Exception e) {
                    LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                            + Long.toHexString(cnxn.sessionId), e);
                }
            }
        }

        public void shutdown() {
            try {
                ss.close();
                clear();
                this.interrupt();
                this.join();
            } catch (InterruptedException e) {
                LOG.warn("Ignoring interrupted exception during shutdown", e);
            } catch (Exception e) {
                LOG.warn("Ignoring unexpected exception during shutdown", e);
            }
            try {
                selector.close();
            } catch (IOException e) {
                LOG.warn("Selector closing", e);
            }
            if (zks != null) {
                zks.shutdown();
            }
        }

        /**
         * 注意这两个用在哪 一个wakeup一个没有
         * TODO
         * */
        synchronized void closeSession(long sessionId) {
            selector.wakeup();
            closeSessionWithoutWakeup(sessionId);
        }

        @SuppressWarnings("unchecked")
        private void closeSessionWithoutWakeup(long sessionId) {
            HashSet<NIOServerCnxn> cnxns;
            synchronized (this.cnxns) {
                cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
            }

            for (NIOServerCnxn cnxn : cnxns) {
                if (cnxn.sessionId == sessionId) {
                    try {
                        cnxn.close();
                    } catch (Exception e) {
                        LOG.warn("exception during session close", e);
                    }
                    break;
                }
            }
        }
    }

    
    
    /**
     * The buffer will cause the connection to be close when we do a send.
     * 
     * 一个特殊的ByteBuffer 当将closeConn扔入发送队列时 在doIO时会检测 一旦检测到 触发该连接关闭
     * 
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    /**
     * 一个NIOServerCnxn持有建立这个NIOServerCnxn的Factory
     * 
     * */
    final Factory factory;

    /** The ZooKeeperServer for this connection. May be null if the server
     * is not currently serving requests (for example if the server is not
     * an active quorum participant.
     * 
     * 该NIOServerCnxn连接的ZooKeeperServer
     *
     */
    private final ZooKeeperServer zk;

    /**
     * 该NIOServerCnxn的SocketChannel 注意这个与Factory里面的ServerSocketChannel的区别
     * Factory是一个建立Server accept的server端、这里是连接建立后的连接端
     * 
     * */
    private SocketChannel sock;

    /**
     * 与sock绑定的SelectionKey
     * 
     * */
    private SelectionKey sk;

    /**
     * 该连接是否初始化 TODO
     * 
     * */
    boolean initialized;

    /**
     * 包长度 为4个字节
     * 
     * */
    ByteBuffer lenBuffer = ByteBuffer.allocate(4);

    /**
     * 包缓冲区--存储一个请求的字节数据 请求对象会从该字节流中反序列化出来
     * 
     * 大小为从lenBuffer读出的长度
     * 这一块实现跟ClientCnxn的类似 一般都是这么做
     * 这里只是初始化为跟lenBuffer一样 当读到len以后 会根据len的大小重新分配空间赋给incomingBuffer
     * */
    ByteBuffer incomingBuffer = lenBuffer;

    /**
     * 等待发送的ByteBuffer队列 用于异步发送
     * 
     * */
    LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

    /**
     * 服务端维护的一个sessionTimeOut
     * TODO
     * */
    int sessionTimeout;

    /**
     * 服务端维护的该连接的验证信息
     * 
     * */
    ArrayList<Id> authInfo = new ArrayList<Id>();

    /* Send close connection packet to the client, doIO will eventually
     * close the underlying machinery (like socket, selectorkey, etc...)
     * 
     * 发送关闭连接的包给客户端
     * doIO最终会关闭连接
     * 
     */
    public void sendCloseSession() {
        sendBuffer(closeConn);
    }

    /**
     * send buffer without using the asynchronous
     * calls to selector and then close the socket
     * @param bb
     * 
     * 同步发送ByteBuffer 直接写sock
     * 区别于使用outgoingBuffers来做异步发送
     * 
     */
    void sendBufferSync(ByteBuffer bb) {
       try {
           /* configure socket to be blocking
            * so that we dont have to do write in 
            * a tight while loop
            */
           sock.configureBlocking(true);
           if (bb != closeConn) {
               if (sock != null) {
                   sock.write(bb);
               }
               // 当发送完packet后更新统计信息
               packetSent();
           } 
       } catch (IOException ie) {
           LOG.error("Error sending data synchronously ", ie);
       }
    }
    
    /**
     * 异步发送ByteBuffer--放入异步发送队列outgoingBuffers
     * 
     * */
    void sendBuffer(ByteBuffer bb) {
        try {
        	/**
        	 * 如果还没有interest WRITE 那么说明待发送队列为空
        	 * 此时可以直接同步发送即可
        	 * 一个优化 可以去掉
        	 * 
        	 * 如果是closeConn 直接放入发送队列 --后续在doIO时会检测closeConn --如果检测到closeConn、那么doIO的死循环退出(表示关闭了连接)
        	 * 
        	 * */
            if (bb != closeConn) {
                // We check if write interest here because if it is NOT set,
                // nothing is queued, so we can try to send the buffer right
                // away without waking up the selector
                if ((sk.interestOps() & SelectionKey.OP_WRITE) == 0) {
                    try {
                        sock.write(bb);
                    } catch (IOException e) {
                        // we are just doing best effort right now
                    }
                }
                // if there is nothing left to send, we are done
                if (bb.remaining() == 0) {
                    packetSent();
                    return;
                }
            }

            /**
             * 将要发送的ByteBuffer放入发送队列
             * 
             * */
            synchronized(this.factory){
                sk.selector().wakeup();
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Add a buffer to outgoingBuffers, sk " + sk
                            + " is valid: " + sk.isValid());
                }
                outgoingBuffers.add(bb);
                if (sk.isValid()) {
                    sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                }
            }
            
        } catch(Exception e) {
            LOG.error("Unexpected Exception: ", e);
        }
    }

    private static class CloseRequestException extends IOException {
        private static final long serialVersionUID = -7854505709816442681L;

        public CloseRequestException(String msg) {
            super(msg);
        }
    }

    private static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -8255690282104294178L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    /**
     * payload:数据包的len后面实际的正文内容
     * 该函数之前incomingBuffer的大小已经根据len确定
     * 该函数确保从sock里将需要的len数据全部读出来 如果不能做到 说明数据出错
     * 
     * */
    /** Read the request payload (everything followng the length prefix) */
    private void readPayload() throws IOException, InterruptedException {
    	/**
    	 * 如果还没有读完整、继续读
    	 * readPayload()应该在一个IO死循环里 直到读完整的数据包
    	 * 
    	 * */
        if (incomingBuffer.remaining() != 0) { // have we read length bytes?
            int rc = sock.read(incomingBuffer); // sock is non-blocking, so ok
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from client sessionid 0x"
                        + Long.toHexString(sessionId)
                        + ", likely client has closed socket");
            }
        }

        /**
         * 已经读到完整的该数据包
         * 注意有哪些步骤
         * 
         * */
        if (incomingBuffer.remaining() == 0) { // have we read length bytes?
        	// 1 更新统计信息
            packetReceived();
            // 2 包数据流flip到最开始
            incomingBuffer.flip();
            // 3 如果连接还没有初始化、那么需要初始化该连接--初始化即读客户端来的ConnectRequest
            // 如果已经初始化(连接已建立) 那么读正常的请求
            if (!initialized) {
                readConnectRequest();
            } else {
                readRequest();
            }
            // 重置lenBuffer和incomingBuffer 等待下一个数据包
            lenBuffer.clear();
            incomingBuffer = lenBuffer;
        }
    }

    /**
     * 所有连接socket上的读写IO事件在此处理
     * 
     * 
     * */
    void doIO(SelectionKey k) throws InterruptedException {
        try {
            if (sock == null) {
                LOG.warn("trying to do i/o on a null socket for session:0x"
                         + Long.toHexString(sessionId));

                return;
            }
            /**
             * 当有读IO事件时:
             * 
             * 
             * */
            if (k.isReadable()) {
                int rc = sock.read(incomingBuffer);
                if (rc < 0) {
                    throw new EndOfStreamException(
                            "Unable to read additional data from client sessionid 0x"
                            + Long.toHexString(sessionId)
                            + ", likely client has closed socket");
                }
                if (incomingBuffer.remaining() == 0) {
                    boolean isPayload;
                    if (incomingBuffer == lenBuffer) { // start of next request
                        incomingBuffer.flip();
                        isPayload = readLength(k);
                        incomingBuffer.clear();
                    } else {
                        // continuation
                        isPayload = true;
                    }
                    if (isPayload) { // not the case for 4letterword
                        readPayload();
                    }
                    else {
                        // four letter words take care
                        // need not do anything else
                        return;
                    }
                }
            }
            /**
             * 当有写IO事件时:
             * 
             * */
            if (k.isWritable()) {
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK
                // "outgoingBuffers.size() = " +
                // outgoingBuffers.size());
                if (outgoingBuffers.size() > 0) {
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                    // "sk " + k + " is valid: " +
                    // k.isValid());

                    /*
                     * This is going to reset the buffer position to 0 and the
                     * limit to the size of the buffer, so that we can fill it
                     * with data from the non-direct buffers that we need to
                     * send.
                     */
                    ByteBuffer directBuffer = factory.directBuffer;
                    directBuffer.clear();

                    for (ByteBuffer b : outgoingBuffers) {
                        if (directBuffer.remaining() < b.remaining()) {
                            /*
                             * When we call put later, if the directBuffer is to
                             * small to hold everything, nothing will be copied,
                             * so we've got to slice the buffer if it's too big.
                             */
                            b = (ByteBuffer) b.slice().limit(
                                    directBuffer.remaining());
                        }
                        /*
                         * put() is going to modify the positions of both
                         * buffers, put we don't want to change the position of
                         * the source buffers (we'll do that after the send, if
                         * needed), so we save and reset the position after the
                         * copy
                         */
                        int p = b.position();
                        directBuffer.put(b);
                        b.position(p);
                        if (directBuffer.remaining() == 0) {
                            break;
                        }
                    }
                    /*
                     * Do the flip: limit becomes position, position gets set to
                     * 0. This sets us up for the write.
                     */
                    directBuffer.flip();

                    int sent = sock.write(directBuffer);
                    ByteBuffer bb;

                    // Remove the buffers that we have sent
                    while (outgoingBuffers.size() > 0) {
                        bb = outgoingBuffers.peek();
                        if (bb == closeConn) {
                            throw new CloseRequestException("close requested");
                        }
                        int left = bb.remaining() - sent;
                        if (left > 0) {
                            /*
                             * We only partially sent this buffer, so we update
                             * the position and exit the loop.
                             */
                            bb.position(bb.position() + sent);
                            break;
                        }
                        packetSent();
                        /* We've sent the whole buffer, so drop the buffer */
                        sent -= bb.remaining();
                        outgoingBuffers.remove();
                    }
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK, "after send,
                    // outgoingBuffers.size() = " + outgoingBuffers.size());
                }

                synchronized(this.factory){
                    if (outgoingBuffers.size() == 0) {
                        if (!initialized
                                && (sk.interestOps() & SelectionKey.OP_READ) == 0) {
                            throw new CloseRequestException("responded to info probe");
                        }
                        sk.interestOps(sk.interestOps()
                                & (~SelectionKey.OP_WRITE));
                    } else {
                        sk.interestOps(sk.interestOps()
                                | SelectionKey.OP_WRITE);
                    }
                }
            }
        } catch (CancelledKeyException e) {
            LOG.warn("Exception causing close of session 0x"
                    + Long.toHexString(sessionId)
                    + " due to " + e);
            if (LOG.isDebugEnabled()) {
                LOG.debug("CancelledKeyException stack trace", e);
            }
            close();
        } catch (CloseRequestException e) {
            // expecting close to log session closure
            close();
        } catch (EndOfStreamException e) {
            LOG.warn(e); // tell user why

            // expecting close to log session closure
            close();
        } catch (IOException e) {
            LOG.warn("Exception causing close of session 0x"
                    + Long.toHexString(sessionId)
                    + " due to " + e);
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOException stack trace", e);
            }
            close();
        }
    }

    /**
     * 读连接以外的其他请求：
     * TODO 参见ZooKeeperServer
     * 
     * */
    private void readRequest() throws IOException {
        // We have the request, now process and setup for next
        InputStream bais = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bais);
        RequestHeader h = new RequestHeader();
        h.deserialize(bia, "header");
        // Through the magic of byte buffers, txn will not be
        // pointing
        // to the start of the txn
        incomingBuffer = incomingBuffer.slice();
        if (h.getType() == OpCode.auth) {
            AuthPacket authPacket = new AuthPacket();
            ZooKeeperServer.byteBuffer2Record(incomingBuffer, authPacket);
            String scheme = authPacket.getScheme();
            AuthenticationProvider ap = ProviderRegistry.getProvider(scheme);
            if (ap == null
                    || (ap.handleAuthentication(this, authPacket.getAuth())
                            != KeeperException.Code.OK)) {
                if (ap == null) {
                    LOG.warn("No authentication provider for scheme: "
                            + scheme + " has "
                            + ProviderRegistry.listProviders());
                } else {
                    LOG.warn("Authentication failed for scheme: " + scheme);
                }
                // send a response...
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.AUTHFAILED.intValue());
                sendResponse(rh, null, null);
                // ... and close connection
                sendCloseSession();
                disableRecv();
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Authentication succeeded for scheme: "
                              + scheme);
                }
                ReplyHeader rh = new ReplyHeader(h.getXid(), 0,
                        KeeperException.Code.OK.intValue());
                sendResponse(rh, null, null);
            }
            return;
        } else {
            Request si = new Request(this, sessionId, h.getXid(), h.getType(), incomingBuffer, authInfo);
            si.setOwner(ServerCnxn.me);
            zk.submitRequest(si);
        }
        if (h.getXid() >= 0) {
            synchronized (this) {
                outstandingRequests++;
            }
            synchronized (this.factory) {        
                // check throttling
                if (zk.getInProcess() > factory.outstandingLimit) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Throttling recv " + zk.getInProcess());
                    }
                    disableRecv();
                    // following lines should not be needed since we are
                    // already reading
                    // } else {
                    // enableRecv();
                }
            }
        }
    }

    public void disableRecv() {
        sk.interestOps(sk.interestOps() & (~SelectionKey.OP_READ));
    }

    public void enableRecv() {
        if (sk.isValid()) {
            int interest = sk.interestOps();
            if ((interest & SelectionKey.OP_READ) == 0) {
                sk.interestOps(interest | SelectionKey.OP_READ);
            }
        }
    }

    /**
     * 读客户端连接的请求:
     * 
     * */
    private void readConnectRequest() throws IOException, InterruptedException {
    	// 将读到的数据incomingBuffer反序列化为ConnectRequest
        BinaryInputArchive bia = BinaryInputArchive
                .getArchive(new ByteBufferInputStream(incomingBuffer));
        ConnectRequest connReq = new ConnectRequest();
        connReq.deserialize(bia, "connect");
        if (LOG.isDebugEnabled()) {
            LOG.debug("Session establishment request from client "
                    + sock.socket().getRemoteSocketAddress()
                    + " client's lastZxid is 0x"
                    + Long.toHexString(connReq.getLastZxidSeen()));
        }
        // ZooKeeperServer须就绪
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        // 注意这里 如果是重连 可能需要连一个比较新的ZooKeeperServer
        if (connReq.getLastZxidSeen() > zk.getZKDatabase().getDataTreeLastProcessedZxid()) {
            String msg = "Refusing session request for client "
                + sock.socket().getRemoteSocketAddress()
                + " as it has seen zxid 0x"
                + Long.toHexString(connReq.getLastZxidSeen())
                + " our last zxid is 0x"
                + Long.toHexString(zk.getZKDatabase().getDataTreeLastProcessedZxid())
                + " client must try another server";

            LOG.info(msg);
            throw new CloseRequestException(msg);
        }
        // 
        sessionTimeout = connReq.getTimeOut();
        byte passwd[] = connReq.getPasswd();
        int minSessionTimeout = zk.getMinSessionTimeout();
        if (sessionTimeout < minSessionTimeout) {
            sessionTimeout = minSessionTimeout;
        }
        int maxSessionTimeout = zk.getMaxSessionTimeout();
        if (sessionTimeout > maxSessionTimeout) {
            sessionTimeout = maxSessionTimeout;
        }
        // We don't want to receive any packets until we are sure that the
        // session is setup
        // TODO 在ZooKeeperServer里面建立session或者重新建立session
        // 
        disableRecv();
        if (connReq.getSessionId() != 0) {
            long clientSessionId = connReq.getSessionId();
            LOG.info("Client attempting to renew session 0x"
                    + Long.toHexString(clientSessionId)
                    + " at " + sock.socket().getRemoteSocketAddress());
            factory.closeSessionWithoutWakeup(clientSessionId);
            setSessionId(clientSessionId);
            zk.reopenSession(this, sessionId, passwd, sessionTimeout);
        } else {
            LOG.info("Client attempting to establish new session at "
                    + sock.socket().getRemoteSocketAddress());
            zk.createSession(this, passwd, sessionTimeout);
        }
        initialized = true;
    }

    private void packetReceived() {
        stats.incrPacketsReceived();
        if (zk != null) {
            zk.serverStats().incrementPacketsReceived();
        }
    }

    private void packetSent() {
        stats.incrPacketsSent();
        if (zk != null) {
            zk.serverStats().incrementPacketsSent();
        }
    }

    
    
    /**
     * 四字命令也要在这里处理 擦
     * 1 为什么是四字命令 这个是有讲究的 这里处理和正常的请求包是一起的 4个字节32位与包的len4字节保持一致
     * 2 
     * 
     * 
     * */
    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int confCmd =
        ByteBuffer.wrap("conf".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int consCmd =
        ByteBuffer.wrap("cons".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int crstCmd =
        ByteBuffer.wrap("crst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int dumpCmd =
        ByteBuffer.wrap("dump".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int enviCmd =
        ByteBuffer.wrap("envi".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int getTraceMaskCmd =
        ByteBuffer.wrap("gtmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int ruokCmd =
        ByteBuffer.wrap("ruok".getBytes()).getInt();
    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int setTraceMaskCmd =
        ByteBuffer.wrap("stmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int srvrCmd =
        ByteBuffer.wrap("srvr".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int srstCmd =
        ByteBuffer.wrap("srst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int statCmd =
        ByteBuffer.wrap("stat".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchcCmd =
        ByteBuffer.wrap("wchc".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchpCmd =
        ByteBuffer.wrap("wchp".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    private final static int wchsCmd =
        ByteBuffer.wrap("wchs".getBytes()).getInt();

    private final static HashMap<Integer, String> cmd2String =
        new HashMap<Integer, String>();

    // specify all of the commands that are available
    static {
        cmd2String.put(confCmd, "conf");
        cmd2String.put(consCmd, "cons");
        cmd2String.put(crstCmd, "crst");
        cmd2String.put(dumpCmd, "dump");
        cmd2String.put(enviCmd, "envi");
        cmd2String.put(getTraceMaskCmd, "gtmk");
        cmd2String.put(ruokCmd, "ruok");
        cmd2String.put(setTraceMaskCmd, "stmk");
        cmd2String.put(srstCmd, "srst");
        cmd2String.put(srvrCmd, "srvr");
        cmd2String.put(statCmd, "stat");
        cmd2String.put(wchcCmd, "wchc");
        cmd2String.put(wchpCmd, "wchp");
        cmd2String.put(wchsCmd, "wchs");
    }

    /**
     * clean up the socket related to a command and also make sure we flush the
     * data before we do that
     * 
     * @param pwriter
     *            the pwriter for a command socket
     *            
     *  清理和关闭PrintWriter --四字命令通过PrintWriter直接来做
     *  
     */
    private void cleanupWriterSocket(PrintWriter pwriter) {
        try {
            if (pwriter != null) {
                pwriter.flush();
                pwriter.close();
            }
        } catch (Exception e) {
            LOG.info("Error closing PrintWriter ", e);
        } finally {
            try {
                close();
            } catch (Exception e) {
                LOG.error("Error closing a command socket ", e);
            }
        }
    }
    
    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     * 
     * 扩展了Writer类、以配合我们需要的一些特性：
     * 1 默认情况下只有当字节数累积到 > 2048才会去实际写
     * 2 flush()时强制写、不管字节数多少。
     * 
     */
    private class SendBufferWriter extends Writer {
        private StringBuffer sb = new StringBuffer();
        
        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         * 
         * 1 强制flush时写
         * 2 字节数>2048时写
         * 
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) {
                sendBufferSync(ByteBuffer.wrap(sb.toString().getBytes()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        /**
         * 关闭前做强制flush
         * */
        @Override
        public void close() throws IOException {
            if (sb == null) return;
            checkFlush(true);
            sb = null; // clear out the ref to ensure no reuse
        }

        /**
         * 强制flush
         * 
         * */
        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        /**
         * 并不会立刻写、实际上放在sb里
         * 当到达2048才会实际写
         * 
         * */
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }
    }

    private static final String ZK_NOT_SERVING =
        "This ZooKeeper instance is not currently serving requests";
    
    /**
     * Set of threads for commmand ports. All the 4
     * letter commands are run via a thread. Each class
     * maps to a correspoding 4 letter command. CommandThread
     * is the abstract class from which all the others inherit.
     * 
     * 1 每个四字命令通过一个线程来处理、而且就运行一次、下一次再起这样一个线程---代价是否太大？？？？T
     * 2 CommandThread是所有四字命令线程的抽象类
     * 
     */
    private abstract class CommandThread extends Thread {
        PrintWriter pw;
        
        CommandThread(PrintWriter pw) {
            this.pw = pw;
        }
        
        /**
         * 该线程就运行一次、处理一个四字命令、运行完了之后就在finally里面关闭了
         * 
         * */
        public void run() {
            try {
                commandRun();
            } catch (IOException ie) {
                LOG.error("Error in running command ", ie);
            } finally {
                cleanupWriterSocket(pw);
            }
        }
        
        public abstract void commandRun() throws IOException;
    }
    
    private class RuokCommand extends CommandThread {
        public RuokCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            pw.print("imok");
            
        }
    }
    
    private class TraceMaskCommand extends CommandThread {
        TraceMaskCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            long traceMask = ZooTrace.getTextTraceLevel();
            pw.print(traceMask);
        }
    }
    
    private class SetTraceMaskCommand extends CommandThread {
        long trace = 0;
        SetTraceMaskCommand(PrintWriter pw, long trace) {
            super(pw);
            this.trace = trace;
        }
        
        @Override
        public void commandRun() {
            pw.print(trace);
        }
    }
    
    private class EnvCommand extends CommandThread {
        EnvCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            List<Environment.Entry> env = Environment.list();

            pw.println("Environment:");
            for(Environment.Entry e : env) {
                pw.print(e.getKey());
                pw.print("=");
                pw.println(e.getValue());
            }
            
        } 
    }
    
    private class ConfCommand extends CommandThread {
        ConfCommand(PrintWriter pw) {
            super(pw);
        }
            
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                zk.dumpConf(pw);
            }
        }
    }
    
    private class StatResetCommand extends CommandThread {
        public StatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else { 
                zk.serverStats().reset();
                pw.println("Server stats reset.");
            }
        }
    }
    
    private class CnxnStatResetCommand extends CommandThread {
        public CnxnStatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                synchronized(factory.cnxns){
                    for(NIOServerCnxn c : factory.cnxns){
                        c.getStats().reset();
                    }
                }
                pw.println("Connection stats reset.");
            }
        }
    }

    private class DumpCommand extends CommandThread {
        public DumpCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else {
                pw.println("SessionTracker dump:");
                zk.sessionTracker.dumpSessions(pw);
                pw.println("ephemeral nodes dump:");
                zk.dumpEphemerals(pw);
            }
        }
    }
    
    private class StatCommand extends CommandThread {
        int len;
        public StatCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            }
            else {   
                pw.print("Zookeeper version: ");
                pw.println(Version.getFullVersion());
                if (len == statCmd) {
                    LOG.info("Stat command output");
                    pw.println("Clients:");
                    // clone should be faster than iteration
                    // ie give up the cnxns lock faster
                    HashSet<NIOServerCnxn> cnxnset;
                    synchronized(factory.cnxns){
                        cnxnset = (HashSet<NIOServerCnxn>)factory
                        .cnxns.clone();
                    }
                    for(NIOServerCnxn c : cnxnset){
                        ((CnxnStats)c.getStats())
                        .dumpConnectionInfo(pw, true);
                    }
                    pw.println();
                }
                pw.print(zk.serverStats().toString());
                pw.print("Node count: ");
                pw.println(zk.getZKDatabase().getNodeCount());
            }
            
        }
    }
    
    private class ConsCommand extends CommandThread {
        public ConsCommand(PrintWriter pw) {
            super(pw);
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                // clone should be faster than iteration
                // ie give up the cnxns lock faster
                HashSet<NIOServerCnxn> cnxns;
                synchronized (factory.cnxns) {
                    cnxns = (HashSet<NIOServerCnxn>) factory.cnxns.clone();
                }
                for (NIOServerCnxn c : cnxns) {
                    ((CnxnStats) c.getStats()).dumpConnectionInfo(pw, false);
                }
                pw.println();
            }
        }
    }
    
    private class WatchCommand extends CommandThread {
        int len = 0;
        public WatchCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }

        @Override
        public void commandRun() {
            if (zk == null) {
                pw.println(ZK_NOT_SERVING);
            } else {
                DataTree dt = zk.getZKDatabase().getDataTree();
                if (len == wchsCmd) {
                    dt.dumpWatchesSummary(pw);
                } else if (len == wchpCmd) {
                    dt.dumpWatches(pw, true);
                } else {
                    dt.dumpWatches(pw, false);
                }
                pw.println();
            }
        }
    }
    
    
    /** Return if four letter word found and responded to, otw false 
     * 检查请求是否是四字命令:
     * @len 已经从k里读出来的四字节长度 有可能是请求包的len或者四字命令
     * @k   SelectionKey 在这里作用不大 --如果发现是四字命令 需要从epoll里取消关注即可
     * 
     * 根据len值判断:
     * 1 不是四字命令：直接返回false
     * 2 是四字命令：启动对应的四字命令线程处理并响应、完成后返回true
     * 
     * **/
    private boolean checkFourLetterWord(final SelectionKey k, final int len)
    throws IOException
    {
        // We take advantage of the limited size of the length to look
        // for cmds. They are all 4-bytes which fits inside of an int
        String cmd = cmd2String.get(len);
        if (cmd == null) {
            return false;
        }
        LOG.info("Processing " + cmd + " command from "
                + sock.socket().getRemoteSocketAddress());
        packetReceived();

        /** cancel the selection key to remove the socket handling
         * from selector. This is to prevent netcat problem wherein
         * netcat immediately closes the sending side after sending the
         * commands and still keeps the receiving channel open. 
         * The idea is to remove the selectionkey from the selector
         * so that the selector does not notice the closed read on the
         * socket channel and keep the socket alive to write the data to
         * and makes sure to close the socket after its done writing the data
         */
        if (k != null) {
            try {
                k.cancel();
            } catch(Exception e) {
                LOG.error("Error cancelling command selection key ", e);
            }
        }

        final PrintWriter pwriter = new PrintWriter(
                new BufferedWriter(new SendBufferWriter()));
        if (len == ruokCmd) {
            RuokCommand ruok = new RuokCommand(pwriter);
            ruok.start();
            return true;
        } else if (len == getTraceMaskCmd) {
            TraceMaskCommand tmask = new TraceMaskCommand(pwriter);
            tmask.start();
            return true;
        } else if (len == setTraceMaskCmd) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new IOException("Read error");
            }

            incomingBuffer.flip();
            long traceMask = incomingBuffer.getLong();
            ZooTrace.setTextTraceLevel(traceMask);
            SetTraceMaskCommand setMask = new SetTraceMaskCommand(pwriter, traceMask);
            setMask.start();
            return true;
        } else if (len == enviCmd) {
            EnvCommand env = new EnvCommand(pwriter);
            env.start();
            return true;
        } else if (len == confCmd) {
            ConfCommand ccmd = new ConfCommand(pwriter);
            ccmd.start();
            return true;
        } else if (len == srstCmd) {
            StatResetCommand strst = new StatResetCommand(pwriter);
            strst.start();
            return true;
        } else if (len == crstCmd) {
            CnxnStatResetCommand crst = new CnxnStatResetCommand(pwriter);
            crst.start();
            return true;
        } else if (len == dumpCmd) {
            DumpCommand dump = new DumpCommand(pwriter);
            dump.start();
            return true;
        } else if (len == statCmd || len == srvrCmd) {
            StatCommand stat = new StatCommand(pwriter, len);
            stat.start();
            return true;
        } else if (len == consCmd) {
            ConsCommand cons = new ConsCommand(pwriter);
            cons.start();
            return true;
        } else if (len == wchpCmd || len == wchcCmd || len == wchsCmd) {
            WatchCommand wcmd = new WatchCommand(pwriter, len);
            wcmd.start();
            return true;
        }
        return false;
    }

    /** Reads the first 4 bytes of lenBuffer, which could be true length or
     *  four letter word.
     *
     * @param k selection key
     * @return true if length read, otw false (wasn't really the length)
     * @throws IOException if buffer size exceeds maxBuffer size
     * 
     * 从lenBuffer读请求包长度(也许是四字命令)--所以在这里处理了两种逻辑:
     * 1 如果是四字命令：处理四字命令、返回false
     * 2 如果是请求包长度：读到了长度、分配incomingBuffer、返回true
     * 
     */
    private boolean readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt();
        if (!initialized && checkFourLetterWord(k, len)) {
            return false;
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) {
            throw new IOException("Len error " + len);
        }
        if (zk == null) {
            throw new IOException("ZooKeeperServer not running");
        }
        incomingBuffer = ByteBuffer.allocate(len);
        return true;
    }

    /**
     * The number of requests that have been submitted but not yet responded to.
     * 已经提交但是还没有响应的请求 TODO 提交到哪？
     */
    int outstandingRequests;

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
     */
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     * 
     * 唯一标示一个session的id
     */
    long sessionId;

    // 没用到
    static long nextSessionId = 1;

    /**
     * NIOServerCnxn对象:
     * @zk   绑定的ZooKeeperServer
     * @sock 绑定的SocketChannel
     * @sk   绑定的SelectionKey
     * @factory 绑定的Factory
     * 
     * */
    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
            SelectionKey sk, Factory factory) throws IOException {
        this.zk = zk;
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        sock.socket().setTcpNoDelay(true);
        sock.socket().setSoLinger(false, -1);
        InetAddress addr = ((InetSocketAddress) sock.socket()
                .getRemoteSocketAddress()).getAddress();
        authInfo.add(new Id("ip", addr.getHostAddress()));
        sk.interestOps(SelectionKey.OP_READ);
    }

    @Override
    public String toString() {
        return "NIOServerCnxn object with sock = " + sock + " and sk = " + sk;
    }

    /*
     * Close the cnxn and remove it from the factory cnxns list.
     * 
     * This function returns immediately if the cnxn is not on the cnxns list.
     * 
     * 关闭NIOServerCnxn对象:
     * 
     */
    public void close() {
        synchronized(factory.cnxns){
        	// 从factory移除
            // if this is not in cnxns then it's already closed
            if (!factory.cnxns.remove(this)) {
                return;
            }

            synchronized (factory.ipMap) {
                Set<NIOServerCnxn> s =
                    factory.ipMap.get(sock.socket().getInetAddress());
                s.remove(this);
            }

            // 从JMX移除
            // unregister from JMX
            try {
                if(jmxConnectionBean != null){
                    MBeanRegistry.getInstance().unregister(jmxConnectionBean);
                }
            } catch (Exception e) {
                LOG.warn("Failed to unregister with JMX", e);
            }
            jmxConnectionBean = null;
    
            // 从ZooKeeperServer移除
            if (zk != null) {
                zk.removeCnxn(this);
            }
    
            // 关闭socket
            closeSock();
    
            if (sk != null) {
                try {
                    // need to cancel this selection key from the selector
                    sk.cancel();
                } catch (Exception e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("ignoring exception during selectionkey cancel", e);
                    }
                }
            }
        }
    }

    /**
     * Close resources associated with the sock of this cnxn. 
     * 
     * 关闭socket
     * 
     */
    private void closeSock() {
        if (sock == null) {
            return;
        }

        LOG.info("Closed socket connection for client "
                + sock.socket().getRemoteSocketAddress()
                + (sessionId != 0 ?
                        " which had sessionid 0x" + Long.toHexString(sessionId) :
                        " (no session established for client)"));
        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
             */
            sock.socket().shutdownOutput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during output shutdown", e);
            }
        }
        try {
            sock.socket().shutdownInput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during input shutdown", e);
            }
        }
        try {
            sock.socket().close();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socket close", e);
            }
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socketchannel close", e);
            }
        }
        sock = null;
    }
    
    // 四字节:len 代表数据包的长度(header + record)
    private final static byte fourBytes[] = new byte[4];

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#sendResponse(org.apache.zookeeper.proto.ReplyHeader,
     *      org.apache.jute.Record, java.lang.String)
     *      
     *      发送内容:len + header + record
     *      @len     代表跟在后面的header+record的长度
     *      @header  “header” —> 消息头
     *      @record  “tag” —>消息体
     *      
     */
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Make space for length
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            try {
                baos.write(fourBytes);
                bos.writeRecord(h, "header");
                if (r != null) {
                    bos.writeRecord(r, tag);
                }
                baos.close();
            } catch (IOException e) {
                LOG.error("Error serializing response");
            }
            byte b[] = baos.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.putInt(b.length - 4).rewind();
            // 异步发送 只是放入响应队列
            sendBuffer(bb);
            // 维护一些状态和限流
            if (h.getXid() > 0) {
                synchronized(this){
                    outstandingRequests--;
                }
                // check throttling
                synchronized (this.factory) {        
                    if (zk.getInProcess() < factory.outstandingLimit
                            || outstandingRequests < 1) {
                        sk.selector().wakeup();
                        enableRecv();
                    }
                }
            }
         } catch(Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
         }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     * 
     * server端的处理监听事件、其实只是给客户端发一个通知即可。
     * 
     */
    synchronized public void process(WatchedEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                                     "Deliver event " + event + " to 0x"
                                     + Long.toHexString(this.sessionId)
                                     + " through " + this);
        }

        // Convert WatchedEvent to a type that can be sent over the wire
        WatcherEvent e = event.getWrapper();

        sendResponse(h, e, "notification");
    }

    /**
     * 当session建立后:
     * valid：session是否有效
     * 
     * */
    public void finishSessionInit(boolean valid) {
    	/**
    	 * 0 注册JMX
    	 * 
    	 * */
        // register with JMX
        try {
            jmxConnectionBean = new ConnectionBean(this, zk);
            MBeanRegistry.getInstance().register(jmxConnectionBean, zk.jmxServerBean);
        } catch (Exception e) {
            LOG.warn("Failed to register with JMX", e);
            jmxConnectionBean = null;
        }

        /**
         * 1 给客户端响应
         * 
         * */
        try {
            ConnectResponse rsp = new ConnectResponse(0, valid ? sessionTimeout
                    : 0, valid ? sessionId : 0, // send 0 if session is no
                    // longer valid
                    valid ? zk.generatePasswd(sessionId) : new byte[16]);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            bos.writeInt(-1, "len");
            rsp.serialize(bos, "connect");
            baos.close();
            ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
            bb.putInt(bb.remaining() - 4).rewind();
            sendBuffer(bb);

            /**
             * 2 根据valid判断是否需要sendCloseSession()--触发后续关闭该连接
             * 
             * */
            if (!valid) {
                LOG.info("Invalid session 0x"
                        + Long.toHexString(sessionId)
                        + " for client "
                        + sock.socket().getRemoteSocketAddress()
                        + ", probably expired");
                sendCloseSession();
            } else {
                LOG.info("Established session 0x"
                        + Long.toHexString(sessionId)
                        + " with negotiated timeout " + sessionTimeout
                        + " for client "
                        + sock.socket().getRemoteSocketAddress());
            }

            /**
             * 3 开启接受数据包
             * 
             * */
            // Now that the session is ready we can start receiving packets
            synchronized (this.factory) {
                sk.selector().wakeup();
                enableRecv();
            }
        } catch (Exception e) {
            LOG.warn("Exception while establishing session, closing", e);
            close();
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public ArrayList<Id> getAuthInfo() {
        return authInfo;
    }

    public synchronized InetSocketAddress getRemoteAddress() {
        if (sock == null) {
            return null;
        }
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    
    
    /**
     * 该连接或者说是session、即一个NIOServerCnxn的状态/统计信息
     * 
     * */
    class CnxnStats implements ServerCnxn.Stats {
        private final Date established = new Date();

        private final AtomicLong packetsReceived = new AtomicLong();
        private final AtomicLong packetsSent = new AtomicLong();

        private long minLatency;
        private long maxLatency;
        private String lastOp;
        private long lastCxid;
        private long lastZxid;
        private long lastResponseTime;
        private long lastLatency;

        private long count;
        private long totalLatency;

        CnxnStats() {
            reset();
        }

        public synchronized void reset() {
            packetsReceived.set(0);
            packetsSent.set(0);
            minLatency = Long.MAX_VALUE;
            maxLatency = 0;
            lastOp = "NA";
            lastCxid = -1;
            lastZxid = -1;
            lastResponseTime = 0;
            lastLatency = 0;

            count = 0;
            totalLatency = 0;
        }

        long incrPacketsReceived() {
            return packetsReceived.incrementAndGet();
        }

        long incrPacketsSent() {
            return packetsSent.incrementAndGet();
        }

        synchronized void updateForResponse(long cxid, long zxid, String op,
                long start, long end)
        {
            // don't overwrite with "special" xids - we're interested
            // in the clients last real operation
            if (cxid >= 0) {
                lastCxid = cxid;
            }
            lastZxid = zxid;
            lastOp = op;
            lastResponseTime = end;
            long elapsed = end - start;
            lastLatency = elapsed;
            if (elapsed < minLatency) {
                minLatency = elapsed;
            }
            if (elapsed > maxLatency) {
                maxLatency = elapsed;
            }
            count++;
            totalLatency += elapsed;
        }

        public Date getEstablished() {
            return established;
        }

        public long getOutstandingRequests() {
            synchronized (NIOServerCnxn.this) {
                synchronized (NIOServerCnxn.this.factory) {
                    return outstandingRequests;
                }
            }
        }

        public long getPacketsReceived() {
            return packetsReceived.longValue();
        }

        public long getPacketsSent() {
            return packetsSent.longValue();
        }

        public synchronized long getMinLatency() {
            return minLatency == Long.MAX_VALUE ? 0 : minLatency;
        }

        public synchronized long getAvgLatency() {
            return count == 0 ? 0 : totalLatency / count;
        }

        public synchronized long getMaxLatency() {
            return maxLatency;
        }

        public synchronized String getLastOperation() {
            return lastOp;
        }

        public synchronized long getLastCxid() {
            return lastCxid;
        }

        public synchronized long getLastZxid() {
            return lastZxid;
        }

        public synchronized long getLastResponseTime() {
            return lastResponseTime;
        }

        public synchronized long getLastLatency() {
            return lastLatency;
        }

        /**
         * Prints detailed stats information for the connection.
         *
         * @see dumpConnectionInfo(PrintWriter, boolean) for brief stats
         */
        @Override
        public String toString() {
            StringWriter sw = new StringWriter();
            PrintWriter pwriter = new PrintWriter(sw);
            dumpConnectionInfo(pwriter, false);
            pwriter.flush();
            pwriter.close();
            return sw.toString();
        }

        /**
         * Print information about the connection.
         * @param brief iff true prints brief details, otw full detail
         * @return information about this connection
         */
        public synchronized void
        dumpConnectionInfo(PrintWriter pwriter, boolean brief)
        {
            Channel channel = sk.channel();
            if (channel instanceof SocketChannel) {
                pwriter.print(" ");
                pwriter.print(((SocketChannel)channel).socket()
                        .getRemoteSocketAddress());
                pwriter.print("[");
                pwriter.print(sk.isValid() ? Integer.toHexString(sk.interestOps())
                        : "0");
                pwriter.print("](queued=");
                pwriter.print(getOutstandingRequests());
                pwriter.print(",recved=");
                pwriter.print(getPacketsReceived());
                pwriter.print(",sent=");
                pwriter.print(getPacketsSent());

                if (!brief) {
                    long sessionId = getSessionId();
                    if (sessionId != 0) {
                        pwriter.print(",sid=0x");
                        pwriter.print(Long.toHexString(sessionId));
                        pwriter.print(",lop=");
                        pwriter.print(getLastOperation());
                        pwriter.print(",est=");
                        pwriter.print(getEstablished().getTime());
                        pwriter.print(",to=");
                        pwriter.print(getSessionTimeout());
                        long lastCxid = getLastCxid();
                        if (lastCxid >= 0) {
                            pwriter.print(",lcxid=0x");
                            pwriter.print(Long.toHexString(lastCxid));
                        }
                        pwriter.print(",lzxid=0x");
                        pwriter.print(Long.toHexString(getLastZxid()));
                        pwriter.print(",lresp=");
                        pwriter.print(getLastResponseTime());
                        pwriter.print(",llat=");
                        pwriter.print(getLastLatency());
                        pwriter.print(",minlat=");
                        pwriter.print(getMinLatency());
                        pwriter.print(",avglat=");
                        pwriter.print(getAvgLatency());
                        pwriter.print(",maxlat=");
                        pwriter.print(getMaxLatency());
                    }
                }
                pwriter.println(")");
            }
        }
    }

    /**
     * 一个NIOServerCnxn持有一个它自己的统计信息
     * 
     * */
    private final CnxnStats stats = new CnxnStats();
    public Stats getStats() {
        return stats;
    }
}
