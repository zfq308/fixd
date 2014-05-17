/*
 * Copyright (C) 2013 BigTesting.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bigtesting.fixd.core;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import org.bigtesting.fixd.Method;
import org.bigtesting.fixd.capture.CapturedRequest;
import org.bigtesting.fixd.capture.impl.SimpleCapturedRequest;
import org.bigtesting.fixd.core.async.AsyncHandler;
import org.bigtesting.fixd.request.impl.SimpleHttpRequest;
import org.bigtesting.fixd.routing.RegexRouteMap;
import org.bigtesting.fixd.routing.Route;
import org.bigtesting.fixd.routing.RouteMap;
import org.bigtesting.fixd.session.Session;
import org.bigtesting.fixd.session.SessionHandler;
import org.simpleframework.http.ContentType;
import org.simpleframework.http.Cookie;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author Luis Antunes
 */
public class FixtureContainer implements Container {
    
    private static final Logger logger = LoggerFactory.getLogger(FixtureContainer.class);
    
    private static final String SESSION_COOKIE_NAME = "Fixd-Session";

    private final Map<HandlerKey, RequestHandlerImpl> handlerMap = 
            new ConcurrentHashMap<HandlerKey, RequestHandlerImpl>();
    
    private final Set<HandlerKey> uponHandlers = 
            Collections.newSetFromMap(new ConcurrentHashMap<HandlerKey, Boolean>());
    
    private final RouteMap routeMap = new RegexRouteMap();
    
    /*
     * a ConcurrentHashMap, as opposed to synchronized map, is chosen here because,
     * from the perspective of a client, an up-to-date view of the map is not 
     * a requirement; i.e. a given client is not interested in sessions created
     * for other clients 
     */
    private final Map<String, Session> sessions = 
            new ConcurrentHashMap<String, Session>();
    
    private final Queue<CapturedRequest> capturedRequests = 
            new ConcurrentLinkedQueue<CapturedRequest>();
    
    private final AsyncHandler asyncHandler;
    
    private int capturedRequestLimit = -1;
    
    public FixtureContainer() {
        asyncHandler = new AsyncHandler(Executors.newCachedThreadPool());
    }
    
    public FixtureContainer(int aysncThreadPoolSize) {
        asyncHandler = new AsyncHandler(Executors.newFixedThreadPool(aysncThreadPoolSize));
    }
    
    public HandlerKey addHandler(RequestHandlerImpl handler, 
            Method method, String resource) {
        
        return addHandler(handler, method, resource, null);
    }
    
    public HandlerKey addHandler(RequestHandlerImpl handler, 
            Method method, String resource, String contentType) {
        
        Route route = new Route(resource);
        HandlerKey key = new HandlerKey(method.name(), route, contentType);
        handlerMap.put(key, handler);
        routeMap.add(route);
        return key;
    }
    
    public void addUponHandler(Upon upon) {
       
        RequestHandlerImpl uponHandler = 
                (RequestHandlerImpl)new RequestHandlerImpl(this).with(200, "text/plain", "");
        HandlerKey uponKey = addHandler(uponHandler, upon.getMethod(), 
                upon.getResource(), upon.getContentType());
        uponHandlers.add(uponKey);
    }
    
    public Queue<CapturedRequest> getCapturedRequests() {
        
        return capturedRequests;
    }
    
    public CapturedRequest nextCapturedRequest() {
        
        return capturedRequests.poll();
    }
    
    public void setCapturedRequestLimit(int limit) {
        
        this.capturedRequestLimit = limit;
    }
    
