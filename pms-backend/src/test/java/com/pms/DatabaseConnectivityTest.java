package com.pms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Spring context boots against the Testcontainers Postgres instance and that the
 * can actually reach it, rather than merely holding valid-looking connection properties.
 */
@SpringBootTest
class DatabaseConnectivityTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void selectOneSucceedsThroughConfiguredDataSource() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }
}
