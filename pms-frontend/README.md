# pms-frontend

Mobile-first React SPA for the Parking Management System.

## Dev mode

```
npm install
npm run dev
```

The dev server proxies `/api/v1/...` to the backend (default `http://localhost:8080`, override with
`VITE_API_PROXY_TARGET`), so the app stays same-origin and the backend needs no CORS configuration.

## Build

```
npm run build
```

## Tests

```
npm test -- --run
```

Vitest + React Testing Library, jsdom environment.

## Compose

Built and served by nginx as the `frontend` service in the root `docker-compose.yml`; nginx also
proxies `/api/v1/` to the `backend` service and falls back to `index.html` for client-side routes.
