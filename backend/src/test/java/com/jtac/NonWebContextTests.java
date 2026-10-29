package com.jtac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The application also starts without a web server (administration commands such as
 * {@code ./gradlew createUser}); web-only beans must not be required there.
 */
@Tag("db")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class NonWebContextTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutAWebServer() {
        assertThat(context.getBeanNamesForType(com.jtac.identity.UserDirectory.class)).isNotEmpty();
    }
}
