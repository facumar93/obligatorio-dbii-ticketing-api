package uy.edu.ucu.ticketing.api;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import uy.edu.ucu.ticketing.api.db.DbConnectionFactory;

@RestController
public class TipoDocumentoController {

    @GetMapping("/tipos-documento")
    public ResponseEntity<?> listarTiposDocumento() {
        List<Map<String, Object>> tiposDocumento = new ArrayList<>();

        String sql = """
                SELECT id_tipo_documento, codigo, nombre
                FROM TIPO_DOCUMENTO
                ORDER BY nombre
                """;

        try (
            Connection connection = DbConnectionFactory.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql)
        ) {
            while (resultSet.next()) {
                tiposDocumento.add(Map.of(
                        "idTipoDocumento", resultSet.getInt("id_tipo_documento"),
                        "codigo", resultSet.getString("codigo").trim(),
                        "nombre", resultSet.getString("nombre").trim()
                ));
            }

            return ResponseEntity.ok(tiposDocumento);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status", "ERROR",
                            "message", "No se pudieron cargar los tipos de documento"
                    ));
        }
    }
}
