package uy.edu.ucu.ticketing.api;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

public final class FirebaseTokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";

    private FirebaseTokenVerifier() {
        // Clase utilitaria: no se instancia.
    }

    public static VerifiedFirebaseToken verifyAuthorizationHeader(String authorizationHeader)
            throws InvalidAuthorizationException {

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidAuthorizationException(
                    "AUTHORIZATION_HEADER_INVALIDO",
                    "Header Authorization ausente o invalido. Se espera: Bearer <ID_TOKEN>"
            );
        }

        String idToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();

        if (idToken.isBlank()) {
            throw new InvalidAuthorizationException(
                    "TOKEN_FIREBASE_VACIO",
                    "El token Firebase no puede estar vacio"
            );
        }

        try {
            FirebaseToken decodedToken = FirebaseAuth.getInstance().verifyIdToken(idToken);

            return new VerifiedFirebaseToken(
                    decodedToken.getUid(),
                    decodedToken.getEmail(),
                    decodedToken.isEmailVerified(),
                    decodedToken.getName()
            );
        } catch (FirebaseAuthException e) {
            throw new InvalidAuthorizationException(
                    "TOKEN_FIREBASE_INVALIDO",
                    "Token Firebase invalido"
            );
        }
    }

    public record VerifiedFirebaseToken(
            String uid,
            String email,
            boolean emailVerified,
            String name
    ) {
    }

    public static final class InvalidAuthorizationException extends Exception {

        private final String code;

        public InvalidAuthorizationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
