package com.filewatcherservice;

import com.filewatcherservice.service.ServiceMain;

/**
 * Entry point for filewatcher-service.jar.
 * No UI, no --service flag needed — this JAR is always the service.
 */
public class Main {
    public static void main(String[] args) {
        ServiceMain.start();
    }
}