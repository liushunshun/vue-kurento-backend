package com.osman.videoer.websocket;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.osman.videoer.component.CallMediaPipeline;
import com.osman.videoer.component.UserRegistry;
import com.osman.videoer.entity.UserSession;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class VideoEventHandler extends TextWebSocketHandler {

    @Resource
    private UserRegistry userRegistry;

    @Resource
    private KurentoClient kurento;

    private final ConcurrentHashMap<String, CallMediaPipeline> pipelines = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JSONObject data = JSON.parseObject(message.getPayload());
            UserSession user = userRegistry.getBySession(session);
            if (user != null) {
                log.debug("server websocket receive from user '{}' : {}", user.getName(), message.getPayload());
            } else {
                log.debug("server websocket receive new user : {}", message.getPayload());
            }
            switch (data.getString("id")) {
                case "register":
                    register(session, data);
                    break;
                case "incomingCallResponse":
                    incomingCallResponse(user, data);
                    break;
                case "onIceCandidate": {
                    JSONObject candidate = data.getJSONObject("candidate");
                    if (user != null) {
                        IceCandidate cand = new IceCandidate(candidate.getString("candidate"),
                                candidate.getString("sdpMid"),
                                candidate.getIntValue("sdpMLineIndex"));
                        user.addCandidate(cand);
                    }
                    break;
                }
                case "call":
                    try {
                        call(user, data);
                    } catch (Throwable t) {
                        handleErrorResponse(t, session, "callResponse");
                    }
                    break;
                case "stop":
                    stop(session);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log.error("websocket event process exception data={}", message.getPayload(), e);
        }
    }

    public void stop(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        if (pipelines.containsKey(sessionId)) {
            pipelines.get(sessionId).release();
            CallMediaPipeline pipeline = pipelines.remove(sessionId);
            pipeline.release();

            UserSession stopperUser = userRegistry.getBySession(session);
            if (stopperUser != null) {
                UserSession stoppedUser = null;
                if (stopperUser.getCallingFrom() != null) {
                    stoppedUser = userRegistry.getByName(stopperUser.getCallingFrom());
                }
                if (stopperUser.getCallingTo() != null) {
                    stoppedUser = userRegistry.getByName(stopperUser.getCallingTo());
                }
                if (stoppedUser != null) {
                    JSONObject message = new JSONObject();
                    message.put("id", "stopCommunication");
                    stoppedUser.sendMessage(message);
                    stoppedUser.clear();
                }
                stopperUser.clear();
            }
        }
    }

    private void incomingCallResponse(UserSession callee, JSONObject data) throws Exception {
        String callResponse = data.getString("callResponse");
        String from = data.getString("from");
        final UserSession caller = userRegistry.getByName(from);
        String to = caller.getCallingTo();
        if ("accept".equals(callResponse)) {
            log.info("Accept call from {} to {}", from, to);
            CallMediaPipeline pipeline = null;
            try {
                pipeline = new CallMediaPipeline(kurento);
                pipelines.put(caller.getSessionId(), pipeline);
                pipelines.put(callee.getSessionId(), pipeline);

                callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());
                pipeline.getCalleeWebRtcEp().addIceCandidateFoundListener(new EventListener<IceCandidateFoundEvent>() {
                    @Override
                    public void onEvent(IceCandidateFoundEvent event) {
                        JSONObject response = new JSONObject();
                        response.put("id", "iceCandidate");
                        response.put("candidate", JSON.parseObject(JSON.toJSONString(event.getCandidate())));
                        try {
                            synchronized (callee.getSession()) {
                                callee.getSession().sendMessage(new TextMessage(response.toString()));
                            }
                        } catch (IOException e) {
                            log.error(e.getMessage());
                        }
                    }
                });
                caller.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());
                pipeline.getCallerWebRtcEp().addIceCandidateFoundListener(
                        new EventListener<IceCandidateFoundEvent>() {

                            @Override
                            public void onEvent(IceCandidateFoundEvent event) {
                                JSONObject response = new JSONObject();
                                response.put("id", "iceCandidate");
                                response.put("candidate", JSON.parseObject(JSON.toJSONString(event.getCandidate())));
                                try {
                                    synchronized (caller.getSession()) {
                                        caller.getSession().sendMessage(new TextMessage(response.toString()));
                                    }
                                } catch (IOException e) {
                                    log.debug(e.getMessage());
                                }
                            }
                        });
                String calleeSdpOffer = data.getString("sdpOffer");
                String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
                JSONObject startCommunication = new JSONObject();
                startCommunication.put("id", "startCommunication");
                startCommunication.put("sdpAnswer", calleeSdpAnswer);
                synchronized (callee) {
                    callee.sendMessage(startCommunication);
                }

                pipeline.getCalleeWebRtcEp().gatherCandidates();

                String callerSdpOffer = userRegistry.getByName(from).getSdpOffer();
                String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
                JSONObject response = new JSONObject();
                response.put("id", "callResponse");
                response.put("response", "accepted");
                response.put("sdpAnswer", callerSdpAnswer);
                synchronized (caller) {
                    caller.sendMessage(response);
                }

                pipeline.getCallerWebRtcEp().gatherCandidates();
            } catch (Throwable t) {
                log.error(t.getMessage(), t);

                if (pipeline != null) {
                    pipeline.release();
                }

                pipelines.remove(caller.getSessionId());
                pipelines.remove(callee.getSessionId());

                JSONObject response = new JSONObject();
                response.put("id", "callResponse");
                response.put("response", "rejected");
                caller.sendMessage(response);

                response = new JSONObject();
                response.put("id", "stopCommunication");
                callee.sendMessage(response);
            }
        } else {
            JSONObject response = new JSONObject();
            response.put("id", "callResponse");
            response.put("response", "rejected");
            caller.sendMessage(response);
        }

    }

    private void call(UserSession caller, JSONObject jsonMessage) throws Exception {
        String to = jsonMessage.getString("to");
        String from = jsonMessage.getString("from");
        JSONObject response = new JSONObject();

        if (userRegistry.exists(to)) {
            caller.setSdpOffer(jsonMessage.getString("sdpOffer"));
            caller.setCallingTo(to);

            response.put("id", "incomingCall");
            response.put("from", from);

            UserSession callee = userRegistry.getByName(to);
            callee.sendMessage(response);
            callee.setCallingFrom(from);
        } else {
            response.put("id", "callResponse");
            response.put("response", "rejected: user '" + to + "' is not registered");

            caller.sendMessage(response);
        }
    }

    private void handleErrorResponse(Throwable throwable, WebSocketSession session, String responseId)
            throws IOException {
        // stop(session);
        log.error(throwable.getMessage(), throwable);
        JSONObject response = new JSONObject();
        response.put("id", responseId);
        response.put("response", "rejected");
        response.put("message", throwable.getMessage());
        session.sendMessage(new TextMessage(response.toString()));
    }

    private void register(WebSocketSession session, JSONObject data) throws Exception {
        String name = data.getString("name");
        UserSession userSession = new UserSession(name, session);
        String responseMsg = "accepted";
        if (StringUtils.isBlank(name)) {
            responseMsg = "rejected: empty user name";
        } else if (userRegistry.exists(name)) {
            responseMsg = "rejected: user '" + name + "' already registered";
        } else {
            userRegistry.register(userSession);
        }

        JSONObject response = new JSONObject();
        response.put("id", "registerResponse");
        response.put("response", responseMsg);
        userSession.sendMessage(response);
    }

    public void sendMessage(WebSocketSession session, String message) throws Exception {
        session.sendMessage(new TextMessage(message));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        stop(session);
        userRegistry.removeBySession(session);
    }
}
