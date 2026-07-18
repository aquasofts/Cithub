# Version History

This file is the canonical record of the app version. Keep it synchronized with the default
`versionName` and `versionCode` in `app/build.gradle.kts`.

## Current version

- Version name: `2.1.30`
- Version code: `36`
- Updated: 2026-07-18

## Changes

### 2.1.30 (versionCode 36) — 2026-07-18

- Fixed the GitHub Actions release pipeline so version tags build the signed Full and Lite APKs and publish them automatically as a verified pre-release with SHA-256 checksums.

### 2.1.29 (versionCode 35) — 2026-07-18

- Refreshed the installable Performance APKs from the current Navigation 3 and Tieba image-saving source state.

### 2.1.28 (versionCode 34) — 2026-07-18

- Migrated every Compose navigation stack to stable Navigation 3 `1.1.4` with serializable typed keys, saved back stacks, unified forward/back animations, and predictive-back transitions.
- Added long-press saving for the native Tieba original-image viewer, with a confirmation dialog, progress/result feedback, and system-gallery storage under `Pictures/CCIT Academic`.
- Preserved in-page WebView history while allowing Navigation 3 to own normal and predictive system back gestures once the WebView has no page to return to.

### 2.1.27 (versionCode 33) — 2026-07-18

- Removed the custom partial-screen Tieba slide transitions and delegated page animation and predictive back to Navigation Compose 2.9.8.
- Enabled Navigation Compose's `launchSingleTop` option for native Tieba destinations so rapid repeated taps do not stack duplicate full-screen pages and animations.

### 2.1.26 (versionCode 32) — 2026-07-18

- Shortened app page transitions and added a fast, clipped horizontal transition for native Tieba detail pages.
- Rebuilt nested-reply pages to match TiebaLite: the title identifies the source floor, the source floor is rendered as the parent body, and the complete reply count precedes the reply list.
- Fixed dynamic client emoticons such as `#(突然兴奋)` by validating both Tieba emoticon ID families and using TiebaLite's HTTP asset endpoint through a domain-scoped cleartext exception.
- Made the native Tieba home forum configurable and kept forum loading, search, follow, and automatic sign-in aligned with the selected forum.

### 2.1.25 (versionCode 31) — 2026-07-18

- Matched TiebaLite's forum action flow: when FRS reports that the account has not joined the forum, the action now follows the forum through the signed Mini client request before sign-in is offered.
- Made the HTTPS web sign fallback tolerate Baidu returning `data` as a string and preserve service error `1011` instead of replacing it with a Gson parsing exception.
- Extended sign diagnostics and regression tests to cover the exact follow request fields, signature, and the real `1011` response captured from the device.

### 2.1.24 (versionCode 30) — 2026-07-17

- Fixed manual Tieba sign-in to submit the displayed FRS forum ID, forum name, and fresh `anti.tbs` through the TiebaLite-compatible mobile request.
- Added an HTTPS web sign-in fallback for mobile service error `300004`, while keeping credentials off plaintext HTTP.
- Added rotating, redacted sign-in diagnostics with copy and clear actions in Tieba settings for future failure analysis.

### 2.1.23 (versionCode 29) — 2026-07-17

- Replaced the previous speculative pre-sign refresh chain with TiebaLite's actual split flow: manual sign-in submits the displayed FRS `anti.tbs`, while automatic sign-in refreshes the account and submits the `/c/s/login` TBS.
- Removed the non-upstream `client_user_token` header from the V12 FRS request and stopped logged-in FRS failures from silently retrying as anonymous responses with unusable sign credentials.
- Added regression coverage for both TiebaLite sign entry points and for the exact FRS header set.

### 2.1.22 (versionCode 28) — 2026-07-17

- Unified manual and automatic Tieba sign-in so every attempt refreshes and persists the official account before requesting an authenticated FRS TBS.
- Removed the forum-page TBS from the manual sign-in API and prohibited anonymous FRS fallback for all sign-in writes.
- Added credential-free stage-specific failures for official login, authenticated FRS, and sign submission, including the exact stage for error `300004`.

### 2.1.21 (versionCode 27) — 2026-07-17

- Fixed Performance-build Tieba login failing before official authentication because R8 optimized the Gson-reflected Sofire ZID response into an abstract class.
- Preserved the complete Sofire request and encrypted-response model family so minified builds can obtain and decode `z_id` reliably.

### 2.1.20 (versionCode 26) — 2026-07-17

- Refreshed the installable Performance packages with the current TiebaLite-compatible official login, Sofire ZID, FRS identity, and forum sign-in fixes.

### 2.1.19 (versionCode 25) — 2026-07-17

