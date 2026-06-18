package uy.edu.ucu.ticketing.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Token QR dinamico, inspirado en TOTP (no es TOTP criptografico completo).
 *
 * CREDENCIAL_QR guarda la semilla persistente por entrada. El codigo visible muta
 * cada 30 segundos y se calcula en memoria como HMAC-SHA256(semilla, idEntrada:ventana),
 * sin almacenar cada token. La validacion recomputa el HMAC y compara con tolerancia
 * de +-1 ventana (anti-replay de capturas de pantalla).
 */
final class QrTokenService {

    static final long VENTANA_SEG = 30;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private QrTokenService() {
    }

    static String nuevaSemilla() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    static String nuevoNonce() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    static long ventanaActual() {
        return Instant.now().getEpochSecond() / VENTANA_SEG;
    }

    static long segundosParaExpirar() {
        return VENTANA_SEG - (Instant.now().getEpochSecond() % VENTANA_SEG);
    }

    /** Token visible: idEntrada.ventana.mac(16 hex). Es lo que se dibuja en el QR. */
    static String token(int idEntrada, String semilla, long ventana) {
        String mac = hmacHex(semilla, idEntrada + ":" + ventana);
        return idEntrada + "." + ventana + "." + mac.substring(0, 16);
    }

    /** Firma estable de la credencial (no rota): sirve como firma_digital persistente. */
    static String firma(int idEntrada, String semilla) {
        return hmacHex(semilla, "entrada:" + idEntrada).substring(0, 32);
    }

    /** Id de entrada embebido en un token leido, o null si el formato es invalido. */
    static Integer idEntradaDe(String token) {
        if (token == null) return null;
        String[] p = token.trim().split("\\.");
        if (p.length != 3) return null;
        try { return Integer.parseInt(p[0]); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Verifica un token contra la semilla de su entrada. Acepta la ventana reclamada
     * si esta dentro de +-tolerancia de la ventana del servidor y el HMAC coincide.
     */
    static boolean verificar(String token, String semilla, int tolerancia) {
        if (token == null) return false;
        String[] p = token.trim().split("\\.");
        if (p.length != 3) return false;
        int id;
        long ventana;
        try {
            id = Integer.parseInt(p[0]);
            ventana = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(ventana - ventanaActual()) > tolerancia) {
            return false;
        }
        String esperado = hmacHex(semilla, id + ":" + ventana).substring(0, 16);
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                p[2].getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String clave, String mensaje) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(mensaje.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular HMAC", e);
        }
    }
}
