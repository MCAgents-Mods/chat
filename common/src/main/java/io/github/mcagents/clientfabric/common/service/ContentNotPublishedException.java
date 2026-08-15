package io.github.mcagents.clientfabric.common.service;

/**
 * The service answered, and does not publish what was asked for.
 *
 * <p>Separate from its parent because the two mean opposite things to a caller. A
 * {@link CentralServiceException} says the service could not tell us — asleep, rate
 * limiting, failing — and the right response is to try later. This one says the service
 * answered clearly: there is no such pack, and trying again will produce the same answer.
 *
 * <p>The console needs the distinction to choose a status code. "No such Knowledge Pack"
 * for a pack that exists on a service which is merely asleep would be a lie, and a player
 * who saw it would stop looking for something that is there.
 */
public class ContentNotPublishedException extends CentralServiceException {

    public ContentNotPublishedException(String message) {
        super(message);
    }
}
