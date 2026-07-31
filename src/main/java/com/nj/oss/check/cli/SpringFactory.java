package com.nj.oss.check.cli;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import picocli.CommandLine;

/**
 * Lets picocli build commands as Spring beans, so a command can be handed what
 * it needs rather than constructing it. Anything the context does not know
 * about falls back to picocli's own factory — which is what keeps commands
 * usable in tests without starting Spring.
 */
public record SpringFactory(ApplicationContext context) implements CommandLine.IFactory {

    @Override
    public <K> K create(Class<K> cls) throws Exception {
        try {
            return context.getBean(cls);
        } catch (NoSuchBeanDefinitionException e) {
            // Only "Spring does not know this type" falls back. A bean that
            // exists but fails to build is a configuration error, and building
            // it here without its dependencies would hide that until it
            // surfaces as something else entirely.
            return CommandLine.defaultFactory().create(cls);
        }
    }
}
