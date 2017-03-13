package com.spread.model;

public class ContinuousTreeModelDTO {

	private String treeFilename = null;
	private String xCoordinate = null; // long
	private String yCoordinate = null; // lat
	private Double hpdLevel = null;
	private String mrsd = "0/0/0";
	private double timescaleMultiplier = 1.0;
	private String geojsonFilename = null;
	private boolean hasExternalAnnotations = false;
	private String outputFilename = "output.json";
	
	public ContinuousTreeModelDTO() {
	}

	public ContinuousTreeModelDTO(String treeFilename, String xCoordinate, String yCoordinate, Double hpdLevel,
			String mrsd, double timescaleMultiplier, String outputFilename, String geojsonFilename,
			boolean hasExternalAnnotations) {
		this.treeFilename = treeFilename;
		this.xCoordinate = xCoordinate;
		this.yCoordinate = yCoordinate;
		this.hpdLevel = hpdLevel;
		this.mrsd = mrsd;
		this.timescaleMultiplier = timescaleMultiplier;
		this.outputFilename = outputFilename;
		this.geojsonFilename = geojsonFilename;
		this.hasExternalAnnotations = hasExternalAnnotations;
	}

	public String getxCoordinate() {
		return xCoordinate;
	}

	public void setxCoordinate(String xCoordinate) {
		this.xCoordinate = xCoordinate;
	}

	public String getyCoordinate() {
		return yCoordinate;
	}

	public void setyCoordinate(String yCoordinate) {
		this.yCoordinate = yCoordinate;
	}

	public Double getHpdLevel() {
		return hpdLevel;
	}

	public void setHpdLevel(Double hpdLevel) {
		this.hpdLevel = hpdLevel;
	}

	public String getMrsd() {
		return mrsd;
	}

	public void setMrsd(String mrsd) {
		this.mrsd = mrsd;
	}

	public double getTimescaleMultiplier() {
		return timescaleMultiplier;
	}

	public void setTimescaleMultiplier(double timescaleMultiplier) {
		this.timescaleMultiplier = timescaleMultiplier;
	}

	public String getOutputFilename() {
		return outputFilename;
	}

	public void setOutputFilename(String outputFilename) {
		this.outputFilename = outputFilename;
	}

	public String getGeojsonFilename() {
		return geojsonFilename;
	}

	public void setGeojsonFilename(String geojsonFilename) {
		this.geojsonFilename = geojsonFilename;
	}

	public boolean hasExternalAnnotations() {
		return hasExternalAnnotations;
	}

	public void setHasExternalAnnotations(boolean hasExternalAnnotations) {
		this.hasExternalAnnotations = hasExternalAnnotations;
	}

	public String getTreeFilename() {
		return treeFilename;
	}

	public void setTreeFilename(String treeFilename) {
		this.treeFilename = treeFilename;
	}

}
