package com.rtmpworld.server.wowza.usagecontrol.dataprovider;


import com.google.gson.Gson;
import com.rtmpworld.server.wowza.CountryInfo;
import com.rtmpworld.server.wowza.ModuleSimpleUsageControl;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;
import com.wowza.wms.logging.WMSLoggerFactory;

import java.io.IOException;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class IPWhoIsWebServiceGeoInfoProvider implements IGeoInfoProvider {
	
	String licenseKey;
	private static String ENDPOINT = "http://ipwho.is/%s";
	
	
	
	public IPWhoIsWebServiceGeoInfoProvider()
	{
		
	}
	
	public IPWhoIsWebServiceGeoInfoProvider(String licenseKey)
	{
		this.licenseKey = licenseKey;
	}
	
	
	@Override
	public void initialize() throws IOException 
	{
		

	}
	
	
	
	@Override
	public CountryInfo getCountryInfo(String ip) throws GeoInfoException
	{
		CountryInfo geoinfo = null;
		
		try
		{
			CloseableHttpClient httpClient = HttpClients.createDefault();
			String url = String.format(ENDPOINT, ip);
		    HttpGet httpPost = new HttpGet(url);
		    
		    CloseableHttpResponse response = httpClient.execute(httpPost);
		    HttpEntity entity = response.getEntity();
            if (entity != null) {
            	Gson gson = new Gson();
                String result = EntityUtils.toString(entity);
                
                WMSLoggerFactory.getLogger(IPWhoIsWebServiceGeoInfoProvider.class).debug(ModuleSimpleUsageControl.MODULE_NAME + ".isResourceActionAllowed => GeoResponse : " + result);
                
                WhoIsCountryInfo georesponse = gson.fromJson(result, WhoIsCountryInfo.class);
                geoinfo = new CountryInfo(georesponse.country, georesponse.country_code);
            }	            
            httpClient.close();
		}
		catch(Exception e)
		{
			WMSLoggerFactory.getLogger(ModuleSimpleUsageControl.class).error(ModuleSimpleUsageControl.MODULE_NAME + ".isResourceActionAllowed => " + e.getMessage());
		}
		
		return geoinfo;

	}



	public String getLicenseKey() {
		return licenseKey;
	}



	public void setLicenseKey(String licenseKey) {
		this.licenseKey = licenseKey;
	}



	class WhoIsCountryInfo{
		
		private String country;
		private String country_code;
		
		public WhoIsCountryInfo()
		{
			
		}
		
		public WhoIsCountryInfo(String country, String country_code)
		{
			this.country = country;
			this.country_code = country_code;
			
		}	
		
	}

}