    public void handle(Request request, Response response) {

        try {
            
            addCapturedRequest(request);
            
            String responseContentType = "text/plain";
            ResponseBody responseBody = new StringResponseBody("");
            
            ResolvedRequest resolved = resolve(request);
            if (resolved.errorStatus != null) {
                response.setStatus(resolved.errorStatus);
                sendAndCommitResponse(response, responseContentType, responseBody);
                return;
            }
            
            if (requestIsForUponHandler(resolved)) {
                
                asyncHandler.broadcastToSubscribers(request, resolved.route);
                /* continue handling the request, as it needs to 
                 * return a normal response */
            }
            
            /* create a new session if required */
            SessionHandler sessionHandler = resolved.handler.sessionHandler();
            if (sessionHandler != null) {
                createNewSession(request, response, resolved.route, sessionHandler);
            }
            
            /* set the response body */
            if (!resolved.handler.isSuspend()) {
                Session session = getSessionIfExists(request);
                ResponseBody handlerBody = resolved.handler.body(
                        new SimpleHttpRequest(request, session, resolved.route), response);
                if (handlerBody != null && handlerBody.hasContent()) {
                    responseBody = handlerBody;
                }
            }
            
            /* set the content type */
            String handlerContentType = resolved.handler.contentType();
            if (handlerContentType != null && 
                    handlerContentType.trim().length() != 0) {
                
                responseContentType = handlerContentType;
            }
            
            /* set the response status code */
            int handlerStatusCode = resolved.handler.statusCode();
            if (handlerStatusCode == -1) {
                throw new RuntimeException("a response status code must be specified");
            }
            response.setCode(resolved.handler.statusCode());
            
            /* set any headers */
            Set<SimpleImmutableEntry<String, String>> headers = 
                    resolved.handler.headers();
            for (SimpleImmutableEntry<String, String> header : headers) {
                response.addValue(header.getKey(), header.getValue());
            }
            
            /* handle the response */
            if (resolved.handler.isAsync()) {
                doAsync(response, resolved.handler, responseContentType, responseBody);
            } else {
                sendAndCommitResponse(response, responseContentType, responseBody);
            }
            
        } catch (Throwable e) {
            
            logger.error("internal server error", e);
            response.setStatus(Status.INTERNAL_SERVER_ERROR);
            sendAndCommitResponse(response, "text/plain", new StringResponseBody(""));
        }
    }

    /*----------------------------------------------------------*/
    
    private void addCapturedRequest(Request request) {
        
        capturedRequests.add(new SimpleCapturedRequest(request));
        
        if (capturedRequestLimit > -1) {
            while(capturedRequests.size() > capturedRequestLimit) capturedRequests.remove();
        }
    }
    
    private boolean requestIsForUponHandler(ResolvedRequest resolved) {
        
        return uponHandlers.contains(resolved.key);
    }
    
    private Session getSessionIfExists(Request request) {
        
        Cookie cookie = request.getCookie(SESSION_COOKIE_NAME);
        if (cookie != null) {
            String sessionId = cookie.getValue();
            Session session = sessions.get(sessionId);
            if (session != null && !session.isValid()) {
                sessions.remove(sessionId);
                return null;
            }
            return session;
        }
        return null;
    }
    
    private void createNewSession(Request request, Response response, 
            Route route, SessionHandler sessionHandler) {
        
        Session session = new Session();
        sessionHandler.onCreate(new SimpleHttpRequest(request, session, route));
        sessions.put(session.getSessionId(), session);
        
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, session.getSessionId());
        response.setCookie(cookie);
    }
    
    private void doAsync(Response response, RequestHandlerImpl handler, 
            String responseContentType, ResponseBody responseBody) {
        
        asyncHandler.doAsync(response, handler, responseContentType, responseBody);
    }
    
    private void sendAndCommitResponse(Response response, 
            String responseContentType, ResponseBody responseBody) {
        
        responseBody.sendAndCommit(response, responseContentType);
    }

    private ResolvedRequest resolve(Request request) {
        
        ResolvedRequest resolved = new ResolvedRequest();
        String method = request.getMethod();
        String path = request.getPath().getPath();
        ContentType requestContentType = request.getContentType();
        
        /* get the route and the handler for this request */
        Route route = routeMap.getRoute(path);
        if (route == null) {
            logger.error("could not find a route for " + path);
            resolved.errorStatus = Status.NOT_FOUND;
            return resolved;
        }
        String contentType = requestContentType != null ? 
                requestContentType.toString() : null;
        HandlerKey key = new HandlerKey(method, route, contentType);
        RequestHandlerImpl handler = handlerMap.get(key);
        if (handler == null) {
            logger.error("could not find a handler for " + 
                    method + " - " + path);
            resolved.errorStatus = Status.METHOD_NOT_ALLOWED;
            return resolved;
        }
        
        resolved.handler = handler;
        resolved.route = route;
        resolved.key = key;
        return resolved;
    }
    
    private class ResolvedRequest {
        
        public Route route;
        public RequestHandlerImpl handler;
        public HandlerKey key;
        public Status errorStatus;
    }
}
