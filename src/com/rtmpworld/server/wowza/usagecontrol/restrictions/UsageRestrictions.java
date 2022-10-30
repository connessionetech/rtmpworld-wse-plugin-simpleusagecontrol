package com.rtmpworld.server.wowza.usagecontrol.restrictions;

public class UsageRestrictions {
	
	public boolean enableRestrictions = false;
	
	public long maxBytesIn = 0;
	
	public long maxBytesOut = 0;
	
	public Ingest ingest;
	
	public Egress egress;

	@Override
	public String toString() {
		return "UsageRestrictions [enableRestrictions=" + enableRestrictions + ", maxBandwidthIn=" + maxBytesIn
				+ ", maxBandwidthOut=" + maxBytesOut + ", ingest=" + ingest + ", egress=" + egress + "]";
	}

	
	
}
