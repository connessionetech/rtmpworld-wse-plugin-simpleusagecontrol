package com.rtmpworld.server.wowza.usagecontrol.restrictions;

import java.util.List;

public class Ingest {
	
	public int maxPublishBitrate = 0;
	public int maxPublishersCount = 0;
	public double maxPublishTime = 0;
	public List<String> allowedFromGeo;
	public List<String> restrictFromGeo;
	
	
	@Override
	public String toString() {
		return "Ingest [maxPublishBitrate=" + maxPublishBitrate + ", maxPublishersCount=" + maxPublishersCount
				+ ", maxPublishTime=" + maxPublishTime + ", allowedFromGeo=" + allowedFromGeo + ", restrictFromGeo="
				+ restrictFromGeo + "]";
	}
	
	
	
	
}