- Matched TiebaLite's `MD5Util` exactly by emitting uppercase hexadecimal request signatures; the previous lowercase signature was accepted by login but rejected by the forum sign endpoint with error `300004`.
- Added an independent uppercase MD5 test vector and a final-form assertion so request tests can no longer reproduce the same casing bug as the implementation.
- Copied TiebaLite's Sofire x6 ZID handshake and now persist the returned `z_id` before official login and authenticated FRS requests.
- Matched TiebaLite's V12 FRS identity fields exactly, including its IMEI fallback, Base64 mode, random fallback client ID, and cookie wire format.
- Manual forum sign-in now passes the current FRS `anti.tbs` straight to TiebaLite's V11 `signFlow`; automatic sign-in uses the refreshed official account TBS.

### 2.1.18 (versionCode 24) — 2026-07-17

- Fixed successful official Tieba logins being rejected when `/c/s/login` returns an empty `user.name`, matching TiebaLite's error-code-only acceptance instead of imposing a stricter custom field check.
- Account hydration now resolves the username from `/c/s/initNickname` and the already authenticated web profile when the login payload omits it, while still requiring the fresh official `anti.tbs`.
- Added a regression test for the real code-0 response shape with a blank login username.

### 2.1.17 (versionCode 23) — 2026-07-17

- Fixed Performance-build Tieba login failure by preserving `TiebaOfficialApi` suspend signatures and all Gson-reflected official login, nickname, sign, sync, and common response models from unsafe R8 full-mode optimization.
- Added post-minification verification of the official Retrofit contract and concrete response types alongside the existing sign-in protocol tests.

### 2.1.16 (versionCode 22) — 2026-07-17

- Ported TiebaLite's official `/c/s/login` and `/c/s/initNickname` account-hydration sequence, including its FormBody drop-parameter behavior, so WebView cookies are validated as an official mobile session before sign-in.
- Manual and automatic sign-in now refresh the official UID/TBS state and an authenticated FRS response immediately before calling `/c/c/forum/sign`, preventing anonymous page fallbacks from supplying write credentials.
- Added stage-specific, credential-free diagnostics for official-login rejection such as Tieba error `300004`.

### 2.1.15 (versionCode 21) — 2026-07-17

- Unified FRS and official sign-in under the same persisted TiebaLite CUID, AID, client ID, sample ID, and client timestamps so `anti.tbs` is consumed by the identity that requested it.
- Included the numeric Tieba service code in unknown sign-in errors to make any remaining server rejection diagnosable without logging credentials or signatures.

### 2.1.14 (versionCode 20) — 2026-07-17

- Ported TiebaLite's Retrofit sign-in API and ordered FormBody interceptor chain instead of maintaining a hand-built request.
- Made manual and automatic sign-in consume the fresh `data.anti.tbs` from the current FRS response, without using the web profile endpoint or the account database's stale TBS.
- Persisted the TiebaLite client identity and sync state, and now reject nominal code-0 responses that omit the complete sign-in user information.

### 2.1.13 (versionCode 19) — 2026-07-17

- Aligned the single-forum Tieba check-in request with TiebaLite's final V11 request fields, headers, ST anti-abuse parameters, encoding, and signature.
- Preserved consecutive signed-day display and fixed alternate Tieba error-code parsing so server errors are no longer reported as code 0.

### 2.1.12 (versionCode 18) — 2026-07-17

- Renamed the Gradle project, launcher label, and in-app product branding to Cithub while preserving the existing application ID and user data.
- Updated documentation, release artifact names, and news request identifiers to the Cithub name.

### 2.1.11 (versionCode 17) — 2026-07-17

- Removed the redundant “来自××” forum-name label beneath user names in Tieba thread floors while preserving time, floor number, badges, and IP location.
- Simplified the Tieba long-press menu to Reply and Copy by removing Report and Collect This Floor.

### 2.1.10 (versionCode 16) — 2026-07-17

- Changed Tieba reply actions to launch the official Baidu Tieba client directly with TiebaLite's exact thread/floor dispatch URI.
- Removed installed-app and intent-handler discovery from the reply launch path to avoid Android package-visibility false negatives.

### 2.1.9 (versionCode 15) — 2026-07-17

- Replaced Tieba floor long-press actions with an anchored animated popup menu matching the supplied reference.
- Added a fully in-app TiebaLite-compatible reply flow, including emoji, original-image selection, and up to nine uploaded images, without checking for the official Tieba app.
- Added persistent disk caching for news list and article images, plus stable shimmer and reveal transitions while article images load.

### 2.1.8 (versionCode 14) — 2026-07-17

