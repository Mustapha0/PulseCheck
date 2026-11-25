# Pulse Check

Mobile data diagnostics with live cell tower readings, plus phone controls through Shizuku.

## Build the APK

1. Create a new GitHub repository and upload everything in this folder (keep the .github folder).
2. Open the Actions tab and run "Build APK". It also runs on every push.
3. When it finishes, open the run and download the PulseCheck-APK artifact. Unzip it and install app-debug.apk.
4. On first launch, allow location and phone access. Android needs them to read tower data. Nothing is stored or sent.

## Tools tab (needs Shizuku)

Install Shizuku and start it (Wireless debugging works on Android 11 and later). Open Pulse Check, go to the Tools tab and tap Allow in Shizuku.

- Reconnect now: airplane mode on for about 6 seconds, then off, then a comparison of your old and new tower.
- Switch mobile data off and on: a lighter reset.
- Private DNS: Cloudflare (one.one.one.one), Google (dns.google), Automatic or Off. When the Test finds slow carrier DNS, the result shows a Set Private DNS for me button.
- Lock to 4G: experimental. It reads the allowed network types, switches 5G off, checks that the phone accepted it and puts the old setting back if not. Restore undoes it.
- Modem report: carrier aggregation, channel widths and 5G state where the phone reports them.
- Real ping and route: ICMP pings to 1.1.1.1 and 8.8.8.8 and the first hops. With Shizuku connected, the Test tab runs this too.

It cannot pick a specific tower or lock a band. That needs root.

## If the GitHub build fails

The Shizuku code has not been compiled on a real Android toolchain yet. Open the failed run, copy the red error lines and send them over.
