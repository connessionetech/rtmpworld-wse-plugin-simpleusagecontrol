package com.rtmpworld.server.wowza.usagecontrol.dataprovider;


import com.maxmind.geoip2.WebServiceClient;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import com.rtmpworld.server.wowza.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;

public class MaxmindWebServiceGeoInfoProvider implements IGeoInfoProvider {
	
	WebServiceClient client;
	String licenseKey;
	
	public MaxmindWebServiceGeoInfoProvider()
	{
		
	}
	
	public MaxmindWebServiceGeoInfoProvider(String licenseKey)
	{
		this.licenseKey = licenseKey;
	}
	
	
	public void initialize() throws IOException 
	{
		client = new WebServiceClient.Builder(42, "license_key")
			    .build();

	}
	
	
	
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
			throw new GeoInfoException(e);
		}

	}



	public String getLicenseKey() {
		return licenseKey;
	}



	public void setLicenseKey(String licenseKey) {
		this.licenseKey = licenseKey;
	}


	

}
