# Protocol notes

Answers found by reading `vendor/gliff` (vendored commit of 2026-10-12). The
gliff code is authoritative; these notes say where to look.

## Open points of the plan

1. **Keycodes: evdev, without +8.** `ClientMsg::Key.keycode` is a Linux evdev
   code (`KEY_A` = 30). The GTK client sends `keycode - 8`
   (`crates/gliff/src/main.rs`, "hardware keycode minus 8 is the evdev code")
   and `hypr-input` documents it (`lib.rs`: "an evdev keycode (xkb keycode
   minus 8)"). Android's `KeyEvent.getScanCode()` is therefore sent as is.
   Buttons: evdev `BTN_*` codes (left `0x110`, right `0x111`, middle `0x112`,
   back `0x116`, forward `0x115`).
2. **`extradata` is empty.** `stream_config()` in
   `crates/gliff-server/src/session.rs` always sends `extradata: []` and
   `aux_extradata: None`: SPS and PPS travel in the stream, in front of every
   IDR. Video payloads are **Annex B** (start codes `00 00 00 01`,
   `crates/gliff-va/src/h264/annexb.rs`). For `MediaCodec`: do not expect
   `csd-0`/`csd-1` from the `StreamConfig`, pass the access unit as is (or
   extract SPS/PPS from the first IDR). High profile, CABAC, a single
   reference, no B-frames.
3. **No `Resize` expected before the first `StreamConfig`.** With `--headless`
   the server creates the output at `min(caps.max_width, 1920) x
   min(caps.max_height, 1080)`, scale 1, and sends `HelloAck`, `Ping`,
   `StreamConfig` without waiting for anything. The client's `Resize` arrives
   afterwards and causes a second `StreamConfig`. Details of `resize()`: width
   and height are clamped by the caps and rounded down to an even number, the
   scale is clamped to [0.5, 4], and a size under 320x240 is **silently
   ignored**. In mirror mode, `Resize` does not change the output: the server
   uses it to fit the stream size to the window (`fit_mirror`).
4. **`--headless` and `--output`: arguments of `gliff-server` only**
   (`crates/gliff-server/src/main.rs`), mutually exclusive. Without either,
   the server takes `--output auto` (the focused output). The GTK client
   always passes one of the three: `--output NAME`, `--headless`, or
   `--output auto`.
5. **Maximum sizes** (`crates/gliff-proto/src/frame.rs`): CBOR body 1 MiB,
   one payload 64 MiB, total payload of a frame 128 MiB. The writer refuses to
   emit beyond that, the reader fails when reading the header, and in both
   cases the connection stops: there is no resynchronisation. A clipboard
   chunk is additionally limited to 256 KiB.

## What the reading taught in addition

- **Frame**: `u32 body_len | u32 payload_len` in little-endian, then the CBOR
  body, then the payload. This layout never changes.
- **Encoding** (minicbor): integers in their shortest form, `f64` always on
  8 bytes, `f32` (the `scale` of `Resize`) on 4. An absent optional field
  **at the end** of a message is not sent at all; in the middle, it is `null`.
  A variant without fields carries an empty array (`RequestKeyframe` =
  `820780`).
- **Handshake**: the `Ping` is sent right after `HelloAck`, without waiting.
  The client answers `Pong` before any other message; `Session` queues what
  is sent before the end of the handshake.
- **`FrameAck`**: the reference client acknowledges as soon as the decoder has
  taken the access unit, with the wall-clock time in milliseconds. The server
  bounds the bytes in flight with these acks.
- **`StreamConfig` change**: recreate the decoder only if
  `(width, height, chroma)` changes. A change of `fps_cap`, of `view_*` or of
  `scale_milli` alone does not touch the decoder.
- **Pointer**: position in the remote logical space, that is stream pixels
  divided by `scale_milli / 1000`. A reduced stream is first stretched to
  `view_width x view_height`: see `to_remote()` in
  `crates/gliff/src/main.rs`.
- **Wheel**: the GTK client sends `value = delta * 15` and
  `discrete = sign(delta)`, `stop = false`.
