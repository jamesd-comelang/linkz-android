# Linkz — WeChat-style UI/UX plan

## Goal

Use bitchat as the base and redo the UI/UX in WeChat style, keeping
bitchat's mesh engine untouched.

- Base: `jamesd-comelang/linkz-android`, a fork of
  `permissionlesstech/bitchat-android` (Android, Kotlin, Jetpack Compose).
- Product name: Linkz.
- Principle: replace only the presentation layer in v1. Do not change
  the packet format, Noise encryption, relay logic, or transport
  selection, so Linkz stays compatible with other bitchat peers.

## Guardrail: WeChat-style, not a WeChat copy

Build the familiar layout, tab structure, and interaction patterns
with original icons, artwork, and branding. Do not copy WeChat's logo,
trademarked assets, or pixel-for-pixel artwork, and do not present
Linkz as WeChat.

## The core tension

Bitchat is peer-to-peer over Bluetooth mesh, with Nostr as the internet
fallback, and needs no accounts, phone numbers, or central servers.
WeChat assumes an account, a contact list, and persistent history.

V1 therefore keeps bitchat's no-account identity (nickname + Noise key
fingerprint) and maps WeChat's structure onto local, mesh-derived data,
instead of inventing a server backend.

## Screen mapping

- **Chats:** existing DMs, channels, and geohash conversations become
  one recent-chat list, sorted by latest message, with unread badges
  and search. Data comes from the existing `ChatViewModel` /
  `ConversationSummary`, restyled, not reimplemented.
- **Contacts:** favorites + verified peers become a local contact book.
  Add by QR code or fingerprint verification, with block/mute.
  No server friend requests in v1.
- **Discover:** nearby peers, channels, Scan QR, and mesh status.
  No Moments feed in v1 — there is no server to host one.
- **Me:** nickname, avatar, identity fingerprint, settings, and mesh /
  Nostr / Tor status in plain language. Bitchat extras that do not fit
  WeChat's main tabs — panic wipe, IRC-style commands, and the mesh
  debug graph — move here under Settings, not into the main tabs.

## Phases

### Phase 0 — Baseline and freeze

- Build the current app and screenshot every flow.
- Inventory the UI surface, starting with:
  - `app/src/main/java/com/bitchat/android/ui/ChatScreen.kt`
  - `app/src/main/java/com/bitchat/android/ui/MessageComponents.kt`
  - `app/src/main/java/com/bitchat/android/ui/InputComponents.kt`
  - `app/src/main/java/com/bitchat/android/ui/theme/Theme.kt`
  - `app/src/main/java/com/bitchat/android/ui/theme/BitchatPalette.kt`
  - `app/src/main/java/com/bitchat/android/ui/theme/ChatVisualTokens.kt`
- Add upstream (`permissionlesstech/bitchat-android`) as a remote and
  set a branch strategy before touching UI.
- Exit: a working baseline build, a screenshot set, and a UI inventory.

### Phase 1 — Design system

- One token set: WeChat green `#07C160`, light grey chat background,
  white incoming bubbles, green outgoing bubbles, white tab bar with
  labelled icons.
- Build reusable `ChatRow`, `Avatar`, `Bubble`, `InputBar`, `TopBar`,
  and `SearchBar` components; stop hardcoding colors elsewhere.
- Exit: every new screen is built from tokens and shared components.

### Phase 2 — Shell + Chats

- Four-tab bottom navigation: Chats, Contacts, Discover, Me.
- Restyle the chat list and chat room.
- Input bar with WeChat-style voice, emoji, image, and file actions,
  wired to the existing sending code.
- Exit: the main chat flow works end to end through the new shell,
  with no mesh-logic changes.

### Phase 3 — Contacts + profile

- Restyle the peer detail and verification sheets.
- Turn favorites into contacts; add QR-based adding, block, and mute.
- Exit: a peer can be found, verified, added, messaged, and blocked
  through the Contacts tab.

### Phase 4 — Discover + Me

- Migrate settings, about, and debug sheets into Me.
- Restyle onboarding around nickname and avatar setup; simplify
  permission screens.
- Exit: Discover and Me cover nearby/channels/scan and all settings
  without dead ends.

### Phase 5 — Polish + release

- Dark mode, accessibility, empty and offline states, notifications.
- Existing unit tests plus screenshot tests for the new components.
- Two-phone BLE test and a Nostr-fallback test before any release
  build.
- Exit: a release build that passes the BLE and Nostr checks above.

## Non-goals for v1

- No protocol, packet-format, or encryption changes.
- No server accounts, Moments feed, payments, or mini-programs.
- The Wear module stays untouched.

## Open decisions (needed before coding)

1. Rename the package from `com.bitchat.android` now? Cleaner, but
   harder to merge upstream later.
2. Should chat history be persistent, or stay ephemeral by default?
3. Is Moments a later phase, or out entirely?

## Status

- 2026-10-29: plan drafted and saved as this file.
- 2026-10-29: implementation started on branch `linkz/wechat-ui`
  (local commits only, not pushed):
  - `fb8ac05` Add Linkz WeChat-style UI plan
  - `68cae0e` Add Linkz WeChat-style theme and shell components
    (LinkzTheme, LinkzComponents, LinkzFormatting + unit tests)
  - `660f644` Add WeChat-style 4-tab shell over the Bitchat engine
    (Chats list, Contacts, Discover, Me; ChatScreen kept unchanged
    for timelines, PrivateChatSheet kept for DMs)
- Still to do: WeChat-style bubble recolour in the chat transcript
  (own = solid green, incoming = white) — deferred, not faked: it
  touches `BubbleTextMessageLayout` in MessageComponents.kt and needs
  a full build + on-device check before landing.
- Build verification, local: `assembleDebug` could NOT run in this VM.
  Gradle's daemon TCP dispatch fails in this environment even for an
  empty project, and the stable SDK repo has no platform 37 package
  (build-tools 37 is present). The pure-Kotlin LinkzFormatting module
  compiles cleanly with kotlinc, and the Compose files pass a kotlinc
  syntax check (unresolved Android imports only, as expected without
  the SDK). The real build gate is CI (`android-build.yml`, which runs
  on pushes/PRs to main/develop) after this branch is pushed or PR'd.
