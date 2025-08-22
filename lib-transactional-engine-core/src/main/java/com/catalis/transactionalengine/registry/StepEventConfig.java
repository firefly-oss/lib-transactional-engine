package com.catalis.transactionalengine.registry;

/**
 * Immutable config extracted from @StepEvent annotation for a step.
 */
public class StepEventConfig {
    public final String topic;
    public final String type;
    public final String key;
    public final boolean enabled;

    public StepEventConfig(String topic, String type, String key, boolean enabled) {
        this.topic = topic;
        this.type = type;
        this.key = key;
        this.enabled = enabled;
    }
}
