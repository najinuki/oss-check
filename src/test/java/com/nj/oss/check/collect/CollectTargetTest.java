package com.nj.oss.check.collect;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CollectTargetTest {

    @Test
    void sharedTargetsAreTheOnlyExceptionsToPerSampleCollection() {
        // Guards the growth rule: a new target is PER_SAMPLE. Making one SHARED
        // freezes its value for the whole window, so a rule reading it can
        // report a state the cluster left minutes ago.
        assertThat(Arrays.stream(CollectTarget.values()).filter(CollectTarget::isShared))
                .containsExactly(
                        CollectTarget.CAT_SEGMENTS,
                        CollectTarget.CAT_PLUGINS,
                        CollectTarget.INDEX_TEMPLATES);
    }

    @Test
    void clusterSettingsIsCollectedPerSample() {
        // Called out on its own because it is the tempting one to share: it looks
        // static, and it is the single most likely thing to change during an
        // incident. OSC-003 fires on a setting an operator forgot to revert, and
        // evaluating rules against the last sample only means anything if that
        // sample carries the settings actually in force at that moment.
        assertThat(CollectTarget.CLUSTER_SETTINGS.isShared()).isFalse();
    }
}
