# TripSplit

TripSplit records the shared expenses of a group on holiday and shows who owes whom.

- It works fully offline. There is no server and no account.
- Phones sync with each other over the same Wi-Fi network, a phone hotspot, or Bluetooth.
- Every member can add and edit expenses. A person can join the group later and gets the full history.
- iOS (SwiftUI) and Android (Jetpack Compose) are two native apps. They share one protocol contract in
  [`protocol/`](protocol/), with golden test vectors that both apps must pass.

Read the full design in [docs/PLAN.md](docs/PLAN.md).

## Security note

The group invite (QR code) holds the group key. Anyone who has the invite can read and change the
group. Show the QR code only to the people in your group.
