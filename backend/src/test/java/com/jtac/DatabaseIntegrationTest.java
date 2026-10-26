package com.jtac;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application context against the local {@code jtac_test} database, connected as
 * {@code jtac_app}. Run with {@code ./gradlew integrationTest} (migrates jtac_test first).
 * Tests must clean up the rows they create; they never truncate or reset the database.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("db")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface DatabaseIntegrationTest {
}
