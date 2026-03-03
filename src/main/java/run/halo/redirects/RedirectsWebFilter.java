package run.halo.redirects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;
import run.halo.redirects.listener.RedirectSettingsUpdatedEvent;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectsWebFilter implements AdditionalWebFilter {
    private final ApplicationEventPublisher eventPublisher;

    public RedirectsWebFilter(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var requestPath = request.getURI().getPath();

        if (!shouldSkip(requestPath)) {
            var redirect = RedirectRuleRegistry.resolve(requestPath, request.getURI().getRawQuery());
            if (redirect.isPresent()) {
                var match = redirect.get();
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatusCode.valueOf(match.statusCode()));
                response.getHeaders().set("Location", match.location());
                response.getHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                return response.setComplete();
            }
        }

        return chain.filter(exchange).doOnSuccess(unused -> {
            if (RedirectRuleRegistry.isSettingsMutation(request)) {
                eventPublisher.publishEvent(RedirectSettingsUpdatedEvent.trigger(this));
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    private boolean shouldSkip(String path) {
        return path == null
            || isReservedPath(path, "/api")
            || isReservedPath(path, "/apis")
            || isReservedPath(path, "/console")
            || isReservedPath(path, "/actuator");
    }

    private boolean isReservedPath(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
