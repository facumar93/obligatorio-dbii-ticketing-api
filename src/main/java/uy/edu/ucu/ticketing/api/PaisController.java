package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class PaisController {

    @GetMapping("/paises")
    public List<Map<String, Object>> listarPaises() {
        List<Map<String, Object>> paises = new ArrayList<>();

        String sql = "SELECT id_pais, codigo_iso, nombre FROM PAIS ORDER BY id_pais";

        try (
            Connection connection = DbConnectionFactory.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)
        ) {
            while (resultSet.next()) {
                paises.add(Map.of(
                    "idPais", resultSet.getInt("id_pais"),
                    "codigoIso", resultSet.getString("codigo_iso").trim(),
                    "nombre", resultSet.getString("nombre").trim()
                ));
            }

            return paises;

        } catch (Exception e) {
            return List.of(
                Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
                )
            );
        }
    }
}