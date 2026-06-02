package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class DbHealthController {

    @GetMapping("/db/health")
    public Map<String, Object> dbHealth() {
        String sql = "SELECT 1 AS OK";

        try (
            Connection connection = DbConnectionFactory.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)
        ) {
            resultSet.next();

            String database = connection.getCatalog();

            if (database == null || database.isBlank()) {
                database = "base configurada por DB_URL";
            }

            return Map.of(
                "status", "OK",
                "database", database,
                "result", resultSet.getInt("OK"),
                "message", "Conexion JDBC correcta",
                "timestamp", OffsetDateTime.now().toString()
            );

        } catch (Exception e) {
            return Map.of(
                "status", "ERROR",
                "database", "base configurada por DB_URL",
                "message", e.getMessage(),
                "timestamp", OffsetDateTime.now().toString()
            );
        }
    }
}