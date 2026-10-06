package com.playdata.calen.common.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmbeddingPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll static void closeValidator() { factory.close(); }

    @Test void disabledDefaultsDoNotRequireSecrets() {
        EmbeddingProperties properties = new EmbeddingProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(4);
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test void enabledRequiresUrlAndKey() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("baseUrlValid", "apiKeyValid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:18001", "http://127.0.0.1:18001/", "https://embedding.example.invalid"})
    void acceptsConfiguredOrigin(String url) {
        EmbeddingProperties properties = enabledProperties();
        properties.setBaseUrl(url);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost", "http://fixture:fixture@localhost", "http://localhost/v1",
            "http://localhost?query=fixture", "http://localhost#fragment", "http://localhost:0", "http://localhost:65536"})
    void rejectsUnsafeOrNonOriginUrl(String url) {
        EmbeddingProperties properties = enabledProperties();
        properties.setBaseUrl(url);
        assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("baseUrlValid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "fixture key", "fixture\r\nkey", "fixture\tkey"})
    void rejectsBlankOrHeaderUnsafeKeys(String key) {
        EmbeddingProperties properties = enabledProperties();
        properties.setApiKey(key);
        assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("apiKeyValid");
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4})
    void acceptsBatchOneThroughFour(int batchSize) {
        EmbeddingProperties properties = enabledProperties();
        properties.setBatchSize(batchSize);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {0, 5})
    void rejectsBatchOutsideReceiverLimit(int batchSize) {
        EmbeddingProperties properties = enabledProperties();
        properties.setBatchSize(batchSize);
        assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                .contains("batchSize");
    }

    @Test void rejectsNullZeroAndNegativeTimeoutsWhenEnabled() {
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            EmbeddingProperties properties = enabledProperties();
            properties.setConnectTimeout(timeout);
            assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("timeoutsValid");
            properties = enabledProperties();
            properties.setReadTimeout(timeout);
            assertThat(validator.validate(properties)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("timeoutsValid");
        }
    }

    @Test void springBindsDurationsAndRejectsInvalidEnabledConfigurationAtStartup() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        ValidationAutoConfiguration.class))
                .withUserConfiguration(EmbeddingProperties.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmbeddingProperties.class).isEnabled()).isFalse();
        });
        runner.withPropertyValues("app.embedding.enabled=true", "app.embedding.base-url=http://127.0.0.1:18001",
                "app.embedding.api-key=contract-fixture-key", "app.embedding.connect-timeout=5s",
                "app.embedding.read-timeout=180s", "app.embedding.batch-size=4").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EmbeddingProperties.class).getReadTimeout()).isEqualTo(Duration.ofSeconds(180));
                });
        runner.withPropertyValues("app.embedding.enabled=true").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.embedding.batch-size=5").run(context -> assertThat(context).hasFailed());
    }

    private EmbeddingProperties enabledProperties() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:18001");
        properties.setApiKey("contract-fixture-key");
        return properties;
    }
}