- **Keys**: no repeat on the client side (the remote compositor repeats on its
  own), and all held keys are released when focus is lost.
- **`Resize`**: the GTK client compares the size of its window to the server's
  `view_*`, not to the stream size, so as not to fight the quality scale. It
  sends nothing under 64 px.
- **Errors**: codes 1 to 255 = incompatibility (1 version, 2 chroma,
  3 codec), no point in retrying.

- **`HelloAck.outputs` lists only the output being served**, not all those of
  the machine (`session.rs` puts a single element in it). So an output choice
  cannot be offered from the handshake: in mirror mode the app asks for the
  output name (`DP-1`...) in the machine's entry, empty for the focused one.
- **`Resize` in mirror mode**: the reference client sends it too, and the
  server uses it to fit the stream size to the window. The app therefore
  sends it in both modes, contrary to what the plan assumed.

## Choices made on the Android side

- **Ack at the decoder's output.** The frame identifier travels as the
  presentation timestamp in `MediaCodec`; the ack leaves when the output
  buffer is rendered. A dropped frame (no surface, waiting for an IDR, codec
  replaced) is acknowledged right away, otherwise the server would stop
  emitting.
- **No `csd`.** The codec is configured with the size alone; it starts on the
  first IDR, which carries SPS and PPS. As long as no IDR has gone through,
  frames are dropped and a `RequestKeyframe` is sent (at most one per 250 ms).
- **`caps.max_width/height`**: the largest side that the device's AVC decoders
  accept, capped at 4096, advertised as a square so that a phone held upright
  is not limited in height.
- **First stream not decoded.** The server streams before it reads the
  `Resize` sent with the handshake (checked with gliff-server 0.4.0: a mirror
  always starts at its output's own size, a keyframe included, then follows
  the window). When a `Resize` went out before the first `StreamConfig`, that
  stream is acked without being decoded, and the decoder starts on the next
  one. If none comes within 500 ms, a keyframe is asked for and the first
  stream is taken after all. `probe --window WxH` shows the sequence.
- **`Resize.scale`**: the screen density (`density`), which the server clamps
  to [0.5, 4]. The remote desktop is therefore rendered at the device's DPI.
- **SSH key**: Ed25519 generated by BouncyCastle, stored as PKCS#8 in
  `noBackupFilesDir`. It is not encrypted by the Android Keystore (Ed25519
  exists there only from API 33): to be hardened later.
- **Remote command**: `exec sh -c 'export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"; exec gliff-server --stdio ...'`.
  Tailscale SSH does not open a PAM session, so `XDG_RUNTIME_DIR` is absent
  and `gliff-server` fails with "find Hyprland instance". The reference
  client lets the user pass `--server-bin 'env XDG_RUNTIME_DIR=...
  gliff-server'`; here the default value is always set. As in the reference,
  the server path is a shell fragment, not a quoted argument.
- **Rewritten SPS**: the server writes no VUI. `MediaCodec` then holds back up
  to 16 pictures (level 5.1) before outputting one, and an idle desktop only
  sends three: black screen. `H264.withoutReordering` adds to the SPS of each
  keyframe a VUI saying "zero reordering, BT.709 limited". The reference
  client does not have this problem: it drives VA-API itself and reads each
  picture as soon as it is decoded.
- **Virtual keyboard**: without a keymap in `Hello`, the server takes the US
  layout (`KeyState::default_us`). This is the fallback when the remote's own
  keymap cannot be read; see the next point.
- **Keyboard layout**: Hyprland resolves its shortcuts with the layout of its
  configuration (`input:kb_layout`), whatever the virtual keyboard's keymap
  is. On a machine in bépo, the "W" key of a US keyboard is "é": Super+W
  closed nothing and the application received a "w". The ssh connection
  therefore first reads the machine's keymap (`hyprctl getoption input:kb_*`
  compiled by `xkbcli compile-keymap`), the client sends it back in `Hello`
  and types with it (`KeyLayout`). Accents come with it. Dead keys are not
  composed yet.
- **Pointer**: the cursor is not in the video (`CursorShape` and `CursorPos`
  are separate). In trackpad mode the app draws its own arrow at the position
  it sends; the remote shape is not displayed yet.
