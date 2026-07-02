package com.filewatcher.service;

/** Commands the UI can send to the backend for a given job (toolbar buttons in Service Management, spec §8). */
public enum JobCommand {
    START, STOP, RESTART, DELETE, TEST_CONNECTION
}
