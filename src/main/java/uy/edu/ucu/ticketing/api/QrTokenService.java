package uy.edu.ucu.ticketing.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class QrTokenService {

    private static final String ENV_SECRET = "QR_SIGNING_SECRET";
    private static final String ALGORITMO_HMAC = "HmacSHA256";
    private static final int LARGO_MINIMO_SECRET_BYTES = 32;
    private static final int LARGO_SEMILLA_BYTES = 32;
    private static final int LARGO_NONCE_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    private QrTokenService(byte[] secret) {
        this.secret = secret.clone();
    }

    public static QrTokenService desdeEntorno() throws QrConfigurationException {
        String valor = System.getenv(ENV_SECRET);

        if (valor == null || valor.isBlank()) {
            throw new QrConfigurationException(
                    "Falta definir la variable de entorno " + ENV_SECRET
            );
        }

        byte[] secret = valor.getBytes(StandardCharsets.UTF_8);

        if (secret.length < LARGO_MINIMO_SECRET_BYTES) {
            throw new QrConfigurationException(
                    ENV_SECRET + " debe contener al menos 32 bytes"
            );
        }

        return new QrTokenService(secret);
    }

    public CredencialGenerada generarCredencial(int idEntrada) {
        String semilla = generarValorAleatorio(LARGO_SEMILLA_BYTES);
        String nonce = generarValorAleatorio(LARGO_NONCE_BYTES);
        String firmaDigital = firmarBase64Url(
                payloadCredencial(idEntrada, semilla, nonce)
        );

        return new CredencialGenerada(semilla, nonce, firmaDigital);
    }

    public boolean validarFirmaCredencial(
            int idEntrada,
            String semilla,
            String nonce,
            String firmaDigital
    ) {

        if (semilla == null || nonce == null || firmaDigital == null) {
            return false;
        }

        byte[] firmaEsperada = firmar(payloadCredencial(idEntrada, semilla, nonce));

        try {
            byte[] firmaGuardada = BASE64_URL_DECODER.decode(firmaDigital);
            return MessageDigest.isEqual(firmaEsperada, firmaGuardada);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String generarToken(
            int idEntrada,
            long ventana,
            String semilla,
            String nonce
    ) {

        String firmaTemporal = firmarBase64Url(
                payloadToken(idEntrada, ventana, semilla, nonce)
        );

        return "v1." + idEntrada + "." + ventana + "." + firmaTemporal;
    }

    private String generarValorAleatorio(int cantidadBytes) {
        byte[] valor = new byte[cantidadBytes];
        SECURE_RANDOM.nextBytes(valor);
        return BASE64_URL.encodeToString(valor);
    }

    private String firmarBase64Url(String payload) {
        return BASE64_URL.encodeToString(firmar(payload));
    }

    private byte[] firmar(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO_HMAC);
            mac.init(new SecretKeySpec(secret, ALGORITMO_HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular la firma HMAC", e);
        }
    }

    private static String payloadCredencial(int idEntrada, String semilla, String nonce) {
        return "CREDENCIAL|v1|" + idEntrada + "|" + semilla + "|" + nonce;
    }

    private static String payloadToken(
            int idEntrada,
            long ventana,
            String semilla,
            String nonce
    ) {

        return "QR|v1|" + idEntrada + "|" + ventana + "|" + semilla + "|" + nonce;
    }

    public record CredencialGenerada(
            String semilla,
            String nonce,
            String firmaDigital
    ) {
    }

    public static final class QrConfigurationException extends Exception {

        public QrConfigurationException(String message) {
            super(message);
        }
    }
}
