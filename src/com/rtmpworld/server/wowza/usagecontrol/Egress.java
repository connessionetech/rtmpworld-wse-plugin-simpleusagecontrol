package com.rtmpworld.server.wowza.usagecontrol;

import java.util.ArrayList;

public class Egress {

	public int maxSubscribers = 0;
	public int maxSubscribersPerStream = 0;
    public int maxPlaybackTime = 0;
    public ArrayList<String> allowedSubscriberTypes; 
    public ArrayList<String> allowedFrom;
    public ArrayList<String> restrictFrom;
}
