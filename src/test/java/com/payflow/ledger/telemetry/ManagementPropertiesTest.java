package com.payflow.ledger.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every management.* key in our YAML must be a real Spring Boot 4 property (checked against the
 * configuration metadata shipped in the Boot jars), so a typo cannot silently disable telemetry.
 */
class ManagementPropertiesTest {

    @Test
    void everyManagementKeyBindsToARealProperty() throws Exception {
        Set<String> known = new HashSet<>();
        Pattern name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:META-INF/spring-configuration-metadata.json")) {
            try (InputStream in = r.getInputStream()) {
                Matcher m = name.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                while (m.find()) known.add(m.group(1));
            }
        }
        assertTrue(known.size() > 1000, "configuration metadata must be on the classpath");

        for (String file : new String[]{"application.yml", "application-telemetry-off.yml"}) {
            YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
            y.setResources(new ClassPathResource(file));
            Properties p = y.getObject();
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith("management.")) continue;
                boolean ok = known.stream().anyMatch(n -> key.equals(n) || key.startsWith(n + ".") || key.startsWith(n + "["));
                assertTrue(ok, file + ": unknown property " + key);
            }
        }
    }

    @Test
    void resourceAttributesCarryRunTagging() {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new ClassPathResource("application.yml"));
        Properties p = y.getObject();
        assertEquals("payflow-ledger", p.getProperty("management.opentelemetry.resource-attributes.service.name"));
        assertTrue(p.getProperty("management.opentelemetry.resource-attributes.run_id").contains("RUN_ID"));
        assertTrue(p.getProperty("management.opentelemetry.resource-attributes.scenario").contains("SCENARIO"));
    }
}
