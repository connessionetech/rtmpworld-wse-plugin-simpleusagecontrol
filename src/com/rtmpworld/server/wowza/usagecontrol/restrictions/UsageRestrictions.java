package com.rtmpworld.server.wowza.usagecontrol.restrictions;

public class UsageRestrictions {
	
	public boolean enableRestrictions = false;
	
	public long maxMegaBytesIn = 0;
	
	public long maxMegaBytesOut = 0;
	
	public Ingest ingest;
	
	public Egress egress;

	@Override
	public String toString() {
		return "UsageRestrictions [enableRestrictions=" + enableRestrictions + ", maxBandwidthIn=" + maxMegaBytesIn
				+ ", maxBandwidthOut=" + maxMegaBytesOut + ", ingest=" + ingest + ", egress=" + egress + "]";
	}

	
	
}
