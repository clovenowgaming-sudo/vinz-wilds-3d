# Vinz Wilds - Aether Frontier

![logo](docs/logo.png)

3D open-world survival shooter for Android (OpenGL ES 3.0), made from scratch.

- Procedural world **64,000 x 64,000 m**, streamed in chunks (terrain LODs, forests, grass, water)
- Single player and online multiplayer (Node.js server in `server/`)
- Settings screen: LOW / MEDIUM / EXTREME presets, resolution, view distance, controls, audio
- Collect Aether Crystals, survive wraiths at night, day/night cycle

## Build
Push to `main`; GitHub Actions builds the APK (artifact `VinzWilds-APK`).

## Server (Termux)
```
cd server && npm install && node server.js
```
Expose `http://localhost:8787` with a Cloudflare Tunnel public hostname, path `/wilds`.
Game connects to `wss://<host>/wilds/ws`; status at `https://<host>/wilds/status`.
