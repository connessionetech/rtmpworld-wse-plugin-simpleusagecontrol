package com.rtmpworld.server.wowza.usagecontrol;

import java.util.ArrayList;
import java.util.List;

public class Egress {

	public int maxSubscribers = 0;
	public int maxSubscribersPerStream = 0;
    public int maxPlaybackTime = 0;
    public List<String> allowedSubscriberTypes; 
    public List<String> allowedFrom;
    public List<String> restrictFrom;
}
