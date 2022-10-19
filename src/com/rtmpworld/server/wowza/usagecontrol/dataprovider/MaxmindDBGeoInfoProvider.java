package com.rtmpworld.server.wowza.usagecontrol.dataprovider;


import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Country;
import com.rtmpworld.server.wowza.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

public class MaxmindDBGeoInfoProvider implements IGeoInfoProvider {
	
	private String dbPath;
	private DatabaseReader reader;
	
	
	public MaxmindDBGeoInfoProvider()
	{
		
	}
	
	public MaxmindDBGeoInfoProvider(String dbPath)
	{
		this.dbPath = dbPath;
	}
	
	
	@Override
	public void initialize() throws IOException 
	{
		File database = new File(dbPath);
		reader = new DatabaseReader.Builder(database).build();
	}
	
	
	
	@Override
	public CountryInfo getCountryInfo(String ip) throws GeoInfoException
	{
		InetAddress ipAddress;
		
		try 
		{
			ipAddress = InetAddress.getByName(ip);
			CityResponse response = reader.city(ipAddress);
			Country country = response.getCountry();
			return new CountryInfo(country.getName(), country.getIsoCode().toUpperCase());
		} 
		catch (IOException | GeoIp2Exception e) 
		{
			throw new GeoInfoException(e);
		}

	}


	public String getDbPath() {
		return dbPath;
	}


	public void setDbPath(String dbPath) {
		this.dbPath = dbPath;
	}

}