- **Touch scrolling**: `PointerAxis` without `discrete` ("finger" source on
  the server side), then `stop` when the fingers lift.
- **Scale**: Hyprland only keeps a scale that is a multiple of 1/120 and
  divides both sides into whole logical pixels, and replaces the others (1.8
  on 1080x1046 became 2). `remoteMode` therefore picks a nearby valid scale
  and trims the size by a few pixels.
- **Web mirror**: `MirrorServer` (port 8765, option off by default) serves the
  foreground session as fragmented MP4, played by Media Source in the
  browser. WebCodecs would have been more direct but requires HTTPS. The page
  is under `/<token>/`; a viewer starts on a keyframe, which the app requests
  from the server when the viewer arrives. Read-only unless the "control from
  the browser" option is on: the page then posts its mouse and keyboard events
  to `/<token>/input` (`BrowserInput`).
- **Fixed resolution**: instead of a display scale, which the server floors
  at 0.5, the options (and each machine, over them) can hold the remote to a
  resolution: a `Resize` of that size, turned the way of the window, at scale
  1. Larger than the screen, the picture shows more, fitted in it. It also
  holds for the web mirror's viewer. Without one, the remote follows the
  window at the device's density, as before.
- **Web mirror, size**: the page posts its window to `/<token>/view`
  (`WIDTH HEIGHT SCALE`, device pixels and `devicePixelRatio`), and the
  session in front sends that as its `Resize` instead of the device's window,
  until no viewer has been there for 3 s. The browser's zoom changes
  `devicePixelRatio`, hence the remote's scale. The new `StreamConfig` drops
  the viewers, who reconnect within the grace time.
- **Web mirror, as an application**: the page has a manifest and, in its row
  of keys, a full screen button that calls `navigator.keyboard.lock()`. Chromium only, and
  only on a secure origin: `http://localhost` through a forwarded port, or
  the address listed in `chrome://flags/#unsafely-treat-insecure-origin-as-secure`.
  Serving HTTPS from the device would need a certificate browsers trust.
- **Web mirror, keys and other screens**: with control on, the page has a
  row of keys (Esc, Tab, and Ctrl, Alt, Super armed for the next key), for
  what the viewer's machine would keep; it snaps to an edge of the window and
  shows only with a session.
- **Web mirror, casting**: the page does not offer it. The Presentation API
  finds no screen for a plain page (a Chromecast only opens a receiver
  application), Remote Playback does not take a Media Source video, and the
  stock Cast receiver given the address of the stream loaded for ever. The
  browser's own "cast tab" mirrors the page as it is.
- **Web mirror, dark screen**: an application cannot turn the screen off and
  keep running in front. The option draws black at the lowest brightness
  instead, which lights nothing on an OLED. Letting the device really sleep
  needs a foreground service and a wake lock: not done.
- **Clipboard**: text only. A copy on the device is announced with `Offer`
  and served when the remote pastes, as in the reference. A copy on the
  remote is fetched at once, because an Android clipboard holds text, not a
  promise of text, and cannot be filled from the background (`ClipboardSync`).
  Requests are numbered odd on the client; at most 1 MiB of text is taken.

## Verified against a real server

`gliff-server` 0.3.1 (Omarchy, edge channel), Radeon 890M, GPU pipeline, over
Tailscale SSH:

- mirror: `HelloAck` on `DP-2`, 2560x1440 H.264 High 4:2:0 stream, decoded by
  ffmpeg;
- headless: output created at 1920x1080, then `Resize(1080, 2200, 2.0)` gives
  a new `StreamConfig` at 1080x2160 (height rounded by the server).

An idle screen produces only a few pictures (the keyframe, then its
refinements): zero pictures per second is then normal.

## Checking the encodings

The vectors of `encodings_are_stable()` are ported into `MessagesTest`.
`ReferenceVectorsTest` adds the messages that this test does not cover
(`Resize`, `PointerAxis`, clipboard, cursor...): their bytes were printed by a
small Rust binary linked against `vendor/gliff/crates/gliff-proto`.
