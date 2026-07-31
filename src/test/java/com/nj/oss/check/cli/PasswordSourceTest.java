package com.nj.oss.check.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordSourceTest {

    @Test
    void theEnvironmentIsTheNonInteractivePath() {
        PasswordSource source = new PasswordSource(
                Map.of(PasswordSource.ENVIRONMENT_VARIABLE, "s3cr3t")::get, null);

        assertThat(source.forUser("admin")).isEqualTo("s3cr3t");
    }

    @Test
    void withoutATerminalAndWithoutTheVariableItFailsInsteadOfAsking() {
        // cron and pipes have no terminal; prompting there would hang the run
        PasswordSource source = new PasswordSource(name -> null, null);

        assertThatThrownBy(() -> source.forUser("admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin")
                .hasMessageContaining(PasswordSource.ENVIRONMENT_VARIABLE);
    }

    @Test
    void anEmptyVariableIsStillAnAnswer() {
        // "" was set deliberately; only an unset variable means "not supplied"
        PasswordSource source = new PasswordSource(
                Map.of(PasswordSource.ENVIRONMENT_VARIABLE, "")::get, null);

        assertThat(source.forUser("admin")).isEmpty();
    }
}
