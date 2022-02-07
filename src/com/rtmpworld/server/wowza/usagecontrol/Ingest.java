package com.rtmpworld.server.wowza.usagecontrol;

import java.util.ArrayList;

public class Ingest {
	
	public int maxPublishBitrate = 0;
	public int maxPublishersCount = 0;
	public int maxPublishTime = 0;
	public ArrayList<String> allowedPublisherTypes;
	public ArrayList<String> allowedFromGeo;
	public ArrayList<String> restrictFromGeo;
}
