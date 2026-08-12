package com.healthplatform.gateway.web;

import org.springframework.http.HttpHeaders;

/** Shared by every data fetcher: attaches the forwarded Authorization header, if present. */
final class DownstreamAuth {

    private DownstreamAuth() {}

    static void addTo(HttpHeaders headers, String authorization) {
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
    }
}