- Reordered the main navigation to Tieba, News, Academic, and Mine, with Tieba opening first.
- Moved article titles into the reader body, added section labels, removed official-account covers, aligned all official-news dates, and replaced eager image prefetching with lazy loading, shimmer transitions, and aspect-ratio-safe rendering.
- Made Tieba rules interactive and added thread-list emoji rendering, IP locations, manager badges, and long-press Copy/Reply actions that launch TiebaLite's official-client reply route.
- Replaced web sign-in with TiebaLite's signed official-client protocol and display the server-returned consecutive sign-in days after success.

### 2.1.7 (versionCode 13) — 2026-07-17

- Fixed blank in-app article readers across official accounts, campus news, and official news, and improved RSS body-image normalization and alignment.
- Replaced infrastructure feed labels with per-article official-account names from RSS metadata and added disk-backed prefetching for the next 20 official-news cover images.
- Reworked Tieba rules and pinned threads into compact scrolling rows, excluded the first floor from displayed reply totals, and added in-thread author titles and levels.

### 2.1.6 (versionCode 12) — 2026-07-17

- Added an Official News home section that concurrently aggregates School News, Notices, and Academic News from the official CCIT portal and sorts all entries newest-first.
- Added in-app official-news detail loading with responsive rich text, HTTPS image normalization, cached list fallback, and source-aware metadata.
- Improved home-feed image loading with stable skeleton placeholders, short cache-friendly crossfades, reduced-motion support, and explicit failure states.

### 2.1.5 (versionCode 11) — 2026-07-17

- Replaced the hand-written feed field matcher with RSS Parser 6.1.7 and added consistent RSS 0.9x/2.0, RSS 1.0/RDF, Atom, Media RSS, enclosure, iTunes, and common namespace mapping.
- Added BOM, HTTP charset, XML declaration, response-size, HTTPS, DOCTYPE, structured per-source failure, and normalized Unicode cache handling.
- Added deterministic placeholders for incomplete or interactive feed content, with user-initiated original-page loading, timeout, retry, and browser fallback states.
- Added regression fixtures for the two supplied feeds and compatibility coverage for alternate XML feed formats, encodings, partial failures, and cache fallback.
- Changed the default official-account subscription to the single Cloudflare RSS Hub feed and kept article ordering newest-first even with one source.

### 2.1.4 (versionCode 10) — 2026-07-16

- Fixed RSS article details appearing blank or remaining indefinitely in a loading state, especially with dark theme enabled.
- Added regression coverage for the live campus-news RSS structure and theme-aware article rendering.

### 2.1.3 (versionCode 9) — 2026-07-16

- Added separate, editable multi-source RSS lists for official accounts and campus news, with the two supplied official-account feeds and campus-news feed as defaults.
- Merged and deduplicated articles from all configured feeds in newest-first publication order.
- Improved RSS and Atom compatibility, HTTPS redirect handling, relative-link resolution, media covers, date parsing, and cached fallback behavior.

### 2.1.2 (versionCode 8) — 2026-07-16

- Added a default home tab that aggregates two official-account feeds and campus news with source attribution and cached refresh.
- Added an in-app rich-content reader with image support and safe fallback to original interactive articles.
- Replaced the campus Tieba toolbar refresh action with animated pull-to-refresh.
- Expanded the bottom navigation to Home, Tieba, Academic, and Mine with narrow-screen-safe sizing.

### 2.1.1 (versionCode 7) — 2026-07-16

- Removed the app-wide WebVPN login gate so Tieba and settings remain available while signed out.
- Required an active WebVPN session before showing the academic-system login.
- Shortened the bottom navigation label from “教务系统” to “教务”.

### 2.1.0 (versionCode 6) — 2026-07-16

- Reworked the app interface, navigation motion, settings, and performance build pipeline.
- Added separate automatic-captcha and manual-captcha product flavors.
- Expanded native academic-system pages and WebVPN protocol handling.
- Added the Changchun Institute of Technology campus Tieba experience, including forum, thread, profile, search, floor replies, and sign-in support.
- Added the branded “长春工程学院 / 校园贴吧” thread header.
- Fixed original-image viewing by following TiebaLite's signed `/c/f/pb/picpage` flow and rejecting the 238×238 Tieba placeholder.

### 1.1.0 (tag `V1.1.0`) — historical

- Updated project documentation and screenshots.
- Consolidated repository guidance and removed the obsolete standalone web-test workspace.

### 1.0.0 (tag `V1.0.0`) — historical

- Established the first tagged production version line.
- Earlier detailed change notes were not preserved; this entry records the existing repository tag without inventing missing release details.
