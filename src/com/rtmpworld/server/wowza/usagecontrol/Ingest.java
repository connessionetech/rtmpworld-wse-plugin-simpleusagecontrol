package com.rtmpworld.server.wowza.usagecontrol;

import java.util.ArrayList;
import java.util.List;

public class Ingest {
	
	public int maxPublishBitrate = 0;
	public int maxPublishersCount = 0;
	public int maxPublishTime = 0;
	public List<String> allowedPublisherTypes;
	public List<String> allowedFromGeo;
	public List<String> restrictFromGeo;
}
