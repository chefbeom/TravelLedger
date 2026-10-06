package com.playdata.calen.ledger.embedding.sync;

import java.util.List;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LedgerEmbeddingHibernateConfiguration {
    @Bean
    HibernatePropertiesCustomizer ledgerEmbeddingIntegrator(LedgerEmbeddingOutboxCapture capture) {
        LedgerEmbeddingHibernateListener listener = new LedgerEmbeddingHibernateListener(capture);
        Integrator integrator = new Integrator() {
            @Override
            public void integrate(Metadata metadata, BootstrapContext bootstrap, SessionFactoryImplementor factory) {
                EventListenerRegistry registry = factory.getServiceRegistry().getService(EventListenerRegistry.class);
                registry.appendListeners(EventType.POST_INSERT, listener);
                registry.appendListeners(EventType.POST_UPDATE, listener);
                registry.appendListeners(EventType.POST_DELETE, listener);
            }
            @Override public void disintegrate(SessionFactoryImplementor factory, SessionFactoryServiceRegistry services) { }
        };
        return properties -> {
            // Preserve any other integrators rather than replacing their registration.
            Object existing = properties.get("hibernate.integrator_provider");
            properties.put("hibernate.integrator_provider", (IntegratorProvider) () -> {
                java.util.ArrayList<Integrator> integrators = new java.util.ArrayList<>();
                if (existing instanceof IntegratorProvider provider) integrators.addAll(provider.getIntegrators());
                integrators.add(integrator);
                return List.copyOf(integrators);
            });
        };
    }
}
