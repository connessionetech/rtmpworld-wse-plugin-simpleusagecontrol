package com.rtmpworld.server.wowza.usagecontrol;

public class UsageRestrictions {
	
	public boolean enableRestrictions = false;
	
	public long maxBandwidthIn = 0;
	
	public long maxBandwidthOut = 0;
	
	public Ingest ingest;
	
	public Egress egress;

}
