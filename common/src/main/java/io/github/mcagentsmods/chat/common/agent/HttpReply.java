package io.github.mcagentsmods.chat.common.agent;

import java.util.Objects;

/**
 * One HTTP response.
 *
 * @param status HTTP status code
 * @param body   response body, which may be an error payload
 */
public record HttpReply(int status, String body) {

    public HttpReply {
        Objects.requireNonNull(body, "body");
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
