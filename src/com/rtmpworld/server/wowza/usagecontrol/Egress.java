package com.rtmpworld.server.wowza.usagecontrol;

import java.util.List;

public class Egress {

	public int maxSubscribers = 0;
	public int maxSubscribersPerStream = 0;
    public double maxPlaybackTime = 0;
    public List<String> allowedSubscriberTypes; 
    public List<String> allowedFromGeo;
    public List<String> restrictFromGeo;
    
    
	@Override
	public String toString() {
		return "Egress [maxSubscribers=" + maxSubscribers + ", maxSubscribersPerStream=" + maxSubscribersPerStream
				+ ", maxPlaybackTime=" + maxPlaybackTime + ", allowedSubscriberTypes=" + allowedSubscriberTypes
				+ ", allowedFromGeo=" + allowedFromGeo + ", restrictFromGeo=" + restrictFromGeo + "]";
	}
    
    
}
