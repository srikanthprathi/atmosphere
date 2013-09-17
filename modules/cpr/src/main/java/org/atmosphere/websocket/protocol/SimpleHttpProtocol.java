/*
* Copyright 2013 Jeanfrancois Arcand
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/
package org.atmosphere.websocket.protocol;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket messages to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation by wrapping the Websocket message's bytes within
 * an {@link javax.servlet.http.HttpServletRequest}.
 * <p/>
 * The content-type is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_CONTENT_TYPE} property
 * The method is defined using {@link org.atmosphere.cpr.ApplicationConfig#WEBSOCKET_METHOD} property
 * <p/>
 *
 * @author Jeanfrancois Arcand
 */
public class SimpleHttpProtocol implements WebSocketProtocol, Serializable {

    private static final Logger logger = LoggerFactory.getLogger(SimpleHttpProtocol.class);
    private String contentType = "text/plain";
    private String methodType = "POST";
    private String delimiter = "@@";
    private boolean destroyable;

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(AtmosphereConfig config) {
        String contentType = config.getInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE);
        if (contentType == null) {
            contentType = "text/plain";
        }
        this.contentType = contentType;

        String methodType = config.getInitParameter(ApplicationConfig.WEBSOCKET_METHOD);
        if (methodType == null) {
            methodType = "POST";
        }
        this.methodType = methodType;

        String delimiter = config.getInitParameter(ApplicationConfig.WEBSOCKET_PATH_DELIMITER);
        if (delimiter == null) {
            delimiter = "@@";
        }
        this.delimiter = delimiter;

        String s = config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        if (s != null && Boolean.valueOf(s)) {
            destroyable = true;
        } else {
            destroyable = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, String d) {
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        if (resource == null) {
            logger.trace("The WebSocket has been closed before the message was processed.");
            return null;
        }
        AtmosphereRequest request = resource.getRequest();
        String pathInfo = request.getPathInfo();
        String requestURI = request.getRequestURI();

        if (d.startsWith(delimiter)) {
            int delimiterLength = delimiter.length();
            int bodyBeginIndex = d.indexOf(delimiter, delimiterLength);
            if (bodyBeginIndex != -1) {
                pathInfo = d.substring(delimiterLength, bodyBeginIndex);
                requestURI += pathInfo;
                d = d.substring(bodyBeginIndex + delimiterLength);
            }
        }

        Map<String,Object> m = attributes(request);
        List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();

        // We need to create a new AtmosphereRequest as WebSocket message may arrive concurrently on the same connection.
        list.add(new AtmosphereRequest.Builder()
                .request(request)
                .method(methodType)
                .contentType(contentType)
                .body(d)
                .attributes(m)
                .pathInfo(pathInfo)
                .requestURI(requestURI)
                .destroyable(destroyable)
                .headers(request.headersMap())
                .session(resource.session())
                .build());

        return list;
    }

    private Map<String, Object> attributes(AtmosphereRequest request) {

        Map<String, Object> m = new HashMap<String, Object>();
        m.put(FrameworkConfig.WEBSOCKET_SUBPROTOCOL, FrameworkConfig.SIMPLE_HTTP_OVER_WEBSOCKET);

        Integer hint = (Integer) request.getAttribute(FrameworkConfig.HINT_ATTRIBUTES_SIZE);
        try {
            if (hint != null && hint != request.attributes().size()) {
                // The original AtmosphereRequest seems to be used, take no risk and clone the attribute.
                Map<String, Object> copy = (HashMap<String,Object>) HashMap.class.cast(request.attributes()).clone();
                m.putAll(copy);
            } else {
                // Propagate the original attribute to WebSocket message.
                m.putAll(request.attributes());
            }
        } catch (ConcurrentModificationException ex) {
            // There is a small risk that m.putAll(request.attributes()) throw this exception, event if we used a hint.
            // Changing the original Atmosphere's request attributes is not something recommended, but an application
            // can always do it. In that case, just clone the array.
            // We could have synchronized here
            logger.trace("", ex);
            Map<String, Object> copy = (HashMap<String,Object>) HashMap.class.cast(request.attributes()).clone();
            m.putAll(copy);
        }
        return m;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AtmosphereRequest> onMessage(WebSocket webSocket, byte[] d, final int offset, final int length) {

        //Converting to a string and delegating to onMessage(WebSocket webSocket, String d) causes issues because the binary data may not be a valid string.
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();
        if (resource == null) {
            logger.trace("The WebSocket has been closed before the message was processed.");
            return null;
        }
        AtmosphereRequest request = resource.getRequest();
        String pathInfo = request.getPathInfo();
        String requestURI = request.getRequestURI();

        Map<String,Object> m = attributes(request);
        List<AtmosphereRequest> list = new ArrayList<AtmosphereRequest>();

        // We need to create a new AtmosphereRequest as WebSocket message may arrive concurrently on the same connection.
        list.add(new AtmosphereRequest.Builder()
                .request(request)
                .method(methodType)
                .contentType(contentType)
                .body(d, offset, length)
                .attributes(m)
                .pathInfo(pathInfo)
                .requestURI(requestURI)
                .destroyable(destroyable)
                .headers(request.headersMap())
                .session(resource.session())
                .build());

        return list;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onOpen(WebSocket webSocket) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(WebSocket webSocket) {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(WebSocket webSocket, WebSocketProcessor.WebSocketException t) {
        logger.warn(t.getMessage() + " Status {} Message {}", t.response().getStatus(), t.response().getStatusMessage());
    }
}

