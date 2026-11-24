package io.casehub.langchain4j.tracking.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrackingProcessorTest {

    @Test
    void oneDefaultAugmentor_tracked() {
        assertThat(TrackingProcessor.shouldTrack(true, true, false)).isTrue();
    }

    @Test
    void noAugmentor_noEasyRag_notTracked() {
        assertThat(TrackingProcessor.shouldTrack(false, false, false)).isFalse();
    }

    @Test
    void noAugmentor_easyRag_tracked() {
        // EasyRAG registers its own augmentor exactly when no RetrievalAugmentor / Supplier bean exists
        assertThat(TrackingProcessor.shouldTrack(false, false, true)).isTrue();
    }

    @Test
    void supplierOrQualifiedOnly_easyRag_notTracked() {
        // EasyRAG then registers nothing: a tracking bean would have nothing to wrap
        assertThat(TrackingProcessor.shouldTrack(false, true, true)).isFalse();
    }
}
