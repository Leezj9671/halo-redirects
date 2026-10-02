package run.halo.redirects.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.JsonExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.app.extension.Watcher;
import run.halo.app.extension.index.IndexedQueryEngine;

/**
 * A tiny in-memory stand-in for Halo's extension store, enough for create / update / delete /
 * list by type. Items keep insertion order, which doubles as creation order.
 */
public class InMemoryExtensionClient implements ReactiveExtensionClient {
    private final Map<String, Extension> store = new LinkedHashMap<>();
    private final AtomicLong clock = new AtomicLong();

    private static String key(Class<?> type, String name) {
        return type.getName() + "/" + name;
    }

    @Override
    public <E extends Extension> Flux<E> list(Class<E> type, Predicate<E> predicate,
        Comparator<E> comparator) {
        return Flux.defer(() -> Flux.fromIterable(snapshot(type, predicate, comparator)));
    }

    private <E extends Extension> List<E> snapshot(Class<E> type, Predicate<E> predicate,
        Comparator<E> comparator) {
        var items = new ArrayList<E>();
        store.values().stream().filter(type::isInstance).map(type::cast)
            .filter(item -> predicate == null || predicate.test(item)).forEach(items::add);
        if (comparator != null) {
            items.sort(comparator);
        }
        return items;
    }

    @Override
    public <E extends Extension> Mono<ListResult<E>> list(Class<E> type, Predicate<E> predicate,
        Comparator<E> comparator, int page, int size) {
        return list(type, predicate, comparator).collectList()
            .map(items -> new ListResult<>(page, size, items.size(), items));
    }

    @Override
    public <E extends Extension> Flux<E> listAll(Class<E> type, ListOptions options, Sort sort) {
        return list(type, null, null);
    }

    @Override
    public <E extends Extension> Mono<ListResult<E>> listBy(Class<E> type, ListOptions options,
        PageRequest page) {
        return list(type, null, null, 1, Integer.MAX_VALUE);
    }

    @Override
    public <E extends Extension> Mono<E> fetch(Class<E> type, String name) {
        return Mono.defer(() -> Mono.justOrEmpty(store.get(key(type, name)))).map(type::cast);
    }

    @Override
    public <E extends Extension> Mono<E> get(Class<E> type, String name) {
        return fetch(type, name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends Extension> Mono<E> create(E extension) {
        return Mono.fromSupplier(() -> {
            var metadata = extension.getMetadata();
            if (metadata.getName() == null) {
                metadata.setName(metadata.getGenerateName()
                    + UUID.randomUUID().toString().substring(0, 8));
            }
            var key = key(extension.getClass(), metadata.getName());
            if (store.containsKey(key)) {
                throw new IllegalStateException("already exists: " + metadata.getName());
            }
            metadata.setVersion(1L);
            metadata.setCreationTimestamp(Instant.ofEpochSecond(clock.incrementAndGet()));
            store.put(key, extension);
            return extension;
        });
    }

    @Override
    public <E extends Extension> Mono<E> update(E extension) {
        return Mono.fromSupplier(() -> {
            var metadata = extension.getMetadata();
            metadata.setVersion(metadata.getVersion() == null ? 1L : metadata.getVersion() + 1);
            store.put(key(extension.getClass(), metadata.getName()), extension);
            return extension;
        });
    }

    @Override
    public <E extends Extension> Mono<E> delete(E extension) {
        return Mono.fromSupplier(() -> {
            store.remove(key(extension.getClass(), extension.getMetadata().getName()));
            return extension;
        });
    }

    public <E extends Extension> List<E> all(Class<E> type) {
        return list(type, null, null).collectList().block();
    }

    @Override
    public Mono<Unstructured> fetch(GroupVersionKind gvk, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Mono<JsonExtension> getJsonExtension(GroupVersionKind gvk, String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IndexedQueryEngine indexedQueryEngine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void watch(Watcher watcher) {
        throw new UnsupportedOperationException();
    }
}
