/*
 * Copyright (C) 2016-2018 phantombot.tv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.gmt2001.httpwsserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import io.netty.util.AttributeKey;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONObject;
import org.json.JSONStringer;

/**
 * Processes WebSocket frames and passes successful ones to the appropriate registered final handler
 *
 * @author gmt2001
 */
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /**
     * A map of registered {@link WsFrameHandler} for handling WebSockets
     */
    static Map<String, WsFrameHandler> wsFrameHandlers = new ConcurrentHashMap<>();
    /**
     * Represents the {@code uri} attribute
     */
    private static final AttributeKey<String> uri = AttributeKey.valueOf("uri");
    /**
     * Represents the {@code uri} attribute
     */
    private static final Queue<Channel> wsSessions = new ConcurrentLinkedQueue<>();

    /**
     * Default Constructor
     */
    WebSocketFrameHandler() {
    }

    /**
     * Handles incoming WebSocket frames and passes them to the appropriate {@link WsFrameHandler}
     *
     * @param ctx The {@link ChannelHandlerContext} of the session
     * @param req The {@link WebSocketFrame} containing the request frame
     * @throws Exception Passes any thrown exceptions up the stack
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        WsFrameHandler h = wsFrameHandlers.get(ctx.channel().attr(uri).get());

        if (h.getAuthHandler().checkAuthorization(ctx, frame)) {
            h.handleFrame(ctx, frame);
        }
    }

    /**
     * Captures {@link HandshakeComplete} events and saves the {@link WsFrameHandler} URI to the session
     *
     * If a handler is not available for the requested path, then {@code 404 NOT FOUND} is sent back to the client using JSON:API format
     *
     * @param ctx The {@link ChannelHandlerContext} of the session
     * @param evt The event object
     * @throws Exception Passes any thrown exceptions up the stack
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HandshakeComplete) {
            HandshakeComplete hc = (HandshakeComplete) evt;
            String ruri = determineWsFrameHandler(hc.requestUri());

            if (ruri.isBlank()) {
                JSONStringer jsonObject = new JSONStringer();
                jsonObject.object().key("errors").array().object()
                        .key("status").value("404")
                        .key("title").value("URI Path Not Found")
                        .key("detail").value("The URI path '" + hc.requestUri() + "' does not have a valid handler")
                        .endObject().endArray().endObject();

                ctx.channel().writeAndFlush(new TextWebSocketFrame(jsonObject.toString()));
                ctx.close();
            } else {
                ctx.channel().attr(uri).set(ruri);
                ctx.channel().closeFuture().addListener((ChannelFutureListener) (ChannelFuture f) -> {
                    wsSessions.remove(f.channel());
                });
                wsSessions.add(ctx.channel());
            }
        }
    }

    /**
     * Handles exceptions that are thrown up the stack
     *
     * @param ctx The {@link ChannelHandlerContext} of the session
     * @param cause The exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        com.gmt2001.Console.debug.printOrLogStackTrace(cause);
        ctx.close();
    }

    /**
     * Determines the best {@link WsFrameHandler} to use for a given URI
     *
     * @param uri The URI to check
     * @return The key of the {@link WsFrameHandler} to use, or {@code ""} if none were found
     */
    static String determineWsFrameHandler(String uri) {
        String bestMatch = "";

        if (uri.contains("..")) {
            return null;
        }

        for (String k : wsFrameHandlers.keySet()) {
            if (uri.startsWith(k) && k.length() > bestMatch.length()) {
                bestMatch = k;
            }
        }

        return bestMatch;
    }

    /**
     * Creates and prepares a text-type {@link WebSocketFrame} for transmission
     *
     * @param content The content to send
     * @return A {@link WebSocketFrame} that is ready to transmit
     */
    public static WebSocketFrame prepareTextWebSocketResponse(String content) {
        return new TextWebSocketFrame(content);
    }

    /**
     * Creates and prepares a text-type {@link WebSocketFrame} for transmission from a {@link JSONObject}
     *
     * @param json The {@link JSONObject} to send
     * @return A {@link WebSocketFrame} that is ready to transmit
     */
    public static WebSocketFrame prepareTextWebSocketResponse(JSONObject json) {
        return new TextWebSocketFrame(json.toString());
    }

    /**
     * Creates and prepares a binary-type {@link WebSocketFrame} for transmission
     *
     * @param content The binary content to send
     * @return A {@link WebSocketFrame} that is ready to transmit
     */
    public static WebSocketFrame prepareBinaryWebSocketResponse(byte[] content) {
        return new BinaryWebSocketFrame(Unpooled.copiedBuffer(content));
    }

    /**
     * Transmits a {@link WebSocketFrame} back to the client
     *
     * @param ctx The {@link ChannelHandlerContext} of the session
     * @param frame The {@link WebSocketFrame} containing the request
     * @param resframe The {@link WebSocketFrame} to transmit
     */
    public static void sendWsFrame(ChannelHandlerContext ctx, WebSocketFrame frame, WebSocketFrame resframe) {
        ctx.channel().writeAndFlush(resframe);
    }

    /**
     * Transmits a {@link WebSocketFrame} to all authenticated clients
     *
     * @param resframe The {@link WebSocketFrame} to transmit
     */
    public static void broadcastWsFrame(WebSocketFrame resframe) {
        wsSessions.forEach((c) -> {
            c.writeAndFlush(resframe);
        });
    }

    /**
     * Registers a WS URI path to a {@link WsFrameHandler}
     *
     * @param path The URI path to bind the handler to
     * @param handler The {@link WsFrameHandler} that will handle the requests
     * @throws IllegalArgumentException If {@code path} is either already registered, or illegal
     * @see validateUriPath
     */
    public static void registerWsHandler(String path, WsFrameHandler handler) {
        if (HTTPWSServer.validateUriPath(path, true)) {
            if (wsFrameHandlers.containsKey(path)) {
                throw new IllegalArgumentException("The specified path is already registered. Please unregister it first");
            } else {
                wsFrameHandlers.put(path, handler);
            }
        } else {
            throw new IllegalArgumentException("Illegal path. Must not contain .. and must start with /ws");
        }
    }

    /**
     * Deregisters a WS URI path
     *
     * @param path The path to deregister
     */
    public static void deregisterWsHandler(String path) {
        wsFrameHandlers.remove(path);
    }

}
