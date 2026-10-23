# Field test (phase 9)

Unit tests, golden vectors and the reference peer prove that the two apps agree byte for byte. They
cannot prove that real radios work. This page lists the tests that only real phones can do.

## What you need

- At least one iPhone (iOS 18 or later) with the TestFlight build.
- At least one Android phone (Android 10 or later) with the release APK. Two Android brands are better,
  because Bluetooth stacks differ.
- A Wi-Fi network that you control, and mobile data for a hotspot.

## Before you start

1. Install both apps. See [RELEASING.md](RELEASING.md).
2. On each phone, set a different display name.
3. On the iPhone, allow "Local Network" and "Bluetooth" when the app asks.
4. On Android, allow "Nearby devices" (Android 12 and later) or "Location" (Android 10 and 11).

For each test, write down: pass or fail, the time it took, and the balances on each phone.

## Tests

### 1. Bluetooth cold join, 500 ops, Wi-Fi off

1. On phone A, make a group and add about 500 ops (for example, use a test script or many expenses).
2. Turn Wi-Fi off on both phones.
3. On phone B, scan the invite QR code.
4. Open the Nearby screen on both phones.

Expected: the join completes in less than 10 minutes. Both phones show the same balances.
Do the test two times: once with the iPhone as the lower deviceId (central), once with Android as the
central. Watch the iPhone send speed: the iPhone must not stay at 20-byte chunks.

### 2. Invite link through a messaging app

1. On phone A, share the invite link through iMessage, WhatsApp and e-mail.
2. On phone B, open the link from each app.

Expected: the app opens the join screen with the group key. Some apps drop the part after `#` from a
link. If the join screen says the link has no key, write down which app did it. Then use the QR code,
which always works.

### 3. Wi-Fi and Bluetooth on at the same time

1. Put both phones on the same Wi-Fi. Keep Bluetooth on.
2. Add an expense on each phone.
3. Open the Nearby screen on both phones.

Expected: each phone shows the other phone one time. Only one sync runs between them (LAN is
preferred). Both phones show the same balances.

### 4. Phone hotspot

1. Make the Android phone a hotspot. Connect the iPhone to it.
2. Add an expense on each phone and open the Nearby screen.
3. Then make the iPhone the hotspot and connect the Android phone.

Expected: the sync works in both directions. If DNS-SD finds nothing, the app tries port 47831 on the
gateway after 5 seconds. Hotel Wi-Fi with client isolation does not work: that is expected.

### 5. A newcomer on day 3

1. On phone A, make a group with members Ali, Ayşe and Mehmet. Add expenses with an equal split, a
   weights split and an exact split.
2. On phone B, join with the QR code and claim "Mehmet".
3. Choose "Also earlier expenses".

Expected: the equal and weights expenses now include Mehmet. The exact expense is listed for a hand
edit and does not change. Both phones show the same balances after a sync.

### 6. Restore and take over

1. On phone B, delete the app and install it again (or restore the phone from a backup).
2. Join the group again with the QR code.
3. Open the claim sheet for your old member and choose "Take over".

Expected: the app explains that the member is linked to another phone. After "Take over", the member is
yours on every phone.

### 7. Three phones, one offline day

1. Use three phones, with both platforms in the group.
2. Turn off all radios for a day. Add, edit and delete expenses on each phone.
3. Turn the radios on and open the Nearby screen.

Expected: all three phones converge to the same balances. This is the phase 9 acceptance test in
[PLAN.md](PLAN.md) §15.

## When something fails

Open an issue with the test number, the two phone models and OS versions, what you saw, and the
balances on each phone. A screenshot of the Nearby screen helps.
