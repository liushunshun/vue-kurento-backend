package com.osman.videoer.entity;

import java.util.ArrayList;
import java.util.List;

import org.kurento.client.IceCandidate;
import org.kurento.client.WebRtcEndpoint;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.alibaba.fastjson2.JSONObject;

import lombok.Data;

@Data
public class UserSession {

    private final String name;
    private final WebSocketSession session;

    private String sdpOffer;
    private String callingTo;
    private String callingFrom;

    private WebRtcEndpoint webRtcEndpoint;
    private final List<IceCandidate> candidateList = new ArrayList<IceCandidate>();

    public UserSession(String name, WebSocketSession session) {
        this.name = name;
        this.session = session;
    }

    public void sendMessage(JSONObject message) throws Exception {
        session.sendMessage(new TextMessage(message.toString()));
    }

    public void addCandidate(IceCandidate candidate) {
        if (this.webRtcEndpoint != null) {
            this.webRtcEndpoint.addIceCandidate(candidate);
        } else {
            candidateList.add(candidate);
        }
    }

    public String getSessionId() {
        return session.getId();
    }
}
