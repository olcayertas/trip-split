---
title: TripSplit privacy policy
---

# TripSplit privacy policy

Last change: 2026-10-23

## Summary

TripSplit does not collect any data. The app has no account and no analytics. The developer runs no server that reads your data. An optional relay passes encrypted group data between phones. The relay cannot read it.

## What the app stores

- The groups, members, expenses and payments that you and your group enter.
- A random device identifier that the app makes on first launch.
- A secret key for each group.

All of this data stays on your phone and on the phones of your group. The app does not send it to the developer or to any other company in a form that anyone can read.

## How the data moves

The app sends group data only to other phones that hold the same group key. It does this directly, over
your local Wi-Fi network, a phone hotspot or Bluetooth, while the Nearby screen is open. The connection is
encrypted with the group key.

## The internet relay

When the setting "Sync over the internet" is on (it is on by default), the app also sends your group data
to a relay: a small server that runs on Cloudflare. The app encrypts every batch of changes on your
phone with a key that comes from the group key. The relay never gets the group key. So it cannot read
the group name, the members, the amounts or any other content.

The relay can see:

- the IP address of each phone that connects to it,
- an identifier of the mailbox, which the app derives from the group key (it is not the group name),
- the size and the time of each request.

From this, the relay can tell how many phones use a group and when. The relay keeps the encrypted data
until 400 days after the last upload, and then it deletes it. The relay does not use cookies, accounts or
analytics. Cloudflare, the company that runs the servers, handles the network traffic under its own
[privacy policy](https://www.cloudflare.com/privacypolicy/).

To stop all use of the relay, switch off "Sync over the internet" in Settings. The setting is for each
phone. Nearby sync (Wi-Fi and Bluetooth) does not use the relay.

Anyone who has the group invite (the QR code or the link) can read and change the group data. Show the
invite only to the people in your group.

## Permissions

| Permission | Why the app needs it |
|---|---|
| Local network | To find group members on the same Wi-Fi network. |
| Internet | To reach the optional relay. |
| Bluetooth | To sync with group members when there is no Wi-Fi. |
| Camera | To scan the QR code of a group invite. |
| Location (Android 10 and 11 only) | Android needs it before an app can scan for Bluetooth devices. The app does not read your location. |

## Backups

The app excludes its database from iCloud and Google backups. If you delete the app, your copy of the
data is deleted. Other group members keep their copies, so you can get the data back when you join the
group again.

## Children

The app does not target children and does not collect data from anyone.

## Contact

Open an issue at https://github.com/olcayertas/trip-split/issues.
