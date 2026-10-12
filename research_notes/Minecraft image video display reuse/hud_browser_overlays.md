# HUD Overlays, Custom GUIs, and Embedded Browser Image Rendering

## Which open source UI frameworks exist for Fabric/Forge, and what does each provide (layout, image widget, HUD anchor, remote images)?

### Takeaway
owo-ui (owo-lib) is the only full declarative layout framework with XML hotswap and layout engines, but it renders client-side Screens with Identifier-based textures, not server-driven JSON or remote-URL images; LibGui is a container/inventory GUI library, Fabric's Hud API is only a layering/anchor mechanism, sgui is server-side vanilla-container GUIs, and Puzzles Lib is not a GUI library at all.

### Cited Findings
- owo-lib repo is `wisp-forest/owo-lib`, described as "A general utility, GUI and config library for modding on Fabric", 268 stars / 85 forks, MIT license — [Source](https://github.com/wisp-forest/owo-lib)
- owo-lib default branch is now `braid-ui`, 1,275 commits, with `owo-ui.xsd` and `stylesheet.css` in repo root — [Source](https://github.com/wisp-forest/owo-lib)
- owo-ui is documented as "a declarative UI framework", "Component tree ... just like modern web tech", "describe your layout in code or ... in standard XML", "hotswapping for free ... reloaded every time you open the screen", "Robust Layout Engines", "component inspector ... like Inspect element" — [Source](https://docs.wispforest.io/owo/ui/)
- owo-ui component catalogue includes Button, Checkbox, Collapsible Container, Dropdown, Flow Layout, Grid Layout, Label, Scroll Container, Slider, Templates — [Source](https://docs.wispforest.io/owo/ui/)
- owo-lib on Modrinth supports Minecraft 26.2 / 26.1.x / 1.21.x / 1.20.x / 1.19.x / 1.18.x / 1.17.x on Fabric, NeoForge, Quilt, client and server, "Licensed MIT, Published 5 years ago, Updated 6 days ago", 45.3M downloads — [Source](https://modrinths.com/mod/ccKDOlHs)
- owo-lib on CurseForge: 80.7M downloads, "Last Update Aug 19, 2026", Fabric and Quilt — [Source](https://www.curseforge.com/minecraft/mc-mods/owo-lib)
- owo-lib Maven artifacts reach `0.13.1+26.2` / `0.13.0+26.1` / `0.13.0+1.21.11` (newer than `0.12.15.1+1.21` Mar 2025), jar ~1.1 MB — [Source](https://mvnrepository.com/artifact/io.wispforest/owo-lib/0.12.15.1+1.21)
- owo-lib organization activity shows owo-lib "Updated Aug 6, 2026" — [Source](http://168.138.51.227:8088/wisp-forest)
- LibGui repo is `CottonMC/LibGui`, described as "Buttons & Co", 313 stars / 60 forks, MIT license — [Source](https://github.com/CottonMC/LibGui)
- LibGui README: "Minecraft GUIs without spending forever painstakingly aligning things to the background image. Instead, LibGui takes a logical description of your GUI, and draws it on-the-fly like any modern GUI system. Controls can be hung on an itemslot grid or offset from it." — [Source](https://github.com/CottonMC/LibGui)
- LibGui Javadoc `16.0.1+26.1` shows packages `gui`, `gui.client`, `gui.networking`, `gui.widget`, `gui.widget.data`, `gui.widget.focus`, `gui.widget.icon` — [Source](https://cottonmc.github.io/docs/libgui)
- LibGui provides `ScreenDrawing` utilities including "a beveled, round rectangle ... similar to default Minecraft UI panels" — [Source](https://github.com/CottonMC/LibGui/blob/master/src/main/java/io/github/cottonmc/cotton/gui/client/ScreenDrawing.java)
- Fabric docs: "Previously, Fabric provided HudRenderCallback ... deprecated since Fabric API 0.116. Usage is strongly discouraged" — [Source](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.8/develop/rendering/hud.md)
- Fabric docs (26.2): "Fabric provides the Hud API to render and layer elements on the HUD ... register a listener to HudElementRegistry ... Each element is an HudElement ... lambda that takes a GuiGraphicsExtractor and a DeltaTracker" — [Source](https://docs.fabricmc.net/develop/rendering/hud)
- Fabric `VanillaHudElements` (26.2 branch) defines ordered vanilla layer identifiers (bottom-drawn-first, e.g. mount_health, info_bar, experience_level, held_item_tooltip, spectator_tooltip, mob_effects, boss_bar, sleep, demo timer) with render-condition inheritance via `Hud#isHidden()` — [Source](https://github.com/FabricMC/fabric-api/blob/26.2/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/hud/VanillaHudElements.java)
- sgui repo is `Patbox/sgui`, "Library for creating custom, server side guis on Fabric", 158 stars / 24 forks, LGPL-3.0 — [Source](https://github.com/Patbox/sgui)
- sgui README: "It's a small, jij-able library that allows creation of server side guis", dependency `eu.pb4:sgui:[TAG]` from `https://maven.nucleoid.xyz`, use `SimpleGUI` directly or extend — [Source](https://github.com/Patbox/sgui)
- Puzzles Lib repo is `Fuzss/puzzles-lib`, "Why is it called Puzzles? That's the puzzle", 38 stars, MPL-2.0 — [Source](https://github.com/Fuzss/puzzles-lib)
- Puzzles Lib CurseForge: 219.1M downloads, "Updated 1 day ago" (Sep 28, 2026), versions 26.3/26.2/26.1.x, loaders Fabric/Forge/NeoForge, "License Mozilla Public License 2.0" — [Source](https://www.curseforge.com/minecraft/mc-mods/puzzles-lib)
- Puzzles Lib CurseForge description: "built mainly for @heyitsfuzs own projects and is not intended as a general-purpose library for other mod developers. Its focus is ... configuration, networking, events, and registration support" with "no new blocks, items, mobs, or gameplay on its own" — [Source](https://www.curseforge.com/minecraft/mc-mods/puzzles-lib)
- Puzzles Lib branch table (Sep 2026): `26.3.x Primary`, `26.1.x Maintained`, `1.20.1 Bugfixes only`, older archived; Maven `fuzs.puzzleslib:puzzleslib-common/fabric/neoforge:26.3.4` — [Source](https://github.com/Fuzss/puzzles-lib)

### Inferences
- owo-ui covers layout + widgets + XML iteration + inspector, but no evidence of built-in async remote-URL image widget or HUD anchoring; its `TextureComponent`-style widgets use packed `Identifier` textures, so remote images would still need custom downloader + `NativeImage`/`DynamicTexture` cache.
- LibGui is for synced container screens (slots + panels), not persistent HUD overlays or free-floating images; no remote-image pipeline.
- Fabric Hud API is the correct anchor/z-order primitive (`attachElementBefore/After` vanilla layers) but provides no layout engine or image loading — it takes raw `GuiGraphics` draw calls.
- sgui solves "no client mod" server GUIs via vanilla containers, but cannot do HUD, positioned images, or custom rendering.
- "Puzzles Lib" is a false positive for UI search: it is a multi-loader backend library, not a HUD/GUI framework.

### Gaps
- No primary-source confirmation found for owo-ui's exact remote-image story (`TextureComponent` URL support, caching, CORS/whitelist); docs page for components was not live-crawled beyond the index in the tool budget.
- CreativeCore GuiAPI (dependency of WATERFrAMES) was not assessed; it may contain a separate server-synced GUI system but no reliable source was fetched.
- LibGui last-commit date and 1.21.x/26.x Fabric-only vs Forge status was not fully verified beyond Javadoc `16.0.1+26.1`.

## Which projects embed a real browser (MCEF, JCEF/JxBrowser/Ultralight) to show web pictures/video as HUD or screen, and what are binary size, platform support, maintenance status?

### Takeaway
MCEF (CinemaMod, LGPL-2.1, Chromium 116) is the only viable open-source Chromium embed for Java Edition; the mod jar is ~200 KB but downloads ~100–170 MB of per-platform CEF natives, supports Windows/Linux/macOS desktop, and upstream is stalled at 1.21.4 with community forks carrying it to 26.x. WebDisplays renders web pages on in-world blocks/MinePad, WebDisplaysTogether moves the browser to the server and streams VP8/VP9+Opus video, and WATERFrAMES uses VLC/WATERMeDIA (not Chromium) and is closed-license. No mature JxBrowser/Ultralight Minecraft integration was found.

### Cited Findings
- MCEF repo is `CinemaMod/mcef`, "A Minecraft mod and library for adding the Chromium web browser into the game", 149 stars / 54 forks, LGPL-2.1 — [Source](https://github.com/CinemaMod/mcef)
- MCEF stack: "based on java-cef ... based on CEF ... based on Chromium. Originally created by montoyo. Rewritten and currently maintained by the CinemaMod Group" — [Source](https://github.com/CinemaMod/mcef)
- MCEF "Current Chromium version: 116.0.5845.190" — [Source](https://github.com/CinemaMod/mcef)
- MCEF "contains a downloader system for downloading the java-cef & CEF binaries ... requires a connection to https://mcef-download.cinemamod.com" — [Source](https://github.com/CinemaMod/mcef)
- MCEF supported platforms: "Windows 10/11 (x86_64, arm64)", "macOS 11 or greater (Intel, Apple Silicon)", "GNU Linux glibc 2.31 or greater (x86_64, arm64)", "will not work on Android", antivirus may block init — [Source](https://github.com/CinemaMod/mcef)
- MCEF Gradle coordinates: `modCompileOnly 'com.cinemamod:mcef:2.1.6-1.21.4'` + `modRuntimeOnly 'com.cinemamod:mcef-fabric:2.1.6-1.21.4'` (NeoForge `fg.deobf` variant), repos `https://mcef-download.cinemamod.com/repositories/releases` (+ snapshots) — [Source](https://github.com/CinemaMod/mcef)
- Original `montoyo/mcef` (209 stars / 140 forks) was Windows 32/64-bit only at the time ("I'll compile JCEF for linux as soon as possible. However I can't compile it for Mac") with features "2D & 3D web view rendering", "Java -> JavaScript (IBrowser.runJavaScript)", "JavaScript -> Java (IJSQueryHandler)", "Embedded files (mod://modname/file.html)" — [Source](https://github.com/montoyo/mcef)
- CCBlueX fork `CCBlueX/mcef` (LiquidBounce-optimized, 26–27 stars) reports "Current Chromium version: 122.0.6261.112", same Win/macOS/Linux matrix, downloader via `https://api.liquidbounce.net/` + Cloudflare, coordinate `com.github.CCBlueX:mcef:3.1.0-1.21.4` via JitPack, branches up to `26.2`/`1.21.11` — [Source](https://github.mosekj.com/CCBlueX/mcef)
- Community Fabric multiversion fork `meowdding/mcef` notes "Removed neo forge support, Added multiversioning for fabric via stonecutter", created Jan 2026 — [Source](https://github.com/meowdding/mcef)
- MCEF CurseForge: 3.2M downloads, "Updated 1 year ago" (Jan 16, 2025), "License GNU Lesser General Public License version 2.1 (LGPLv2.1)", game versions 1.21.4–1.21 + 9 older, loaders Forge/Fabric/NeoForge — [Source](https://www.curseforge.com/minecraft/mc-mods/mcef/files/all)
- MCEF file sizes: `[FABRIC][1.21.4] MCEF 2.1.6-1.21.4` 219.0 KB (50.5K downloads Jan 16 2025); `[NEOFORGE][1.21.4]` 212.7 KB — [Source](https://www.curseforge.com/minecraft/mc-mods/mcef/files/all)
- Runtime native size evidence: java-cef `jcef-linux64-distribution.tar.gz` 168 MB — [Source](https://github.com/guusdk/java-cef/releases)
- Mirrored java-cef bundles `eaeb3d43` (Feb 2026): `macos_amd64.tar.gz` 116 MB, `macos_arm64.tar.gz` 111 MB, linux/windows/macOS amd64+arm64 tarballs + sha256 — [Source](https://github.com/Keksuccino/mcef_resources/releases)
- WebDisplaysTogether README: "java-cef natives (~120 MB) are not bundled in the jar" — [Source](https://github.com/He-Horatio/WebDisplaysTogether/blob/main/README.md)
- MCEF downloader implementation downloads `${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz` + `.sha256` with progress listener — [Source](https://github.com/WaterMediaTeam/HydroChrome/blob/1.21.4/common/src/main/java/com/cinemamod/mcef/MCEFDownloader.java)
- WebDisplays repo is `CinemaMod/webdisplays`, "A Minecraft mod for creating and interacting with web browsers", 73 stars / 45 forks, MIT, "originally written by montoyo ... currently maintained by CinemaMod Group", "Requires MCEF" — [Source](https://github.com/CinemaMod/webdisplays)
- WebDisplays CurseForge: 2.4M downloads, "Updated 1 year ago" (Oct 19, 2024), MIT, versions 1.20.1/1.20/1.19.2/1.18.2/1.12.2, loaders Forge/NeoForge, latest `WebDisplays 2.0.2-1.20.1` — [Source](https://www.curseforge.com/minecraft/mc-mods/webdisplays/files/4334038)
- Unofficial WebDisplays NeoForge 1.21.1 port (`brother-bill/webdisplays-mc`, MIT, 4 stars) requires "MCEF 2.1.0+", lists screens + MinePad + multiplayer share — [Source](https://github.com/brother-bill/webdisplays-mc)
- CurseForge search shows WebDisplays forks: "WebDisplays Fork" 1.21.1 NeoForge 43.1K (May 30 2026), "WebDisplays [2.0] REMASTER" 1.21.1 NeoForge 36.8K (Jun 24 2026), "WebDisplays Revived ... for Minecraft 26.2 ... on Fabric" 146 downloads (Sep 7 2026) — [Source](https://www.curseforge.com/minecraft/mc-mods/webdisplays/files/4334038)
- WebDisplaysTogether CurseForge: "Watch the web together. The web page runs on the server and is streamed to every player as video", MIT, 1.3K downloads, "Last Update Sep 4, 2026", builds `1.08 (fabric1.21.1 / neoforge1.21.1 / forge1.20.1)` feature-identical — [Source](https://www.curseforge.com/minecraft/mc-mods/webdisplaystogether)
- WebDisplaysTogether features: "One shared browser per screen ... runs on the server; all players see the identical picture", "Adaptive video quality ... 360p–1080p ... smooth 30 fps", VP8/VP9 + Opus implied, "Screen audio ... placeable speakers ... background music ducks", "Multiblock screens ... keyboards, laser pointer remote, redstone", "Incognito by default ... wiped when server shuts down" — [Source](https://www.curseforge.com/minecraft/mc-mods/webdisplaystogether)
- WebDisplaysTogether README (Forge 1.20.1 build): "Browsable web pages on screens ... rendered on the server and streamed to every player as video", requires "MCEF 2.1.1+ (client and server)", "Windows x64, Linux x64, Linux arm64 (no macOS build yet)", dedicated Linux server needs `libnss3, libatk1.0-0, libgbm1, ...` + X server or `Xvfb`, "merges the bytedeco JavaCPP + FFmpeg classes and natives for windows-x86_64, linux-x86_64 and linux-arm64 into the mod jar", own code MIT with FFmpeg LGPL-2.1+/JavaCPP Apache-2.0/MCEF/CEF/jQuery in NOTICE — [Source](https://github.com/He-Horatio/WebDisplaysTogether/blob/main/README.md)
- WATERFrAMES repo is `SrRapero720/waterframes`, "Mod which let you see pictures in-game", 49 stars / 42 forks, default branch 1.20.1 — [Source](https://github.com/SrRapero720/waterframes)
- WATERFrAMES README: "play videos and show pictures in your Minecraft worlds only using URLs powered by WATERMeDIA", "Multiple ways to show images (Frames, Projectors, TVs and Big TVs)", "Local files support (powered by WATERMeDIA)", "Fully customizable and configurable", "designed to work in survival mode" — [Source](https://github.com/SrRapero720/waterframes)
- WATERFrAMES CurseForge: 6.5M downloads, "Last Update Jun 22, 2026", "License All Rights Reserved", versions 1.21.8/1.21.5/1.21.4/1.21.1/1.20.1+, loaders Forge/Fabric/NeoForge, latest `WF-NEOFORGE/mc1.21.1/v2.1.23` May 8 2026 / `WF-FORGE/mc1.20.1/v2.2.0-beta.6` Jun 22 2026, jar ~233–250 KB — [Source](https://www.curseforge.com/minecraft/mc-mods/waterframes/files/7129275)
- WATERFrAMES features per 9Minecraft: "using URLs, powered by WATERMeDIA", "Frames and projectors ... standard and large TVs", "Remote Control ... /waterframes", "Local files ... water://local/<path>", "YouTube, Twitch, Kick.com, Google Drive, OneDrive, Twitter (X)", "rotation, transparency, brightness", "audio distance", dependencies "CreativeCore ... WaterMedia" — [Source](https://www.9minecraft.net/waterframes-mod)
- WATERMeDIA implies VLC-based playback (WATERFrAMES FAQ: block shows "Failed to Load VLC"), distinct from Chromium — [Source](https://www.modpackindex.com/mod/43012/waterframes-multimedia-displays)

### Inferences
- MCEF's split (tiny mod jar + large per-platform native download) is the dominant cost: expect ~120–170 MB first-run download, firewall/host allowlisting for `mcef-download.cinemamod.com`, antivirus false positives, and glibc/X11 system-lib prerequisites on Linux servers.
- Upstream CinemaMod MCEF looks stalled (Jan 2025, Chromium 116 from ~2023) while CCBlueX (Chromium 122, 26.x branches) and Stonecutter/multiversion forks are fresher; pinning to a fork + mirrored natives (e.g. Keksuccino) reduces availability risk.
- WebDisplays model = per-client browser on blocks; WDT model = one server browser + video stream (consistent shared view + login sharing, but server CPU/bandwidth heavy and no macOS server build). Neither targets HUD overlays out of the box; both need adaptation to draw to HUD/screen quads.
- WATERFrAMES/WATERMeDIA (VLC) is lighter for plain image/video/GIF display and far more popular (6.5M vs 2.4M), but All Rights Reserved + survival/block-oriented design + dropped Fabric maintenance make it unsuitable as a reusable library.

### Gaps
- No reliable source found for JxBrowser or Ultralight Minecraft integrations; if they exist they are not discoverable via the queries used.
- Exact extracted CEF disk footprint and RAM/CPU per browser instance were not found; only archive sizes (~111–168 MB) and "~100–200 MB"/"~120 MB" statements.
- MCEF 26.x official support status as of Oct 2026 could not be confirmed from upstream; only forks show 26.x branches.
- Whether MCEF can render directly into a Fabric `HudElement` (offscreen texture → HUD quad) vs only Screen/block entities was not verified in the tool budget.

## Any server-driven UI protocols (server sends JSON/pages, client renders) similar to declarative UI over plugin messages?

### Takeaway
No Java Edition open-source equivalent to a server-sent JSON-page UI protocol was found; the closest analogues are sgui/ServUI (server drives vanilla container slots, no custom rendering), WebDisplays miniserv/URL sync and WDT server-rendered video (server drives browser content, not HUD JSON), and Bedrock's server-ui/DDUI forms (server drives native dialogs, Bedrock-only). A custom plugin-message JSON protocol with vanilla fallback remains the gap MineUI-style systems fill.

### Cited Findings
- sgui is explicitly "server side guis" built on vanilla screens: `SimpleGUI` classes, jij-able, Fabric — [Source](https://github.com/Patbox/sgui)
- ServUI (CurseForge `servgui`) is "a port of the SGui Library ... small, jij-able library that allows creation of server side guis", LGPLv3, Fabric + NeoForge, 960 downloads, "Last Update Jan 6, 2026", dependency `co.lemee:servui:1.9.1+1.21.5-neoforge` — [Source](https://www.curseforge.com/minecraft/mc-mods/servgui)
- WebDisplaysTogether server model: "One shared browser per screen. The page runs in a headless Chromium instance on the server. Every player sees the identical frame ... Clients only decode and display, and forward mouse/keyboard input back" — [Source](https://github.com/He-Horatio/WebDisplaysTogether/blob/main/README.md)
- WebDisplaysTogether config: `webdisplaystogether-common.toml` (screen size/resolution caps, stream fps/bitrate/height, concurrent browser limit, idle timeout, incognito, URL blacklist, home page, Chromium switches, miniserv port/quota) + `webdisplaystogether-client.toml` (render distance, MinePad resolution, music ducking) — [Source](https://github.com/He-Horatio/WebDisplaysTogether/blob/main/README.md)
- Original WebDisplays/MCEF supports `mod://modname/file.html => /assets/modname/html/file.html` embedded files plus `runJavaScript` / `IJSQueryHandler` bridges — [Source](https://github.com/montoyo/mcef)
- Bedrock `@minecraft/server-ui` provides `ActionFormData` (button lists with captions+images), `MessageFormData` (two-button Yes/No, OK/Cancel), `ModalFormData` (questionnaire controls) — [Source](https://learn.microsoft.com/en-us/minecraft/creator/documents/scripting/intro-to-ddui)
- Bedrock DDUI unifies these into `CustomForm` + `MessageBox` with `Observable` state and "dynamic, real-time UI updates without rebuilding forms" — [Source](https://learn.microsoft.com/en-us/minecraft/creator/documents/scripting/intro-to-ddui)
- owo-ui XML is client-authored layout with dev-mode file hotswap, not a server→client page protocol — [Source](https://docs.wispforest.io/owo/ui/)

### Inferences
- Java server-driven UI today is bimodal: lowest-common-denominator (sgui containers work with vanilla clients) vs full-client-mod (custom JSON/rendering). Nothing found in between (e.g. standardized JSON pages over plugin messages) as reusable OSS.
- WDT's "server renders, client decodes video" is the strongest server-authoritative web-content pattern, but it trades interactivity latency and server cost for pixel-perfect sync — overkill for static HUD images, relevant only for synchronized video.
- Bedrock DDUI's Observable/custom-form model is a useful design reference (server-held state, incremental updates, player-bound forms) but is Bedrock-scripting-only and not portable to Java plugin channels.

### Gaps
- No Java plugin-message JSON UI framework (MineUI-like) was found in search results; targeted queries ("server driven UI mod plugin messages") returned Bedrock docs and chat-message plugins instead.
- Whether any WebDisplays fork exposes a server-to-client URL/command packet API reusable for HUD pages was not verified.
- Vanilla fallback options for image HUDs without a client mod (maps, boss bars, scoreboard, chat images via fonts) were out of scope and not researched.

## For a project that already has its own declarative JSON UI with remote-image support, what is worth reusing vs duplicating?

### Takeaway
Keep the custom JSON protocol, layout, and remote-image cache; reuse only Fabric's HudElementRegistry for HUD layering and MCEF (a maintained fork) strictly as an opt-in video/HTML renderer; study sgui for vanilla fallback and WDT/WebDisplays for browser-to-texture patterns; do not adopt owo-ui, LibGui, Puzzles Lib, or WATERFrAMES as foundations.

### Cited Findings
- Fabric Hud API layering (`HudElementRegistry.attachElementBefore/After` + `VanillaHudElements` order/conditions) is the supported way to anchor HUD content without fighting vanilla draw order — [Source](https://docs.fabricmc.net/develop/rendering/hud)
- Deprecated `HudRenderCallback` warning ("extremely limited ... strongly discouraged" since Fabric API 0.116) means new HUD code should target `HudElementRegistry`, not copy old HUD-render snippets — [Source](https://github.com/FabricMC/fabric-docs/blob/main/versions/1.21.8/develop/rendering/hud.md)
- owo-lib MIT license permits reuse, but its value (XML authoring, client Screens, config, networking, registration) overlaps rather than extends a server-driven JSON system; adding it forces Fabric/Quilt/NeoForge + owo-sentinel dependency for little HUD gain — [Source](https://github.com/wisp-forest/owo-lib)
- LibGui MIT license likewise permits reuse, but its domain is logical container GUIs hung on itemslot grids, not HUD overlays or remote images — [Source](https://github.com/CottonMC/LibGui)
- sgui LGPL-3.0 permits library use with jij/include (`eu.pb4:sgui:[TAG]`), establishing the pattern of degrading to vanilla containers when the client mod is absent — [Source](https://github.com/Patbox/sgui)
- MCEF LGPL-2.1: "as long as your project doesn't modify or include MCEF source code, you can choose a different license" — [Source](https://github.com/CinemaMod/mcef)
- MCEF cost facts restated: ~219 KB mod jar vs ~111–168 MB per-platform native archives, Win/macOS/Linux desktop only, no Android, AV/host allowlisting risks — [Source](https://www.curseforge.com/minecraft/mc-mods/mcef/files/all); [Source](https://github.com/Keksuccino/mcef_resources/releases); [Source](https://github.com/CinemaMod/mcef)
- WDT's own-code MIT + third-party NOTICE (FFmpeg LGPL-2.1+, JavaCPP Apache-2.0) shows the compliance shape of bundling video natives, a precedent if MineDisplay ever bundles FFmpeg/JavaCPP — [Source](https://github.com/He-Horatio/WebDisplaysTogether/blob/main/README.md)
- WATERFrAMES All Rights Reserved blocks code reuse despite its relevant feature set (URL images/video, `water://local`, whitelist, brightness/transparency/rotation, remote control) — [Source](https://www.curseforge.com/minecraft/mc-mods/waterframes/files/7129275)
- Puzzles Lib "not intended as a general-purpose library for other mod developers" — direct evidence against adopting it for UI — [Source](https://www.curseforge.com/minecraft/mc-mods/puzzles-lib)

### Inferences
- Worth reusing: (1) Fabric `HudElementRegistry`/`VanillaHudElements` for HUD anchor points and F1/`hideGui` behavior; (2) MCEF fork (CCBlueX/Stonecutter line) behind an optional video/HTML widget that renders to a texture the existing JSON layout positions — never as the layout system; (3) sgui-style vanilla-container fallback and URL-whitelist/permission patterns from WATERFrAMES/WDT as design references.
- Worth keeping custom (duplicate, not adopt): JSON page protocol over plugin messages, layout/anchor engine, async remote-image loader with disk/memory cache + placeholder/error states, and Paper-side API — none of the surveyed OSS provides this combination, and adopting owo-ui/LibGui would add a second layout paradigm plus loader lock-in.
- Video/HTML should stay opt-in and isolated: gate MCEF behind config + platform check, pin a Chromium/commit + mirror natives, surface download progress and failure states, and keep static-image HUDs on the lightweight `NativeImage` path so most users never pay the ~120 MB CEF cost.
- License-compliant shape: depend on MCEF as an external mod (LGPL dynamic-use), keep proprietary/JSON code separate and unmodified; do not copy WATERFrAMES code (ARR); MIT sources (owo, LibGui, WebDisplays, WDT own code) allow pattern-borrowing with attribution but do not justify the dependency weight.

### Gaps
- No head-to-head measurement of `NativeImage` async download vs CEF offscreen texture vs VLC frame upload (VRAM, frame time, GC) was found; performance guidance above is architectural reasoning, not benchmarked.
- The exact MCEF offscreen-render → Minecraft-texture API surface to wrap for a HUD widget was not extracted (needs `MCEF`/`java-cef` Javadoc + example-mod fetch).
- Long-term maintenance risk of pinning to a community MCEF fork (CCBlueX vs CinemaMod upstream) needs a commit-history/version-support check beyond the README-level evidence gathered here.
