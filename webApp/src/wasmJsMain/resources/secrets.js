// Encrypted storage for the Home Assistant token in the browser version of NeonSync, used from WebSecrets.kt.
// The token is AES-GCM encrypted in local storage; the key is a non-extractable WebCrypto key kept in
// IndexedDB, so scripts can use it but not read it out, and the token never sits in storage as plain text.
globalThis.NeonSyncSecrets = {
    /** False outside secure contexts (plain http other than localhost), where there is no WebCrypto. */
    available() {
        return !!(globalThis.crypto && crypto.subtle && globalThis.indexedDB);
    },

    /** Resolves to the stored secret, or null if there is none or it can't be decrypted. */
    async read(name) {
        const stored = localStorage.getItem(name);
        if (stored === null) return null;
        try {
            const key = await this._key(false);
            if (!key) return null;
            const data = Uint8Array.from(atob(stored), c => c.charCodeAt(0));
            const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: data.slice(0, 12) }, key, data.slice(12));
            return new TextDecoder().decode(plain);
        } catch (e) {
            console.warn("Could not decrypt " + name, e);
            return null;
        }
    },

    /** An empty value removes the secret. */
    async write(name, value) {
        if (!value) {
            localStorage.removeItem(name);
            return;
        }
        const key = await this._key(true);
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(value)));
        const data = new Uint8Array(iv.length + cipher.length);
        data.set(iv);
        data.set(cipher, iv.length);
        localStorage.setItem(name, btoa(String.fromCharCode(...data)));
    },

    /** The key from IndexedDB; created there if [create] and missing. */
    async _key(create) {
        const db = await new Promise((resolve, reject) => {
            const request = indexedDB.open("neonsync", 1);
            request.onupgradeneeded = () => request.result.createObjectStore("keys");
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
        const run = (mode, action) => new Promise((resolve, reject) => {
            const request = action(db.transaction("keys", mode).objectStore("keys"));
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
        try {
            let key = await run("readonly", store => store.get("token"));
            if (!key && create) {
                key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
                await run("readwrite", store => store.put(key, "token"));
            }
            return key ?? null;
        } finally {
            db.close();
        }
    },
};
