package com.rtmpworld.server.wowza.usagecontrol.interfaces;

import java.io.IOException;

import com.rtmpworld.server.wowza.usagecontrol.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.wowza.wms.logging.WMSLogger;

public interface IGeoInfoProvider {
	
	void initialize() throws IOException;
	
	void setLogger(WMSLogger logger); 
	
	WMSLogger getLogger(); 
	
	CountryInfo getCountryInfo(String ip) throws GeoInfoException;
}
