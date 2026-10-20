# NextTask privacy policy

*Last updated: 20 October 2026*

NextTask is an app for reading and editing the tasks stored on **your own Nextcloud server**. It is made by
Mikkel Jeppesen ("I"). I do not run any server for the app, and I do not receive any data from it.

## What the app does with your data

- **Your tasks** are exchanged only between your device and the Nextcloud server whose address you enter. They
  are not sent to me or to anyone else.
- **Signing in** happens in your browser, on your server's own login page. The app never sees your Nextcloud
  password. It receives an *app password* that your server creates for this device.
- **The app collects nothing about you.** It has no analytics, no advertising, no crash reporting and no
  tracking, and it contains no third-party services.

## What is stored on your device

- The server address, your user name and the app password. They are encrypted with a key held in the Android
  Keystore.
- A copy of your task lists, so the app opens quickly and works offline. It lives in the app's private storage
  and is not included in Android backups.
- Your settings (for example how completed tasks are ordered).

Signing out removes all of this from the device and asks your server to revoke the app password. You can also
revoke it yourself in Nextcloud under *Settings → Security*. Uninstalling the app removes everything it stored.

## Network connections

The app connects only to the server you enter. Use an `https://` address so the connection is encrypted. The app
also lets you choose `http://`, for example for a server in your home network; it then warns you that tasks and
the app password travel unencrypted.

## Files you export

*Export tasks* writes your tasks to a file in a place you choose. What happens to that file afterwards is up to
you.

## Permissions

The app asks for internet access and for the state of the network connection (to sync when you are back online).

If you turn on task reminders in the app's preferences, it also asks to show notifications, and it uses alarms
(also after a restart of the device) to show each reminder on time. Reminders are worked out on your device from
your tasks; nothing about them is sent anywhere.

## Other parties

Google Play, the operating system and your Nextcloud server have their own privacy practices, which I do not
control. NextTask is not affiliated with Nextcloud GmbH.

## Children

NextTask is not directed at children under 13. It collects no personal data from anyone, children included.

## Changes

If this policy changes, the new version will be published at the same address with a new date.

## Contact

Questions about this policy or the app: [hi@sche.cc](mailto:hi@sche.cc)
