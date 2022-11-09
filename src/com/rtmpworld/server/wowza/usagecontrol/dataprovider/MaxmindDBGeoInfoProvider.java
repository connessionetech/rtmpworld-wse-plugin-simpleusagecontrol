package com.rtmpworld.server.wowza.usagecontrol.dataprovider;


import com.maxmind.db.CHMCache;
import com.maxmind.db.DatabaseRecord;
import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;
import com.rtmpworld.server.wowza.ModuleSimpleUsageControl;
import com.rtmpworld.server.wowza.usagecontrol.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;
import com.wowza.wms.logging.WMSLogger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

public class MaxmindDBGeoInfoProvider implements IGeoInfoProvider {
	
	private WMSLogger logger;
	
	private String dbPath;
	private Reader reader;
	
	
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
		reader = new Reader(database, new CHMCache());

	}
	
	
	
	@Override
	public CountryInfo getCountryInfo(String ip) throws GeoInfoException
	{
		InetAddress ipAddress;
		
		try 
		{				
			ipAddress = InetAddress.getByName(ip);			
            DatabaseRecord<LookupResult> record = reader.getRecord(ipAddress, LookupResult.class);			
			return new CountryInfo("NA",record.getData().getCountry().getIsoCode().toUpperCase());
		} 
		catch (IOException  e) 
		{
			logger.error(ModuleSimpleUsageControl.MODULE_NAME + ".isResourceActionAllowed => " + e.getMessage());
			throw new GeoInfoException(e);
		}

	}


	public String getDbPath() {
		return dbPath;
	}


	public void setDbPath(String dbPath) {
		this.dbPath = dbPath;
	}

	@Override
	public void setLogger(WMSLogger logger) {
		this.logger = logger;
	}

	@Override
	public WMSLogger getLogger() {
		return logger;
	}
	
	
	public static class LookupResult {
        private final Country country;

        @MaxMindDbConstructor
        public LookupResult (
            @MaxMindDbParameter(name="country") Country country
        ) {
            this.country = country;
        }

        public Country getCountry() {
            return this.country;
        }
    }

    public static class Country {
        private final String isoCode;

        @MaxMindDbConstructor
        public Country (
            @MaxMindDbParameter(name="iso_code") String isoCode
        ) {
            this.isoCode = isoCode;
        }

        public String getIsoCode() {
            return this.isoCode;
        }
    }
}
