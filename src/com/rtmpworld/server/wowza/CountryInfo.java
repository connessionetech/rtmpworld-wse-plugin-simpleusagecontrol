package com.rtmpworld.server.wowza;

public class CountryInfo {
	
	public String country = null;
	public String countryCode = null;
	public String country_code = null;
	
	
	public CountryInfo() {
		
	}
	
	public CountryInfo(String country, String countryCode) {
		this.country = country;
		this.countryCode = countryCode;
	}
	
	
	@Override
	public String toString() {
		return "CountryInfo [country=" + country + ", countryCode=" + countryCode + ", country_code=" + country_code
				+ "]";
	}
	
	
	

}
