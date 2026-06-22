const CACHE_NAME = "chatdrama-pwa-v1"

const APP_SHELL = ["./", "./index.html", "./styles.css", "./manifest.webmanifest", "./icons/favicon-32.png", "./icons/apple-touch-icon.png", "./icons/icon-192.png", "./icons/icon-512.png", "./icons/icon-maskable-512.png"]

const CACHEABLE_ASSET = /\.(?:css|html|ico|js|json|mjs|otf|png|svg|ttf|wasm|webmanifest|woff2?)$/i

self.addEventListener("install", (event) => {
    self.skipWaiting()

    event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)))
})

self.addEventListener("activate", (event) => {
    event.waitUntil(
        Promise.all([
            caches.keys().then((keys) =>
                Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))
            ),
            self.clients.claim()
        ])
    )
})

self.addEventListener("fetch", (event) => {
    const {request} = event

    if (request.method !== "GET") return

    const url = new URL(request.url)

    if (url.origin !== location.origin) return

    if (request.mode === "navigate") {
        event.respondWith(networkFirst(request, "./index.html"))
        return
    }

    if (!CACHEABLE_ASSET.test(url.pathname)) return

    event.respondWith(networkFirst(request))
})

async function networkFirst(request, fallbackUrl) {
    const cache = await caches.open(CACHE_NAME)

    try {
        const response = await fetch(request)

        if (response && response.ok) await cache.put(request, response.clone())

        return response
    } catch (error) {
        const cached = await caches.match(request)

        if (cached) return cached
        if (fallbackUrl) return caches.match(fallbackUrl)

        throw error
    }
}
