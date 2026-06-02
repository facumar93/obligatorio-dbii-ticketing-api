package uy.edu.ucu.ticketing.api.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DbConnectionFactory {

    private DbConnectionFactory() {
        // Clase utilitaria: no se instancia.
    }

    public static Connection getConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Falta definir la variable de entorno DB_URL");
        }

        if (user == null || user.isBlank()) {
            throw new IllegalStateException("Falta definir la variable de entorno DB_USER");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Falta definir la variable de entorno DB_PASSWORD");
        }

        return DriverManager.getConnection(url, user, password);
    }
}