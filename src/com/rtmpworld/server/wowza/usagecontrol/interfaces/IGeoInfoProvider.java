package com.rtmpworld.server.wowza.usagecontrol.interfaces;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.rtmpworld.server.wowza.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;

public interface IGeoInfoProvider {
	
	void initialize() throws IOException;
	
	CountryInfo getCountryInfo(String ip) throws GeoInfoException;
}
