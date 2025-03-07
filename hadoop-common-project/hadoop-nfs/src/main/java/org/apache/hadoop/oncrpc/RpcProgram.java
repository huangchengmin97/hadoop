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
package org.apache.hadoop.oncrpc;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.oncrpc.RpcAcceptedReply.AcceptState;
import org.apache.hadoop.oncrpc.security.VerifierNone;
import org.apache.hadoop.portmap.PortmapMapping;
import org.apache.hadoop.portmap.PortmapRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for writing RPC server programs based on RFC 1050. Extend this class
 * and implement {@link #handleInternal} to handle the requests received.
 */
public abstract class RpcProgram extends ChannelInboundHandlerAdapter {
  static final Logger LOG = LoggerFactory.getLogger(RpcProgram.class);
  public static final int RPCB_PORT = 111;
  private final String program;
  private final String host;
  private int port; // Ephemeral port is chosen later
  private final int progNumber;
  private final int lowProgVersion;
  private final int highProgVersion;
  protected final boolean allowInsecurePorts;
  
  /**
   * If not null, this will be used as the socket to use to connect to the
   * system portmap daemon when registering this RPC server program.
   */
  private final DatagramSocket registrationSocket;
  /*
   * Timeout value in millisecond for the rpc connection to portmap
   */
  private final int portmapUdpTimeoutMillis;

  protected RpcProgram(String program, String host, int port, int progNumber,
      int lowProgVersion, int highProgVersion,
      DatagramSocket registrationSocket, boolean allowInsecurePorts) {
    this(program, host, port, progNumber, lowProgVersion, highProgVersion,
            registrationSocket, allowInsecurePorts, 500);
  }

  /**
   * Constructor
   * 
   * @param program program name
   * @param host host where the Rpc server program is started
   * @param port port where the Rpc server program is listening to
   * @param progNumber program number as defined in RFC 1050
   * @param lowProgVersion lowest version of the specification supported
   * @param highProgVersion highest version of the specification supported
   * @param registrationSocket if not null, use this socket to register
   *        with portmap daemon
   * @param allowInsecurePorts true to allow client connections from
   *        unprivileged ports, false otherwise
   * @param  portmapUdpTimeoutMillis timeout in milliseconds for RPC connection
   */
  protected RpcProgram(String program, String host, int port, int progNumber,
      int lowProgVersion, int highProgVersion,
      DatagramSocket registrationSocket, boolean allowInsecurePorts,
      int portmapUdpTimeoutMillis) {
    this.program = program;
    this.host = host;
    this.port = port;
    this.progNumber = progNumber;
    this.lowProgVersion = lowProgVersion;
    this.highProgVersion = highProgVersion;
    this.registrationSocket = registrationSocket;
    this.allowInsecurePorts = allowInsecurePorts;
    this.portmapUdpTimeoutMillis = portmapUdpTimeoutMillis;
    LOG.info("Will " + (allowInsecurePorts ? "" : "not ") + "accept client "
        + "connections from unprivileged ports");
  }

  /**
   * Register this program with the local portmapper.
   * @param transport transport layer for port map
   * @param boundPort port number of bounded RPC program
   */
  public void register(int transport, int boundPort) {
    if (boundPort != port) {
      LOG.info("The bound port is " + boundPort
          + ", different with configured port " + port);
      port = boundPort;
    }
    // Register all the program versions with portmapper for a given transport
    for (int vers = lowProgVersion; vers <= highProgVersion; vers++) {
      PortmapMapping mapEntry = new PortmapMapping(progNumber, vers, transport,
          port);
      register(mapEntry, true);
    }
  }
  
  /**
   * Unregister this program with the local portmapper.
   * @param transport transport layer for port map
   * @param boundPort port number of bounded RPC program
   */
  public void unregister(int transport, int boundPort) {
    if (boundPort != port) {
      LOG.info("The bound port is " + boundPort
          + ", different with configured port " + port);
      port = boundPort;
    }
    // Unregister all the program versions with portmapper for a given transport
    for (int vers = lowProgVersion; vers <= highProgVersion; vers++) {
      PortmapMapping mapEntry = new PortmapMapping(progNumber, vers, transport,
          port);
      register(mapEntry, false);
    }
  }
  
  /**
   * Register the program with Portmap or Rpcbind.
   * @param mapEntry port map entries
   * @param set specifies registration or not
   */
  protected void register(PortmapMapping mapEntry, boolean set) {
    XDR mappingRequest = PortmapRequest.create(mapEntry, set);
    SimpleUdpClient registrationClient = new SimpleUdpClient(host, RPCB_PORT,
        mappingRequest, true, registrationSocket, portmapUdpTimeoutMillis);
    try {
      registrationClient.run();
    } catch (IOException e) {
      String request = set ? "Registration" : "Unregistration";
      LOG.error(request + " failure with " + host + ":" + port
          + ", portmap entry: " + mapEntry);
      throw new RuntimeException(request + " failure", e);
    }
  }

  // Start extra daemons or services
  public void startDaemons() {}
  public void stopDaemons() {}
  
  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg)
      throws Exception {
    RpcInfo info = (RpcInfo) msg;
    RpcCall call = (RpcCall) info.header();
    
    SocketAddress remoteAddress = info.remoteAddress();
    if (LOG.isTraceEnabled()) {
      LOG.trace(program + " procedure #" + call.getProcedure());
    }
    
    if (this.progNumber != call.getProgram()) {
      LOG.warn("Invalid RPC call program " + call.getProgram());
      sendAcceptedReply(call, remoteAddress, AcceptState.PROG_UNAVAIL, ctx);
      return;
    }

    int ver = call.getVersion();
    if (ver < lowProgVersion || ver > highProgVersion) {
      LOG.warn("Invalid RPC call version " + ver);
      sendAcceptedReply(call, remoteAddress, AcceptState.PROG_MISMATCH, ctx);
      return;
    }
    
    handleInternal(ctx, info);
  }
  
  public boolean doPortMonitoring(SocketAddress remoteAddress) {
    if (!allowInsecurePorts) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Will not allow connections from unprivileged ports. "
            + "Checking for valid client port...");
      }

      if (remoteAddress instanceof InetSocketAddress) {
        InetSocketAddress inetRemoteAddress = (InetSocketAddress) remoteAddress;
        if (inetRemoteAddress.getPort() > 1023) {
          LOG.warn("Connection attempted from '" + inetRemoteAddress + "' "
              + "which is an unprivileged port. Rejecting connection.");
          return false;
        }
      } else {
        LOG.warn("Could not determine remote port of socket address '"
            + remoteAddress + "'. Rejecting connection.");
        return false;
      }
    }
    return true;
  }
  
  private void sendAcceptedReply(RpcCall call, SocketAddress remoteAddress,
      AcceptState acceptState, ChannelHandlerContext ctx) {
    RpcAcceptedReply reply = RpcAcceptedReply.getInstance(call.getXid(),
        acceptState, VerifierNone.INSTANCE);

    XDR out = new XDR();
    reply.write(out);
    if (acceptState == AcceptState.PROG_MISMATCH) {
      out.writeInt(lowProgVersion);
      out.writeInt(highProgVersion);
    }
    ByteBuf b = Unpooled.wrappedBuffer(out.asReadOnlyWrap()
        .buffer());
    RpcResponse rsp = new RpcResponse(b, remoteAddress);
    RpcUtil.sendRpcResponse(ctx, rsp);
  }
  
  protected static void sendRejectedReply(RpcCall call,
      SocketAddress remoteAddress, ChannelHandlerContext ctx) {
    XDR out = new XDR();
    RpcDeniedReply reply = new RpcDeniedReply(call.getXid(),
        RpcReply.ReplyState.MSG_DENIED,
        RpcDeniedReply.RejectState.AUTH_ERROR, new VerifierNone());
    reply.write(out);
    ByteBuf buf = Unpooled.wrappedBuffer(out.asReadOnlyWrap()
        .buffer());
    RpcResponse rsp = new RpcResponse(buf, remoteAddress);
    RpcUtil.sendRpcResponse(ctx, rsp);
  }

  protected abstract void handleInternal(ChannelHandlerContext ctx, RpcInfo info);
  
  @Override
  public String toString() {
    return "Rpc program: " + program + " at " + host + ":" + port;
  }
  
  protected abstract boolean isIdempotent(RpcCall call);
  
  public int getPort() {
    return port;
  }

  @VisibleForTesting
  public int getPortmapUdpTimeoutMillis() {
    return portmapUdpTimeoutMillis;
  }
}