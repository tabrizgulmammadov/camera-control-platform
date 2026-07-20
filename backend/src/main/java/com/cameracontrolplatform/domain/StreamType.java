package com.cameracontrolplatform.domain;

public enum StreamType {
    MAIN,
    SUB,
    OTHER;

    /**
     * Contract heuristic: name contains "main" or first profile -> MAIN;
     * name contains "sub" or second profile -> SUB; else OTHER.
     */
    public static StreamType classify(String profileName, int index) {
        String n = profileName == null ? "" : profileName.toLowerCase();
        if (n.contains("main")) {
            return MAIN;
        }
        if (n.contains("sub")) {
            return SUB;
        }
        if (index == 0) {
            return MAIN;
        }
        if (index == 1) {
            return SUB;
        }
        return OTHER;
    }
}
