package org.banbridge.api;

public record CommandAckRequest(
        String serverKey,
        long id
) {}