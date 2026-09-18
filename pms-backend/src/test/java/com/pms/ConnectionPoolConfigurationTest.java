package com.pms;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app is the sole database client, so the pool is capped at 10 connections
 * rather than left at the driver default.
 */
@SpringBootTest
class ConnectionPoolConfigurationTest extends AbstractPostgresIT {
    @Autowired
    private DataSource dataSource;

    @Test
    void resolvedPoolMaximumSizeIsTen() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(10);
    }
}
