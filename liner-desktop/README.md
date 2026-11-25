<div align="center">

# vasyan

**Self-hosted desktop music streaming player with syllable-synced lyrics, LAN Connect, and reactive dynamic visuals.**

[![Platforms](https://img.shields.io/badge/platforms-Windows%20·%20macOS%20·%20Linux-2ec4b6?style=flat-square&labelColor=0d0d10)](#building) [![License](https://img.shields.io/badge/license-source--available-534dff?style=flat-square&labelColor=0d0d10)](./LICENSE)

[Preview](#preview) • [Architecture](#architecture) • [Getting Started](#getting-started) • [Building](#building) • [Docs](#docs) • [Contributing](#contributing) • [License](#license)

<br/>

<img src="./.github/assets/album.png" alt="vasyan Album Page" width="100%" />

</div>

---

## Preview

<div align="center">
  <table>
    <tr>
      <td align="center" width="50%">
        <img src="./.github/assets/library.png" alt="Liner Library" />
        <br/>
        <sub><b>Library</b></sub>
      </td>
      <td align="center" width="50%">
        <img src="./.github/assets/lyrics.png" alt="Liner Fullscreen Lyrics" />
        <br/>
        <sub><b>Fullscreen lyrics</b></sub>
      </td>
    </tr>
  </table>
</div>

---

> [!NOTE]
> **vasyan** (internal package `@liner/client`, `executableName: "liner"`) connects to the self-hosted `liner-worker` Cloudflare Worker backend (`https://liner-worker.vasyan.workers.dev`) for music catalog, streaming playback, and synchronized lyrics, and syncs with `liner-android` over local LAN Connect.

---

## Architecture

The renderer follows [Feature-Sliced Design](https://feature-sliced.design): imports only point down the layers (`app` → `widgets` → `features` → `entities` → `shared`).

```
liner-desktop/
├── electron/
│   ├── main.ts              # Main process: windows, IPC, updates, deep links (remote bundle disabled)
│   ├── preload.ts           # Context bridge between the renderer and the main process (window.linerElectron)
│   ├── lan-connect.ts       # Local LAN Connect: UDP 45788 beacon + WS 45789 server
│   ├── auth-session-*.ts    # Encrypted Electron auth session persistence
│   ├── local-tracks/        # Imported audio files and the liner-local:// protocol
│   ├── saved-tracks/        # Offline saved tracks cache
│   ├── youtube/             # Helpers for the YouTube playback fallback
│   ├── compositor/          # Hyprland/Sway placement and shortcut bridge
│   ├── overlay.ts           # Floating overlay player window
│   ├── discord.ts           # Discord Rich Presence
│   ├── crypto.ts            # In-memory WebAssembly request signer
│   └── signer.ts            # Embedded WebAssembly binary
├── native/                  # Windows snap preview add-on
├── src/
│   ├── app/                 # App shell, routes, providers, global styles
│   ├── widgets/             # Screen blocks: sidebar, header, player bar, fullscreen player, modals
│   ├── features/            # player, lyrics, library, search, collection, settings, overlay, …
│   ├── entities/            # track, playlist, artist, user
│   ├── shared/              # API client, storage, UI kit, contracts, utilities
│   ├── languages/           # Translations for 11 languages
│   └── main.tsx             # Vite entry point
├── docs/                    # Design, storage, privacy, translations, FAQ
├── package.json
└── vite.config.ts
```

---

## Getting Started

### Prerequisites

- **Node.js** `v22+` and **pnpm** `v10+`
- Windows 10/11, macOS or Linux

---

### Setup

```bash
git clone https://github.com/tryliner/desktop.git
cd desktop
pnpm install
```

---

### Running in Development

```bash
# Electron app with Vite hot reload
pnpm dev
```

---

## Testing

```bash
# Tests only
pnpm test

# Everything CI runs on a pull request: lint, format check, types, build, tests
pnpm check
```

---

## Building

```bash
# Linux (AppImage & .deb)
pnpm electron:build:linux

# Windows (.exe installer)
pnpm electron:build:win

# macOS (.dmg & .zip, for both Intel and Apple Silicon)
pnpm electron:build:mac
```

The macOS build produces `x64` (Intel) and `arm64` (Apple Silicon) packages. To build only one, add `--arm64` or `--x64`. Packages are placed in the `release/` directory.

---

## Docs

- [Design guidelines](./docs/DESIGN.md): tokens, glass surfaces, motion and building blocks
- [Storage](./docs/STORAGE.md): where data lives on your device and what "Clear cache" removes
- [Privacy](./docs/PRIVACY.md): everything the app sends off your device
- [Translations](./docs/I18N.md): adding strings, plurals and languages
- [FAQ](./docs/FAQ.md): answers to the most common problems

---

## Contributing

Bug fixes, small features, translations and docs fixes are welcome. Read [CONTRIBUTING.md](./CONTRIBUTING.md) and [AGENTS.md](./AGENTS.md) before making changes, and see [SECURITY.md](./SECURITY.md) for security issues.

---

## Special Thanks

- [`@braccato/core`](https://github.com/better-lyrics/braccato) & `@braccato/parsers` — Synced lyrics components and format parsers.
- [Kawarp](https://github.com/better-lyrics/kawarp) — WebGL background shader the fullscreen background is based on.
- [Solar Icons](https://solar-icons.com) & [MingCute Icons](https://www.mingcute.com) — Icon collections.

---

## License

**vasyan** is a self-hosted fork of Liner. See [LICENSE](./LICENSE) for the upstream source-available license terms.
