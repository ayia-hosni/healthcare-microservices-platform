package com.healthplatform.gateway.config;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Copies the caller's Authorization header into the GraphQL execution context so data
 * fetchers can forward it to downstream REST calls unchanged. This gateway never verifies or
 * reissues tokens itself — each downstream service still runs its own JWT verification
 * exactly as it does for a direct caller (see ADR-004: shared verification, not shared logic).
 */
@Component
public class AuthHeaderInterceptor implements WebGraphQlInterceptor {

    public static final String AUTHORIZATION_CONTEXT_KEY = "authorization";

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            request.configureExecutionInput((executionInput, builder) ->
                    builder.graphQLContext(context -> context.put(AUTHORIZATION_CONTEXT_KEY, authorization)).build());
        }
        return chain.next(request);
    }
}
