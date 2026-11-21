package io.casehub.langchain4j.crag.quarkus;

import io.quarkus.arc.ClientProxy;

final class Unwrap {

    private Unwrap() {}

    /** Strips every client-proxy layer (a producer may return another bean's proxy). */
    static Object all(Object bean) {
        Object current = bean;
        while (current instanceof ClientProxy) {
            current = ClientProxy.unwrap(current);
        }
        return current;
    }
}
