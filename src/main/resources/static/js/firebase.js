// Sesión Firebase compartida por el app-shell.
// Web SDK por CDN (sin build). inMemoryPersistence: el token vive solo en memoria.
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.14.0/firebase-app.js";
import {
    getAuth,
    GoogleAuthProvider,
    signInWithPopup,
    signOut,
    onAuthStateChanged,
    setPersistence,
    browserSessionPersistence
} from "https://www.gstatic.com/firebasejs/12.14.0/firebase-auth.js";

// Config web PÚBLICA (no es secreto; identifica el proyecto). No pegar service-account acá.
const firebaseConfig = {
    apiKey: "AIzaSyA1APg-Eq-ulU749IitSiEsKsDE1oD0wpc",
    authDomain: "dbii-ticketing-grupo6.firebaseapp.com",
    projectId: "dbii-ticketing-grupo6",
    storageBucket: "dbii-ticketing-grupo6.firebasestorage.app",
    messagingSenderId: "454622640345",
    appId: "1:454622640345:web:af52e5c7e653c4373926c8"
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
auth.languageCode = "es";
const googleProvider = new GoogleAuthProvider();
// Persistencia por pestaña (sessionStorage): la sesion sobrevive la navegacion
// registro -> app dentro de la misma pestaña; se borra al cerrarla. No usa localStorage.
const persistenceReady = setPersistence(auth, browserSessionPersistence);

export async function login() {
    await persistenceReady;
    return signInWithPopup(auth, googleProvider);
}

export function logout() {
    return signOut(auth);
}

export function onUser(callback) {
    return onAuthStateChanged(auth, callback);
}

export function currentUser() {
    return auth.currentUser;
}

// Token fresco para el header Authorization. Nunca se imprime ni se persiste.
export async function getIdToken() {
    if (!auth.currentUser) {
        throw new Error("SIN_SESION_FIREBASE");
    }
    return auth.currentUser.getIdToken();
}
