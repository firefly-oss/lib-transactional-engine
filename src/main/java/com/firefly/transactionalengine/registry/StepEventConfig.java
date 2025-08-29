package com.firefly.transactionalengine.registry;

/**
 * Immutable configuration extracted from @StepEvent annotation.
 */
public class StepEventConfig {
    
    public final String topic;
    public final String type;
    public final String key;
    
    public StepEventConfig(String topic, String type, String key) {
        this.topic = topic;
        this.type = type;
        this.key = key;
    }

}
