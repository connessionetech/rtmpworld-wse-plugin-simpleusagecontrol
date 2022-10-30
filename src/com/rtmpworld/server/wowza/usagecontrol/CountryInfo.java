package com.rtmpworld.server.wowza.usagecontrol;

public class CountryInfo {
	
	private String country = null;
	private String countryCode = null;	
	
	
	public CountryInfo() {
		
	}
	
	public CountryInfo(String country, String countryCode) {
		this.country = country;
		this.countryCode = countryCode;
	}
	
	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public void setCountryCode(String countryCode) {
		this.countryCode = countryCode;
	}

	@Override
	public String toString() {
		return "CountryInfo [country=" + country + ", countryCode=" + countryCode + "]";
	}

}
