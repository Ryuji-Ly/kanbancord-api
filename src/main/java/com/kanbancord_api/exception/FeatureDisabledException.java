package com.kanbancord_api.exception;

/** The request uses a feature the server has switched off. */
public class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException(String featureLabel) {
        super(featureLabel + " are turned off on this server.");
    }
}
