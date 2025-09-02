package com.wechat.dumpdb;

public class EmojiInfo {
    private String catalog;
    private String name;
    private String cdnUrl;
    private String encryptUrl;
    private String aesKey;

    public EmojiInfo(String catalog, String name, String cdnUrl, String encryptUrl, String aesKey) {
        this.catalog = catalog;
        this.name = name;
        this.cdnUrl = cdnUrl;
        this.encryptUrl = encryptUrl;
        this.aesKey = aesKey;
    }

    // Getters
    public String getCatalog() {
        return catalog;
    }

    public String getName() {
        return name;
    }

    public String getCdnUrl() {
        return cdnUrl;
    }

    public String getEncryptUrl() {
        return encryptUrl;
    }

    public String getAesKey() {
        return aesKey;
    }
}
