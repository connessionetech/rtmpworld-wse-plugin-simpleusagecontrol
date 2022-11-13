package com.rtmpworld.server.wowza.usagecontrol.dataprovider;


import com.maxmind.geoip2.WebServiceClient;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import com.rtmpworld.server.wowza.ModuleSimpleUsageControl;
import com.rtmpworld.server.wowza.usagecontrol.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;

import java.io.IOException;
import java.net.InetAddress;

public class MaxmindWebServiceGeoInfoProvider implements IGeoInfoProvider {
	
	WMSLogger logger;
	
	WebServiceClient client;
	String licenseKey;
	int accountId;
	
	public MaxmindWebServiceGeoInfoProvider()
	{
		
	}
	
	public MaxmindWebServiceGeoInfoProvider(int accountId, String licenseKey)
	{
		this.accountId = accountId;
		this.licenseKey = licenseKey;
	}
	
	
	@Override
	public void initialize() throws IOException 
	{
		client = new WebServiceClient.Builder(this.accountId, this.licenseKey)
			    .build();

	}
	
	
	
	@Override
	public CountryInfo getCountryInfo(String ip) throws GeoInfoException
	{
		InetAddress ipAddress;
		
		try 
		{
			ipAddress = InetAddress.getByName(ip);
			CountryResponse response = client.country(ipAddress);
			Country country = response.getCountry();
			return new CountryInfo(country.getName(), country.getIsoCode().toUpperCase());
		} 
		catch (IOException | GeoIp2Exception e) 
		{
			WMSLoggerFactory.getLogger(MaxmindWebServiceGeoInfoProvider.class).error(ModuleSimpleUsageControl.MODULE_NAME + ".isResourceActionAllowed => " + e.getMessage());
			throw new GeoInfoException(e);
		}

	}



	public String getLicenseKey() {
		return licenseKey;
	}



	public void setLicenseKey(String licenseKey) {
		this.licenseKey = licenseKey;
	}

	@Override
	public void setLogger(WMSLogger logger) {
		this.logger = logger;
	}

	@Override
	public WMSLogger getLogger() {
		return logger;
	}

}
