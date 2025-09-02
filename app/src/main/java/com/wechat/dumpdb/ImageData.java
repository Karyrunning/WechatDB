package com.wechat.dumpdb;

public class ImageData {
    private String base64Data;
    private String format;

    public ImageData(String base64Data, String format) {
        this.base64Data = base64Data;
        this.format = format;
    }

    public String getBase64Data() { return base64Data; }
    public String getFormat() { return format; }
}
