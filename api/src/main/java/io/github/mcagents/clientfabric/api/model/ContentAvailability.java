package io.github.mcagents.clientfabric.api.model;

/** The free/premium filter offered by the content browser. */
public enum ContentAvailability {

    /** No filter. */
    ANY,

    /** Only packs that were free. */
    FREE,

    /** Only packs that were paid for. */
    PREMIUM
}
