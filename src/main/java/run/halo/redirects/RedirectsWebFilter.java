package run.halo.redirects;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;
import run.halo.redirects.manager.RedirectRuleRegistry;

@Component
public class RedirectsWebFilter implements AdditionalWebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var requestPath = request.getURI().getPath();

        if (isRedirectableMethod(request.getMethod()) && !shouldSkip(requestPath)) {
            var redirect = RedirectRuleRegistry.resolve(requestPath, request.getURI().getRawQuery());
            if (redirect.isPresent()) {
                var match = redirect.get();
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatusCode.valueOf(match.statusCode()));
                if (match.location() != null) {
                    response.getHeaders().set(HttpHeaders.LOCATION, match.location());
                }
                response.getHeaders().set(HttpHeaders.CACHE_CONTROL,
                    "no-cache, no-store, must-revalidate");
                return response.setComplete();
            }
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    /**
     * Only page views are redirected; redirecting a form or API submission would silently drop
     * its body (or turn it into a GET for 301/302).
     */
    private boolean isRedirectableMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
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
