package com.wechat.dumpdb;

public class AudioResult {
    public final String mp3Url;
    public final Long duration;

    public AudioResult(String mp3Url, Long duration) {
        this.mp3Url = mp3Url;
        this.duration = duration;
    }
}
