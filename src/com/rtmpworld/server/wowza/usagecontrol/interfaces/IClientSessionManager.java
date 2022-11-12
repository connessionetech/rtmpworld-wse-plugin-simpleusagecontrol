package com.rtmpworld.server.wowza.usagecontrol.interfaces;

import com.rtmpworld.server.wowza.decorators.StreamingSessionTarget;

public interface IClientSessionManager {
	
	public void addSession(StreamingSessionTarget target);	
	public boolean hasSession(StreamingSessionTarget target);
	public void clearSessions();

}
